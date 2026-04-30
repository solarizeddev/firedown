package com.solarized.firedown.manager;

import android.text.TextUtils;
import android.util.Log;

import com.solarized.firedown.data.Download;
import com.solarized.firedown.ffmpegutils.FFmpegDownloader;
import com.solarized.firedown.ffmpegutils.FFmpegErrors;
import com.solarized.firedown.ffmpegutils.FFmpegListener;
import com.solarized.firedown.sabr.SabrDownloader;
import com.solarized.firedown.sabr.SabrMessages;
import com.solarized.firedown.utils.FileUriHelper;
import com.solarized.firedown.utils.MessageHelper;
import com.solarized.firedown.utils.WebUtils;

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

    private static final int DOWNLOAD_WEIGHT = 95;
    private static final int MUX_WEIGHT = 5;

    private SabrDownloader sabrDownloader;
    private DownloadCallback callback;
    private DownloadContext context;
    private long lastUpdated;
    private volatile boolean stopped;

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

        sabrDownloader = new SabrDownloader(context.getOkHttpClient());
        sabrDownloader.setStreamingUrl(sabrUrl);
        sabrDownloader.setUstreamerConfig(sabrConfig);

        // PO token from BotGuard — enables full download without attestation wall
        String poToken = request.getSabrPoToken();
        if (!TextUtils.isEmpty(poToken)) {
            sabrDownloader.setPoToken(poToken);
            Log.d(TAG, "PO token applied: " + poToken.length() + " chars");
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
            reportProgress(dlPct, downloadedMs, totalMs);
        });

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
                cleanupTempDir(tempDir);
                if (file.exists()) file.delete();
                return;
            }

            // Try to salvage: if we have some segments, mux them
            File videoTemp = new File(tempDir, "video_sabr.mp4");
            File audioTemp = new File(tempDir, "audio_sabr.m4a");
            if (!videoTemp.exists() || videoTemp.length() == 0) {
                cleanupTempDir(tempDir);
                callback.onError(MessageHelper.IOEXCEPTION);
                return;
            }
            // Fall through to mux with partial data
            result = new SabrDownloader.Result(videoTemp, audioTemp,
                    durationMs, 0, 0);
        }

        // ====================================================================
        // 4. Handle delete — abort, clean up everything, no mux
        // ====================================================================

        if (isDeleted()) {
            Log.d(TAG, "Download deleted, cleaning up");
            cleanupTempDir(tempDir);
            if (file.exists()) file.delete();
            return;
        }

        // Check if we have anything to mux
        if (result.videoFile == null || !result.videoFile.exists()
                || result.videoFile.length() == 0) {
            Log.w(TAG, "No video data downloaded");
            cleanupTempDir(tempDir);
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

        reportProgress(DOWNLOAD_WEIGHT, 0, 0);

        FFmpegDownloader ffmpegDownloader = new FFmpegDownloader();
        ffmpegDownloader.addListener(new FFmpegListener() {
            @Override
            public void onStarted() {}

            @Override
            public void onProgress(long downloaded, long total) {
                if (isDeleted() || total <= 0) return;
                int muxPct = DOWNLOAD_WEIGHT
                        + (int) Math.min(downloaded * MUX_WEIGHT / total, MUX_WEIGHT);
                reportProgress(muxPct, downloaded, total);
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
                (java.util.Map<String, String>) null,
                null,
                file.getAbsolutePath(),
                result.videoFile.length()
                        + (result.audioFile != null ? result.audioFile.length() : 0)
        );

        ffmpegDownloader.free();

        // Always clean temp files after mux
        cleanupTempDir(tempDir);

        // Delete arrived during mux
        if (isDeleted()) {
            if (file.exists()) file.delete();
            return;
        }

        if (muxResult < 0) {
            Log.e(TAG, "FFmpeg mux failed: " + muxResult);
            if (muxResult == FFmpegErrors.ENOENT) {
                callback.onError(MessageHelper.EXTERNAL_STORAGE);
            } else {
                callback.onError(MessageHelper.IOEXCEPTION);
            }
            return;
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
            return;
        }

        // Normal completion
        reportProgress(100, file.length(), file.length());
        callback.onImgResolved(file.getAbsolutePath());
        callback.onFileSizeKnown(file.length());
        callback.onStatusChanged(Download.FINISHED);
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

    private void reportProgress(int percent, long downloaded, long total) {
        if (callback == null) return;
        long now = System.currentTimeMillis();
        if (now - lastUpdated > UPDATE_RATE || percent >= 100 || percent == 0) {
            lastUpdated = now;
            callback.onProgress(Math.min(percent, 100), downloaded, total);
        }
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