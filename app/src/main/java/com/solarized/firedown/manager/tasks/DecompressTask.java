package com.solarized.firedown.manager.tasks;

import android.util.Log;

import com.solarized.firedown.data.Download;
import com.solarized.firedown.data.TaskEvent;
import com.solarized.firedown.data.entity.DownloadEntity;
import com.solarized.firedown.data.repository.DownloadDataRepository;
import com.solarized.firedown.manager.ServiceActions;
import com.solarized.firedown.utils.FileUriHelper;
import com.solarized.firedown.StoragePaths;
import com.solarized.firedown.utils.GalleryPublisher;

import org.apache.commons.io.FilenameUtils;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Extracts a {@code .zip} archive's entries into the download folder,
 * registering each as a finished download, and leaves the source archive
 * in place. The inverse of {@link CompressTask}; reuses the same
 * {@link TaskRunnable} machinery (Started / progress / Finished events,
 * bottom progress bar).
 *
 * <p>Entry names are flattened to their base name before writing, which
 * also neutralises Zip-Slip path-traversal entries ({@code ../}).
 */
public class DecompressTask extends TaskRunnable {

    private static final String TAG = DecompressTask.class.getSimpleName();

    private final DownloadDataRepository mDownloadRepository;
    private final TaskManager mTaskManager;

    public DecompressTask(TaskManager taskManager, DownloadDataRepository downloadDataRepository) {
        super(taskManager);
        mTaskManager = taskManager;
        mDownloadRepository = downloadDataRepository;
    }

    @Override
    public void stoppableRun() {

        final ArrayList<DownloadEntity> mQueueList = getQueueList();

        if (mQueueList == null || mQueueList.isEmpty()) {
            Log.w(TAG, "Empty Queue");
            return;
        }

        DownloadEntity source = mQueueList.get(0);
        if (source.getFilePath() == null) {
            Log.w(TAG, "Null source path");
            return;
        }

        int extracted = 0;
        boolean failed = false;

        deliverMessage(new TaskEvent.Started(ServiceActions.EXTRACT));

        StoragePaths.ensureDownloadPath(mTaskManager);
        final String outDir = StoragePaths.getDownloadPath(mTaskManager);
        final byte[] buffer = new byte[BYTE_SIZE];

        try (ZipFile zip = new ZipFile(new File(source.getFilePath()))) {

            // Total uncompressed payload across entries — drives progress.
            long totalLength = 0L;
            for (Enumeration<? extends ZipEntry> e = zip.entries(); e.hasMoreElements(); ) {
                ZipEntry entry = e.nextElement();
                if (!entry.isDirectory() && entry.getSize() > 0) totalLength += entry.getSize();
            }

            long written = 0;

            for (Enumeration<? extends ZipEntry> e = zip.entries(); e.hasMoreElements(); ) {

                if (Thread.interrupted() || isStopped()) {
                    Log.d(TAG, "Interrupted");
                    failed = true;
                    return;
                }

                ZipEntry entry = e.nextElement();
                if (entry.isDirectory()) continue;

                // Flatten to base name: drops any directory prefix and, with
                // it, any "../" traversal an attacker-crafted zip might carry.
                String name = FilenameUtils.getName(entry.getName());
                if (name == null || name.isEmpty()) continue;

                File outFile = ensureFilePath(new File(outDir, name).getAbsolutePath());

                try (InputStream in = zip.getInputStream(entry);
                     FileOutputStream out = new FileOutputStream(outFile)) {
                    int read;
                    while ((read = in.read(buffer)) != -1) {
                        if (Thread.currentThread().isInterrupted() || isStopped()) {
                            Log.d(TAG, "Thread interrupted");
                            out.close();
                            outFile.delete();
                            failed = true;
                            return;
                        }
                        out.write(buffer, 0, read);
                        written += read;
                        publishProgress(written, totalLength);
                    }
                }

                DownloadEntity extractedEntity = buildEntity(source, outFile);
                mDownloadRepository.addSync(extractedEntity);
                GalleryPublisher.publishIfEnabled(mTaskManager, extractedEntity.getFilePath(), extractedEntity.getFileMimeType());
                extracted++;
            }

        } catch (IOException e) {
            Log.e(TAG, "decompress", e);
            failed = true;
        } finally {
            // extracted==0 with no failure is a valid no-op (empty or
            // directory-only archive) — not an error.
            ServiceActions result;
            if (failed) {
                result = isStopped() ? ServiceActions.CANCEL_EXTRACT : ServiceActions.ERROR_EXTRACT;
            } else {
                result = ServiceActions.EXTRACT;
            }
            deliverMessage(new TaskEvent.Finished(result, extracted));
            stopService();
            Log.d(TAG, "Finished");
        }
    }

    /** A finished download entry for a single extracted file. */
    private DownloadEntity buildEntity(DownloadEntity source, File outFile) {
        DownloadEntity entity = new DownloadEntity();
        entity.setId(UUID.randomUUID().hashCode());
        entity.setFileName(outFile.getName());
        entity.setFilePath(outFile.getAbsolutePath());
        entity.setFileMimeType(FileUriHelper.getMimeTypeFromFile(outFile.getName()));
        entity.setFileSize(outFile.length());
        entity.setFileDate(System.currentTimeMillis());
        entity.setFileOriginUrl(source.getOriginUrl());
        entity.setFileStatus(Download.FINISHED);
        entity.setFileThumbnailUnavailable(false);
        return entity;
    }
}
