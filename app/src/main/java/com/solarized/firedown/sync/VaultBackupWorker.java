package com.solarized.firedown.sync;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.graphics.BitmapFactory;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;
import androidx.hilt.work.HiltWorker;
import androidx.work.Data;
import androidx.work.ExistingWorkPolicy;
import androidx.work.ForegroundInfo;
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

/**
 * Backs one finished download up into the backup folder (encrypted → one
 * object file + its sidecar, see {@link VaultEngine}). Runs as a foreground
 * worker so a large file isn't killed when the app is backgrounded — the app
 * already declares the {@code dataSync} foreground-service type its downloads
 * use. No network constraint for a FOLDER: it is written through its
 * provider (a local copy for Drive/Dropbox/Nextcloud, which upload on their
 * own; the SD card needs nothing), so a backup works offline. A WebDAV server
 * or an S3 bucket is a request per byte, so those carry a connection
 * constraint ({@link BackupStores#transferConstraints}).
 *
 * <p>This worker only loads the shared recovery-code identity, drives the
 * engine, and reports a terminal status the UI observes. The one failure it
 * NAMES is the place being unreachable ({@link #SLUG_FOLDER}, or
 * {@link #SLUG_SERVER} for a WebDAV server or an S3 bucket): terminal, and told once as a
 * notification (tap → the Backup screen, whose hero shows the lost state with
 * "Choose the folder again" / "Reconnect"). No key/credential data is ever
 * logged.
 */
@HiltWorker
public class VaultBackupWorker extends Worker {

    private static final String TAG = VaultBackupWorker.class.getSimpleName();

    /** Input-data keys (set by the enqueuing fragment). */
    public static final String KEY_PATH = "path";
    public static final String KEY_MIME = "mime";
    public static final String KEY_NAME = "name";
    /** The download's origin URL, stored in the sidecar so a restored file's row
     *  shows its real {@code MIME · domain} instead of a blank domain (nullable). */
    public static final String KEY_ORIGIN = "origin";
    /** The download's OWN date (epoch millis), stored so a restored file lands
     *  in the same date section the original sat in. 0 / absent = unknown. */
    public static final String KEY_DOWNLOADED_AT = "downloaded_at";

    /**
     * Tag prefixes the enqueuing fragment stamps on the BACKUP request so the
     * Backups list can render a transfer row for an ENQUEUED worker.
     * {@code WorkInfo} exposes tags but NOT input data, and progress is empty
     * until the worker actually starts (FGS spin-up + code load) — so without
     * these, the list looked completely EMPTY right after "Back up" (the exact
     * moment the snackbar's View lands the user there). Restores deliberately
     * carry none, so they stay row-less as before.
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
    /** Output data: the named cause of a terminal failure, so the list row can
     *  say WHY instead of only "Backup failed". Absent for the retryable /
     *  unknown failures, which stay generic. */
    public static final String KEY_ERROR_SLUG = "error_slug";
    /** The folder could not be reached (grant revoked, provider gone, card
     *  removed) — the one cause worth its own words. */
    public static final String SLUG_FOLDER = "folder";
    /** The WebDAV server could not be reached or refused us (offline, the
     *  password changed, its certificate changed). The folder's twin, with
     *  the server's words: the remedy is "Reconnect", not "choose it again". */
    public static final String SLUG_SERVER = "server";
    /** No backup folder has been chosen (a job queued by a build that still
     *  had one, or the folder was forgotten between enqueue and run). */
    public static final String SLUG_NOT_SET_UP = "not-set-up";
    /** The FILE could not be read (deleted mid-flight, or a foreign-owned
     *  restored file whose grant vanished). Permanent for this file. */
    public static final String SLUG_FILE = "file";
    /** A write on a REACHABLE folder kept failing past the retry ceiling (a
     *  provider refusing the write, a full disk). */
    public static final String SLUG_WRITE = "write";
    /** The place answered that it is out of space: a full WebDAV server
     *  (507), an S3 storage cap or quota. Terminal (a retry fills nothing)
     *  and in its own words: the remedy is at the provider, not here. */
    public static final String SLUG_FULL = "full";
    /** Anything else — the generic sentence, never silence. */
    public static final String SLUG_ERROR = "error";

    /** Progress-data keys the Backups list reads to render a per-item
     *  determinate bar (KEY_NAME/KEY_MIME above identify the file). */
    public static final String KEY_PROGRESS_DONE = "progress_done";
    public static final String KEY_PROGRESS_TOTAL = "progress_total";

    /** A fixed notification id for the in-progress foreground notification. */
    private static final int NOTIFICATION_ID = 0x6242; // "Bb"
    /** The ONE "can't reach your backup folder" notification — a fixed id so
     *  a burst of failing backups replaces it rather than stacking. */
    private static final int NOTIFICATION_FOLDER_LOST_ID = 0x6246; // "Bf"
    /** The ONE "couldn't back up <file>" notification for every OTHER terminal
     *  failure — same fixed-id rule; the Backups list holds the per-file rows. */
    private static final int NOTIFICATION_FAILED_ID = 0x6245; // "Be"

    /** Give-up ceiling for the retry branch: a backup that keeps failing on a
     *  reachable folder (a provider that refuses the write, a full disk) must
     *  not retry forever. getRunAttemptCount() is 0 on the first run. */
    private static final int MAX_RUN_ATTEMPTS = 5;

    private final Context mContext;
    private final SharedPreferences mPrefs;
    /** The most recent setProgressAsync future, drained before returning a Result. */
    private ListenableFuture<Void> mLastProgress;
    /** The most recent foreground-notification update (the determinate bar),
     *  drained with the progress for the same reason: WorkManager fails a
     *  setForegroundAsync that lands after the Result, and the failure must
     *  not be left to surface as a stray error after the work is done. */
    private ListenableFuture<Void> mLastForeground;
    /** Last published whole-percent, to throttle per-chunk progress writes. */
    private int mLastPct = -1;
    /** The in-flight engine, so {@link #onStopped()} can stop it at the next
     *  chunk — {@code Worker} threads are NOT interrupted on cancel. */
    private volatile VaultEngine mEngine;

    /**
     * Builds and enqueues the backup worker for one finished download — the ONE
     * definition of the request shape (input data, identity tags) and the
     * unique-work key, shared by every door: the download options sheet /
     * multi-select ({@code BaseDownloadFragment}) and the download-finished
     * notification's "Back up" action ({@link CloudBackupNotificationReceiver}).
     *
     * <p>UNIQUE per file CONTENT (name + size), REPLACE. Keyed on content — NOT
     * the path — because the SAME video downloaded twice lands at two different
     * paths with the same name+size; name+size matches the engine's own dedup
     * key, so unique work still collapses every backup of the same content to
     * ONE worker. REPLACE so a re-tap means RETRY NOW: under KEEP a wedged
     * worker made "Back up" a silent no-op. A replaced partial write is freed by
     * the engine's own cleanup, and a re-tap after SUCCESS re-runs into the
     * engine's dedup, which returns the existing entry.
     */
    public static OneTimeWorkRequest enqueue(Context context, DownloadEntity entity) {
        String origin = entity.getOriginUrl();
        if (origin == null || origin.isEmpty()) {
            origin = entity.getFileUrl();
        }
        Data input = new Data.Builder()
                .putString(KEY_PATH, entity.getFilePath())
                .putString(KEY_MIME, entity.getFileMimeType())
                .putString(KEY_NAME, entity.getFileName())
                .putString(KEY_ORIGIN, origin)
                .putLong(KEY_DOWNLOADED_AT, entity.getFileDate())
                .putLong(KEY_FRAME_US, GlideHelper.thumbnailFrameUs(entity))
                .build();
        OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(VaultBackupWorker.class)
                .setInputData(input)
                .setConstraints(BackupStores.transferConstraints(context))
                .addTag(CloudBackupManager.WORK_TAG)
                .addTag(TAG_NAME + truncateTag(entity.getFileName()))
                .addTag(TAG_MIME + truncateTag(entity.getFileMimeType()))
                .addTag(TAG_SIZE + entity.getFileSize())
                .build();
        WorkManager.getInstance(context.getApplicationContext())
                .enqueueUniqueWork(uniqueName(entity), ExistingWorkPolicy.REPLACE, request);
        return request;
    }

    /** The unique-work name of this download's backup (content-keyed, see
     *  {@link #enqueue}); what {@link #cancel} cancels. */
    public static String uniqueName(DownloadEntity entity) {
        return "cloud_backup:" + entity.getFileName() + ":" + entity.getFileSize();
    }

    /**
     * Cancels this download's in-flight backup (the Downloads row's "Cancel
     * backup", the twin of the Backups list's per-row cancel and the
     * notification's action). A no-op when nothing is enqueued or running for
     * it; a cancelled worker frees its partial object through
     * {@link #onStopped()}.
     */
    public static void cancel(Context context, DownloadEntity entity) {
        WorkManager.getInstance(context.getApplicationContext())
                .cancelUniqueWork(uniqueName(entity));
    }

    /**
     * The key under which the Downloads list matches a row to this file's
     * in-flight backup — the content key ({@link CloudBackupManager#contentKey})
     * built from the TRUNCATED name, because the only identity a queued work
     * carries is its {@code bname:} tag (progress data exists only once the
     * worker runs). Both sides (the fragment reading the work, the adapter
     * reading the row) go through this, so a filename past the tag cap still
     * matches instead of silently never showing its progress.
     */
    public static String progressKey(String name, long size) {
        return CloudBackupManager.contentKey(truncateTag(name), size);
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
            SharedPreferences prefs) {
        super(context, params);
        this.mContext = context;
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
            return failTerminal(name, SLUG_ERROR);
        }
        BackupStore store = BackupStores.open(mContext, mPrefs);
        if (!store.isConfigured()) {
            return failure(SLUG_NOT_SET_UP);
        }
        File file = new File(path);
        // exists()/canRead() false ALSO means "present but unreadable" — the
        // state of a RESTORED foreign-owned file (Android 11+). Resolve access
        // the way every other read path does (RestoredFileAccess): the owned
        // file first, then the persisted SAF grant. Only when NEITHER opens is
        // the file genuinely inaccessible — PERMANENT, failed terminally.
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
                return failTerminal(name, SLUG_FILE);
            }
        }

        // Promote to foreground for the write (dataSync). Best-effort: if the OS
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
            return failure(SLUG_NOT_SET_UP);
        }
        SyncIdentity identity;
        try {
            identity = SyncIdentity.fromCode(code);
        } finally {
            SyncSecrets.wipe(code);
        }

        VaultEngine engine = new VaultEngine(store, identity);
        mEngine = engine;
        if (isStopped()) {
            engine.cancel();
        }
        // A tiny preview, stored in the encrypted sidecar so the list shows a
        // thumbnail even after the local copy is deleted, on every phone sharing
        // the code. Context-aware: reads a RESTORED foreign-owned file via the
        // SAF grant.
        String thumb = VaultThumbnail.generate(mContext, path, mime, frameUs);
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "thumb for " + mime + ": "
                    + (thumb == null ? "NULL (row will fall back to the local file, "
                            + "and other phones will show the mime glyph)"
                            : thumb.length() + " b64 chars"));
        }
        final String fName = name;
        final String fMime = mime;
        publishProgress(fName, fMime, 0, size);
        try {
            if (direct) {
                engine.backupFile(file, mime, thumb,
                        (done, total) -> publishProgress(fName, fMime, done, total),
                        origin, downloadedAt);
            } else {
                engine.backupStream(file.getName(), size, () -> {
                    ParcelFileDescriptor pfd = RestoredFileAccess.openReadOnly(mContext, path);
                    if (pfd == null) {
                        throw new FileNotFoundException("restored file not readable: " + path);
                    }
                    return new ParcelFileDescriptor.AutoCloseInputStream(pfd);
                }, mime, thumb, (done, total) -> publishProgress(fName, fMime, done, total),
                        origin, downloadedAt);
            }
        } catch (BackupStore.UnreachableException e) {
            // The FOLDER, not the file: grant revoked, provider gone, card out,
            // a server that refused the sign-in or changed its certificate.
            // Terminal — a retry cannot grow a grant — and told ONCE as a
            // notification, because the Backup hero's lost state is only seen
            // by someone who opens the app. Checked BEFORE the bare-IOException
            // branch (it IS an IOException).
            if (BuildConfig.DEBUG) {
                Log.e(TAG, "backup failed: folder unreachable", e);
            }
            awaitLastProgress();
            boolean server = store instanceof WebDavStore || store instanceof S3Store;
            if (!isStopped()) {
                notifyPlaceLost(store.providerLabel(), server);
            }
            return failure(server ? SLUG_SERVER : SLUG_FOLDER);
        } catch (BackupStore.FullException e) {
            // The place answered "out of space". Retrying fills nothing: the
            // user frees space there or raises the cap. Before the bare
            // IOException branch (it IS an IOException).
            if (BuildConfig.DEBUG) {
                Log.e(TAG, "backup failed: the place is full", e);
            }
            awaitLastProgress();
            return failTerminal(name, SLUG_FULL);
        } catch (FileNotFoundException e) {
            // The file itself can't be opened (deleted mid-flight, or a foreign-
            // owned file whose grant vanished). PERMANENT.
            if (BuildConfig.DEBUG) {
                Log.e(TAG, "backup failed: file unreadable", e);
            }
            awaitLastProgress();
            return failTerminal(name, SLUG_FILE);
        } catch (IOException e) {
            // A failed write on a reachable folder, a cancel, a provider hiccup:
            // retry with WorkManager's backoff, bounded.
            if (BuildConfig.DEBUG) {
                Log.e(TAG, "backup attempt " + getRunAttemptCount() + " failed (transient)", e);
            }
            awaitLastProgress();
            if (isStopped()) {
                // A cancel lands here as an IOException on the stream; it is the
                // user's own stop, not a failure to announce.
                return failure(SLUG_WRITE);
            }
            if (getRunAttemptCount() >= MAX_RUN_ATTEMPTS) {
                return failTerminal(name, SLUG_WRITE);
            }
            return Result.retry();
        } catch (Exception e) {
            if (BuildConfig.DEBUG) {
                Log.e(TAG, "backup failed (unexpected)", e);
            }
            awaitLastProgress();
            return failTerminal(name, SLUG_ERROR);
        }

        // A pending setProgressAsync MUST complete before we return a Result, or
        // WorkManager throws "Calls to setProgressAsync() must complete before a
        // ListenableWorker signals completion".
        awaitLastProgress();
        // The first successful backup marks Backup as in use, so the shared
        // recovery code survives a bookmark-sync sign-out (see SyncManager).
        mPrefs.edit().putBoolean(Preferences.CLOUD_BACKUP_ENABLED, true).apply();
        return Result.success(new Data.Builder()
                .putString(KEY_STATUS, STATUS_OK)
                .build());
    }

    @Override
    public void onStopped() {
        VaultEngine engine = mEngine;
        if (engine != null) {
            engine.cancel();
        }
        super.onStopped();
    }

    /**
     * A terminal failure that is NOT the folder's: told as the "couldn't back
     * up <file>" notification (the folder's own failure has its own, see
     * {@link #notifyPlaceLost}) and returned with its slug. A stopped worker
     * announces nothing — the stop was the user's.
     */
    private Result failTerminal(@Nullable String name, @NonNull String slug) {
        if (!isStopped()) {
            notifyFailed(name, slug);
        }
        return failure(slug);
    }

    /** @param slug the named cause when there is one, else null — the list row
     *              uses it to say why. */
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
        // The foreground notification rides the same whole-percent throttle:
        // a determinate bar outside the app for a transfer the user can leave
        // the screen for. Best-effort — a refused update changes nothing about
        // the backup (and the Downloads row carries the same percent).
        if (total > 0) {
            try {
                mLastForeground = setForegroundAsync(foregroundInfo(name, pct));
            } catch (RuntimeException ignored) {
                // Not foreground (the OS refused at start) — the bar stays
                // wherever it was.
            }
        }
    }

    /** Blocks until the most recent progress update has been persisted — required
     *  before returning a Result (see the doWork comment). Best-effort. */
    private void awaitLastProgress() {
        ListenableFuture<Void> progress = mLastProgress;
        ListenableFuture<Void> foreground = mLastForeground;
        mLastProgress = null;
        mLastForeground = null;
        drain(progress);
        drain(foreground);
    }

    private static void drain(ListenableFuture<Void> f) {
        if (f == null) {
            return;
        }
        try {
            f.get();
        } catch (Exception ignored) {
            // interrupted / already-completing — the worker is about to finish anyway.
        }
    }

    private ForegroundInfo foregroundInfo(String name) {
        return foregroundInfo(name, -1);
    }

    /**
     * The in-progress foreground notification. Short and branded: the action
     * is the title, the file name is the body (ellipsized by the system).
     * Tapping opens the Backups list (the live per-item progress), like a
     * download notification opens Downloads. {@code pct} below 0 is the
     * pre-first-byte state (indeterminate); from the first chunk on the bar is
     * determinate and re-posted per whole percent by {@link #publishProgress}.
     * Carries a Cancel action, because this notification is the only surface
     * a user who left the app has for a transfer that may run for minutes —
     * the same cancel the Backups list's transfer row and the Downloads
     * row's sheet offer, through WorkManager's own cancel intent.
     */
    private ForegroundInfo foregroundInfo(String name, int pct) {
        String title = mContext.getString(R.string.cloud_backup_notification_title);
        PendingIntent cancel = WorkManager.getInstance(mContext)
                .createCancelPendingIntent(getId());
        NotificationCompat.Builder builder = new NotificationCompat.Builder(
                mContext, App.DOWNLOADS_NOTIFICATION_ID)
                .setSmallIcon(R.drawable.cloud_upload_24)
                .setLargeIcon(BitmapFactory.decodeResource(
                        mContext.getResources(), R.mipmap.ic_launcher_round))
                .setContentTitle(title)
                .setContentText(name != null ? name : title)
                .setContentIntent(cloudBackupFilesIntent(mContext))
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .addAction(0, mContext.getString(android.R.string.cancel), cancel)
                .setPriority(NotificationCompat.PRIORITY_LOW);
        if (pct < 0) {
            builder.setProgress(0, 0, true); // indeterminate
        } else {
            builder.setProgress(100, pct, false);
        }
        Notification notification = builder.build();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return new ForegroundInfo(NOTIFICATION_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        }
        return new ForegroundInfo(NOTIFICATION_ID, notification);
    }

    /**
     * The ONE notification the folder model posts: a backup failed because the
     * folder could not be reached. Tap → the Backup screen, whose hero shows
     * the folder-lost state and the "Choose the folder again" CTA. Silent when
     * notifications are denied (the hero and the Backups header carry the
     * state regardless). Fixed id: a batch of failing backups replaces it.
     */
    private void notifyPlaceLost(@Nullable String place, boolean server) {
        if (ActivityCompat.checkSelfPermission(mContext, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        NotificationManager nm = (NotificationManager)
                mContext.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) {
            return;
        }
        // The PLACE is named, never the file: "Firedown can't reach Google
        // Drive", not "can't reach video.mp4" (the file name used to be
        // passed here, and the sentence named the wrong thing).
        boolean named = !TextUtils.isEmpty(place);
        String title = mContext.getString(server
                ? R.string.webdav_lost_notif_title : R.string.folder_backup_lost_notif_title);
        String text;
        if (server) {
            text = mContext.getString(R.string.webdav_lost_notif_text,
                    named ? place : mContext.getString(R.string.folder_backup_place_webdav));
        } else if (named) {
            text = mContext.getString(R.string.folder_backup_lost_notif_text, place);
        } else {
            text = mContext.getString(R.string.folder_backup_lost_notif_text_noname);
        }
        Notification notification = new NotificationCompat.Builder(
                mContext, App.DOWNLOADS_NOTIFICATION_ID)
                .setSmallIcon(R.drawable.cloud_off_24)
                .setLargeIcon(BitmapFactory.decodeResource(
                        mContext.getResources(), R.mipmap.ic_launcher_round))
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(text))
                .setContentIntent(cloudBackupIntent(mContext))
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .build();
        nm.notify(NOTIFICATION_FOLDER_LOST_ID, notification);
    }

    /**
     * "Couldn't back up <file>" — every terminal failure that is not the
     * folder's is announced, because the two in-app surfaces that name it are
     * both conditional: the Downloads snackbar needs that fragment alive, the
     * Backups list's failed row needs the list opened. A backup started from the
     * finished-download notification and left alone used to fail in silence.
     * The text is the slug's sentence (the ONE mapping, {@link #failureSummaryRes});
     * tap → the Backups list, where the failed row sits with its ✕. Fixed id,
     * so a failing batch replaces rather than stacks; the rows are the record.
     */
    private void notifyFailed(@Nullable String name, @NonNull String slug) {
        if (ActivityCompat.checkSelfPermission(mContext, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        NotificationManager nm = (NotificationManager)
                mContext.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) {
            return;
        }
        String title = name != null
                ? mContext.getString(R.string.folder_backup_failed_notif_title, name)
                : mContext.getString(R.string.folder_backup_failed_notif_title_noname);
        String text = mContext.getString(failureSummaryRes(slug));
        Notification notification = new NotificationCompat.Builder(
                mContext, App.DOWNLOADS_NOTIFICATION_ID)
                .setSmallIcon(R.drawable.cloud_upload_24)
                .setLargeIcon(BitmapFactory.decodeResource(
                        mContext.getResources(), R.mipmap.ic_launcher_round))
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(text))
                .setContentIntent(cloudBackupFilesIntent(mContext))
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .build();
        nm.notify(NOTIFICATION_FAILED_ID, notification);
    }

    /**
     * The ONE slug → sentence mapping: the Backups list's snackbar, the
     * Downloads snackbar and the failure notification all read it, so a
     * failure says the same thing everywhere. A null or unknown slug (a
     * worker from an older build) gets the generic sentence, never silence.
     */
    @StringRes
    public static int failureSummaryRes(@Nullable String slug) {
        if (slug == null) {
            return R.string.cloud_backup_failed;
        }
        switch (slug) {
            case SLUG_FOLDER:
                return R.string.folder_backup_transfer_lost;
            case SLUG_SERVER:
                return R.string.webdav_transfer_lost;
            case SLUG_NOT_SET_UP:
                return R.string.folder_backup_transfer_not_set_up;
            case SLUG_FILE:
                return R.string.folder_backup_transfer_file;
            case SLUG_WRITE:
                return R.string.folder_backup_transfer_write;
            case SLUG_FULL:
                return R.string.backup_transfer_full;
            default:
                return R.string.cloud_backup_failed;
        }
    }

    /** The slug's SHORT label for the failed transfer row (one line beside the
     *  mime chip); same null/unknown rule as {@link #failureSummaryRes}. */
    @StringRes
    public static int failureLabelRes(@Nullable String slug) {
        if (slug == null) {
            return R.string.cloud_backup_transfer_failed;
        }
        switch (slug) {
            case SLUG_FOLDER:
                return R.string.folder_backup_state_lost;
            case SLUG_SERVER:
                return R.string.webdav_state_lost;
            case SLUG_NOT_SET_UP:
                return R.string.folder_backup_state_not_set_up;
            case SLUG_FILE:
                return R.string.folder_backup_state_file;
            case SLUG_WRITE:
                return R.string.folder_backup_state_write;
            case SLUG_FULL:
                return R.string.backup_state_full;
            default:
                return R.string.cloud_backup_transfer_failed;
        }
    }

    /** True when the Backup screen is the remedy (choose the folder again,
     *  finish the setup) — the snackbar carries a door only then. */
    public static boolean failureFixedInBackupScreen(@Nullable String slug) {
        return SLUG_FOLDER.equals(slug) || SLUG_SERVER.equals(slug)
                || SLUG_NOT_SET_UP.equals(slug);
    }

    /** PendingIntent that opens the Backup screen (the merged settings screen
     *  with the hero). Used by the restore notification and the folder-lost
     *  notification. */
    static PendingIntent cloudBackupIntent(Context context) {
        Intent intent = new Intent(context, SettingsActivity.class);
        intent.putExtra(SettingsActivity.EXTRA_OPEN_CLOUD_BACKUP, true);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        return PendingIntent.getActivity(context, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    /** PendingIntent that opens the Backups list directly, so the backup
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
