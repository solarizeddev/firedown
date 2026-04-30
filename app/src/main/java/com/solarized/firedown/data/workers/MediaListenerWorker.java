package com.solarized.firedown.data.workers;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.hilt.work.HiltWorker;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.solarized.firedown.data.Download;
import com.solarized.firedown.data.entity.DownloadEntity;
import com.solarized.firedown.data.repository.DownloadDataRepository;
import com.solarized.firedown.utils.MessageHelper;

import java.io.File;
import java.util.List;

import dagger.assisted.Assisted;
import dagger.assisted.AssistedInject;

@HiltWorker
public class MediaListenerWorker extends Worker {

    private static final String TAG = "MediaListenerWorker";

    private final DownloadDataRepository mDownloadDataRepository;

    @AssistedInject
    public MediaListenerWorker(
            @Assisted @NonNull Context context,
            @Assisted @NonNull WorkerParameters workerParams,
            DownloadDataRepository downloadDataRepository) {
        super(context, workerParams);
        // Hilt now injects the repository directly
        this.mDownloadDataRepository = downloadDataRepository;
    }

    @NonNull
    @Override
    public Result doWork() {
        Log.d(TAG, "Starting media existence check...");

        try {
            // Get all records from the database
            List<DownloadEntity> entityList = mDownloadDataRepository.getAllRawList();

            if (entityList == null || entityList.isEmpty()) {
                return Result.success();
            }

            for (DownloadEntity entity : entityList) {
                // We only care about files marked as FINISHED
                if (entity.getFileStatus() == Download.FINISHED) {
                    String path = entity.getFilePath();

                    if (path == null || !new File(path).exists()) {
                        Log.d(TAG, "File missing for entity: " + entity.getFileName());

                        // Update the entity state
                        entity.setFileStatus(Download.ERROR);
                        entity.setFileErrorType(MessageHelper.FILE_NOT_FOUND);

                        // Save update back to DB synchronously
                        mDownloadDataRepository.addSync(entity);
                    }
                }
            }

            return Result.success();
        } catch (Exception e) {
            Log.e(TAG, "Error during media check", e);
            return Result.failure();
        }
    }
}