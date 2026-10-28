package com.solarized.firedown.manager.tasks;

import android.util.Log;

import com.solarized.firedown.data.Download;
import com.solarized.firedown.data.TaskEvent;
import com.solarized.firedown.data.entity.DownloadEntity;
import com.solarized.firedown.data.repository.DownloadDataRepository;
import com.solarized.firedown.manager.ServiceActions;
import com.solarized.firedown.utils.FileUriHelper;
import com.solarized.firedown.StoragePaths;
import com.solarized.firedown.utils.Utils;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Bundles the queued downloads into a single {@code .zip} archive in the
 * download folder, leaving the originals untouched. Mirrors the other
 * {@link TaskRunnable}s (GIF / audio / encryption): emits a {@code Started}
 * event, streams bytes while publishing progress, and finishes with the
 * created archive entity so the UI can offer a one-tap "View".
 *
 * <p>The archive descends from the first queued item — it inherits its
 * source URL / origin for provenance, then the media-specific fields
 * (thumbnail, duration) are cleared because they make no sense for a zip.
 */
public class CompressTask extends TaskRunnable {

    private static final String TAG = CompressTask.class.getSimpleName();

    private final DownloadDataRepository mDownloadRepository;
    private final TaskManager mTaskManager;

    public CompressTask(TaskManager taskManager, DownloadDataRepository downloadDataRepository) {
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

            deliverMessage(new TaskEvent.Started(ServiceActions.COMPRESS));

            StoragePaths.ensureDownloadPath(mTaskManager);

            mDownloadEntity = mQueueList.get(0);
            String firstPath = mDownloadEntity.getFilePath();

            // The descended entity arrives as a FINISHED download; mark it
            // in-progress so the finally-block's status check reliably
            // detects an interrupted/failed run (and cleans up the archive).
            mDownloadEntity.setFileStatus(Download.PROGRESS);

            // Total payload across every readable source — drives the
            // progress percentage, summed before we start writing.
            long totalLength = 0L;
            for (DownloadEntity entity : mQueueList) {
                if (entity.getFilePath() == null) continue;
                File f = new File(entity.getFilePath());
                if (f.isFile()) totalLength += f.length();
            }

            outFile = ensureFilePath(Utils.changeExtension(firstPath, "zip"));

            long written = 0;
            final Set<String> usedNames = new HashSet<>();
            final byte[] buffer = new byte[BYTE_SIZE];

            try (ZipOutputStream zos = new ZipOutputStream(
                    new BufferedOutputStream(new FileOutputStream(outFile)))) {

                for (DownloadEntity entity : mQueueList) {

                    if (Thread.interrupted() || isStopped()) {
                        Log.d(TAG, "Interrupted");
                        return;
                    }

                    if (entity.getFilePath() == null) continue;
                    File inFile = new File(entity.getFilePath());
                    if (!inFile.isFile()) {
                        Log.w(TAG, "Skipping missing file: " + entity.getFilePath());
                        continue;
                    }

                    zos.putNextEntry(new ZipEntry(uniqueName(usedNames, inFile.getName())));

                    try (FileInputStream fis = new FileInputStream(inFile)) {
                        int read;
                        while ((read = fis.read(buffer)) != -1) {
                            if (Thread.currentThread().isInterrupted() || isStopped()) {
                                Log.d(TAG, "Thread interrupted");
                                return;
                            }
                            zos.write(buffer, 0, read);
                            written += read;
                            publishProgress(written, totalLength);
                        }
                    }
                    zos.closeEntry();
                }
            }

            if (Thread.interrupted() || isStopped()) {
                Log.d(TAG, "Interrupted");
                return;
            }

            // Re-point the descended entity at the freshly written archive
            // and strip the media-specific metadata.
            mDownloadEntity.setId(UUID.randomUUID().hashCode());
            mDownloadEntity.setFileName(outFile.getName());
            mDownloadEntity.setFilePath(outFile.getAbsolutePath());
            mDownloadEntity.setFileMimeType(FileUriHelper.MIMETYPE_ZIP_2);
            mDownloadEntity.setFileSize(outFile.length());
            mDownloadEntity.setFileDate(System.currentTimeMillis());
            mDownloadEntity.setFileImg(null);
            mDownloadEntity.setFileSafe(false);
            mDownloadEntity.setFileEncrypted(false);
            mDownloadEntity.setFileDuration(0);
            mDownloadEntity.setFileDurationFormatted(null);
            mDownloadEntity.setFileThumbnailDuration(0);
            mDownloadEntity.setFileThumbnailUnavailable(true);
            mDownloadEntity.setFileStatus(Download.FINISHED);

            mDownloadRepository.addSync(mDownloadEntity);

            /* Pass the archive entity through the Finished event so the UI
             * can offer a "View" action that opens it without re-querying. */
            deliverMessage(new TaskEvent.Finished(ServiceActions.COMPRESS, mDownloadEntity));

        } catch (IOException e) {
            Log.e(TAG, "compress", e);
            if (mDownloadEntity != null)
                mDownloadEntity.setFileStatus(Download.ERROR);
        } finally {
            int status = mDownloadEntity != null ? mDownloadEntity.getFileStatus() : Download.ERROR;
            if (status != Download.FINISHED) {
                if (outFile != null)
                    outFile.delete();
                deliverMessage(new TaskEvent.Finished(
                        isStopped() ? ServiceActions.CANCEL_COMPRESS : ServiceActions.ERROR_COMPRESS,
                        null));
            }
            Log.d(TAG, "Finished");
        }
    }

    /** Keeps zip entry names unique when two sources share a file name. */
    private static String uniqueName(Set<String> used, String name) {
        String candidate = name;
        int i = 1;
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        while (!used.add(candidate)) {
            candidate = base + "_" + i++ + ext;
        }
        return candidate;
    }
}
