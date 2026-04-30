package com.solarized.firedown.manager.tasks;


import com.solarized.firedown.data.TaskEvent;
import com.solarized.firedown.data.entity.DownloadEntity;
import com.solarized.firedown.manager.UrlParser;
import com.solarized.firedown.utils.Utils;

import java.io.File;
import java.util.ArrayList;

public abstract class TaskRunnable implements Runnable {

    public static final int BYTE_SIZE = 8192;

    public static final long UPDATE_RATE = 1500;

    private volatile boolean mIsStopped = false;

    protected long mLastUpdated = 0L;

    private final TaskManager mTaskManager;

    public abstract void stoppableRun();

    public TaskRunnable(TaskManager taskManager){
        mTaskManager = taskManager;
    }

    public void run() {
        setStopped(false);
        while(!mIsStopped) {
            stoppableRun();
            stop();
        }
    }

    public boolean isStopped() {
        return mIsStopped;
    }

    private void setStopped(boolean isStop) {
        if (mIsStopped != isStop)
            mIsStopped = isStop;
    }

    public void stop() {
        setStopped(true);
    }

    public void publishProgress(long downloadedLength, long totalLength){
        if (System.currentTimeMillis() > (mLastUpdated + UPDATE_RATE)) {
            if (totalLength > 0) {
                int progress = (int) ((downloadedLength * 100) / totalLength);
                mTaskManager.deliverMessage(new TaskEvent.Progress(progress));
            }
            mLastUpdated = System.currentTimeMillis();
        }
    }

    public void stopService(){
        mTaskManager.stopSelf();
    }

    public void deliverMessage(TaskEvent taskEvent){
        mTaskManager.deliverMessage(taskEvent);
    }


    public ArrayList<DownloadEntity> getQueueList(){
        return mTaskManager.getQueueList();
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