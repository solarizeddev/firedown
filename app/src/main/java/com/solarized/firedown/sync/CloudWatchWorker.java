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
import com.solarized.firedown.phone.SettingsActivity;
import com.solarized.firedown.sync.crypto.SyncIdentity;

import java.io.IOException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.concurrent.TimeUnit;

import dagger.assisted.Assisted;
import dagger.assisted.AssistedInject;
import okhttp3.OkHttpClient;

/**
 * The account watch: a periodic check of the metered quota while Cloud Backup
 * is set up, posting the LAPSE notifications a subscription business gets
 * from its billing system and a prepaid, anonymous account otherwise lacks
 * (Signal's "Your backups subscription expired" / "Download your backup
 * data" sheets).
 *
 * <p>The home card shows the grace countdown — to a user who opens the app.
 * One who doesn't learns nothing until the reap has deleted the only copy of
 * every file whose phone copy was freed (Storage → Free up space). So the
 * watch posts ONCE when the account enters read-only grace, and ONCE more
 * when {@link #REAP_WARNING_DAYS} or fewer days remain, each with two
 * actions: Top up (the Cloud screen) and Restore (the Backups list with its
 * "Restore all to this phone" confirmation armed). Once per grace EPISODE:
 * the marker is the quota's {@code grace_until}, so a later lapse is told
 * again while a 6-hourly re-run of the same one is not.
 *
 * <p>Armed by {@link CloudBackupManager#markEnabled} and at boot while set
 * up; cancels itself the first run it finds the account not set up. It
 * never pays anything: an auto top-up half existed briefly on top of the
 * (since removed) Nostr Wallet Connect integration and went with it — the
 * only rails are a QR/invoice the user pays by hand.
 */
@HiltWorker
public class CloudWatchWorker extends Worker {

    private static final String TAG = "CloudWatchWorker";
    private static final String UNIQUE_NAME = "cloud-watch";
    private static final long PERIOD_HOURS = 6;
    private static final int NOTIFICATION_GRACE_ID = 4102;
    /** Second (urgent) lapse notification at this many days left or fewer. */
    static final int REAP_WARNING_DAYS = 3;

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
