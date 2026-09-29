package com.solarized.firedown.sync;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.BitmapFactory;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;
import androidx.hilt.work.HiltWorker;
import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.solarized.firedown.App;
import com.solarized.firedown.BuildConfig;
import com.solarized.firedown.Preferences;
import com.solarized.firedown.R;
import com.solarized.firedown.nwc.NwcClient;
import com.solarized.firedown.nwc.NwcUri;
import com.solarized.firedown.nwc.NwcWallet;
import com.solarized.firedown.phone.SettingsActivity;
import com.solarized.firedown.sync.crypto.Hex;
import com.solarized.firedown.sync.crypto.SyncIdentity;

import java.io.IOException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;

import dagger.assisted.Assisted;
import dagger.assisted.AssistedInject;
import okhttp3.OkHttpClient;

/**
 * The account watch: a periodic check of the metered quota while Cloud Backup
 * is set up, doing the two things a subscription business gets from its
 * billing system and a prepaid, anonymous one otherwise lacks.
 *
 * <p><b>Lapse notifications</b> (Signal's "Your backups subscription
 * expired" / "Download your backup data" sheets). The home card already shows
 * the grace countdown — to a user who opens the app. One who doesn't learns
 * nothing until the reap has deleted the only copy of every file whose phone
 * copy was freed (Storage → Free up space). So the watch posts a notification
 * ONCE when the account enters read-only grace, and ONCE more when
 * {@link #REAP_WARNING_DAYS} or fewer days remain, each with two actions:
 * Top up (the Cloud screen) and Restore (the Backups list with its
 * "Restore all to this phone" confirmation armed). Once per grace EPISODE:
 * the marker is the quota's {@code grace_until}, so a later lapse is told
 * again while a 6-hourly re-run of the same one is not.
 *
 * <p><b>Auto top-up</b> (every subscription's auto-renew, adapted to the
 * rails). Opt-in ({@link Preferences#CLOUD_AUTO_TOPUP}), and only with a
 * connected NIP-47 wallet — the one payment channel that can pay without the
 * user present, within the budget the WALLET enforces (a wallet's
 * {@code QUOTA_EXCEEDED} is a normal, expected refusal here, not an error).
 * When coverage drops to {@link #TOPUP_UNDER_MONTHS} or the account is in
 * grace, it re-buys the LAST plan tile the user chose
 * ({@link Preferences#CLOUD_AUTO_TOPUP_KEYSET}, falling back to the
 * cheapest active plan when that tile is gone from the catalog) over
 * Lightning, through the same {@link CreditPurchase} → {@link
 * PendingPurchase} → {@link CreditSettlement} pipeline as the wizard and the
 * settle worker, so a death anywhere in the middle is finished by
 * {@link CreditSettleWorker} exactly as a hand-paid invoice would be. Rules
 * that keep it from ever costing more than one plan:
 * <ul>
 *   <li>never while a {@link PendingPurchase} exists — that is a purchase
 *       already in flight, the settle worker's to finish;</li>
 *   <li>at most one ATTEMPT per {@link #ATTEMPT_COOLDOWN_MS}, success or
 *       failure, so a refusing wallet is asked once a day, not every run;</li>
 *   <li>a wallet REFUSAL ({@link NwcClient.WalletException}) proves nothing
 *       was paid, so the fresh record is dropped and the user is told why,
 *       with a tap into the manual flow; an AMBIGUOUS failure (timeout,
 *       socket drop) keeps the record — the wallet may have paid — and the
 *       settle worker settles or expires it;</li>
 *   <li>a successful top-up suppresses that run's grace notification, since
 *       the next quota read will show the account funded.</li>
 * </ul>
 *
 * <p>Armed by {@link CloudBackupManager#markEnabled} and at boot while set
 * up; cancels itself the first run it finds the account not set up. Nothing
 * here decides a payment is good — the mint's blind signature does (see
 * {@link CreditSettleWorker}).
 */
@HiltWorker
public class CloudWatchWorker extends Worker {

    private static final String TAG = "CloudWatchWorker";
    private static final String UNIQUE_NAME = "cloud-watch";
    private static final long PERIOD_HOURS = 6;
    private static final int NOTIFICATION_GRACE_ID = 4102;
    private static final int NOTIFICATION_TOPUP_ID = 4103;
    /** Second (urgent) lapse notification at this many days left or fewer. */
    static final int REAP_WARNING_DAYS = 3;
    /** Auto top-up fires at this runway or less ({@link CloudBackupManager#runwayMonths}
     *  floors at 1, so 1 = "a month or less"). */
    static final int TOPUP_UNDER_MONTHS = 1;
    static final long ATTEMPT_COOLDOWN_MS = TimeUnit.HOURS.toMillis(24);
    /** How long a run waits for the mint to see the wallet's Lightning payment
     *  before handing the record to the settle worker. */
    private static final int ISSUE_POLLS = 6;
    private static final long ISSUE_POLL_MS = 3000;

    private final Context mContext;
    private final OkHttpClient mClient;
    private final SharedPreferences mPrefs;
    private final CloudBackupManager mCloud;

    @AssistedInject
    public CloudWatchWorker(
            @Assisted @NonNull Context context,
            @Assisted @NonNull WorkerParameters params,
            OkHttpClient client,
            SharedPreferences prefs,
            CloudBackupManager cloud) {
        super(context, params);
        this.mContext = context;
        this.mClient = client;
        this.mPrefs = prefs;
        this.mCloud = cloud;
    }

    /** Arms the periodic watch (idempotent — KEEP leaves a running one alone). */
    public static void schedule(Context context) {
        Constraints constraints = new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build();
        PeriodicWorkRequest request = new PeriodicWorkRequest.Builder(
                CloudWatchWorker.class, PERIOD_HOURS, TimeUnit.HOURS)
                .setConstraints(constraints)
                .build();
        WorkManager.getInstance(context.getApplicationContext())
                .enqueueUniquePeriodicWork(UNIQUE_NAME, ExistingPeriodicWorkPolicy.KEEP, request);
    }

    public static void cancel(Context context) {
        WorkManager.getInstance(context.getApplicationContext()).cancelUniqueWork(UNIQUE_NAME);
    }

    @NonNull
    @Override
    public Result doWork() {
        if (!mCloud.isSetUp()) {
            cancel(mContext);
            return Result.success();
        }
        byte[] code = null;
        try {
            code = new SyncSecrets(mContext).load();
            if (code == null) {
                return Result.success();
            }
            SyncIdentity id = SyncIdentity.fromCode(code);
            StorageApiClient storage = new StorageApiClient(mClient, mCloud.backendUrl());
            CloudBackupManager.ensureRegistered(mPrefs, storage, id);
            StorageApiClient.Quota quota = storage.quota(id);
            if (quota == null || !quota.metered) {
                return Result.success(); // the unmetered beta has nothing to run out of
            }
            int runway = CloudBackupManager.runwayMonths(quota);
            boolean low = quota.readOnly || (runway >= 0 && runway <= TOPUP_UNDER_MONTHS);
            if (low && mPrefs.getBoolean(Preferences.CLOUD_AUTO_TOPUP, false)) {
                boolean paying = tryAutoTopUp(id, storage);
                if (paying) {
                    // Money is on its way (or already booked): the next read
                    // shows the account funded, so don't also alarm about it.
                    return Result.success();
                }
            }
            if (quota.readOnly) {
                notifyGrace(quota);
            }
            return Result.success();
        } catch (IOException io) {
            return Result.retry();
        } catch (RuntimeException e) {
            if (BuildConfig.DEBUG) {
                Log.w(TAG, "watch failed", e);
            }
            return Result.success();
        } finally {
            SyncSecrets.wipe(code);
        }
    }

    // ---------------------------------------------------------------- top-up

    /**
     * One auto top-up attempt. Returns true when a payment was SENT (whether or
     * not it is booked yet) — the caller then skips the grace alarm for this
     * run; false when nothing was paid (not attempted, refused, or failed
     * before paying).
     */
    private boolean tryAutoTopUp(SyncIdentity id, StorageApiClient storage) {
        if (PendingPurchase.load(mContext) != null) {
            return false; // a purchase is in flight — CreditSettleWorker owns it
        }
        long now = System.currentTimeMillis();
        long last = mPrefs.getLong(Preferences.CLOUD_AUTO_TOPUP_LAST_ATTEMPT, 0);
        if (now - last < ATTEMPT_COOLDOWN_MS) {
            return false;
        }
        mPrefs.edit().putLong(Preferences.CLOUD_AUTO_TOPUP_LAST_ATTEMPT, now).apply();

        NwcUri connection = new NwcWallet(mContext).load();
        if (connection == null) {
            notifyTopUpFailed(mContext.getString(R.string.buy_credit_wallet_gone));
            return false;
        }

        MintClient mint = new MintClient(mClient, Preferences.MINT_DEFAULT_BACKEND);
        CreditPurchase purchase = new CreditPurchase(mint, storage);
        CreditPurchase.Session session;
        PendingPurchase pending;
        try {
            MintClient.Catalog catalog = mint.fetchCatalog();
            if (!catalog.methods.contains("lightning")) {
                notifyTopUpFailed(mContext.getString(R.string.buy_credit_error_rail_unavailable));
                return false;
            }
            MintClient.Keyset keyset = pickKeyset(catalog.keysets,
                    mPrefs.getString(Preferences.CLOUD_AUTO_TOPUP_KEYSET, null));
            if (keyset == null) {
                notifyTopUpFailed(mContext.getString(R.string.cloud_auto_topup_no_plan));
                return false;
            }
            session = purchase.startByKeyset(Hex.encode(keyset.id), "lightning");
            if (session.quote.payRequest == null) {
                notifyTopUpFailed(mContext.getString(R.string.buy_credit_error_rail_unavailable));
                return false;
            }
            // Persist BEFORE paying, marked submitted, with the settle worker
            // armed: from here on a death anywhere is finished exactly as a
            // hand-paid invoice would be (the blinding secret is the only
            // proof of the payment).
            pending = CreditPurchase.toPending(session).withSubmitted();
            pending.save(mContext);
            CreditSettleWorker.schedule(mContext);
        } catch (IOException | RuntimeException e) {
            if (BuildConfig.DEBUG) {
                Log.w(TAG, "auto top-up: quote failed", e);
            }
            return false; // nothing paid, nothing persisted — quietly next time
        }

        try {
            new NwcClient(connection, mClient).payInvoice(session.quote.payRequest);
        } catch (NwcClient.WalletException e) {
            // The wallet REFUSED (balance, budget, permission, no route):
            // nothing was paid, so the fresh record can go — leaving it would
            // send every wizard entry to a pay screen for an invoice the user
            // never asked for. Told once, with the manual door.
            PendingPurchase.clear(mContext);
            CreditSettleWorker.cancel(mContext);
            notifyTopUpFailed(NwcWallet.errorMessage(mContext, e));
            return false;
        } catch (IOException | RuntimeException e) {
            // Ambiguous — the wallet may have paid after we stopped listening.
            // The record stays; the settle worker books it if the mint sees
            // the payment, or expires it. Never "failed": that invites a
            // second payment for a credit the user may already own.
            notifyTopUpFailed(mContext.getString(R.string.buy_credit_wallet_unconfirmed));
            return true;
        }

        // Paid. Give the mint a short while to see it; past that the settle
        // worker's next run finishes the purchase.
        try {
            for (int i = 0; i < ISSUE_POLLS; i++) {
                if (purchase.issueAndUnblind(session)) {
                    break;
                }
                Thread.sleep(ISSUE_POLL_MS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return true;
        } catch (IOException | RuntimeException e) {
            return true; // settle worker's turn
        }
        if (session.sig() == null) {
            return true;
        }
        pending = pending.withSig(session.sig());
        pending.save(mContext);
        try {
            purchase.redeem(id, session);
        } catch (StorageApiClient.FatalException fe) {
            if (!StorageApiClient.SLUG_CREDIT_SPENT.equals(fe.slug)) {
                return true; // record kept; the settle worker retries redeem
            }
        } catch (IOException | RuntimeException e) {
            return true;
        }
        boolean booked = CreditSettlement.commitRedeemed(mContext, mPrefs, mCloud,
                pending.quoteIdHex, pending.sizeGb, pending.durationMonths);
        CreditSettleWorker.cancel(mContext);
        if (booked) {
            notifyTopUpDone();
        }
        return true;
    }

    /**
     * The tile to re-buy: the user's LAST purchase when the catalog still
     * carries it active, else the cheapest active plan tile (a rotated
     * catalog must not silently stop the renewal), else null.
     */
    @Nullable
    static MintClient.Keyset pickKeyset(List<MintClient.Keyset> keysets, @Nullable String lastHex) {
        MintClient.Keyset cheapest = null;
        for (MintClient.Keyset k : keysets) {
            if (!k.active || !k.isPlan()) {
                continue;
            }
            if (lastHex != null && lastHex.equalsIgnoreCase(Hex.encode(k.id))) {
                return k;
            }
            if (cheapest == null || k.priceCents < cheapest.priceCents) {
                cheapest = k;
            }
        }
        return cheapest;
    }

    // ---------------------------------------------------------------- grace

    /**
     * The lapse notification, once per stage per grace episode. Stage 1 fires
     * on entering grace; stage 2 (a different title, same id so it REPLACES
     * stage 1) at {@link #REAP_WARNING_DAYS} left or fewer. Keyed by
     * {@code grace_until} so the same episode is never re-told by the
     * 6-hourly re-run, while a later lapse is.
     */
    private void notifyGrace(StorageApiClient.Quota quota) {
        String until = quota.graceUntil != null ? quota.graceUntil : "unknown";
        long daysLeft = daysUntil(quota.graceUntil);
        boolean urgent = daysLeft >= 0 && daysLeft <= REAP_WARNING_DAYS;
        String key = urgent ? Preferences.CLOUD_REAP_NOTIFIED_UNTIL
                : Preferences.CLOUD_GRACE_NOTIFIED_UNTIL;
        if (until.equals(mPrefs.getString(key, null))) {
            return;
        }
        mPrefs.edit().putString(key, until).apply();

        String title = mContext.getString(urgent
                ? R.string.cloud_reap_notif_title : R.string.cloud_grace_notif_title);
        String text;
        if (daysLeft >= 0) {
            int shown = (int) Math.max(1, daysLeft);
            text = mContext.getResources().getQuantityString(
                    R.plurals.cloud_grace_notif_text, shown, shown);
        } else {
            text = mContext.getString(R.string.cloud_grace_notif_text_nodate);
        }
        PendingIntent topUp = cloudIntent(0, false);
        PendingIntent restore = cloudIntent(1, true);
        NotificationCompat.Builder b = baseNotification(title, text)
                .setContentIntent(urgent ? restore : topUp)
                .addAction(0, mContext.getString(R.string.home_cloud_top_up), topUp)
                .addAction(0, mContext.getString(R.string.settings_sync_restore_action), restore);
        post(NOTIFICATION_GRACE_ID, b);
    }

    /** Whole days from now to an RFC3339 instant (0 when it has passed), or -1
     *  when missing/unparseable. */
    static long daysUntil(@Nullable String rfc3339) {
        if (rfc3339 == null) {
            return -1;
        }
        try {
            Instant end = OffsetDateTime.parse(rfc3339).toInstant();
            long ms = end.toEpochMilli() - System.currentTimeMillis();
            return Math.max(0, TimeUnit.MILLISECONDS.toDays(ms));
        } catch (RuntimeException e) {
            return -1;
        }
    }

    // ---------------------------------------------------------------- notify

    private void notifyTopUpDone() {
        post(NOTIFICATION_TOPUP_ID, baseNotification(
                mContext.getString(R.string.cloud_auto_topup_done_title),
                mContext.getString(R.string.cloud_auto_topup_done_text))
                .setContentIntent(cloudIntent(0, false)));
    }

    private void notifyTopUpFailed(String reason) {
        post(NOTIFICATION_TOPUP_ID, baseNotification(
                mContext.getString(R.string.cloud_auto_topup_failed_title),
                mContext.getString(R.string.cloud_auto_topup_failed_text, reason))
                .setContentIntent(cloudIntent(0, false)));
    }

    private NotificationCompat.Builder baseNotification(String title, String text) {
        return new NotificationCompat.Builder(mContext, App.DOWNLOADS_NOTIFICATION_ID)
                .setSmallIcon(R.drawable.cloud_24)
                .setLargeIcon(BitmapFactory.decodeResource(
                        mContext.getResources(), R.mipmap.ic_launcher_round))
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(text))
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT);
    }

    /** Silent when the user denied notifications; the home card and the Cloud
     *  hero carry the state regardless. */
    private void post(int id, NotificationCompat.Builder builder) {
        if (ActivityCompat.checkSelfPermission(mContext, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        NotificationManager nm = (NotificationManager)
                mContext.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) {
            return;
        }
        Notification notification = builder.build();
        nm.notify(id, notification);
    }

    /** The Cloud screen (top-up), or the Backups list with "Restore all to this
     *  phone" armed. Distinct request codes so the two don't collide under
     *  FLAG_UPDATE_CURRENT. */
    private PendingIntent cloudIntent(int requestCode, boolean restoreAll) {
        Intent intent = new Intent(mContext, SettingsActivity.class);
        if (restoreAll) {
            intent.putExtra(SettingsActivity.EXTRA_OPEN_CLOUD_BACKUP_FILES, true);
            intent.putExtra(SettingsActivity.EXTRA_RESTORE_ALL, true);
        } else {
            intent.putExtra(SettingsActivity.EXTRA_OPEN_CLOUD_BACKUP, true);
        }
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        return PendingIntent.getActivity(mContext, 100 + requestCode, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }
}
