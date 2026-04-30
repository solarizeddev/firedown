package com.solarized.firedown.manager.tasks;

import android.util.Log;

import com.solarized.firedown.data.Download;
import com.solarized.firedown.data.TaskEvent;
import com.solarized.firedown.data.entity.DownloadEntity;
import com.solarized.firedown.data.repository.DownloadDataRepository;
import com.solarized.firedown.ffmpegutils.FFmpegEncoder;
import com.solarized.firedown.ffmpegutils.FFmpegListener;
import com.solarized.firedown.manager.ServiceActions;
import com.solarized.firedown.utils.FileUriHelper;
import com.solarized.firedown.StoragePaths;
import com.solarized.firedown.utils.Utils;

import java.io.File;
import java.util.ArrayList;
import java.util.UUID;

import okhttp3.internal.concurrent.Task;


public class AudoEncodeTask extends TaskRunnable implements FFmpegListener {

    private static final String TAG = AudoEncodeTask.class.getSimpleName();

    private FFmpegEncoder mFFmpegEncoder;

    private final DownloadDataRepository mDownloadRepository;
    private final TaskManager mTaskManager;


    public AudoEncodeTask(TaskManager taskManager, DownloadDataRepository downloadDataRepository) {
        super(taskManager);
        mTaskManager = taskManager;
        mDownloadRepository = downloadDataRepository;
    }

    @Override
    public void stoppableRun() {

        final ArrayList<DownloadEntity> mQueueList = getQueueList();

        File outFile = null;

        DownloadEntity mDownloadEntity = null;

        if(mQueueList == null || mQueueList.isEmpty()){
            Log.w(TAG, "Empty Queue");
            return;
        }

        try {

            deliverMessage(new TaskEvent.Started(ServiceActions.AUDIO_ENCODE));

            StoragePaths.ensureDownloadPath(mTaskManager);

            mDownloadEntity = mQueueList.get(0);

            String filePath = mDownloadEntity.getFilePath();

            if (Thread.interrupted() || isStopped()) {
                Log.d(TAG, "Interrupted");
                mDownloadEntity.setFileStatus(Download.ERROR);
                return;
            }

            //get content address

            Log.d(TAG, "start FFmpeg download");

            mDownloadEntity.setFileStatus(Download.PROGRESS);

            mDownloadEntity.setId(UUID.randomUUID().hashCode());

            mDownloadEntity.setFileDate(System.currentTimeMillis());

            mDownloadEntity.setFileMimeType(FileUriHelper.AUDIO_AAC);

            mFFmpegEncoder = new FFmpegEncoder();

            mFFmpegEncoder.addListener(this);

            outFile = ensureFilePath(Utils.changeExtension(filePath, "aac"));

            mDownloadEntity.setFilePath(outFile.getAbsolutePath());

            mDownloadEntity.setFileImg(null);

            mDownloadEntity.setFileName(outFile.getName());

            if ((mFFmpegEncoder.start(null, null, filePath, outFile.getAbsolutePath(), FFmpegEncoder.UNKNOWN_STREAM, FFmpegEncoder.UNKNOWN_STREAM)) < 0) {
                Log.d(TAG, "FFmpegDownloader start error");
                mDownloadEntity.setFileStatus(Download.ERROR);
                return;
            }

            if (Thread.currentThread().isInterrupted() || isStopped()) {
                Log.d(TAG, "Thread interrupted");
                mDownloadEntity.setFileStatus(Download.ERROR);
                return;
            }

            mDownloadEntity.setFileSize(outFile.length());

            mDownloadEntity.setFileStatus(Download.FINISHED);

            mDownloadRepository.addSync(mDownloadEntity);

            deliverMessage(new TaskEvent.Finished(ServiceActions.AUDIO_ENCODE, null));

        } catch (IllegalArgumentException e) {
            Log.e(TAG, "IOException ", e);
            if(mDownloadEntity != null)
                mDownloadEntity.setFileStatus(Download.ERROR);
        } finally {
            int status = mDownloadEntity != null ? mDownloadEntity.getFileStatus() : Download.ERROR;
            if(status != Download.FINISHED){
                if (outFile != null)
                    outFile.delete();
                deliverMessage(new TaskEvent.Finished(isStopped() ? ServiceActions.CANCEL_AUDIO_ENCODE : ServiceActions.ERROR_AUDIO_ENCODE, null));
            }
            if (mFFmpegEncoder != null) {
                mFFmpegEncoder.stop();
                mFFmpegEncoder.free();
            }
            stopService();
            Log.d(TAG, "Finished");
        }


    }

    @Override
    public void stop() {
        super.stop();
        Log.d(TAG, "FFmpegRunnable STOP");
        mFFmpegEncoder.interrupt();
    }


    @Override
    public void onProgress(long downloadedLength, long totalLength) {
        publishProgress(downloadedLength, totalLength);
    }

    @Override
    public void onStarted() {

    }

    @Override
    public void onFinished() {

    }

}
