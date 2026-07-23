package com.solarized.firedown.sync;

import android.app.Notification;
import android.content.Context;
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
import com.solarized.firedown.Preferences;
import com.solarized.firedown.R;
import com.solarized.firedown.StoragePaths;
import com.solarized.firedown.data.Download;
import com.solarized.firedown.data.entity.DownloadEntity;
import com.solarized.firedown.data.repository.DownloadDataRepository;
import com.solarized.firedown.manager.UrlParser;
import com.solarized.firedown.sync.crypto.SyncIdentity;
import com.solarized.firedown.sync.model.VaultEntry;

import java.io.File;
import java.io.IOException;

import dagger.assisted.Assisted;
import dagger.assisted.AssistedInject;
import okhttp3.OkHttpClient;

/**
 * Restores one backed-up file from Cloud Backup into the public
 * {@code Download/Firedown} folder and registers it as a FINISHED download so it
 * appears in the Downloads list — the reverse of {@link VaultBackupWorker}.
 *
 * <p>The fetch + decrypt + reassemble is {@link VaultEngine#restoreFile}; this
 * worker chooses a non-colliding destination (mirroring the normal download
 * path), writes there, and inserts a {@link DownloadEntity} via the same
 * repository every download uses. Runs as a foreground (dataSync) worker so a
 * large restore isn't killed when backgrounded.
 */
@HiltWorker
public class VaultRestoreWorker extends Worker {

    /** Input-data keys (the VaultEntry fields, set by the list fragment). */
    public static final String KEY_OBJECT_ID = "object_id";
    public static final String KEY_WRAPPED_DEK = "wrapped_dek";
    public static final String KEY_NAME = "name";
    public static final String KEY_MIME = "mime";
    public static final String KEY_SIZE = "size";
    public static final String KEY_DOWNLOADED_AT = "downloaded_at";
    public static final String KEY_CHUNK_COUNT = "chunk_count";
    /** The download's origin URL from the manifest (nullable — absent on files
     *  backed up before it was stored), used for the restored row's MIME · domain. */
    public static final String KEY_ORIGIN = "origin";

    /** Synthetic origin for a restored file whose manifest entry predates
     *  {@link #KEY_ORIGIN} (so its real domain is unknown). Names the transport
     *  honestly rather than leaving the row's domain blank — same convention as a
     *  P2P-received file's {@code p2p://<device>} origin. */
    private static final String RESTORED_ORIGIN_FALLBACK = "cloud://firedown";

    /** Output-data keys read by the list fragment to report a result. */
    public static final String KEY_STATUS = "status";
    public static final String STATUS_OK = "ok";
    public static final String STATUS_ERROR = "error";
    /** True when the restore was skipped because the file is already in Downloads
     *  (a no-op success — the fragment shows "Already in your downloads"). */
    public static final String KEY_ALREADY_PRESENT = "already_present";

    private static final int NOTIFICATION_ID = 0x6272; // "Br"

    private final Context mContext;
    private final OkHttpClient mClient;
    private final DownloadDataRepository mRepo;
    /** In-flight storage client, cancelled by {@link #onStopped()} (Worker threads
     *  aren't interrupted on cancel, so a rate-limit retry loop would otherwise run
     *  to exhaustion). Volatile: onStopped runs off the doWork thread. */
    private volatile StorageApiClient mApi;

    @AssistedInject
    public VaultRestoreWorker(
            @Assisted @NonNull Context context,
            @Assisted @NonNull WorkerParameters params,
            OkHttpClient client,
            DownloadDataRepository repo) {
        super(context, params);
        this.mContext = context;
        this.mClient = client;
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
        // match is by name + byte-size and is only honoured when the local file
        // still exists on disk — a stale row pointing at a deleted file falls
        // through to a real restore.
        DownloadEntity present = mRepo.findByNameSize(name, size);
        if (present != null && present.getFilePath() != null
                && new File(present.getFilePath()).exists()) {
            return Result.success(new Data.Builder()
                    .putString(KEY_STATUS, STATUS_OK)
                    .putBoolean(KEY_ALREADY_PRESENT, true)
                    .build());
        }

        try {
            setForegroundAsync(foregroundInfo(name)).get();
        } catch (Exception ignored) {
            // Couldn't go foreground — proceed in the background.
        }

        byte[] code = new SyncSecrets(mContext).load();
        if (code == null) {
            return failure();
        }
        SyncIdentity identity;
        try {
            identity = SyncIdentity.fromCode(code);
        } finally {
            SyncSecrets.wipe(code);
        }

        StoragePaths.ensureDownloadPath(mContext);
        File dest = uniqueDestination(StoragePaths.getDownloadPath(mContext)
                + File.separator + name);

        StorageApiClient api = new StorageApiClient(mClient, Preferences.STORAGE_DEFAULT_BACKEND);
        mApi = api;
        if (isStopped()) {
            api.cancel();
        }
        VaultEngine engine = new VaultEngine(api, identity);
        VaultEntry entry = new VaultEntry(objectId, wrappedDek, name, size, mime,
                downloadedAt, chunkCount, null, origin); // thumb unused on restore
        try {
            engine.restoreFile(entry, dest);
        } catch (StorageApiClient.FatalException e) {
            // A 4xx (e.g. the object was reaped) won't fix itself — terminal.
            // Checked before the bare-IOException branch (FatalException IS one).
            deleteQuietly(dest);
            return failure();
        } catch (IOException e) {
            // 429/5xx (TransientException) OR a bare socket drop / read timeout
            // mid-download — retryable; drop the partial and let WorkManager retry
            // clean rather than failing the restore on a flaky link.
            deleteQuietly(dest);
            return Result.retry();
        } catch (Exception e) {
            deleteQuietly(dest);
            return failure();
        }

        DownloadEntity download = new DownloadEntity();
        // uid keyed on the (unique) restored path so two restores never collide.
        download.setId(dest.getAbsolutePath().hashCode());
        download.setFilePath(dest.getAbsolutePath());
        download.setFileName(dest.getName());
        download.setFileMimeType(mime);
        download.setFileStatus(Download.FINISHED);
        download.setFileProgress(100);
        download.setFileSize(dest.length());
        download.setFileSafe(false);
        download.setFileDate(downloadedAt > 0 ? downloadedAt : System.currentTimeMillis());
        // Restore the origin URL so the row's MIME · domain reads like any download
        // (e.g. "youtube.com"). Older backups stored no origin — fall back to the
        // honest cloud transport label so the domain is never blank.
        String rowOrigin = (origin == null || origin.isEmpty())
                ? RESTORED_ORIGIN_FALLBACK : origin;
        download.setFileUrl(rowOrigin);
        download.setFileOriginUrl("");
        mRepo.addSync(download);

        return Result.success(new Data.Builder()
                .putString(KEY_STATUS, STATUS_OK)
                .build());
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
        // See VaultBackupWorker.onStopped — cancel the storage client so its
        // rate-limit retry loop unwinds at once instead of running to exhaustion.
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
