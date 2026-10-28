package com.solarized.firedown.manager.tasks;


import android.util.Log;

import com.solarized.firedown.data.TaskEvent;
import com.solarized.firedown.data.entity.DownloadEntity;
import com.solarized.firedown.manager.UrlParser;
import com.solarized.firedown.utils.Utils;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;

public abstract class TaskRunnable implements Runnable {

    private static final String TAG = TaskRunnable.class.getSimpleName();

    public static final int BYTE_SIZE = 8192;

    public static final long UPDATE_RATE = 1500;

    private volatile boolean mIsStopped = false;

    protected long mLastUpdated = 0L;

    private final TaskManager mTaskManager;

    public abstract void stoppableRun();

    public TaskRunnable(TaskManager taskManager){
        mTaskManager = taskManager;
    }

    /* Runs the task once. A stop that lands before this starts is kept: the
     * task sees isStopped() at its first check and reports the cancel, where
     * resetting the flag here would run it to completion anyway. */
    public void run() {
        stoppableRun();
        stop();
    }

    public boolean isStopped() {
        return mIsStopped;
    }

    public void stop() {
        mIsStopped = true;
    }

    public void publishProgress(long downloadedLength, long totalLength){
        if (System.currentTimeMillis() > (mLastUpdated + UPDATE_RATE)) {
            if (totalLength > 0) {
                int progress = (int) ((downloadedLength * 100) / totalLength);
                mTaskManager.deliverMessage(new TaskEvent.Progress(progress));
                mTaskManager.onTaskProgress(progress);
            }
            mLastUpdated = System.currentTimeMillis();
        }
    }

    public void deliverMessage(TaskEvent taskEvent){
        mTaskManager.deliverMessage(taskEvent);
    }


    public ArrayList<DownloadEntity> getQueueList(){
        return mTaskManager.getQueueList();
    }


    /**
     * Copies {@code inFile} to {@code outFile}, adding what it copies to
     * {@code written[0]} for the progress bar.
     *
     * @return true when the whole file was copied, false when the task was
     *         stopped first.
     * @throws IOException when a read or write failed.
     *
     * Both streams are closed on every path, and {@code outFile} is deleted
     * unless the copy finished: the vault tasks closed them only on success,
     * so a cancel kept two descriptors open, and a read error left a partial
     * file with no row behind it.
     */
    protected boolean copyFile(File inFile, File outFile, byte[] buffer,
                               long[] written, long totalLength) throws IOException {
        boolean copied = false;
        try (FileInputStream in = new FileInputStream(inFile);
             FileOutputStream out = new FileOutputStream(outFile)) {
            int read = in.read(buffer);
            while (read != -1) {
                if (isStopped()) {
                    return false;
                }
                out.write(buffer, 0, read);
                written[0] += read;
                publishProgress(written[0], totalLength);
                read = in.read(buffer);
            }
            copied = !isStopped();
            return copied;
        } finally {
            if (!copied && outFile.exists() && !outFile.delete()) {
                Log.w(TAG, "copyFile: could not delete " + outFile.getAbsolutePath());
            }
        }
    }

    public File ensureFilePath(String filePath){
        synchronized (mTaskManager) {
            File newFile = new File(filePath);
            if (newFile.exists() || !Utils.isFileWriteable(newFile)) {
                do {
                    filePath = UrlParser.parseFilePath(filePath);
                    newFile = new File(filePath);
                } while (newFile.exists() || !Utils.isFileWriteable(newFile));
            }
            return newFile;
        }
    }
}