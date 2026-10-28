package com.solarized.firedown.manager.tasks;

import android.util.Log;

import com.solarized.firedown.data.Download;
import com.solarized.firedown.data.TaskEvent;
import com.solarized.firedown.data.entity.DownloadEntity;
import com.solarized.firedown.data.repository.DownloadDataRepository;
import com.solarized.firedown.manager.ServiceActions;
import com.solarized.firedown.StoragePaths;
import com.solarized.firedown.utils.GalleryPublisher;

import org.apache.commons.io.FileUtils;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;

public class OutSafeTask extends TaskRunnable {

    private static final String TAG = OutSafeTask.class.getSimpleName();

    private final DownloadDataRepository mDownloadRepository;

    private final TaskManager mTaskManager;

    public OutSafeTask(TaskManager taskManager, DownloadDataRepository downloadDataRepository) {
        super(taskManager);
        mTaskManager = taskManager;
        mDownloadRepository = downloadDataRepository;
    }


    @Override
    public void stoppableRun() {


        final byte[] buffer = new byte[BYTE_SIZE];

        final ArrayList<DownloadEntity> mQueueList = getQueueList();

        if(mQueueList == null || mQueueList.isEmpty()){
            Log.w(TAG, "Empty Queue");
            return;
        }

        StoragePaths.ensureDownloadPath(mTaskManager);

        long[] written = {0L};
        long totalLength = 0L;

        for(DownloadEntity entity :  mQueueList){
            totalLength += entity.getFileSize();
        }

        /* Counted as each file lands. The count used to read every entity's
         * status at the end, and a finished download arrives FINISHED, so
         * files a cancel or an error never reached were reported as moved. */
        int moved = 0;

        try{

            deliverMessage(new TaskEvent.Started(ServiceActions.DECRYPTION));

            for(DownloadEntity entity : mQueueList){

                if (isStopped()) {
                    Log.d(TAG, "Interrupted");
                    return;
                }

                String fileName = entity.getFileName();
                String filePath = entity.getFilePath();
                File inFile = new File(filePath);
                File outFile = new File(StoragePaths.getDownloadPath(mTaskManager), fileName);

                outFile = ensureFilePath(outFile.getAbsolutePath());

                boolean copied;
                try {
                    copied = copyFile(inFile, outFile, buffer, written, totalLength);
                } catch (IOException e) {
                    // This file stays where it was; the rest still move.
                    Log.e(TAG, "copy failed: " + fileName, e);
                    continue;
                }

                if (!copied) {
                    Log.d(TAG, "Interrupted");
                    return;
                }

                entity.setFileSize(outFile.length());
                entity.setFilePath(outFile.getAbsolutePath());
                entity.setFileSafe(false);
                entity.setFileStatus(Download.FINISHED);

                mDownloadRepository.addSync(entity);
                moved++;

                try {
                    FileUtils.delete(inFile);
                } catch (IOException e) {
                    Log.e(TAG, "could not delete the vault copy: " + filePath, e);
                }
                // Unlocked into the public folder: a normal download again.
                GalleryPublisher.publishIfEnabled(mTaskManager, entity.getFilePath(), entity.getFileMimeType());

            }

        }finally {
            deliverMessage(new TaskEvent.Finished(ServiceActions.DECRYPTION, moved));
        }

    }


}
