package com.solarized.firedown.manager;

import android.text.TextUtils;
import android.util.Log;

import com.solarized.firedown.data.Download;
import com.solarized.firedown.ffmpegutils.FFmpegDownloader;
import com.solarized.firedown.ffmpegutils.FFmpegErrors;
import com.solarized.firedown.ffmpegutils.FFmpegListener;
import com.solarized.firedown.ffmpegutils.FFmpegUtils;
import com.solarized.firedown.utils.BrowserHeaders;
import com.solarized.firedown.utils.FileUriHelper;
import com.solarized.firedown.utils.MessageHelper;
import com.solarized.firedown.utils.WebUtils;

import org.apache.commons.io.FilenameUtils;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.util.Locale;
import java.util.Map;

import okhttp3.Headers;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Single-input FFmpeg download for HLS, DASH, and TS manifests.
 * OkHttp probes the URL first for mime/content info, then FFmpeg handles segment downloading.
 * Falls back to raw byte copy if FFmpeg reports UNSUPPORTED.
 */
public class FFmpegMuxStrategy implements DownloadStrategy, FFmpegListener {

    private static final String TAG = FFmpegMuxStrategy.class.getSimpleName();
    private static final int BYTE_SIZE = 8192;
    private static final long UPDATE_RATE = 1500;

    private FFmpegDownloader downloader;
    private DownloadCallback callback;
    private long lastUpdated;
    private volatile boolean stopped;

    @Override
    public void execute(DownloadRequest request, DownloadContext context, DownloadCallback callback) throws IOException {
        this.callback = callback;

        Response httpResponse = null;
        ResponseBody body = null;
        BufferedInputStream input = null;
        BufferedOutputStream output = null;

        try {
            String contentAddress = WebUtils.deParameterize(request.getUrl());

            if (TextUtils.isEmpty(contentAddress)) {
                callback.onError(MessageHelper.IOEXCEPTION);
                return;
            }

            OkHttpClient client = context.getOkHttpClient();

            // Probe
            Request httpRequest = new Request.Builder()
                    .url(contentAddress)
                    .headers(Headers.of(context.getHeaders()))
                    .build();

            httpResponse = client.newCall(httpRequest).execute();

            int status = httpResponse.code();
            if (status >= HttpURLConnection.HTTP_BAD_REQUEST
                    && status <= HttpURLConnection.HTTP_VERSION) {
                Log.d(TAG, "BadRequest: " + status);
                callback.onError(status);
                return;
            }

            body = httpResponse.body();
            long totalLength = body.contentLength();

            // ICY detection
            if (isIcyStream(httpResponse)) {
                totalLength = -1;
                callback.onLiveStream(true);
            }

            // Content-Disposition
            String location = httpResponse.header(BrowserHeaders.LOCATION);
            if (!TextUtils.isEmpty(location)) {
                callback.onNameResolved(WebUtils.getFileNameFromURL(location));
            }

            if (!request.isFileNameForced()) {
                String contentDisposition = httpResponse.header(BrowserHeaders.CONTENT_DISPOSITION);
                if (contentDisposition != null) {
                    String dispositionName = WebUtils.getFileNameFromDisposition(contentDisposition);
                    if (dispositionName != null) {
                        callback.onNameResolved(FileUriHelper.sanitizeFileName(dispositionName));
                    }
                }
            }

            // Mime
            String mimeType = request.getMimeType();
            if (TextUtils.isEmpty(mimeType)) mimeType = WebUtils.getMimeType(body);
            callback.onMimeResolved(mimeType);

            if (TextUtils.isEmpty(request.getDescription())) {
                callback.onDescriptionResolved(WebUtils.getTitle(request.getOrigin()));
            }

            // Resolve FFmpeg mime and file extension
            String ffmpegMime = resolveFFmpegMime(mimeType);
            callback.onMimeResolved(ffmpegMime);

            File file = context.getOutputFile();
            String ext = FileUriHelper.getFileExtensionFromMimeType(ffmpegMime);
            if (TextUtils.isEmpty(ext)) ext = "mp4";
            file = new File(file.getParent(), FilenameUtils.getBaseName(file.getName()) + "." + ext);
            String resolvedPath = callback.onFilePathResolved(file.getAbsolutePath());
            file = new File(resolvedPath);

            // FFmpeg download
            downloader = new FFmpegDownloader();
            downloader.addListener(this);

            Map<String, String> dict = FFmpegUtils.buildFFmpegOptions(context.getHeaders());

            StreamSelection selection = request.toStreamSelection();
            int videoStream = selection.getVideoNumber() >= 0 ? selection.getVideoNumber() : -1;
            int audioStream = selection.getAudioNumber() >= 0 ? selection.getAudioNumber() : -1;

            int error = downloader.start(dict, null, contentAddress,
                    file.getAbsolutePath(), videoStream, audioStream, totalLength);

            // Free after start() returns (C threads have exited)
            downloader.free();

            if (error < 0) {
                Log.e(TAG, "FFmpegDownloader error: " + error);

                if (error == FFmpegErrors.UNSUPPORTED || error == FFmpegErrors.MUX_HEADER_ERROR) {
                    // Close the probe response — its body stream is consumed
                    body.close();
                    body = null;
                    httpResponse.close();
                    httpResponse = null;

                    // Fresh request for raw byte copy fallback
                    Request fallbackRequest = new Request.Builder()
                            .url(contentAddress)
                            .headers(Headers.of(context.getHeaders()))
                            .build();
                    httpResponse = client.newCall(fallbackRequest).execute();
                    body = httpResponse.body();
                    totalLength = body.contentLength();

                    input = new BufferedInputStream(body.byteStream());
                    output = new BufferedOutputStream(new FileOutputStream(file, false));
                    copyStream(input, output, totalLength);
                } else if (error == FFmpegErrors.ENOENT) {
                    callback.onError(MessageHelper.EXTERNAL_STORAGE);
                    return;
                } else {
                    callback.onError(MessageHelper.IOEXCEPTION);
                    return;
                }
            }

            if (context.isInterrupted()) {
                // User stopped the stream — file may still be valid (trailer written).
                if (file.exists() && file.length() > 0) {
                    callback.onImgResolved(file.getAbsolutePath());
                    callback.onFileSizeKnown(file.length());
                }
                return;
            }

            // Close output before status change so file is fully flushed for Glide
            if (output != null) {
                output.flush();
                output.close();
                output = null;
            }

            callback.onImgResolved(file.getAbsolutePath());
            callback.onFileSizeKnown(file.length());
            callback.onStatusChanged(Download.FINISHED);

        } finally {
            if (body != null) body.close();
            if (httpResponse != null) httpResponse.close();
            try { if (input != null) input.close(); } catch (IOException ignored) {}
            try { if (output != null) { output.flush(); output.close(); } } catch (IOException ignored) {}
        }
    }

    @Override
    public void stop() {
        stopped = true;
        if (downloader != null) {
            downloader.stop();
        }
    }

    private void copyStream(BufferedInputStream in, BufferedOutputStream out, long totalLength) throws IOException {
        byte[] data = new byte[BYTE_SIZE];
        long downloaded = 0;
        int count;
        while ((count = in.read(data)) != -1) {
            if (stopped) return;
            downloaded += count;
            out.write(data, 0, count);
            reportProgress(downloaded, totalLength);
        }
        out.flush();
    }

    private void reportProgress(long downloaded, long total) {
        if (callback == null) return;
        long now = System.currentTimeMillis();
        if (now - lastUpdated > UPDATE_RATE) {
            lastUpdated = now;
            // AV_NOPTS_VALUE (Long.MIN_VALUE) signals unknown duration (live stream)
            if (downloaded == Long.MIN_VALUE || total == Long.MIN_VALUE) {
                callback.onProgress(0, 0, -1);
            } else if (total > 0) {
                int percent = (int) ((downloaded * 100) / total);
                callback.onProgress(percent, downloaded, total);
            } else {
                callback.onProgress(0, downloaded, total);
            }
        }
    }

    private String resolveFFmpegMime(String mimeType) {
        if (FileUriHelper.isFFmpeg(mimeType)) return FileUriHelper.MIMETYPE_MP4;
        if (FileUriHelper.isAudio(mimeType)) return mimeType;
        if (FileUriHelper.isImage(mimeType)) return mimeType;
        if (FileUriHelper.isUnkown(mimeType)) return FileUriHelper.MIMETYPE_MP4;
        return mimeType;
    }

    private static boolean isIcyStream(Response response) {
        for (int i = 0; i < response.headers().size(); i++) {
            // Locale.ROOT — Turkish locale otherwise lowercases "I" to "ı"
            // and this startsWith comparison would silently miss "icy-*" headers.
            if (response.headers().name(i).toLowerCase(Locale.ROOT).startsWith("icy-")) {
                return true;
            }
        }
        return false;
    }

    // FFmpegListener
    @Override
    public void onProgress(long downloadedLength, long totalLength) {
        reportProgress(downloadedLength, totalLength);
    }

    @Override
    public void onStarted() {}

    @Override
    public void onFinished() {}
}