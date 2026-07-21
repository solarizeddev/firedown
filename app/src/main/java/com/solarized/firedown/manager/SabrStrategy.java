package com.solarized.firedown.manager;

import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.solarized.firedown.data.Download;
import com.solarized.firedown.geckoview.PoTokenGenerator;
import com.solarized.firedown.ffmpegutils.FFmpegDownloader;
import com.solarized.firedown.ffmpegutils.FFmpegEntity;
import com.solarized.firedown.ffmpegutils.FFmpegErrors;
import com.solarized.firedown.ffmpegutils.FFmpegListener;
import com.solarized.firedown.ffmpegutils.FFmpegMetaData;
import com.solarized.firedown.ffmpegutils.FFmpegMetaDataReader;
import com.solarized.firedown.ffmpegutils.FFmpegStreamInfo;
import com.solarized.firedown.sabr.Fmp4Muxer;
import com.solarized.firedown.sabr.SabrDownloader;
import com.solarized.firedown.sabr.SabrMessages;
import com.solarized.firedown.utils.FileUriHelper;
import com.solarized.firedown.utils.MessageHelper;
import com.solarized.firedown.utils.WebUtils;

import java.util.ArrayList;
import java.util.Map;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.FilenameUtils;

import java.io.File;
import java.io.IOException;

/**
 * SABR (Server-Adaptive Bitrate) download strategy.
 *
 * Downloads video and audio segments via YouTube's SABR protocol using
 * MWEB client identity with BotGuard PO token for attestation,
 * writes them to temp files, then muxes into a single MP4 with FFmpeg
 * (stream copy, no re-encoding).
 */
public class SabrStrategy implements DownloadStrategy {

    private static final String TAG = SabrStrategy.class.getSimpleName();
    private static final long UPDATE_RATE = 1500;

    // The download phase fills the whole 0–100% bar; the subsequent FFmpeg
    // mux is shown as an indeterminate "Finishing…" state, not a determinate
    // slice, so it no longer needs a reserved weight.
    private static final int DOWNLOAD_WEIGHT = 100;

    /** Minimum time the "Finishing…" state is held on screen. The mux can be
     *  sub-second (stream copy), too fast for the UI to ever render the state
     *  before FINISHED replaces it; this guarantees it's perceptible. */
    private static final long MIN_FINISHING_VISIBLE_MS = 700;

    private SabrDownloader sabrDownloader;
    private DownloadCallback callback;
    private DownloadContext context;
    private long lastUpdated;
    private volatile boolean stopped;
    private Fmp4Muxer muxer;

    @Override
    public void execute(DownloadRequest request, DownloadContext context, DownloadCallback callback)
            throws IOException {
        this.callback = callback;
        this.context = context;

        // ====================================================================
        // 1. Validate SABR parameters
        // ====================================================================

        String sabrUrl = request.getSabrUrl();
        String sabrConfig = request.getSabrConfig();

        if (TextUtils.isEmpty(sabrUrl) || TextUtils.isEmpty(sabrConfig)) {
            Log.e(TAG, "Missing SABR data, cannot proceed");
            callback.onError(MessageHelper.IOEXCEPTION);
            return;
        }

        // ====================================================================
        // 2. Resolve output file path
        // ====================================================================

        String mimeType = request.getMimeType();
        if (TextUtils.isEmpty(mimeType)) mimeType = FileUriHelper.MIMETYPE_MP4;
        callback.onMimeResolved(mimeType);

        File file = context.getOutputFile();
        String ext = FileUriHelper.getFileExtensionFromMimeType(mimeType);
        if (TextUtils.isEmpty(ext)) ext = "mp4";
        file = new File(file.getParent(),
                FilenameUtils.getBaseName(file.getName()) + "." + ext);
        String resolvedPath = callback.onFilePathResolved(file.getAbsolutePath());
        file = new File(resolvedPath);

        if (TextUtils.isEmpty(request.getDescription())) {
            callback.onDescriptionResolved(WebUtils.getTitle(request.getOrigin()));
        }

        // ====================================================================
        // 3. Configure and run SABR downloader
        // ====================================================================

        File tempDir = new File(file.getParent(), ".sabr_" + System.currentTimeMillis());
        if (!tempDir.mkdirs() && !tempDir.isDirectory()) {
            Log.e(TAG, "Failed to create temp dir: " + tempDir);
            callback.onError(MessageHelper.EXTERNAL_STORAGE);
            return;
        }

        // try/finally so tempDir is removed on every exit path including
        // the uncaught IOException from sabrDownloader.download() (network
        // / disk error) and any exception out of the FFmpeg mux step. The
        // previous structure relied on inline cleanupTempDir() calls and
        // missed at least the IOException case, leaving .sabr_<ts> dirs
        // accumulating under /Download/Firedown.
        try {
            executeInternal(request, file, tempDir);
        } finally {
            // Close the inline muxer's output stream on every exit path
            // (idempotent — also called before validation). cleanupTempDir then
            // removes the raw temp segments.
            if (muxer != null) {
                muxer.close();
            }
            cleanupTempDir(tempDir);
        }
    }


    private void executeInternal(DownloadRequest request, File file, File tempDir)
            throws IOException {
        sabrDownloader = new SabrDownloader(context.getOkHttpClient());
        sabrDownloader.setStreamingUrl(request.getSabrUrl());
        sabrDownloader.setUstreamerConfig(request.getSabrConfig());

        // PO token resolution — two paths in order of preference:
        //
        //   1. NATIVE — PoTokenGenerator owns a long-lived hidden GeckoSession
        //      and mints a fresh per-video token over a native port. This
        //      bypasses the WebExtension Tabs API fragility (the
        //      `webProgress is undefined` / `WindowEventDispatcher win is
        //      null` cascade that's been killing tabs mid-mint) and uses
        //      Java CountDownLatch / CompletableFuture timeouts that don't
        //      die alongside the JS macrotask scheduler.
        //
        //   2. JS-EMBEDDED — token attached to the DownloadRequest from the
        //      youtube WebExtension's background.js path. Kept as a fallback
        //      until the native path is proven across all repro cases.
        //
        // Per-video binding matters: each PO token is bound to a specific
        // videoId (the BotGuard `contentBinding`), so we always want a fresh
        // mint for the video being downloaded. The native path mints fresh;
        // the JS path may return a cached token bound to a different video
        // (a latent bug in the JS cache).
        String poToken = mintPoToken(request);
        if (!TextUtils.isEmpty(poToken)) {
            sabrDownloader.setPoToken(poToken);
            Log.d(TAG, "PO token applied: " + poToken.length() + " chars");
        } else {
            Log.w(TAG, "No PO token available — SABR will hit the attestation wall");
        }

        // Dynamic MWEB client version from HTML — CDN validates cver= matches
        String clientVersion = request.getSabrClientVersion();
        if (!TextUtils.isEmpty(clientVersion)) {
            sabrDownloader.setClientInfo(SabrMessages.CLIENT_MWEB, clientVersion);
            Log.d(TAG, "Client version: " + clientVersion);
        }

        SabrMessages.FormatId videoFmt = new SabrMessages.FormatId(
                request.getSabrVideoItag(),
                parseLongSafe(request.getSabrVideoLastModified()),
                request.getSabrVideoXtags()
        );
        SabrMessages.FormatId audioFmt = new SabrMessages.FormatId(
                request.getSabrAudioItag(),
                parseLongSafe(request.getSabrAudioLastModified()),
                request.getSabrAudioXtags()
        );

        sabrDownloader.setVideoFormat(videoFmt);
        sabrDownloader.setAudioFormat(audioFmt);

        // DownloadRequest carries durationTime in microseconds (sourced from
        // FFprobe metadata or the JS path's ms→µs conversion). SabrDownloader
        // expects milliseconds, so convert once here.
        long durationMs = request.getDurationTime() / 1000;
        sabrDownloader.setDurationMs(durationMs);
        sabrDownloader.setTargetResolution(request.getSabrTargetHeight());

        String audioTrackId = request.getSabrAudioTrackId();
        if (!TextUtils.isEmpty(audioTrackId)) {
            sabrDownloader.setAudioTrackId(audioTrackId);
        }

        // Unified progress: SABR download = 0-95%
        sabrDownloader.setProgressListener((downloadedMs, totalMs, videoSegs, audioSegs) -> {
            if (stopped) return;
            int dlPct = totalMs > 0
                    ? Math.min((int)(downloadedMs * DOWNLOAD_WEIGHT / totalMs), DOWNLOAD_WEIGHT)
                    : 0;
            // SABR writes segments to a temp dir and only muxes to the output
            // file at the end, so the generic onProgress size probe
            // (new File(outputPath).length()) reads 0 for the whole download —
            // the "Downloading · N files · X MB" header would sit frozen on the
            // capture-time estimate. Feed the live accumulated temp bytes
            // (video + audio) so the header reflects real progress, the same
            // downloaded-so-far figure a direct-to-file download reports.
            reportProgress(dlPct, downloadedMs, totalMs, sabrDownloadedBytes(tempDir));
        });

        // Inline fMP4 muxer: interleaves video+audio into `file` as segments
        // arrive, so the playable file is ready the instant the download ends —
        // no FFmpeg mux pass. It writes ALONGSIDE the temp files (which are kept
        // as the fallback) and self-fails on anything it can't handle; after the
        // download we probe-validate `file` and fall back to the FFmpeg remux of
        // the temps if the inline result isn't usable.
        muxer = new Fmp4Muxer(file);
        sabrDownloader.setMuxSink(muxer);

        Log.d(TAG, "Starting SABR download: video=" + request.getSabrVideoItag()
                + " audio=" + request.getSabrAudioItag()
                + " duration=" + durationMs + "ms"
                + " target=" + request.getSabrTargetHeight() + "p"
                + (poToken != null ? " poToken=yes" : " poToken=no"));

        // Report initial progress so UI shows 0%
        reportProgress(0, 0, durationMs);

        SabrDownloader.Result result;
        try {
            result = sabrDownloader.download(tempDir);
        } catch (SabrDownloader.SabrException e) {
            Log.e(TAG, "SABR download failed: " + e.getMessage(), e);

            if (isDeleted()) {
                if (file.exists()) file.delete();
                return;
            }

            // Try to salvage: if we have some segments, mux them
            File videoTemp = new File(tempDir, "video_sabr.mp4");
            File audioTemp = new File(tempDir, "audio_sabr.m4a");
            if (!videoTemp.exists() || videoTemp.length() == 0) {
                callback.onError(MessageHelper.IOEXCEPTION);
                return;
            }
            // Fall through to mux with partial data
            result = new SabrDownloader.Result(videoTemp, audioTemp,
                    durationMs, 0, 0);
        }

        // ====================================================================
        // 4. Handle delete — abort, no mux
        // ====================================================================

        if (isDeleted()) {
            Log.d(TAG, "Download deleted, cleaning up");
            if (file.exists()) file.delete();
            return;
        }

        // Check if we have anything to mux
        if (result.videoFile == null || !result.videoFile.exists()
                || result.videoFile.length() == 0) {
            Log.w(TAG, "No video data downloaded");
            if (!stopped) {
                callback.onError(MessageHelper.IOEXCEPTION);
            }
            return;
        }

        Log.d(TAG, "SABR download phase complete: "
                + result.videoFile.length() / 1024 + "KB video + "
                + (result.audioFile != null ? result.audioFile.length() / 1024 : 0) + "KB audio"
                + (stopped ? " (user stopped, muxing partial)" : ""));

        // ====================================================================
        // 5. Mux with FFmpeg (stream copy)
        // Always mux — even on stop (finish). User gets a playable partial file.
        // ====================================================================

        // Bytes are all in; the FFmpeg mux is a second pass. Rather than freeze
        // the bar near the top (the confusing "stuck" the mux used to show),
        // flip the row into an indeterminate "Finishing…" state for the duration
        // of the mux. onProcessing() is honoured even when sealed, so this also
        // covers the user-finish path (which seals to FINISHED, then muxes the
        // partial data — often the LONGEST mux, and the one most in need of a
        // clear signal).
        callback.onProcessing();
        long finishingShownAt = System.currentTimeMillis();

        // Prefer the inline-muxed file. SabrDownloader streamed video+audio into
        // `file` as the segments arrived, so when the muxer produced a valid
        // interleaved fragmented MP4 there is no mux pass left to run. Probe-
        // validate it first; on any shortfall (the muxer self-failed, or the
        // probe can't read a clean file) fall back to the proven FFmpeg
        // stream-copy of the kept temp files.
        muxer.close();
        long inlineDuration = 0;
        if (muxer.isHeaderWritten() && file.exists() && file.length() > 0) {
            inlineDuration = probePlayableDuration(file);
        }
        boolean inlineMuxed = inlineDuration > 0;
        Log.d(TAG, "Inline fMP4 mux "
                + (inlineMuxed ? "succeeded — skipping FFmpeg"
                               : "unavailable — using FFmpeg"));
        if (inlineMuxed) {
            // Hand the just-probed file duration to the task so its
            // post-download refreshMetadataFromFile can skip re-probing the
            // SAME file seconds later. The duplicate probe was pure waste on
            // every SABR finish, and in the user-finish repro the second
            // probe was the step that never returned (row stuck on
            // "Finishing…" forever).
            callback.onFileDurationProbed(inlineDuration);
        }

        if (!inlineMuxed) {
            FFmpegDownloader ffmpegDownloader = new FFmpegDownloader();
            ffmpegDownloader.addListener(new FFmpegListener() {
                @Override
                public void onStarted() {}

                @Override
                public void onProgress(long downloaded, long total) {
                    // No per-tick progress during the mux — the row stays in the
                    // indeterminate "Finishing…" state set above.
                }

                @Override
                public void onFinished() {}
            });

            // Build inputs — only include audio if it has data
            String[] inputs;
            if (result.audioFile != null && result.audioFile.exists()
                    && result.audioFile.length() > 0) {
                inputs = new String[]{
                        result.videoFile.getAbsolutePath(),
                        result.audioFile.getAbsolutePath()
                };
            } else {
                inputs = new String[]{ result.videoFile.getAbsolutePath() };
            }

            int muxResult = ffmpegDownloader.start(
                    inputs,
                    (Map<String, String>) null,
                    null,
                    file.getAbsolutePath(),
                    result.videoFile.length()
                            + (result.audioFile != null ? result.audioFile.length() : 0)
            );

            ffmpegDownloader.free();

            if (!isDeleted() && muxResult < 0) {
                Log.e(TAG, "FFmpeg mux failed: " + muxResult);
                if (muxResult == FFmpegErrors.ENOENT) {
                    callback.onError(MessageHelper.EXTERNAL_STORAGE);
                } else {
                    callback.onError(MessageHelper.IOEXCEPTION);
                }
                return;
            }
        }

        // Delete arrived during mux / validation
        if (isDeleted()) {
            if (file.exists()) file.delete();
            return;
        }

        // The mux for SABR is a stream copy of already-fMP4 segments, so for a
        // small clip it can complete in well under a second — fast enough that
        // the "Finishing…" state would be written and then overwritten by
        // FINISHED within one frame, and the Paging differ coalesces the two so
        // the user never sees it. Hold the state on screen for a short minimum
        // so the transition is always perceptible. Applies to the user-finish
        // path too (a tiny partial muxes just as fast).
        long shownFor = System.currentTimeMillis() - finishingShownAt;
        if (shownFor < MIN_FINISHING_VISIBLE_MS) {
            sleep(MIN_FINISHING_VISIBLE_MS - shownFor);
        }

        // ====================================================================
        // 6. Done
        // ====================================================================

        // Stopped (user finish): mux completed with partial data.
        if (stopped) {
            if (file.exists() && file.length() > 0) {
                callback.onImgResolved(file.getAbsolutePath());
                callback.onFileSizeKnown(file.length());
            }
            // Boundary log — everything after this line is DownloadTask/
            // RunnableManager territory (onRunComplete → recycleTask), so a
            // "Finishing…" row stuck with this line printed puts the wedge
            // there, not in the strategy.
            Log.d(TAG, "user finish: partial file finalized ("
                    + file.length() + " bytes)");
            return;
        }

        // Normal completion
        reportProgress(100, file.length(), file.length());
        callback.onImgResolved(file.getAbsolutePath());
        callback.onFileSizeKnown(file.length());
        callback.onStatusChanged(Download.FINISHED);
        Log.d(TAG, "download finished: " + file.length() + " bytes");
    }

    // ========================================================================
    // Stop / Delete
    // ========================================================================

    @Override
    public void stop() {
        stopped = true;
        if (sabrDownloader != null) {
            sabrDownloader.abort();
        }
    }

    private boolean isDeleted() {
        return context != null && context.isDeleted();
    }

    // ========================================================================
    // Helpers
    // ========================================================================

    /**
     * Mint a PO token for this download via {@link PoTokenGenerator}. Returns
     * {@code null} on failure — the SABR download will start without one and
     * YouTube will cut it at ~60s with status=3 (attestation required), which
     * is the correct fail-mode: better to fail fast than to ship a cold-start
     * placeholder that fakes a head start and dies the same way.
     */
    @Nullable
    private String mintPoToken(@NonNull DownloadRequest request) {
        PoTokenGenerator gen = context.getPoTokenGenerator();
        String videoId = request.getSabrVideoId();
        String visitorData = request.getSabrVisitorData();

        if (gen == null) {
            Log.w(TAG, "mintPoToken: PoTokenGenerator unavailable");
            return null;
        }
        if (TextUtils.isEmpty(videoId) || TextUtils.isEmpty(visitorData)) {
            Log.w(TAG, "mintPoToken: missing videoId/visitorData on request");
            return null;
        }

        long t0 = System.currentTimeMillis();
        try {
            String token = gen.generate(videoId, visitorData);
            long dt = System.currentTimeMillis() - t0;
            if (!TextUtils.isEmpty(token)) {
                Log.d(TAG, "Native PO token: " + token.length() + " chars (" + dt + "ms)");
                return token;
            }
            Log.w(TAG, "Native mint returned empty after " + dt + "ms");
        } catch (Exception e) {
            long dt = System.currentTimeMillis() - t0;
            Log.w(TAG, "Native mint failed after " + dt + "ms: " + e.getMessage());
        }
        return null;
    }

    /** Interruptible sleep — re-flags the thread interrupt on wake so the
     *  download thread's cancellation path still sees it. */
    private static void sleep(long ms) {
        if (ms <= 0) return;
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void reportProgress(int percent, long downloaded, long total) {
        reportProgress(percent, downloaded, total, -1);
    }

    /**
     * As {@link #reportProgress(int, long, long)}, but also publishes the
     * current downloaded byte count ({@code bytes <= 0} = unknown, skipped).
     * Throttled together with the progress tick so the size update rides the
     * same ~1.5s cadence rather than firing a DB write per segment callback.
     */
    private void reportProgress(int percent, long downloaded, long total, long bytes) {
        if (callback == null) return;
        long now = System.currentTimeMillis();
        if (now - lastUpdated > UPDATE_RATE || percent >= 100 || percent == 0) {
            lastUpdated = now;
            if (bytes > 0) callback.onFileSizeKnown(bytes);
            callback.onProgress(Math.min(percent, 100), downloaded, total);
        }
    }

    /**
     * Probe-validates an inline-muxed file before it's trusted over the FFmpeg
     * fallback: it must read as a real container with a positive duration and at
     * least one video stream. A muxer bug that produced a malformed file fails
     * here, and the caller re-muxes the kept temp files with FFmpeg instead — so
     * an unverified muxer can never ship a broken download.
     *
     * @return the probed duration when the file is a playable mux, {@code 0}
     *         otherwise. Returning the duration (rather than a boolean) lets the
     *         caller forward it via {@code onFileDurationProbed} so the task's
     *         post-download refresh doesn't probe the same file a second time.
     */
    private static long probePlayableDuration(File file) {
        FFmpegMetaDataReader reader = new FFmpegMetaDataReader();
        try {
            FFmpegMetaData meta = reader.getStreamInfo(file.getAbsolutePath(), null, false);
            if (meta == null || meta.getDuration() <= 0) {
                return 0;
            }
            ArrayList<FFmpegEntity> streams = reader.getStreams();
            if (streams == null) {
                return 0;
            }
            for (FFmpegEntity stream : streams) {
                if (stream != null
                        && stream.getCodecType() == FFmpegStreamInfo.CodecType.VIDEO.getValue()) {
                    return meta.getDuration();
                }
            }
            return 0;
        } catch (Exception e) {
            return 0;
        } finally {
            reader.stop();
            reader.release();
        }
    }

    /** Sum of the SABR temp segment files currently on disk (video + audio) —
     *  the downloaded-so-far byte count while the output file doesn't yet
     *  exist. File names mirror {@code SabrDownloader.download}. */
    private static long sabrDownloadedBytes(File tempDir) {
        if (tempDir == null) return 0;
        long bytes = 0;
        File video = new File(tempDir, "video_sabr.mp4");
        if (video.exists()) bytes += video.length();
        File audio = new File(tempDir, "audio_sabr.m4a");
        if (audio.exists()) bytes += audio.length();
        return bytes;
    }

    private static long parseLongSafe(String value) {
        if (value == null || value.isEmpty()) return 0;
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * Recursively delete the temp dir.
     * Commons-io's {@link FileUtils#deleteDirectory(File)} handles sub-dirs,
     * unlike the previous hand-rolled non-recursive version.
     */
    private static void cleanupTempDir(File dir) {
        if (dir == null || !dir.exists()) return;
        try {
            FileUtils.deleteDirectory(dir);
        } catch (IOException e) {
            Log.w(TAG, "cleanupTempDir failed: " + dir, e);
        }
    }
}