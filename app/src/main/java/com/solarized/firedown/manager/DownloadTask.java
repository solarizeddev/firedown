package com.solarized.firedown.manager;

import android.text.TextUtils;

import com.solarized.firedown.StoragePaths;
import com.solarized.firedown.data.Download;
import com.solarized.firedown.data.entity.DownloadEntity;
import com.solarized.firedown.data.repository.DownloadDataRepository;
import com.solarized.firedown.utils.Utils;

import org.apache.commons.io.FilenameUtils;

import java.io.File;
import java.security.SecureRandom;
import java.util.concurrent.atomic.AtomicBoolean;

import okhttp3.OkHttpClient;

/**
 * Manages a single download's lifecycle.
 * Implements DownloadCallback to receive updates from the strategy.
 *
 * Thread-safety: the {@link #sealed} flag prevents strategy callbacks (running
 * on the download thread) from overwriting a terminal status set by
 * finishDownloadToExecutor / cancelDownloadTask (running on the service handler thread).
 */
public class DownloadTask implements DownloadCallback {

    private static final String TAG = DownloadTask.class.getSimpleName();

    private static final int MAX_FILENAME_RETRIES = 50;

    /** Single shared SecureRandom for ID generation — seeded once, used forever. */
    private static final SecureRandom ID_RANDOM = new SecureRandom();

    private final DownloadDataRepository repository;
    private final RunnableManager runnableManager;
    private final OkHttpClient okHttpClient;
    private final DownloadEntity entity;

    /** Once set, no callback may mutate the entity or write to the repository. */
    private final AtomicBoolean sealed = new AtomicBoolean(false);

    /** Set when onError sends MSG_ERROR — prevents onRunComplete from sending duplicate MSG_FINISH. */
    private final AtomicBoolean terminalMessageSent = new AtomicBoolean(false);

    private DownloadRunnable runnable;
    private DownloadContext context;

    public DownloadTask(RunnableManager runnableManager,
                        DownloadDataRepository repository,
                        OkHttpClient okHttpClient) {
        this.runnableManager = runnableManager;
        this.repository = repository;
        this.okHttpClient = okHttpClient;
        this.entity = new DownloadEntity();
    }

    // ========================================================================
    // Initialization
    // ========================================================================

    public void initialize(int id, DownloadRequest request, String filePath) {
        synchronized (runnableManager) {
            sealed.set(false);
            terminalMessageSent.set(false);

            entity.setId(id);
            entity.setFileType(request.getFileType());
            entity.setFileUrl(request.getUrl());
            entity.setFileName(FilenameUtils.getName(filePath));
            entity.setFilePath(filePath);
            entity.setFileOriginUrl(!TextUtils.isEmpty(request.getOrigin()) ? request.getOrigin() : request.getUrl());
            entity.setFileDescription(request.getDescription());
            entity.setFileMimeType(request.getMimeType() != null ? request.getMimeType() : "");

            // Merge cookie into headers so it persists in the DB for resume/retry
            String headers = request.getHeaders();
            String cookie = request.getCookieHeader();
            if (!TextUtils.isEmpty(cookie)) {
                headers = (TextUtils.isEmpty(headers) ? "" : headers + "\r\n") + "Cookie=" + cookie;
            }
            entity.setFileHeaders(headers);
            entity.setFileDate(System.currentTimeMillis());
            entity.setFileSize(request.getFileLength());
            entity.setFileProgress(0);
            entity.setFileErrorType(0);
            entity.setFilelive(false);
            entity.setFileStatus(Download.PROGRESS);
            entity.setFileSafe(request.isSaveToVault());
            entity.setFileDuration(request.getDurationTime());
            entity.setFileDurationFormatted(request.getDurationFormatted());

            String actualPath = request.isSaveToVault()
                    ? new File(StoragePaths.getSafePath(runnableManager), FilenameUtils.getName(filePath)).getAbsolutePath()
                    : filePath;
            entity.setFilePath(actualPath);
            entity.setFileName(FilenameUtils.getName(actualPath));
            context = buildContext(actualPath, request.getHeaders(), request.getCookieHeader());

            DownloadStrategy strategy = selectStrategy(request);

            runnable = new DownloadRunnable(
                    request, context, this, strategy,
                    () -> runnableManager.handleState(DownloadTask.this, RunnableManager.MSG_STARTED),
                    this::onRunComplete
            );

            repository.add(entity);
        }
    }

    public boolean isFileSafe() {
        return entity.isFileSafe();
    }

    public void resume(DownloadEntity existing) {
        sealed.set(false);
        terminalMessageSent.set(false);
        entity.parseDownload(existing);

        DownloadRequest request = new DownloadRequest.Builder(existing.getFileUrl())
                .name(existing.getFileName())
                .description(existing.getFileDescription())
                .origin(existing.getOriginUrl())
                .mimeType(existing.getFileMimeType())
                .headers(existing.getFileHeaders())
                .fileType(existing.getFileType())
                .fileLength(existing.getFileSize())
                .build();

        context = buildContext(existing.getFilePath(), existing.getFileHeaders(), null);
        DownloadStrategy strategy = selectStrategy(request);

        runnable = new DownloadRunnable(
                request, context, this, strategy,
                () -> runnableManager.handleState(DownloadTask.this, RunnableManager.MSG_STARTED),
                this::onRunComplete
        );

        repository.add(entity);
    }

    /** Central DownloadContext factory — keeps the OkHttpClient wiring in one place. */
    private DownloadContext buildContext(String path, String headers, String cookie) {
        return new DownloadContext(okHttpClient, runnableManager, path, headers, cookie);
    }

    /** Called from DownloadRunnable.finally — runs on the download thread. */
    private void onRunComplete() {
        DownloadContext ctx = context;
        if (ctx != null && ctx.isDeleted()) {
            repository.deleteDownload(entity);
        } else {
            // Always write — entity has the correct status (FINISHED from
            // sealWithStatus, or ERROR from onError). For SABR/FFmpeg finish,
            // onFileSizeKnown already updated the size before we get here.
            repository.add(entity);
        }
        if (!terminalMessageSent.getAndSet(true)) {
            runnableManager.handleState(this, RunnableManager.MSG_FINISH);
        }
    }

    // ========================================================================
    // Strategy selection
    // ========================================================================

    private DownloadStrategy selectStrategy(DownloadRequest request) {
        // SABR download: YouTube adaptive via SABR protocol (segments + mux)
        // Takes priority when SABR data is available on the request
        if (request.hasSabrData()) {
            return new SabrStrategy();
        }

        // YouTube adaptive: separate video + audio URLs merged by FFmpeg
        if (request.hasAudioUrl()) {
            return new FFmpegMergeStrategy();
        }

        UrlType type = UrlType.getType(request.getFileType());

        // HLS, DASH, TS manifests
        if (type.usesFFmpeg()) {
            return new FFmpegMuxStrategy();
        }

        // GeckoView WebResponse body stream
        if (type == UrlType.GECKO) {
            return new GeckoStreamStrategy();
        }

        // YouTube timed text → SRT conversion
        if (type == UrlType.TIMEDTEXT) {
            return new TimedTextStrategy();
        }

        // Default: direct HTTP byte copy with resume
        return new HttpDownloadStrategy();
    }

    // ========================================================================
    // DownloadCallback implementation
    // ========================================================================

    @Override
    public void onProgress(int percent, long downloaded, long total) {
        if (sealed.get()) return;
        entity.setFileProgress(percent);
        // Mark as live if total is unknown — covers HLS live streams
        // where ICY headers aren't present but C reports AV_NOPTS_VALUE
        if (total < 0 && !entity.getFileIsLive()) {
            entity.setFilelive(true);
        }
        // Update file size from disk so the UI can show current size during download.
        // For FFmpeg strategies, 'downloaded' is a timestamp not bytes, so use file length.
        String filePath = entity.getFilePath();
        if (filePath != null) {
            long fileLen = new File(filePath).length();
            if (fileLen > 0) {
                entity.setFileSize(fileLen);
            }
        }
        repository.add(entity);
    }

    @Override
    public void onStatusChanged(int status) {
        if (sealed.get()) return;
        entity.setFileStatus(status);
        repository.add(entity);
    }

    @Override
    public void onError(int errorType) {
        if (sealed.get()) return;
        sealed.set(true);
        terminalMessageSent.set(true);
        entity.setFileStatus(Download.ERROR);
        entity.setFileErrorType(errorType);
        repository.add(entity);
        runnableManager.handleState(this, RunnableManager.MSG_ERROR);
    }

    @Override
    public void onNameResolved(String name) {
        if (sealed.get()) return;
        entity.setFileName(name);
    }

    @Override
    public void onMimeResolved(String mimeType) {
        if (sealed.get()) return;
        entity.setFileMimeType(mimeType);
    }

    @Override
    public void onFileSizeKnown(long size) {
        // Allow through even when sealed — file size and accumulated
        // metadata (img path) need to be persisted for thumbnail generation
        // after user-initiated stop.
        entity.setFileSize(size);
        repository.add(entity);
    }

    @Override
    public String onFilePathResolved(String path) {
        if (sealed.get())
            return path;

        int selfId = entity.getId();
        File newFile = new File(path);
        synchronized (runnableManager.mQueuedFileTasks) {
            runnableManager.mQueuedFileTasks.remove(entity.getFilePath());
            int retries = 0;
            while (runnableManager.filePathInTasks(path, selfId) || !Utils.isFileWriteable(newFile)) {
                if (++retries > MAX_FILENAME_RETRIES) {
                    String dir = newFile.getParent();
                    String ext = FilenameUtils.getExtension(path);
                    path = dir + File.separator + "download_" + System.currentTimeMillis()
                            + (ext.isEmpty() ? "" : "." + ext);
                    break;
                }
                path = UrlParser.parseFilePath(path);
                newFile = new File(path);
            }
            runnableManager.mQueuedFileTasks.add(path);
        }
        entity.setFileName(FilenameUtils.getName(path));
        entity.setFilePath(path);
        entity.setFileImg(path);
        repository.add(entity);
        return path;
    }

    @Override
    public void onImgResolved(String imgPath) {
        // Allow through even when sealed — thumbnail path is non-destructive
        // metadata needed for Glide to generate thumbnails after user-initiated stop.
        entity.setFileImg(imgPath);
    }

    @Override
    public void onLiveStream(boolean isLive) {
        if (sealed.get()) return;
        entity.setFilelive(isLive);
        repository.add(entity);
    }

    @Override
    public void onDescriptionResolved(String description) {
        if (sealed.get()) return;
        entity.setFileDescription(description);
    }

    @Override
    public void onDurationResolved(long duration, String formatted) {
        if (sealed.get()) return;
        entity.setFileDuration(duration);
        entity.setFileDurationFormatted(formatted);
    }

    @Override
    public void onFinished() {
        if (sealed.get()) return;
        repository.add(entity);
    }

    // ========================================================================
    // Task lifecycle — called by RunnableManager on the service handler thread
    // ========================================================================

    public DownloadRunnable getRunnable() {
        return runnable;
    }

    public int getFileId() {
        return entity.getId();
    }

    public String getName() {
        return entity.getFileName();
    }

    public String getFilePath() {
        return entity.getFilePath();
    }

    public int getFileStatus() {
        return entity.getFileStatus();
    }

    /**
     * Sets status AND seals the task so no strategy callback can overwrite it.
     * Called by finishDownloadToExecutor when the user explicitly stops a download.
     */
    public void sealWithStatus(int status) {
        sealed.set(true);
        entity.setFileStatus(status);
    }

    public void setFileStatus(int status) {
        entity.setFileStatus(status);
    }

    public Thread getCurrentThread() {
        return context != null ? context.getCurrentThread() : null;
    }

    public void deleteRepository() {
        repository.deleteDownload(entity);
    }

    public void updateRepository() {
        repository.add(entity);
    }

    public void recycle() {
        sealed.set(true);
        // Do NOT reset terminalMessageSent here — the download thread may still
        // be winding down and onRunComplete needs to see it as true to skip the
        // duplicate MSG_FINISH. It gets reset in initialize()/resume() when the
        // task is reused for a new download.
        terminalMessageSent.set(true);
        if (runnable != null) {
            runnable.stop();
        }
        runnable = null;
        context = null;
    }

    /**
     * Generate a random 32-bit id.
     *
     * <p>Previously used {@code new Random()} seeded per call, which both wasted
     * entropy and returned the same value when called twice in the same
     * millisecond on some platforms. A single shared {@link SecureRandom} is
     * seeded once at class load and gives uniformly-distributed ids.
     */
    public static int generateId() {
        return ID_RANDOM.nextInt();
    }
}