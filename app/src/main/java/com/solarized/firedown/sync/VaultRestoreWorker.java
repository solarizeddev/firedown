package com.solarized.firedown.sync;

import android.app.Notification;
import android.content.Context;
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

import com.solarized.firedown.App;
import com.solarized.firedown.R;
import com.solarized.firedown.StoragePaths;
import com.solarized.firedown.data.Download;
import com.solarized.firedown.data.entity.DownloadEntity;
import com.solarized.firedown.data.repository.DownloadDataRepository;
import com.solarized.firedown.manager.UrlParser;
import com.solarized.firedown.sync.crypto.SyncIdentity;
import com.solarized.firedown.sync.model.VaultEntry;
import com.solarized.firedown.utils.GalleryPublisher;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;

import dagger.assisted.Assisted;
import dagger.assisted.AssistedInject;

/**
 * Restores one backed-up file from the backup folder into the public
 * {@code Download/Firedown} folder and registers it as a FINISHED download so
 * it appears in the Downloads list — the reverse of {@link VaultBackupWorker}.
 *
 * <p>The read + decrypt + reassemble is {@link VaultEngine#restoreFile}; this
 * worker chooses a non-colliding destination (mirroring the normal download
 * path), writes there, and inserts a {@link DownloadEntity} via the same
 * repository every download uses. Runs as a foreground (dataSync) worker so a
 * large restore isn't killed when backgrounded. A provider that fetches on
 * read (Drive, with a cold cache) makes the stream a download in disguise;
 * nothing here needs to know.
 */
@HiltWorker
public class VaultRestoreWorker extends Worker {

    /**
     * Tag prefix carrying the objectId of the file being restored, so the
     * Backups list can tell WHICH entry a running restore belongs to.
     * (WorkManager exposes tags on WorkInfo but not input data.) Lets the list
     * warn before removing an entry mid-restore and cancel deterministically.
     */
    public static final String TAG_OBJECT = "robj:";

    public static final String KEY_OBJECT_ID = "object_id";
    public static final String KEY_WRAPPED_DEK = "wrapped_dek";
    public static final String KEY_NAME = "name";
    public static final String KEY_MIME = "mime";
    public static final String KEY_SIZE = "size";
    public static final String KEY_DOWNLOADED_AT = "downloaded_at";
    public static final String KEY_CHUNK_COUNT = "chunk_count";
    /** The download's origin URL from the sidecar (nullable — absent on files
     *  backed up before it was stored), used for the restored row's MIME · domain. */
    public static final String KEY_ORIGIN = "origin";

    /** Synthetic origin for a restored file whose entry carries no origin (so
     *  its real domain is unknown). Names the transport honestly rather than
     *  leaving the row's domain blank — same convention as a P2P-received
     *  file's {@code p2p://<device>} origin. */
    private static final String RESTORED_ORIGIN_FALLBACK = "backup://folder";

    /** Output-data keys read by the list fragment to report a result. */
    public static final String KEY_STATUS = "status";
    public static final String STATUS_OK = "ok";
    public static final String STATUS_ERROR = "error";
    /** True when the restore was skipped because the file is already in Downloads
     *  (a no-op success — the fragment shows "Already in your downloads"). */
    public static final String KEY_ALREADY_PRESENT = "already_present";

    private static final int NOTIFICATION_ID = 0x6272; // "Br"

    /** Retry ceiling for a persistently-failing restore. Without it a transient
     *  error that never clears would retry forever, pinning the in-flight
     *  PROGRESS row at the top of Downloads; past the cap the row resolves to a
     *  visible ERROR the user can dismiss or retry deliberately. */
    private static final int MAX_RUN_ATTEMPTS = 5;

    private final Context mContext;
    private final SharedPreferences mPrefs;
    private final DownloadDataRepository mRepo;
    /** In-flight engine, stopped by {@link #onStopped()} (Worker threads aren't
     *  interrupted on cancel). Volatile: onStopped runs off the doWork thread. */
    private volatile VaultEngine mEngine;

    @AssistedInject
    public VaultRestoreWorker(
            @Assisted @NonNull Context context,
            @Assisted @NonNull WorkerParameters params,
            SharedPreferences prefs,
            DownloadDataRepository repo) {
        super(context, params);
        this.mContext = context;
        this.mPrefs = prefs;
        this.mRepo = repo;
    }

    @NonNull
    @Override
    public Result doWork() {
        String objectId = getInputData().getString(KEY_OBJECT_ID);
        String wrappedDek = getInputData().getString(KEY_WRAPPED_DEK);
        String name = getInputData().getString(KEY_NAME);
        String mime = getInputData().getString(KEY_MIME);
        long size = getInputData().getLong(KEY_SIZE, 0);
        long downloadedAt = getInputData().getLong(KEY_DOWNLOADED_AT, 0);
        int chunkCount = getInputData().getInt(KEY_CHUNK_COUNT, 1);
        String origin = getInputData().getString(KEY_ORIGIN);
        if (objectId == null || wrappedDek == null || name == null) {
            return failure();
        }

        // Already in Downloads? Skip the restore (don't duplicate the file). The
        // match is by name + byte-size and is only honoured for a FINISHED row
        // whose local file still exists on disk (we insert our OWN in-flight
        // PROGRESS row with file_size = the full size, which must not match
        // itself on a retry).
        DownloadEntity present = mRepo.findByNameSize(name, size);
        if (present != null && present.getFileStatus() == Download.FINISHED
                && present.getFilePath() != null
                && new File(present.getFilePath()).exists()) {
            return Result.success(new Data.Builder()
                    .putString(KEY_STATUS, STATUS_OK)
                    .putBoolean(KEY_ALREADY_PRESENT, true)
                    .build());
        }

        StoragePaths.ensureDownloadPath(mContext);

        // Stable uid keyed on the OBJECT (not the destination path) so a
        // WorkManager retry finds THIS in-flight row instead of minting a second
        // one — the retry-idempotency backbone.
        int rowId = ("cloud-restore:" + objectId).hashCode();

        // Retry-idempotent destination: reuse a prior attempt's row + path (and
        // drop its partial) so a retry rewrites the SAME file/row rather than
        // drifting to "name (1)" and leaving an orphan file + a dangling row.
        DownloadEntity prior = mRepo.findByIdSync(rowId);
        File dest;
        if (prior != null && prior.getFilePath() != null) {
            dest = new File(prior.getFilePath());
            deleteQuietly(dest);
        } else {
            dest = uniqueDestination(StoragePaths.getDownloadPath(mContext)
                    + File.separator + name);
        }

        // Insert the row NOW as a LIVE download (PROGRESS, 0%) so the restore is
        // visible in the Downloads list as it streams. One reused entity drives
        // every progress tick and the terminal write (repository.add snapshots
        // each call, so mutating this object is safe).
        String rowOrigin = (origin == null || origin.isEmpty())
                ? RESTORED_ORIGIN_FALLBACK : origin;
        DownloadEntity download = new DownloadEntity();
        download.setId(rowId);
        download.setFilePath(dest.getAbsolutePath());
        download.setFileName(dest.getName());
        download.setFileMimeType(mime);
        download.setFileStatus(Download.PROGRESS);
        download.setFileProgress(0);
        download.setFileSize(size); // sidecar plaintext size — the bar's total
        download.setFileSafe(false);
        download.setFileDate(downloadedAt > 0 ? downloadedAt : System.currentTimeMillis());
        download.setFileUrl(rowOrigin);
        download.setFileOriginUrl("");
        mRepo.add(download);

        try {
            setForegroundAsync(foregroundInfo(name)).get();
        } catch (Exception ignored) {
            // Couldn't go foreground — proceed in the background.
        }

        byte[] code = new SyncSecrets(mContext).load();
        if (code == null) {
            return restoreFailed(download, dest);
        }
        SyncIdentity identity;
        try {
            identity = SyncIdentity.fromCode(code);
        } finally {
            SyncSecrets.wipe(code);
        }

        BackupStore store = BackupStores.open(mContext, mPrefs);
        VaultEngine engine = new VaultEngine(store, identity);
        mEngine = engine;
        if (isStopped()) {
            engine.cancel();
        }
        VaultEntry entry = new VaultEntry(objectId, wrappedDek, name, size, mime,
                downloadedAt, chunkCount, null, origin); // thumb unused on restore

        // Throttle progress writes to whole-percent steps (mirrors the backup
        // worker's publishProgress) so a many-chunk restore doesn't hammer the DB.
        final int[] lastPct = { -1 };
        try {
            engine.restoreFile(entry, dest, (done, total) -> {
                int pct = total > 0 ? (int) (done * 100 / total) : 0;
                if (pct != lastPct[0]) {
                    lastPct[0] = pct;
                    download.setFileProgress(pct);
                    mRepo.add(download);
                }
            });
        } catch (BackupStore.UnreachableException | FileNotFoundException e) {
            // The folder is gone, or the object is not in it (deleted from
            // another phone, or never synced here) — a retry cannot change
            // that. Mark the row ERROR (a visible failed download the user can
            // dismiss/retry), not a silent delete. Both ARE IOExceptions, so
            // this branch precedes the bare one.
            return restoreFailed(download, dest);
        } catch (IOException e) {
            // A read that failed on a reachable folder (a provider fetching
            // from the network mid-stream, a cancel) — retryable. Drop the
            // partial so the retry rewrites clean.
            deleteQuietly(dest);
            if (isStopped() || getRunAttemptCount() >= MAX_RUN_ATTEMPTS) {
                // CANCELLED (the Backups list cancels a restore whose entry is
                // being removed) or persistently failing: a cancelled worker
                // never runs its retry, so Result.retry() would strand the
                // Downloads row at PROGRESS forever. Resolve it to a visible
                // ERROR the user can dismiss.
                download.setFileStatus(Download.ERROR);
                download.setFileProgress(0);
                mRepo.add(download);
                return failure();
            }
            // Leave the row in place (WorkManager reschedules; the retry reuses it
            // via rowId and re-inserts PROGRESS 0% on its first tick).
            return Result.retry();
        } catch (Exception e) {
            return restoreFailed(download, dest);
        }

        download.setFileStatus(Download.FINISHED);
        download.setFileProgress(100);
        download.setFileSize(dest.length());
        mRepo.add(download);
        GalleryPublisher.publishIfEnabled(mContext, download.getFilePath(), download.getFileMimeType());

        return Result.success(new Data.Builder()
                .putString(KEY_STATUS, STATUS_OK)
                .build());
    }

    /** Terminal failure: mark the live row as a visible ERROR download (like a
     *  failed normal download) and drop the partial file. Returns the worker
     *  failure result. */
    private Result restoreFailed(DownloadEntity download, File dest) {
        deleteQuietly(dest);
        download.setFileStatus(Download.ERROR);
        download.setFileProgress(0);
        mRepo.add(download);
        return failure();
    }

    /** Picks a non-colliding path in Download/Firedown (mirrors TaskRunnable). */
    private static File uniqueDestination(String path) {
        File file = new File(path);
        while (file.exists()) {
            path = UrlParser.parseFilePath(path);
            file = new File(path);
        }
        return file;
    }

    private static void deleteQuietly(File file) {
        if (file != null && file.exists()) {
            //noinspection ResultOfMethodCallIgnored
            file.delete();
        }
    }

    @Override
    public void onStopped() {
        VaultEngine engine = mEngine;
        if (engine != null) {
            engine.cancel();
        }
        super.onStopped();
    }

    private Result failure() {
        return Result.failure(new Data.Builder()
                .putString(KEY_STATUS, STATUS_ERROR)
                .build());
    }

    private ForegroundInfo foregroundInfo(String name) {
        String title = mContext.getString(R.string.cloud_restore_notification_title);
        Notification notification = new NotificationCompat.Builder(
                mContext, App.DOWNLOADS_NOTIFICATION_ID)
                .setSmallIcon(R.drawable.settings_backup_restore_24)
                .setLargeIcon(BitmapFactory.decodeResource(
                        mContext.getResources(), R.mipmap.ic_launcher_round))
                .setContentTitle(title)
                .setContentText(name != null ? name : title)
                .setContentIntent(VaultBackupWorker.cloudBackupIntent(mContext))
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
}
