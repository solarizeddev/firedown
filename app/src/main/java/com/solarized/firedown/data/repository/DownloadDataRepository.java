package com.solarized.firedown.data.repository;

import android.util.Log;

import androidx.lifecycle.LiveData;
import androidx.paging.PagingSource;

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

    @Inject
    public DownloadDataRepository(DownloadDatabase database, @Qualifiers.DiskIO Executor diskExecutor) {
        this.mDatabase = database;
        this.mDiskExecutor = diskExecutor;
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

    // --- Standard Queries ---

    public LiveData<List<DownloadEntity>> getDownloadsLimit(int limit) {
        return mDatabase.downloadDao().getDownloadsLimit(limit);
    }

    public List<DownloadEntity> getAllRaw() {
        return mDatabase.downloadDao().getAllRaw();
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
     * Refreshes metadata and thumbnail timestamp for a download.
     */
    public void updateDownloadThumb(DownloadEntity download) {
        mDiskExecutor.execute(() -> {
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
                }
            }

            long randomThumbPos = ThreadLocalRandom.current().nextLong(duration > 0 ? duration : 100);
            newEntity.setFileDuration(duration);
            newEntity.setFileDurationFormatted(FFmpegUtils.getFileDuration(duration));
            newEntity.setFileThumbnailDuration(randomThumbPos);

            mDatabase.downloadDao().insert(newEntity);
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
            for (DownloadEntity entity : downloads) {
                mDatabase.downloadDao().deleteSyncEntity(entity);
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
            if (file.exists()) {
                if (file.isDirectory()) {
                    Utils.deleteDirectory(file);
                }
                if (file.delete()) {
                    Log.d(TAG, "Deleted file: " + path);
                } else {
                    Log.w(TAG, "Failed to delete file: " + path);
                }
            }
        }
    }
}