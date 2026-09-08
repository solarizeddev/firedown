package com.solarized.firedown.sync;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.BitmapFactory;
import android.util.Log;

import androidx.annotation.NonNull;
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
import com.solarized.firedown.sync.crypto.SyncIdentity;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import dagger.assisted.Assisted;
import dagger.assisted.AssistedInject;
import okhttp3.OkHttpClient;

/**
 * Background settlement of a credit purchase whose payment is in flight — the
 * on-chain rail's answer to "I paid and closed the app". Confirmation takes
 * minutes to hours, so the buy wizard's poll (which lives only while that
 * screen is open) is the wrong place to wait; this periodic job asks the mint
 * once per run whether the quote has settled and, when it has, finishes the
 * purchase — issue → unblind → redeem → book — exactly as the wizard would,
 * and posts a notification so the user learns the credit landed.
 *
 * <p><b>It never decides that a payment is good.</b> The mint does: its
 * watch-only node must have received the quoted sats with the configured
 * confirmations before {@code /v1/mint/issue} blind-signs anything. This
 * worker only polls that answer; a "pending" (seen but unconfirmed) reply is
 * treated as not-yet, the same as silence.
 *
 * <p>Scheduled by the wizard whenever a record is marked submitted (an on-chain
 * address shown, a connected wallet reporting an invoice paid); runs at
 * WorkManager's 15-minute floor on a connected network; cancels itself the
 * first time it finds no pending record, so it costs nothing once the
 * purchase is done or cancelled. Shares {@link CreditSettlement} with the
 * wizard so a credit both of them reach is booked once.
 */
@HiltWorker
public class CreditSettleWorker extends Worker {

    private static final String TAG = "CreditSettleWorker";
    private static final String UNIQUE_NAME = "credit-settle";
    private static final int NOTIFICATION_ID = 4101;

    private final Context mContext;
    private final OkHttpClient mClient;
    private final SharedPreferences mPrefs;
    private final CloudBackupManager mCloud;

    @AssistedInject
    public CreditSettleWorker(
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

    /** Arms the periodic settle (idempotent — KEEP leaves a running one alone). */
    public static void schedule(Context context) {
        Constraints constraints = new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build();
        PeriodicWorkRequest request = new PeriodicWorkRequest.Builder(
                CreditSettleWorker.class, PeriodicWorkRequest.MIN_PERIODIC_INTERVAL_MILLIS,
                TimeUnit.MILLISECONDS)
                .setConstraints(constraints)
                .build();
        WorkManager.getInstance(context.getApplicationContext())
                .enqueueUniquePeriodicWork(UNIQUE_NAME, ExistingPeriodicWorkPolicy.KEEP, request);
    }

    /** Disarms it — the record is gone (booked or cancelled). */
    public static void cancel(Context context) {
        WorkManager.getInstance(context.getApplicationContext()).cancelUniqueWork(UNIQUE_NAME);
    }

    @NonNull
    @Override
    public Result doWork() {
        PendingPurchase pending = PendingPurchase.load(mContext);
        if (pending == null) {
            cancel(mContext);
            return Result.success();
        }
        boolean issued = pending.sigHex != null && !pending.sigHex.isEmpty();
        if (!issued && !pending.submitted) {
            // An unsubmitted record is the wizard's business (its leave-cleanup
            // drops it); polling the mint for a quote nobody paid is noise.
            return Result.success();
        }
        byte[] code = null;
        try {
            code = new SyncSecrets(mContext).load();
            if (code == null) {
                return Result.success(); // no account to redeem into — the wizard reports it
            }
            SyncIdentity id = SyncIdentity.fromCode(code);
            MintClient mint = new MintClient(mClient, Preferences.MINT_DEFAULT_BACKEND);
            StorageApiClient storage = new StorageApiClient(mClient, mCloud.backendUrl());
            CloudBackupManager.ensureRegistered(mPrefs, storage, id);
            CreditPurchase purchase = new CreditPurchase(mint, storage);
            CreditPurchase.Session session = purchase.restore(pending);

            try {
                if (!purchase.issueAndUnblind(session)) {
                    return Result.success(); // not confirmed yet — next period
                }
            } catch (MintClient.FatalException fe) {
                if (MintClient.SLUG_QUOTE_EXPIRED.equals(fe.slug)) {
                    // Unpaid past its (extended) TTL. For LIGHTNING that is a
                    // dead record (the invoice can't be paid any more). For
                    // ON-CHAIN it only means nothing was seen YET: the address
                    // stays payable, the mint polls its node before the expiry
                    // test and keeps the row for weeks — so keep asking for the
                    // late window, and only then drop it. Same rule as the
                    // wizard's isDeadQuote branch.
                    boolean onchain = "onchain".equals(pending.method);
                    if (onchain && !OnchainPollPolicy.beyondLateWindow(
                            pending.expiresAt, System.currentTimeMillis())) {
                        return Result.success(); // next period
                    }
                    PendingPurchase.clear(mContext);
                    cancel(mContext);
                }
                return Result.success(); // anything else: keep the record, the wizard reports it
            } catch (IOException io) {
                return Result.retry();
            }

            // Paid + issued: persist the sig BEFORE redeeming, so a death here
            // resumes redeem-only (the mint refuses a re-issue).
            pending = pending.withSig(session.sig());
            pending.save(mContext);

            try {
                purchase.redeem(id, session);
            } catch (StorageApiClient.FatalException fe) {
                if (!StorageApiClient.SLUG_CREDIT_SPENT.equals(fe.slug)) {
                    return Result.success(); // keep the record; resume retries redeem
                }
                // credit-spent: an earlier redeem already applied it — book it.
            } catch (IOException io) {
                return Result.retry();
            }

            boolean booked = CreditSettlement.commitRedeemed(mContext, mPrefs, mCloud,
                    pending.quoteIdHex, pending.sizeGb, pending.durationMonths);
            cancel(mContext);
            if (booked) {
                notifySettled();
            }
            return Result.success();
        } catch (IOException io) {
            return Result.retry();
        } catch (RuntimeException e) {
            if (BuildConfig.DEBUG) {
                Log.w(TAG, "settle failed", e);
            }
            return Result.success(); // record kept; the wizard's resume owns the diagnosis
        } finally {
            SyncSecrets.wipe(code);
        }
    }

    /** "Storage credit added" — tap opens the Cloud screen. Silent when the
     *  user denied notifications; the Cloud hero shows the balance regardless. */
    private void notifySettled() {
        if (ActivityCompat.checkSelfPermission(mContext, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        NotificationManager nm = (NotificationManager)
                mContext.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) {
            return;
        }
        Notification notification = new NotificationCompat.Builder(
                mContext, App.DOWNLOADS_NOTIFICATION_ID)
                .setSmallIcon(R.drawable.cloud_24)
                .setLargeIcon(BitmapFactory.decodeResource(
                        mContext.getResources(), R.mipmap.ic_launcher_round))
                .setContentTitle(mContext.getString(R.string.buy_credit_settled_title))
                .setContentText(mContext.getString(R.string.buy_credit_settled_text))
                .setContentIntent(VaultBackupWorker.cloudBackupIntent(mContext))
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .build();
        nm.notify(NOTIFICATION_ID, notification);
    }
}
