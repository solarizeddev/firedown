package com.solarized.firedown.data.workers;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
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

        // File.exists() == false means EITHER "file deleted" OR "file present
        // but unreadable" — and the second is exactly the state right after a
        // backup-restored reinstall: on Android <= 12 every restored entry's
        // file is unreadable until the user grants READ_EXTERNAL_STORAGE, and
        // on 13+ files owned by the pre-reinstall install stay invisible to
        // the File API entirely. Marking on an untrustworthy negative would
        // wreck the whole restored list on the first Downloads open. So:
        //  - only MARK a file missing when the negative is trustworthy. The
        //    trust is PER PATH: app-private paths (filesDir — the safe vault
        //    lives there) are always readable, no permission involved, so a
        //    missing vault file is always marked. Shared-storage paths are
        //    trustworthy only while the read permission is held on <= 32 (on
        //    33+ no read permission exists in the manifest and the app's own
        //    files are always readable — restored foreign entries there
        //    can't be validated by path at all, which the SAF restore flow
        //    owns);
        //  - always HEAL: an entry this worker errored (FILE_NOT_FOUND) whose
        //    file is readable again (permission granted since) goes back to
        //    FINISHED. Only this worker's own error type is healed, so a
        //    download that genuinely failed is never resurrected.
        // This worker deliberately NEVER deletes a row: exists() is not
        // trustworthy enough for an irreversible action, and the record of
        // what was downloaded (origin, title) is user data in its own right —
        // removing it is the user's call, made on the visible error entry.
        boolean sharedStorageTrustworthy;
        if (Build.VERSION.SDK_INT <= 32) {
            sharedStorageTrustworthy = getApplicationContext().checkSelfPermission(
                    Manifest.permission.READ_EXTERNAL_STORAGE)
                    == PackageManager.PERMISSION_GRANTED;
        } else {
            sharedStorageTrustworthy = true;
        }
        String privateRoot = getApplicationContext().getFilesDir().getAbsolutePath();

        try {
            // Get all records from the database
            List<DownloadEntity> entityList = mDownloadDataRepository.getAllRawList();

            if (entityList == null || entityList.isEmpty()) {
                return Result.success();
            }

            for (DownloadEntity entity : entityList) {
                String path = entity.getFilePath();
                boolean onDisk = path != null && new File(path).exists();
                // A null path is broken data, not a permission question — always
                // markable. Otherwise trust is per path: app-private always,
                // shared storage only while readable.
                boolean canTrustMissing = path == null
                        || path.startsWith(privateRoot)
                        || sharedStorageTrustworthy;

                if (entity.getFileStatus() == Download.FINISHED) {
                    if (!onDisk && canTrustMissing) {
                        Log.d(TAG, "File missing for entity: " + entity.getFileName());

                        // Update the entity state
                        entity.setFileStatus(Download.ERROR);
                        entity.setFileErrorType(MessageHelper.FILE_NOT_FOUND);

                        // Save update back to DB synchronously
                        mDownloadDataRepository.addSync(entity);
                    }
                } else if (entity.getFileStatus() == Download.ERROR
                        && entity.getFileErrorType() == MessageHelper.FILE_NOT_FOUND
                        && onDisk) {
                    Log.d(TAG, "File readable again — healing entity: " + entity.getFileName());

                    entity.setFileStatus(Download.FINISHED);
                    entity.setFileErrorType(MessageHelper.NO_ERROR);

                    mDownloadDataRepository.addSync(entity);
                }
            }

            return Result.success();
        } catch (Exception e) {
            Log.e(TAG, "Error during media check", e);
            return Result.failure();
        }
    }
}