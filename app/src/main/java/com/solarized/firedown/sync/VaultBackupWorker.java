package com.solarized.firedown.sync;

import android.app.Notification;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.graphics.BitmapFactory;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.hilt.work.HiltWorker;
import androidx.work.Constraints;
import androidx.work.Data;
import androidx.work.ExistingWorkPolicy;
import androidx.work.ForegroundInfo;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.google.common.util.concurrent.ListenableFuture;
import com.solarized.firedown.App;
import com.solarized.firedown.BuildConfig;
import com.solarized.firedown.GlideHelper;
import com.solarized.firedown.data.RestoredFileAccess;
import com.solarized.firedown.data.entity.DownloadEntity;
import com.solarized.firedown.Preferences;
import com.solarized.firedown.R;
import com.solarized.firedown.phone.SettingsActivity;
import com.solarized.firedown.sync.crypto.SyncIdentity;

import java.io.File;
import java.io.FileNotFoundException;
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

    private static final String TAG = VaultBackupWorker.class.getSimpleName();

    /** Input-data keys (set by the enqueuing fragment). */
    public static final String KEY_PATH = "path";
    public static final String KEY_MIME = "mime";
    public static final String KEY_NAME = "name";
    /** The download's origin URL, stored in the manifest so a restored file's row
     *  shows its real {@code MIME · domain} instead of a blank domain (nullable). */
    public static final String KEY_ORIGIN = "origin";

    /** The download's OWN date (epoch millis), stored in the manifest so a
     *  restored file lands in the same date section the original sat in. Without
     *  it the entry was stamped with the BACKUP time and a restored row jumped to
     *  "Last 7 days". 0 / absent = unknown, engine falls back to now(). */
    public static final String KEY_DOWNLOADED_AT = "downloaded_at";

    /**
     * Tag prefixes the enqueuing fragment stamps on the BACKUP request so the
     * backed-up-files list can render a transfer row for an ENQUEUED worker.
     * {@code WorkInfo} exposes tags but NOT input data, and progress is empty
     * until the worker actually starts (FGS spin-up + code load) — so without
     * these, the list looked completely EMPTY right after "Back up to cloud"
     * (the exact moment the snackbar's View lands the user there). Restores
     * deliberately carry none, so they stay row-less as before.
     */
    public static final String TAG_NAME = "bname:";
    public static final String TAG_MIME = "bmime:";
    public static final String TAG_SIZE = "bsize:";
    /** The exact thumbnail frame position (µs) the Downloads list uses for this
     *  download (GlideHelper.thumbnailFrameUs), so the stored preview matches. */
    public static final String KEY_FRAME_US = "frame_us";

    /** Output-data keys read by the Downloads fragment to report a result. */
    public static final String KEY_STATUS = "status";
    public static final String STATUS_OK = "ok";
    public static final String STATUS_ERROR = "error";
    /**
     * Output data: the server's error slug on a terminal failure, so the list
     * row can NAME the cause instead of saying only "Backup failed". The slugs
     * that matter to a user are the 402 pair — "quota-exhausted" (unmetered flat
     * cap) and "payment-required" (metered, no credit) — plus "payload-too-large"
     * (the FILE is over the per-object cap, a different problem from being out of
     * room). Absent for the retryable/unknown failures, which stay generic.
     */
    public static final String KEY_ERROR_SLUG = "error_slug";

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

    /**
     * Builds and enqueues the backup worker for one finished download — the ONE
     * definition of the request shape (input data, identity tags, constraints)
     * and the unique-work key, shared by every door: the download options
     * sheet / multi-select ({@code BaseDownloadFragment}) and the
     * download-finished notification's "Back up to cloud" action
     * ({@link CloudBackupNotificationReceiver}). Returns the request so a
     * caller with a lifecycle can observe its terminal {@code WorkInfo} (the
     * fragment's failure snackbar); fire-and-forget callers ignore it.
     *
     * <p>UNIQUE per file CONTENT (name + size), REPLACE. Keyed on content — NOT
     * the path — because the SAME video downloaded twice lands at two different
     * paths with the same name+size; a path key let both run concurrently
     * (4 workers all uploading the same 665 MB file were seen on-device,
     * spamming "setProgressAsync must complete before Result" and making cancel
     * useless). name+size matches the engine's own dedup key, so unique work
     * still collapses every backup of the same content to ONE worker. REPLACE
     * (was KEEP) so a re-tap means RETRY NOW: under KEEP a wedged/back-off
     * worker made "Back up to cloud" a silent no-op — there was NO way to kick
     * a stuck backup (on-device: legacy pre-fix workers spinning in retry
     * backoff for hours, un-cancellable rows). REPLACE cancels the old attempt
     * and starts fresh; rapid duplicate taps still collapse (each replaces the
     * last, one survivor), a replaced partial upload is just a pending orphan
     * the server's ReapPending sweeps, and a re-tap after SUCCESS re-runs into
     * the engine's commit-time dedup which returns the existing entry (no
     * duplicate, no re-upload of the object).
     */
    public static OneTimeWorkRequest enqueue(Context context, DownloadEntity entity) {
        // The origin URL to preserve in the manifest, so a restored file's row
        // shows its real MIME · domain — same source the list adapter parses
        // the domain from (origin URL first, media URL as the fallback).
        String origin = entity.getOriginUrl();
        if (origin == null || origin.isEmpty()) {
            origin = entity.getFileUrl();
        }
        Data input = new Data.Builder()
                .putString(KEY_PATH, entity.getFilePath())
                .putString(KEY_MIME, entity.getFileMimeType())
                .putString(KEY_NAME, entity.getFileName())
                .putString(KEY_ORIGIN, origin)
                // The download's own date, so a restored copy keeps it instead of
                // being stamped with the backup time (see KEY_DOWNLOADED_AT).
                .putLong(KEY_DOWNLOADED_AT, entity.getFileDate())
                // Reuse the exact frame the Downloads list renders, so the stored
                // preview is the same (precise) frame, not a guessed offset.
                .putLong(KEY_FRAME_US, GlideHelper.thumbnailFrameUs(entity))
                .build();
        Constraints constraints = new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build();
        OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(VaultBackupWorker.class)
                .setInputData(input)
                .setConstraints(constraints)
                .addTag(CloudBackupManager.WORK_TAG)
                // Identity tags so the backed-up-files list can render a transfer
                // row while the worker is still ENQUEUED — WorkInfo exposes tags
                // but NOT input data, and progress is empty until the worker
                // starts, which made the list look EMPTY right after "Back up to
                // cloud" (the moment the snackbar's View opens it). Name payload
                // capped (tags are DB rows, filenames can be huge; display-only).
                .addTag(TAG_NAME + truncateTag(entity.getFileName()))
                .addTag(TAG_MIME + truncateTag(entity.getFileMimeType()))
                .addTag(TAG_SIZE + entity.getFileSize())
                .build();
        String uniqueName = "cloud_backup:" + entity.getFileName() + ":" + entity.getFileSize();
        WorkManager.getInstance(context.getApplicationContext())
                .enqueueUniqueWork(uniqueName, ExistingWorkPolicy.REPLACE, request);
        return request;
    }

    /** Caps a tag payload (a null value becomes ""; a huge filename is trimmed —
     *  the tag identifies a transfer row, so display fidelity is enough). */
    private static String truncateTag(String value) {
        if (value == null) {
            return "";
        }
        return value.length() <= 120 ? value : value.substring(0, 120);
    }

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
        String origin = getInputData().getString(KEY_ORIGIN);
        long downloadedAt = getInputData().getLong(KEY_DOWNLOADED_AT, 0L);
        long frameUs = getInputData().getLong(KEY_FRAME_US, 0L);
        if (path == null) {
            if (BuildConfig.DEBUG) {
                Log.e(TAG, "backup failed: no path in input data");
            }
            return failure();
        }
        File file = new File(path);
        // exists()/canRead() false ALSO means "present but unreadable" — the state
        // of a RESTORED foreign-owned file (Android 11+: a reinstalled app doesn't
        // own its old public files; a direct File open EACCES-es). Resolve access
        // the way every other read path does (RestoredFileAccess): the owned file
        // first, then the persisted SAF grant. Without this, the raw open's
        // FileNotFoundException fell into the transient-IOException branch and the
        // worker silently RETRIED for hours — the on-device "stuck at Backing up…
        // 0%" on a restored file. Only when NEITHER path opens is the file
        // genuinely inaccessible — a PERMANENT condition, failed terminally
        // (a retry can't grow a grant).
        final boolean direct = file.exists() && file.canRead();
        long size = direct ? file.length() : RestoredFileAccess.length(mContext, path);
        if (!direct) {
            ParcelFileDescriptor probe = RestoredFileAccess.openReadOnly(mContext, path);
            boolean readable = probe != null && size > 0;
            if (probe != null) {
                try {
                    probe.close();
                } catch (IOException ignored) {
                    // probe only
                }
            }
            if (!readable) {
                if (BuildConfig.DEBUG) {
                    Log.e(TAG, "backup failed: file missing/unreadable (no SAF grant?): " + path);
                }
                return failure();
            }
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
            if (BuildConfig.DEBUG) {
                Log.e(TAG, "backup failed: no recovery code (signed out between enqueue and run?)");
            }
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
        // Context-aware: reads a RESTORED foreign-owned file via the SAF grant,
        // so restored files back up WITH a preview instead of the mime glyph.
        String thumb = VaultThumbnail.generate(mContext, path, mime, frameUs);
        if (BuildConfig.DEBUG) {
            // A null here is why a backed-up file can show a thumbnail on the
            // device that uploaded it and a mime glyph on every other device
            // sharing the recovery code: the row falls back to a preview decoded
            // from the LOCAL file, which the other device does not have. It used
            // to fail SILENTLY, which is what made that class of report hard to
            // attribute — so say so.
            Log.d(TAG, "thumb for " + mime + ": "
                    + (thumb == null ? "NULL (row will fall back to the local file, "
                            + "and other devices will show the mime glyph)"
                            : thumb.length() + " b64 chars"));
        }
        // Publish the file's identity + a determinate per-chunk progress so the
        // backed-up-files list can show a per-item bar (like the Downloads list).
        final String fName = name;
        final String fMime = mime;
        publishProgress(fName, fMime, 0, size);
        try {
            // Register ONCE per install (not per backup) — Cloudflare rate-limits
            // the register endpoints, so re-registering on every upload bursts them.
            CloudBackupManager.ensureRegistered(mPrefs, api, identity);
            if (direct) {
                engine.backupFile(file, mime, thumb,
                        (done, total) -> publishProgress(fName, fMime, done, total),
                        origin, downloadedAt);
            } else {
                // Restored foreign-owned file: stream via the SAF grant. The engine
                // opens the source exactly once per attempt and reads sequentially.
                engine.backupStream(file.getName(), size, () -> {
                    ParcelFileDescriptor pfd = RestoredFileAccess.openReadOnly(mContext, path);
                    if (pfd == null) {
                        throw new FileNotFoundException("restored file not readable: " + path);
                    }
                    return new ParcelFileDescriptor.AutoCloseInputStream(pfd);
                }, mime, thumb, (done, total) -> publishProgress(fName, fMime, done, total),
                        origin, downloadedAt);
            }
        } catch (StorageApiClient.FatalException e) {
            // A 4xx with a slug (bad request / unknown keyset / …) won't fix itself
            // — terminal. Checked BEFORE the bare-IOException branch because
            // FatalException IS an IOException.
            if (BuildConfig.DEBUG) {
                Log.e(TAG, "backup failed (fatal server error)", e);
            }
            awaitLastProgress();
            return failure(e.slug);
        } catch (FileNotFoundException e) {
            // The file itself can't be opened (deleted mid-flight, or a foreign-
            // owned file whose grant vanished). PERMANENT — retrying re-fails
            // identically forever; must be caught BEFORE the bare-IOException
            // retry branch (FileNotFoundException IS an IOException).
            if (BuildConfig.DEBUG) {
                Log.e(TAG, "backup failed: file unreadable", e);
            }
            awaitLastProgress();
            return failure();
        } catch (IOException e) {
            // Everything retryable: a 429/5xx (TransientException) AND a bare
            // socket drop / read timeout mid-upload (a plain IOException OkHttp
            // throws, which is NOT a TransientException) AND manifest OCC
            // exhaustion. On a flaky mobile link these are the common case — retry
            // with WorkManager's backoff instead of failing the backup permanently
            // and orphaning the uploaded object.
            if (BuildConfig.DEBUG) {
                Log.e(TAG, "backup attempt " + getRunAttemptCount() + " failed (transient)", e);
            }
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
            if (BuildConfig.DEBUG) {
                Log.e(TAG, "backup failed (unexpected)", e);
            }
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
        return failure(null);
    }

    /** @param slug the server's error slug when there was one, else null — the
     *              list row uses it to name the cause. */
    private Result failure(@Nullable String slug) {
        Data.Builder out = new Data.Builder().putString(KEY_STATUS, STATUS_ERROR);
        if (slug != null) {
            out.putString(KEY_ERROR_SLUG, slug);
        }
        return Result.failure(out.build());
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
                // Open the Backups files list DIRECTLY (not the Cloud settings
                // screen) so tapping the "Backing up…" notification lands on the
                // live per-item progress — the items being uploaded.
                .setContentIntent(cloudBackupFilesIntent(mContext))
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

    /** PendingIntent that opens the Downloads-backup status screen (the merged
     *  Cloud settings screen). Used by the restore notification. */
    static PendingIntent cloudBackupIntent(Context context) {
        Intent intent = new Intent(context, SettingsActivity.class);
        intent.putExtra(SettingsActivity.EXTRA_OPEN_CLOUD_BACKUP, true);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        return PendingIntent.getActivity(context, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    /** PendingIntent that opens the backed-up-FILES list directly, so the backup
     *  notification lands on the live per-item progress (Back returns to the
     *  caller, not into the settings tree — see SettingsActivity). A distinct
     *  request code from {@link #cloudBackupIntent} so the two PendingIntents
     *  don't collide under FLAG_UPDATE_CURRENT. */
    static PendingIntent cloudBackupFilesIntent(Context context) {
        Intent intent = new Intent(context, SettingsActivity.class);
        intent.putExtra(SettingsActivity.EXTRA_OPEN_CLOUD_BACKUP_FILES, true);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        return PendingIntent.getActivity(context, 1, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }
}
