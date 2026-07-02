package com.solarized.firedown.sync;

import android.app.Notification;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.graphics.BitmapFactory;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;
import androidx.hilt.work.HiltWorker;
import androidx.work.Data;
import androidx.work.ForegroundInfo;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.google.common.util.concurrent.ListenableFuture;
import com.solarized.firedown.App;
import com.solarized.firedown.Preferences;
import com.solarized.firedown.R;
import com.solarized.firedown.phone.SettingsActivity;
import com.solarized.firedown.sync.crypto.SyncIdentity;

import java.io.File;
import java.io.IOException;

import dagger.assisted.Assisted;
import dagger.assisted.AssistedInject;
import okhttp3.OkHttpClient;

/**
 * Backs one finished download up to Cloud Backup (encrypted → presigned PUT to
 * R2 → manifest). Runs as a foreground worker so a larger upload isn't killed
 * when the app is backgrounded — the app already declares the {@code dataSync}
 * foreground-service type its downloads use.
 *
 * <p>The actual encrypt/chunk/upload/manifest work is {@link VaultEngine}; this
 * worker only loads the shared recovery-code identity, drives the engine, and
 * reports a terminal status the UI observes (mirrors {@link SyncWorker}). No
 * key/credential data is ever logged.
 */
@HiltWorker
public class VaultBackupWorker extends Worker {

    /** Input-data keys (set by the enqueuing fragment). */
    public static final String KEY_PATH = "path";
    public static final String KEY_MIME = "mime";
    public static final String KEY_NAME = "name";
    /** The exact thumbnail frame position (µs) the Downloads list uses for this
     *  download (GlideHelper.thumbnailFrameUs), so the stored preview matches. */
    public static final String KEY_FRAME_US = "frame_us";

    /** Output-data keys read by the Downloads fragment to report a result. */
    public static final String KEY_STATUS = "status";
    public static final String STATUS_OK = "ok";
    public static final String STATUS_ERROR = "error";

    /** Progress-data keys the backed-up-files list reads to render a per-item
     *  determinate bar (KEY_NAME/KEY_MIME above identify the file). */
    public static final String KEY_PROGRESS_DONE = "progress_done";
    public static final String KEY_PROGRESS_TOTAL = "progress_total";

    /** A fixed notification id for the in-progress foreground notification. */
    private static final int NOTIFICATION_ID = 0x6242; // "Bb"

    /** Give-up ceiling for the retry branch. A backup that keeps failing — e.g. a
     *  large file whose per-chunk presigned URLs keep expiring mid-upload on a slow
     *  link (the whole file re-uploads from scratch each attempt), or a wedged
     *  manifest — must not re-upload forever (battery + bandwidth churn, a fresh
     *  pending object leaked per attempt). getRunAttemptCount() is 0 on the first
     *  run, so this allows ~this many attempts before a clean terminal failure the
     *  user can retry manually. */
    private static final int MAX_RUN_ATTEMPTS = 10;

    private final Context mContext;
    private final OkHttpClient mClient;
    private final SharedPreferences mPrefs;
    /** The most recent setProgressAsync future, drained before returning a Result. */
    private ListenableFuture<Void> mLastProgress;
    /** Last published whole-percent, to throttle per-chunk progress writes. */
    private int mLastPct = -1;
    /** The in-flight storage client, so {@link #onStopped()} can cancel its
     *  network calls. {@code Worker} threads are NOT interrupted on cancel, so a
     *  rate-limit retry loop would otherwise run to exhaustion after the user
     *  cancels. Volatile: onStopped runs on a different thread than doWork. */
    private volatile StorageApiClient mApi;

    @AssistedInject
    public VaultBackupWorker(
            @Assisted @NonNull Context context,
            @Assisted @NonNull WorkerParameters params,
            OkHttpClient client,
            SharedPreferences prefs) {
        super(context, params);
        this.mContext = context;
        this.mClient = client;
        this.mPrefs = prefs;
    }

    @NonNull
    @Override
    public Result doWork() {
        String path = getInputData().getString(KEY_PATH);
        String mime = getInputData().getString(KEY_MIME);
        String name = getInputData().getString(KEY_NAME);
        long frameUs = getInputData().getLong(KEY_FRAME_US, 0L);
        if (path == null) {
            return failure();
        }
        File file = new File(path);
        if (!file.exists()) {
            return failure();
        }

        // Promote to foreground for the upload (dataSync). Best-effort: if the OS
        // refuses (rare), the work still runs in the background.
        try {
            setForegroundAsync(foregroundInfo(name)).get();
        } catch (Exception ignored) {
            // Couldn't go foreground — proceed in the background.
        }

        byte[] code = new SyncSecrets(mContext).load();
        if (code == null) {
            return failure(); // not set up (e.g. signed out between enqueue and run)
        }
        SyncIdentity identity;
        try {
            identity = SyncIdentity.fromCode(code);
        } finally {
            SyncSecrets.wipe(code);
        }

        StorageApiClient api = new StorageApiClient(mClient, Preferences.STORAGE_DEFAULT_BACKEND);
        mApi = api;
        // A cancel that landed before the client was built (the worker was stopped
        // while going foreground / loading the code) still applies — refuse calls.
        if (isStopped()) {
            api.cancel();
        }
        VaultEngine engine = new VaultEngine(api, identity);
        // A tiny preview, stored in the encrypted manifest so the list shows a
        // thumbnail offline even after the local copy is deleted.
        String thumb = VaultThumbnail.generate(path, mime, frameUs);
        // Publish the file's identity + a determinate per-chunk progress so the
        // backed-up-files list can show a per-item bar (like the Downloads list).
        final String fName = name;
        final String fMime = mime;
        publishProgress(fName, fMime, 0, file.length());
        try {
            // Register ONCE per install (not per backup) — Cloudflare rate-limits
            // the register endpoints, so re-registering on every upload bursts them.
            CloudBackupManager.ensureRegistered(mPrefs, api, identity);
            engine.backupFile(file, mime, thumb,
                    (done, total) -> publishProgress(fName, fMime, done, total));
        } catch (StorageApiClient.FatalException e) {
            // A 4xx with a slug (bad request / unknown keyset / …) won't fix itself
            // — terminal. Checked BEFORE the bare-IOException branch because
            // FatalException IS an IOException.
            awaitLastProgress();
            return failure();
        } catch (IOException e) {
            // Everything retryable: a 429/5xx (TransientException) AND a bare
            // socket drop / read timeout mid-upload (a plain IOException OkHttp
            // throws, which is NOT a TransientException) AND manifest OCC
            // exhaustion. On a flaky mobile link these are the common case — retry
            // with WorkManager's backoff instead of failing the backup permanently
            // and orphaning the uploaded object.
            awaitLastProgress();
            if (getRunAttemptCount() >= MAX_RUN_ATTEMPTS) {
                // Persistently failing (e.g. a large file whose per-chunk presign
                // URLs keep expiring mid-upload on a slow link — each retry restarts
                // the whole upload). Stop re-uploading forever; fail cleanly so the
                // user can retry deliberately. (The real cure is resumable / lazy
                // per-chunk presign so a long upload isn't bounded by one TTL.)
                return failure();
            }
            return Result.retry();
        } catch (Exception e) {
            awaitLastProgress();
            return failure();
        }

        // A pending setProgressAsync MUST complete before we return a Result, or
        // WorkManager throws "Calls to setProgressAsync() must complete before a
        // ListenableWorker signals completion" (the last per-chunk update races
        // the return).
        awaitLastProgress();
        // First successful backup marks Cloud Backup as in use, so the shared
        // recovery code survives a bookmark-sync sign-out (see SyncManager).
        mPrefs.edit().putBoolean(Preferences.CLOUD_BACKUP_ENABLED, true).apply();
        return Result.success(new Data.Builder()
                .putString(KEY_STATUS, STATUS_OK)
                .build());
    }

    @Override
    public void onStopped() {
        // WorkManager does NOT interrupt a Worker's thread on cancel, so cancel the
        // storage client's in-flight call here — otherwise its rate-limit retry
        // loop keeps hammering the (already throttled) register endpoint after the
        // user cancelled. Cancelling makes the loop's next chain.proceed throw
        // "Canceled", and the pre-sleep isCanceled() check bails out immediately.
        StorageApiClient api = mApi;
        if (api != null) {
            api.cancel();
        }
        super.onStopped();
    }

    private Result failure() {
        return Result.failure(new Data.Builder()
                .putString(KEY_STATUS, STATUS_ERROR)
                .build());
    }

    /** Publishes the in-flight file's identity + byte progress. The returned
     *  future is tracked so {@link #awaitLastProgress()} can drain it before the
     *  worker returns a Result. */
    private void publishProgress(String name, String mime, long done, long total) {
        // A cancelled/stopped worker's WorkSpec is being torn down — a late
        // setProgressAsync then logs "must complete before Result"/"not RUNNING".
        // Skip it once stopped.
        if (isStopped()) {
            return;
        }
        // Throttle to whole-percent steps (always emit the first and last) so a
        // large file doesn't hammer the WorkManager DB with a write per chunk.
        int pct = total > 0 ? (int) Math.min(100, done * 100 / total) : 0;
        if (done != 0 && done != total && pct == mLastPct) {
            return;
        }
        mLastPct = pct;
        Data.Builder b = new Data.Builder()
                .putLong(KEY_PROGRESS_DONE, done)
                .putLong(KEY_PROGRESS_TOTAL, total);
        if (name != null) {
            b.putString(KEY_NAME, name);
        }
        if (mime != null) {
            b.putString(KEY_MIME, mime);
        }
        mLastProgress = setProgressAsync(b.build());
    }

    /** Blocks until the most recent progress update has been persisted — required
     *  before returning a Result (see the doWork comment). Best-effort. */
    private void awaitLastProgress() {
        ListenableFuture<Void> f = mLastProgress;
        if (f == null) {
            return;
        }
        mLastProgress = null;
        try {
            f.get();
        } catch (Exception ignored) {
            // interrupted / already-completing — nothing we can do, and the
            // worker is about to finish anyway.
        }
    }

    private ForegroundInfo foregroundInfo(String name) {
        // Short and branded: the action is the title, the file name is the body
        // (ellipsized by the system) — not a two-line sentence. Tapping opens the
        // Downloads-backup screen (the live status), like a download notification
        // opens the Downloads list.
        String title = mContext.getString(R.string.cloud_backup_notification_title);
        Notification notification = new NotificationCompat.Builder(
                mContext, App.DOWNLOADS_NOTIFICATION_ID)
                .setSmallIcon(R.drawable.ic_cloud_upload_24)
                .setLargeIcon(BitmapFactory.decodeResource(
                        mContext.getResources(), R.mipmap.ic_launcher_round))
                .setContentTitle(title)
                .setContentText(name != null ? name : title)
                .setContentIntent(cloudBackupIntent(mContext))
                .setOngoing(true)
                .setProgress(0, 0, true) // indeterminate
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return new ForegroundInfo(NOTIFICATION_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        }
        return new ForegroundInfo(NOTIFICATION_ID, notification);
    }

    /** PendingIntent that opens the Downloads-backup status screen. */
    static PendingIntent cloudBackupIntent(Context context) {
        Intent intent = new Intent(context, SettingsActivity.class);
        intent.putExtra(SettingsActivity.EXTRA_OPEN_CLOUD_BACKUP, true);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        return PendingIntent.getActivity(context, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }
}
