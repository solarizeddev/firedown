package com.solarized.firedown.manager.tasks;

import android.util.Log;

import com.solarized.firedown.data.Download;
import com.solarized.firedown.data.TaskEvent;
import com.solarized.firedown.data.entity.DownloadEntity;
import com.solarized.firedown.data.repository.DownloadDataRepository;
import com.solarized.firedown.ffmpegutils.FFmpegGifMaker;
import com.solarized.firedown.ffmpegutils.FFmpegListener;
import com.solarized.firedown.ffmpegutils.FFmpegMetaDataReader;
import com.solarized.firedown.manager.ServiceActions;
import com.solarized.firedown.utils.FileUriHelper;
import com.solarized.firedown.StoragePaths;
import com.solarized.firedown.utils.GalleryPublisher;
import com.solarized.firedown.utils.Utils;

import java.io.File;
import java.util.ArrayList;
import java.util.UUID;


public class GifMakerTask extends TaskRunnable implements FFmpegListener {

    private static final String TAG = GifMakerTask.class.getSimpleName();

    /* Built on the worker, interrupted from the main thread. */
    private volatile FFmpegGifMaker mFFmpegGifMaker;

    private final DownloadDataRepository mDownloadRepository;
    private final TaskManager mTaskManager;


    public GifMakerTask(TaskManager taskManager, DownloadDataRepository downloadDataRepository) {
        super(taskManager);
        mTaskManager = taskManager;
        mDownloadRepository = downloadDataRepository;
    }

    @Override
    public void stoppableRun() {

        final ArrayList<DownloadEntity> mQueueList = getQueueList();

        File outFile = null;

        DownloadEntity mDownloadEntity = null;

        if (mQueueList == null || mQueueList.isEmpty()) {
            Log.w(TAG, "Empty Queue");
            return;
        }

        try {

            deliverMessage(new TaskEvent.Started(ServiceActions.MAKE_GIF));

            StoragePaths.ensureDownloadPath(mTaskManager);

            mDownloadEntity = mQueueList.get(0);

            String filePath = mDownloadEntity.getFilePath();

            if (Thread.interrupted() || isStopped()) {
                Log.d(TAG, "Interrupted");
                mDownloadEntity.setFileStatus(Download.ERROR);
                return;
            }

            Log.d(TAG, "start FFmpeg gif maker");

            mDownloadEntity.setFileStatus(Download.PROGRESS);

            mDownloadEntity.setId(UUID.randomUUID().hashCode());

            mDownloadEntity.setFileDate(System.currentTimeMillis());

            mDownloadEntity.setFileMimeType(FileUriHelper.MIMETYPE_GIF);

            mFFmpegGifMaker = new FFmpegGifMaker();

            mFFmpegGifMaker.addListener(this);

            outFile = ensureFilePath(Utils.changeExtension(filePath, "gif"));

            mDownloadEntity.setFilePath(outFile.getAbsolutePath());

            mDownloadEntity.setFileImg(null);

            mDownloadEntity.setFileName(outFile.getName());

            /* The trim UI hands us start/end/fps/width via the intent, which
             * TaskManager parks on a side channel because the existing
             * intent only forwards the entity list. If the args are missing
             * (legacy callers), fall through to whole-file defaults. */
            GifMakerArgs args = mTaskManager.getGifMakerArgs();
            long startMs = args != null ? args.startMs : 0L;
            long endMs = args != null ? args.endMs : 0L;
            int fps = args != null && args.fps > 0 ? args.fps : FFmpegGifMaker.DEFAULT_FPS;
            int width = args != null && args.width > 0 ? args.width : FFmpegGifMaker.DEFAULT_WIDTH;

            /* stop() sets the flag, then interrupts the native object if it
             * exists. A cancel that landed while it was being built found
             * none and interrupted nothing, so look at the flag again now
             * that it is set, before the long native run. */
            if (isStopped()) {
                Log.d(TAG, "Stopped before start");
                mDownloadEntity.setFileStatus(Download.ERROR);
                return;
            }

            int ret = mFFmpegGifMaker.start(filePath, outFile.getAbsolutePath(),
                    startMs, endMs, fps, width);

            if (ret < 0) {
                Log.d(TAG, "FFmpegGifMaker start error " + ret);
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

            // Stamp the resolution so the Downloads list/info row shows a tag.
            // Only the target width is known here (ffmpeg derives the height
            // from the source aspect), so probe the finished GIF for "WxH".
            // This entity is added straight to the repo — it never passes
            // through DownloadTask's metadata backfill.
            mDownloadEntity.setFileResolution(
                    FFmpegMetaDataReader.probeImageResolution(outFile.getAbsolutePath()));

            mDownloadRepository.addSync(mDownloadEntity);
            GalleryPublisher.publishIfEnabled(mTaskManager, mDownloadEntity.getFilePath(), mDownloadEntity.getFileMimeType());

            /* Pass the entity through the Finished event so the UI can
             * offer a "View" action that launches PlayerActivity on the
             * just-created GIF without re-querying the repository. */
            deliverMessage(new TaskEvent.Finished(ServiceActions.MAKE_GIF, mDownloadEntity));

        } catch (IllegalArgumentException e) {
            Log.e(TAG, "IOException ", e);
            if (mDownloadEntity != null)
                mDownloadEntity.setFileStatus(Download.ERROR);
        } finally {
            int status = mDownloadEntity != null ? mDownloadEntity.getFileStatus() : Download.ERROR;
            if (status != Download.FINISHED) {
                if (outFile != null)
                    outFile.delete();
                deliverMessage(new TaskEvent.Finished(
                        isStopped() ? ServiceActions.CANCEL_MAKE_GIF : ServiceActions.ERROR_MAKE_GIF,
                        null));
            }
            if (mFFmpegGifMaker != null) {
                mFFmpegGifMaker.stop();
                mFFmpegGifMaker.free();
            }
            Log.d(TAG, "Finished");
        }
    }

    @Override
    public void stop() {
        super.stop();
        Log.d(TAG, "GifMakerTask STOP");
        FFmpegGifMaker gifMaker = mFFmpegGifMaker;
        if (gifMaker != null) {
            gifMaker.interrupt();
        }
    }


    @Override
    public void onProgress(long currentLength, long totalLength) {
        publishProgress(currentLength, totalLength);
    }

    @Override
    public void onStarted() {
    }

    @Override
    public void onFinished() {
    }
}
