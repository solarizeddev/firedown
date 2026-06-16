package com.solarized.firedown.data.repository;

import android.util.Log;

import androidx.lifecycle.LiveData;
import androidx.paging.PagingSource;

import com.solarized.firedown.BuildConfig;
import com.solarized.firedown.data.DownloadDatabase;
import com.solarized.firedown.data.di.Qualifiers;
import com.solarized.firedown.data.entity.DownloadEntity;
import com.solarized.firedown.ffmpegutils.FFmpegMetaData;
import com.solarized.firedown.ffmpegutils.FFmpegMetaDataReader;
import com.solarized.firedown.ffmpegutils.FFmpegUtils;
import com.solarized.firedown.utils.Utils;

import java.io.File;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadLocalRandom;

import javax.inject.Inject;
import javax.inject.Singleton;

@Singleton
public class DownloadDataRepository {

    private static final String TAG = "DownloadRepository";

    private final DownloadDatabase mDatabase;
    private final Executor mDiskExecutor;
    private final Executor mHeavyExecutor;

    @Inject
    public DownloadDataRepository(DownloadDatabase database,
                                  @Qualifiers.DiskIO Executor diskExecutor,
                                  @Qualifiers.HeavyIO Executor heavyExecutor) {
        this.mDatabase = database;
        this.mDiskExecutor = diskExecutor;
        this.mHeavyExecutor = heavyExecutor;
    }

    // --- Paging Queries ---

    public PagingSource<Integer, DownloadEntity> getDownloads() {
        return mDatabase.downloadDao().getDownloads();
    }

    public PagingSource<Integer, DownloadEntity> getDownloadsSize() {
        return mDatabase.downloadDao().getDownloadsSize();
    }

    public PagingSource<Integer, DownloadEntity> getDownloadsName() {
        return mDatabase.downloadDao().getDownloadsName();
    }

    public PagingSource<Integer, DownloadEntity> getSafe() {
        return mDatabase.downloadDao().getSafe();
    }

    public PagingSource<Integer, DownloadEntity> getSafeSize() {
        return mDatabase.downloadDao().getSafeSize();
    }

    public PagingSource<Integer, DownloadEntity> getSafeName() {
        return mDatabase.downloadDao().getSafeName();
    }

    public PagingSource<Integer, DownloadEntity> getDownloadsDomain() {
        return mDatabase.downloadDao().getDownloadsDomain();
    }

    public PagingSource<Integer, DownloadEntity> getSafeDomain() {
        return mDatabase.downloadDao().getSafeDomain();
    }

    public PagingSource<Integer, DownloadEntity> getSearch(int sorting, boolean safe, String query) {
        return mDatabase.downloadDao().search(sorting, safe, query);
    }

    public LiveData<List<DownloadEntity>> getAllRegularLive() {
        return mDatabase.downloadDao().getAllRegularLive();
    }

    public LiveData<List<DownloadEntity>> getAllSafeLive() {
        return mDatabase.downloadDao().getAllSafeLive();
    }

    // --- Standard Queries ---

    /** Live total bytes for finished regular downloads — drives the
     *  home "N saved" stat chip. */
    public LiveData<Long> getRegularFinishedSize() {
        return mDatabase.downloadDao().getRegularFinishedSizeLive();
    }

    /** Live count of Safe Folder (vault) items — drives the home stats card. */
    public LiveData<Integer> getSafeCount() {
        return mDatabase.downloadDao().getSafeCountLive();
    }

    /** Live total bytes of Safe Folder (vault) items — drives the home stats
     *  card's third column headline (the item count goes to its footer). */
    public LiveData<Long> getSafeTotalSize() {
        return mDatabase.downloadDao().getSafeTotalSizeLive();
    }

    /** Live count of finished regular downloads since :since (epoch millis) —
     *  drives the home Saved column's "this week" trend line. */
    public LiveData<Integer> getRegularFinishedCountSince(long since) {
        return mDatabase.downloadDao().getRegularFinishedCountSinceLive(since);
    }

    public List<DownloadEntity> getAllRawList() {
        return mDatabase.downloadDao().getAllRawList();
    }

    public List<DownloadEntity> getAllRawEnc() {
        return mDatabase.downloadDao().getAllRawEnc();
    }

    public DownloadEntity findByFilePath(String filePath) {
        return mDatabase.downloadDao().findByFilePath(filePath);
    }


    public void add(DownloadEntity download) {
        mDiskExecutor.execute(() -> mDatabase.downloadDao().insert(download));

    }

    public void addSync(DownloadEntity download) {
        mDiskExecutor.execute(() -> mDatabase.downloadDao().insertSync(download));

    }

    public void insertAllSync(List<DownloadEntity> entityList) {
        mDiskExecutor.execute(() -> mDatabase.downloadDao().insertAll(entityList));

    }

    /**
     * Mark a download's Glide thumbnail pipeline as known-unproductive
     * (no embedded cover art, no decodable video frame, corrupt media,
     * etc). Called from the Glide load listener after every decoder in
     * the chain has failed for a {@link Download#FINISHED} file. The
     * next paging access reads the flag from the entity and short-
     * circuits to the static mime icon — no more {@code MediaMetadata-
     * Retriever} or FFmpeg contexts per scroll past this row.
     *
     * <p>Dispatched on {@code mDiskExecutor} so the Room write never
     * runs on the main thread.</p>
     */
    public void setThumbnailUnavailable(int id, boolean unavailable) {
        mDiskExecutor.execute(() ->
                mDatabase.downloadDao().setThumbnailUnavailableSync(id, unavailable));
    }

    /**
     * Refreshes metadata and thumbnail timestamp for a download.
     *
     * <p>The FFmpeg probe runs on the HEAVY executor, not {@code @DiskIO}:
     * it can grind for a long time on a corrupt/odd file, and {@code @DiskIO}
     * is the single serial lane all short DB mutations (incl. deletes) ride
     * on — one wedged probe there froze the Downloads list for the rest of
     * the session. The final write then HOPS BACK to {@code @DiskIO} with an
     * existence check: every row delete runs on that same serial lane, so
     * check+insert there cannot interleave with a delete — without the hop,
     * a probe finishing after the user deleted the row would
     * insert(REPLACE) it back (the two executors DO run concurrently).</p>
     */
    public void updateDownloadThumb(DownloadEntity download) {
        mHeavyExecutor.execute(() -> {
            DownloadEntity newEntity = new DownloadEntity(download);
            String filePath = newEntity.getFilePath();
            long duration = newEntity.getDuration();

            if (duration <= 0) {
                FFmpegMetaDataReader reader = new FFmpegMetaDataReader();
                try {
                    FFmpegMetaData meta = reader.getStreamInfo(filePath, null, true);
                    if (meta != null) {
                        duration = meta.getDuration();
                    }
                } catch (IOException e) {
                    Log.e(TAG, "Failed to read stream info for thumb update", e);
                } finally {
                    reader.stop();
                    reader.release();
                }
            }

            long randomThumbPos = ThreadLocalRandom.current().nextLong(duration > 0 ? duration : 100);
            newEntity.setFileDuration(duration);
            newEntity.setFileDurationFormatted(FFmpegUtils.getFileDuration(duration));
            newEntity.setFileThumbnailDuration(randomThumbPos);

            mDiskExecutor.execute(() -> {
                // Row gone (deleted while the probe ran) — drop the result
                // instead of resurrecting it via insert(REPLACE).
                if (mDatabase.downloadDao().findByIdSync(newEntity.getId()) == null) {
                    return;
                }
                mDatabase.downloadDao().insert(newEntity);
            });
        });
    }


    public void deleteDownload(DownloadEntity download) {
        mDiskExecutor.execute(() -> {
            mDatabase.downloadDao().deleteSyncEntity(download);
            deleteFilesInternal(Collections.singletonList(download));
        });
    }

    /**
     * Deletes a single download and runs the callback on the disk executor after completion.
     */
    public void deleteDownload(DownloadEntity download, Runnable onComplete) {
        mDiskExecutor.execute(() -> {
            mDatabase.downloadDao().deleteSyncEntity(download);
            deleteFilesInternal(Collections.singletonList(download));
            if (onComplete != null) onComplete.run();
        });
    }

    /**
     * Batch-deletes a list of downloads, running onComplete once after ALL are processed.
     */
    public void deleteDownloads(List<DownloadEntity> downloads, Runnable onComplete) {
        if (downloads == null || downloads.isEmpty()) {
            if (onComplete != null) onComplete.run();
            return;
        }
        mDiskExecutor.execute(() -> {
            // Sum the rows actually removed: @Delete matches by PRIMARY KEY
            // (uid), while the file cleanup below matches by PATH — so
            // "files vanished but list entries stayed" means this count was
            // 0 (stale/mismatched uid), not a UI-refresh failure. Keep the
            // diagnostic; it splits the two failure modes in one logcat line.
            int removed = 0;
            for (DownloadEntity entity : downloads) {
                Integer result = mDatabase.downloadDao().deleteSyncEntity(entity);
                if (result != null) {
                    removed += result;
                }
            }
            if (BuildConfig.DEBUG) {
                Log.d(TAG, "deleteDownloads: requested=" + downloads.size()
                        + " rowsRemoved=" + removed);
            }
            deleteFilesInternal(downloads);
            if (onComplete != null) onComplete.run();
        });
    }



    /**
     * Internal helper to clean up physical files after DB records are removed.
     */
    private void deleteFilesInternal(List<DownloadEntity> entities) {
        if (entities == null || entities.isEmpty()) return;

        for (DownloadEntity entity : entities) {
            String path = entity.getFilePath();
            if (path == null) continue;

            File file = new File(path);
            if (!file.exists()) continue;

            // Mutually exclusive — directory deletion is recursive and
            // already removes the entry, so falling through to file.delete()
            // afterwards would always return false and log a misleading
            // "Failed to delete file" warning for every directory we cleaned.
            if (file.isDirectory()) {
                Utils.deleteDirectory(file);
                Log.d(TAG, "Deleted directory: " + path);
            } else if (file.delete()) {
                Log.d(TAG, "Deleted file: " + path);
            } else {
                Log.w(TAG, "Failed to delete file: " + path);
            }
        }
    }
}