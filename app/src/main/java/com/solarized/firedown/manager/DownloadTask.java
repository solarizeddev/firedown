package com.solarized.firedown.manager;

import android.text.TextUtils;
import android.util.Log;

import androidx.preference.PreferenceManager;

import com.solarized.firedown.Preferences;
import com.solarized.firedown.StoragePaths;
import com.solarized.firedown.data.Download;
import com.solarized.firedown.data.entity.DownloadEntity;
import com.solarized.firedown.data.repository.DownloadDataRepository;
import com.solarized.firedown.ffmpegutils.FFmpegEntity;
import com.solarized.firedown.ffmpegutils.FFmpegMetaData;
import com.solarized.firedown.ffmpegutils.FFmpegMetaDataReader;
import com.solarized.firedown.ffmpegutils.FFmpegStreamInfo;
import com.solarized.firedown.ffmpegutils.FFmpegUtils;
import com.solarized.firedown.geckoview.PoTokenGenerator;
import com.solarized.firedown.utils.FileUriHelper;
import com.solarized.firedown.utils.GalleryPublisher;
import com.solarized.firedown.utils.Utils;

import org.apache.commons.io.FilenameUtils;

import java.io.File;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
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

    /** Terminal status that {@link #onProcessing()} temporarily overrode to show
     *  the "Finishing…" state (user-finish seals to FINISHED before its mux);
     *  {@link #onRunComplete()} restores it. {@link Integer#MIN_VALUE} = unset. */
    private int mStatusBeforeFinishing = Integer.MIN_VALUE;

    /** Duration the strategy already probed from the finished OUTPUT file
     *  ({@link #onFileDurationProbed}); {@code 0} = none. Lets
     *  {@link #refreshMetadataFromFile()} skip re-probing the same file. */
    private long mProbedFileDuration;

    /** Bounds the post-download metadata probe: a local-file probe is normally
     *  sub-second, so a probe still running after this long is wedged (FUSE
     *  stall, odd partial file) and gets its native AVIO interrupt flag set
     *  via {@code reader.stop()} — the same non-blocking unwind a user Stop /
     *  tab-close uses on capture probes ({@code GeckoInspectTask}). */
    private static final long PROBE_WATCHDOG_SECONDS = 30;
    private static final ScheduledExecutorService PROBE_WATCHDOG =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread thread = new Thread(r, "download-probe-watchdog");
                thread.setDaemon(true);
                return thread;
            });

    private DownloadRunnable runnable;
    private DownloadContext context;

    public DownloadTask(RunnableManager runnableManager,
                        DownloadDataRepository repository,
                        OkHttpClient okHttpClient,
                        PoTokenGenerator poTokenGenerator) {
        this.runnableManager = runnableManager;
        this.repository = repository;
        this.okHttpClient = okHttpClient;
        this.poTokenGenerator = poTokenGenerator;
        this.entity = new DownloadEntity();
    }

    private final PoTokenGenerator poTokenGenerator;

    // ========================================================================
    // Initialization
    // ========================================================================

    public void initialize(int id, DownloadRequest request, String filePath) {
        synchronized (runnableManager) {
            sealed.set(false);
            terminalMessageSent.set(false);
            mStatusBeforeFinishing = Integer.MIN_VALUE;
            mProbedFileDuration = 0;

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
            entity.setFileLanguage(request.getLanguage());
            entity.setFileResolution(request.getResolution());

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
        mStatusBeforeFinishing = Integer.MIN_VALUE;
        mProbedFileDuration = 0;
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
        return new DownloadContext(okHttpClient, runnableManager, path, headers, cookie, poTokenGenerator);
    }

    /** Called from DownloadRunnable.finally — runs on the download thread. */
    private void onRunComplete() {
        Log.d(TAG, "onRunComplete: id=" + entity.getId()
                + " status=" + entity.getFileStatus());
        DownloadContext ctx = context;
        if (ctx != null && ctx.isDeleted()) {
            repository.deleteDownload(entity);
        } else {
            // Restore a terminal status that onProcessing temporarily overrode
            // to PROGRESS for the transient "Finishing…" display (user-finish
            // seals to FINISHED before its partial-data mux runs).
            if (mStatusBeforeFinishing != Integer.MIN_VALUE) {
                entity.setFileStatus(mStatusBeforeFinishing);
                mStatusBeforeFinishing = Integer.MIN_VALUE;
            }
            // Always write — entity has the correct status (FINISHED from
            // sealWithStatus, or ERROR from onError). For SABR/FFmpeg finish,
            // onFileSizeKnown already updated the size before we get here.
            if (entity.getFileStatus() == Download.FINISHED) {
                // A user-finished row otherwise keeps PROCESSING_PROGRESS (101)
                // forever: the stopped path never reports 100%, and the restore
                // above only fixes the STATUS. Normalize so a FINISHED row never
                // carries the transient "Finishing…" progress sentinel into the DB.
                if (entity.getFileProgress() == Download.PROCESSING_PROGRESS) {
                    entity.setFileProgress(100);
                }
                refreshMetadataFromFile();
            }
            repository.add(entity);
        }
        Log.d(TAG, "onRunComplete: id=" + entity.getId() + " final write queued");
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

        // Mega.nz: resolve the temp download URL via Mega's API, then stream it
        // through AES-CTR decryption (the captured URL serves ciphertext).
        if (type == UrlType.MEGA) {
            return new MegaStrategy();
        }

        // Deezer: re-mint tokens from the session cookie, resolve the encrypted
        // CDN URL, then stream it through Blowfish-stripe decryption.
        if (type == UrlType.DEEZER) {
            return new DeezerStrategy();
        }

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
    public void onProcessing() {
        // Honoured even when sealed (unlike onProgress): a user-finished
        // download is sealed to FINISHED before its partial-data mux, but we
        // still want a transient "Finishing…" row while the mux runs. The
        // sealed terminal status is saved so onRunComplete can restore it once
        // the mux is done. Render state = PROGRESS + PROCESSING_PROGRESS, which
        // the adapter shows as an indeterminate "Finishing…".
        if (entity.getFileStatus() != Download.PROGRESS) {
            mStatusBeforeFinishing = entity.getFileStatus();
        }
        entity.setFileStatus(Download.PROGRESS);
        entity.setFileProgress(Download.PROCESSING_PROGRESS);
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
    public void onFileDurationProbed(long duration) {
        // Allow through even when sealed — this is ground truth read from the
        // finished file itself, and the user-finish path is sealed when the
        // strategy reports it. Only stored; refreshMetadataFromFile applies it.
        mProbedFileDuration = duration;
    }

    @Override
    public void onFinished() {
        if (sealed.get()) return;
        repository.add(entity);
        publishToGalleryIfEnabled();
    }

    /**
     * Re-read metadata from the finished file on disk: <b>duration</b> for
     * audio/video (always), <b>resolution</b> for images (only when missing).
     *
     * <p><b>Duration is re-probed unconditionally and the probe result wins
     * over the capture-time value.</b> The stored duration comes from the
     * parser/capture probe and describes the <i>full</i> media — but the user
     * can hit Finish mid-download ({@code finishDownloadToExecutor} seals the
     * entity FINISHED and stops the runnable), leaving a file that is cut in
     * half while the entity still claims the full length. The local file is
     * the only ground truth, so probe it once here (cheap, no network, no
     * single-use keys — unlike a capture-time probe). When the probe can't
     * read a duration at all (e.g. a progressive MP4 truncated before its
     * moov atom), the capture-time value is unverified and almost certainly
     * wrong, so it's <i>cleared</i> rather than left to lie.
     *
     * <p>Resolution stays backfill-only: it covers an image saved straight
     * from the browser long-press menu ("save image", a bare
     * {@link DownloadRequest} that never went through the parser/
     * {@code VariantProcessor}), and an image can't be "shorter" than
     * captured — a truncated one just fails to decode. Reuses the reader's
     * stream formatter ("WxH", SVG-aware), matching what the parser path
     * emits.
     */
    private void refreshMetadataFromFile() {
        String mime = entity.getFileMimeType();
        boolean isAv = FileUriHelper.isAudio(mime) || FileUriHelper.isVideo(mime);
        boolean isImage = FileUriHelper.isImage(mime) || FileUriHelper.isSVG(mime);

        boolean needResolution = isImage && TextUtils.isEmpty(entity.getFileResolution());
        if (!isAv && !needResolution) {
            return;
        }

        String path = entity.getFilePath();
        if (TextUtils.isEmpty(path)) {
            return;
        }

        // The strategy may have ALREADY probed the finished file (SABR's
        // inline-mux validation) and handed the duration over — don't open
        // the same file a second time seconds later. Besides the waste, the
        // duplicate probe is where a user-finished SABR download was observed
        // wedging forever (row stuck on "Finishing…" with the final DB write
        // never reached).
        if (isAv && !needResolution && mProbedFileDuration > 0) {
            Log.d(TAG, "refreshMetadataFromFile: using strategy-probed duration "
                    + mProbedFileDuration);
            entity.setFileDuration(mProbedFileDuration);
            entity.setFileDurationFormatted(FFmpegUtils.getFileDuration(mProbedFileDuration));
            return;
        }

        Log.d(TAG, "refreshMetadataFromFile: probing " + path);
        long duration = 0;
        FFmpegMetaDataReader reader = new FFmpegMetaDataReader();
        // Watchdog: a local-file probe is normally sub-second; if it wedges
        // (FUSE stall, odd partial file) nothing else would ever unblock the
        // download thread — the row would sit on "Finishing…" forever and the
        // final FINISHED write would never land. After the deadline the
        // watchdog sets the reader's native AVIO interrupt flag (stop() is
        // non-blocking) so the probe unwinds; the fallback below (keep/clear
        // the stored duration) is correct output for an aborted probe. The
        // lock orders watchdog-stop before release so a late watchdog can
        // never stop() a freed reader (the GeckoInspectTask rule).
        final Object readerLock = new Object();
        final boolean[] readerReleased = {false};
        ScheduledFuture<?> watchdog = PROBE_WATCHDOG.schedule(() -> {
            synchronized (readerLock) {
                if (!readerReleased[0]) {
                    Log.w(TAG, "refreshMetadataFromFile: probe exceeded "
                            + PROBE_WATCHDOG_SECONDS + "s, interrupting: " + path);
                    reader.stop();
                }
            }
        }, PROBE_WATCHDOG_SECONDS, TimeUnit.SECONDS);
        try {
            FFmpegMetaData meta = reader.getStreamInfo(path, null, false);
            if (meta != null) {
                duration = meta.getDuration();
                if (needResolution) {
                    String resolution = readVideoStreamInfo(reader);
                    if (!TextUtils.isEmpty(resolution)) {
                        entity.setFileResolution(resolution);
                    }
                }
            }
        } catch (Exception e) {
            // best-effort — duration stays 0 and is cleared below for A/V
        } finally {
            watchdog.cancel(false);
            synchronized (readerLock) {
                readerReleased[0] = true;
                reader.stop();
                reader.release();
            }
        }
        Log.d(TAG, "refreshMetadataFromFile: probe done, duration=" + duration);

        if (isAv) {
            if (duration > 0) {
                entity.setFileDuration(duration);
                entity.setFileDurationFormatted(FFmpegUtils.getFileDuration(duration));
            } else {
                // Unreadable file — don't keep a capture-time duration the
                // bytes on disk can't back up. Same clearing convention as
                // CompressTask.
                entity.setFileDuration(0);
                entity.setFileDurationFormatted(null);
            }
        }
    }

    /**
     * Pull the formatted resolution ("WxH" for images) of the first video
     * stream from a reader whose {@code getStreamInfo} has already run. Reuses
     * {@link FFmpegMetaDataReader#getStreams()} so the image-vs-video and
     * SVG-encoded-size formatting stays in one place.
     */
    private static String readVideoStreamInfo(FFmpegMetaDataReader reader) {
        ArrayList<FFmpegEntity> streams = reader.getStreams();
        if (streams == null) {
            return null;
        }
        for (FFmpegEntity stream : streams) {
            if (stream == null) {
                continue;
            }
            if (stream.getCodecType() == FFmpegStreamInfo.CodecType.VIDEO.getValue()
                    && !TextUtils.isEmpty(stream.getInfo())) {
                return stream.getInfo();
            }
        }
        return null;
    }

    /**
     * Surfaces the finished file to the system gallery when the user
     * has opted in via "Add to Media Gallery". Skipped for vault /
     * incognito downloads — the whole point of those is that they
     * stay out of every other app's index.
     */
    private void publishToGalleryIfEnabled() {
        if (entity.isFileSafe()) return;
        if (!Preferences.getSaveToGallery(
                PreferenceManager.getDefaultSharedPreferences(runnableManager))) {
            return;
        }
        GalleryPublisher.publish(runnableManager, entity.getFilePath(), entity.getFileMimeType());
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