package com.solarized.firedown.manager.tasks;

import android.util.Log;

import com.solarized.firedown.data.Download;
import com.solarized.firedown.data.TaskEvent;
import com.solarized.firedown.data.entity.DownloadEntity;
import com.solarized.firedown.data.repository.DownloadDataRepository;
import com.solarized.firedown.manager.ServiceActions;
import com.solarized.firedown.StoragePaths;

import org.apache.commons.io.FileUtils;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
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

        int read;
        long write = 0;
        long totalLength = 0L;

        for(DownloadEntity entity :  mQueueList){
            totalLength += entity.getFileSize();
        }

        try{

            deliverMessage(new TaskEvent.Started(ServiceActions.DECRYPTION));


            for(DownloadEntity entity : mQueueList){

                if (Thread.interrupted() || isStopped()) {
                    Log.d(TAG, "Interrupted");
                    entity.setFileStatus(Download.ERROR);
                    return;
                }

                String fileName = entity.getFileName();
                String filePath = entity.getFilePath();
                File inFile = new File(filePath);
                File outFile = new File(StoragePaths.getDownloadPath(mTaskManager), fileName);

                outFile = ensureFilePath(outFile.getAbsolutePath());

                FileInputStream encryptedInputStream = new FileInputStream(inFile);

                FileOutputStream outputStream = new FileOutputStream(outFile);

                while ((read = encryptedInputStream.read(buffer)) != -1) {

                    if (Thread.currentThread().isInterrupted() || isStopped() ) {
                        Log.d(TAG, "Thread interrupted");
                        if(!outFile.delete()){
                            Log.w(TAG, "Thread interrupted error deleting file: " + outFile.getAbsolutePath());
                        }
                        entity.setFileStatus(Download.ERROR);
                        return;
                    }

                    write += read;
                    publishProgress(write, totalLength);
                    outputStream.write(buffer, 0, read);
                }


                if (Thread.interrupted() || isStopped()) {
                    Log.d(TAG, "Interrupted");
                    if(!outFile.delete()){
                        Log.w(TAG, "Thread interrupted error deleting file: " + outFile.getAbsolutePath());
                    }
                    entity.setFileStatus(Download.ERROR);
                    return;
                }

                entity.setFileSize(outFile.length());
                entity.setFilePath(outFile.getAbsolutePath());
                entity.setFileSafe(false);
                entity.setFileStatus(Download.FINISHED);

                encryptedInputStream.close();
                outputStream.flush();
                outputStream.close();
                mDownloadRepository.addSync(entity);
                FileUtils.delete(inFile);

            }

        }catch(IOException e){
            Log.e(TAG, "moveDownloadToSafe", e);
        }finally {

            int success = 0;

            for(DownloadEntity entity : mQueueList){
                if(entity.getFileStatus() == Download.FINISHED) {
                    success++;
                }
            }

            deliverMessage(new TaskEvent.Finished(ServiceActions.DECRYPTION, success));

            stopService();
        }

    }

}

