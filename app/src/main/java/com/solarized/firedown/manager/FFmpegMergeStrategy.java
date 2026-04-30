package com.solarized.firedown.manager;

import android.text.TextUtils;
import android.util.Log;

import com.solarized.firedown.data.Download;
import com.solarized.firedown.ffmpegutils.FFmpegDownloader;
import com.solarized.firedown.ffmpegutils.FFmpegErrors;
import com.solarized.firedown.ffmpegutils.FFmpegListener;
import com.solarized.firedown.utils.FileUriHelper;
import com.solarized.firedown.utils.MessageHelper;

import org.apache.commons.io.FilenameUtils;

import java.io.File;
import java.io.IOException;
import java.util.Map;

/**
 * Multi-input FFmpeg merge: separate video + audio URLs combined into one MP4.
 * Used for YouTube adaptive formats. No OkHttp probe — FFmpeg opens URLs directly.
 * URLs are passed as-is (no deParameterize) to preserve auth query params.
 */
public class FFmpegMergeStrategy implements DownloadStrategy, FFmpegListener {

    private static final String TAG = FFmpegMergeStrategy.class.getSimpleName();
    private static final long UPDATE_RATE = 1500;

    private FFmpegDownloader downloader;
    private DownloadCallback callback;
    private long lastUpdated;

    @Override
    public void execute(DownloadRequest request, DownloadContext context, DownloadCallback callback) throws IOException {
        this.callback = callback;

        String videoUrl = request.getUrl();
        String audioUrl = request.getAudioUrl();

        if (TextUtils.isEmpty(videoUrl) || TextUtils.isEmpty(audioUrl)) {
            callback.onError(MessageHelper.IOEXCEPTION);
            return;
        }

        // Resolve mime and file name
        String mimeType = request.getMimeType();
        if (TextUtils.isEmpty(mimeType)) {
            mimeType = FileUriHelper.MIMETYPE_MP4;
        }
        String ffmpegMime = resolveFFmpegMime(mimeType);
        callback.onMimeResolved(ffmpegMime);

        File file = context.getOutputFile();
        String ext = FileUriHelper.getFileExtensionFromMimeType(ffmpegMime);
        if (TextUtils.isEmpty(ext)) ext = "mp4";
        file = new File(file.getParent(), FilenameUtils.getBaseName(file.getName()) + "." + ext);
        String resolvedPath = callback.onFilePathResolved(file.getAbsolutePath());
        file = new File(resolvedPath);

        Log.d(TAG, "Multi-input merge: video=" + videoUrl + " audio=" + audioUrl);

        downloader = new FFmpegDownloader();
        downloader.addListener(this);

        Map<String, String> headersMap = context.getHeaders();

        int error = downloader.start(
                new String[]{videoUrl, audioUrl},
                headersMap,
                null, // no output metadata
                file.getAbsolutePath(),
                -1);  // unknown total length — FFmpeg reports progress via duration

        // Free after start() returns (C threads have exited at this point)
        downloader.free();

        if (error < 0) {
            Log.e(TAG, "FFmpegDownloader error: " + error);
            if (error == FFmpegErrors.ENOENT) {
                callback.onError(MessageHelper.EXTERNAL_STORAGE);
            } else {
                callback.onError(MessageHelper.IOEXCEPTION);
            }
            return;
        }

        if (context.isInterrupted()) {
            // User stopped the stream — file may still be valid (trailer written).
            // Report img path and size so Glide can generate a thumbnail.
            if (file.exists() && file.length() > 0) {
                callback.onImgResolved(file.getAbsolutePath());
                callback.onFileSizeKnown(file.length());
            }
            return;
        }

        callback.onImgResolved(file.getAbsolutePath());
        callback.onFileSizeKnown(file.length());
        callback.onStatusChanged(Download.FINISHED);
    }

    @Override
    public void stop() {
        if (downloader != null) {
            downloader.stop();
        }
    }

    private String resolveFFmpegMime(String mimeType) {
        if (FileUriHelper.isFFmpeg(mimeType)) return FileUriHelper.MIMETYPE_MP4;
        if (FileUriHelper.isAudio(mimeType)) return mimeType;
        if (FileUriHelper.isImage(mimeType)) return mimeType;
        if (FileUriHelper.isUnkown(mimeType)) return FileUriHelper.MIMETYPE_MP4;
        return mimeType;
    }

    // FFmpegListener callbacks
    @Override
    public void onProgress(long downloadedLength, long totalLength) {
        if (callback == null) return;
        long now = System.currentTimeMillis();
        if (now - lastUpdated > UPDATE_RATE) {
            lastUpdated = now;
            // AV_NOPTS_VALUE (Long.MIN_VALUE) signals unknown duration (live stream)
            if (downloadedLength == Long.MIN_VALUE || totalLength == Long.MIN_VALUE) {
                callback.onProgress(0, 0, -1);
            } else if (totalLength > 0) {
                int percent = (int) ((downloadedLength * 100) / totalLength);
                callback.onProgress(percent, downloadedLength, totalLength);
            } else {
                callback.onProgress(0, downloadedLength, totalLength);
            }
        }
    }

    @Override
    public void onStarted() {}

    @Override
    public void onFinished() {}
}