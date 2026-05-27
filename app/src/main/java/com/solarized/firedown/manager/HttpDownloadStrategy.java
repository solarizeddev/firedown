package com.solarized.firedown.manager;

import android.text.TextUtils;
import android.util.Base64;
import android.util.Log;

import com.solarized.firedown.StoragePaths;
import com.solarized.firedown.data.Download;
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
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import com.solarized.firedown.okhttp.SafeHeaders;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Direct HTTP byte-copy download with resume support.
 * Handles regular MP4, Twitter, Instagram, and other direct file URLs.
 */
public class HttpDownloadStrategy implements DownloadStrategy {

    private static final String TAG = HttpDownloadStrategy.class.getSimpleName();
    private static final int BYTE_SIZE = 8192;
    private static final long UPDATE_RATE = 1500;
    private volatile boolean stopped;
    private long lastUpdated;
    private long downloadedLength;

    @Override
    public void execute(DownloadRequest request, DownloadContext context, DownloadCallback callback) throws IOException {

        Response httpResponse = null;
        ResponseBody body = null;
        BufferedInputStream input = null;
        BufferedOutputStream output = null;

        try {
            String downloadUrl = request.getUrl();

            if (TextUtils.isEmpty(downloadUrl)) {
                callback.onError(MessageHelper.IOEXCEPTION);
                return;
            }

            // Handle data: URLs
            if (UrlParser.isDataUrl(downloadUrl)) {
                handleDataUrl(context, downloadUrl, callback);
                return;
            }

            File file = context.getOutputFile();
            boolean isResume = file.exists() && file.length() > 0;

            if (isResume) {
                downloadedLength = file.length();
            }

            Log.d(TAG, "execute: url=" + downloadUrl
                    + " file=" + file.getAbsolutePath()
                    + " exists=" + file.exists()
                    + " existingLen=" + (file.exists() ? file.length() : -1)
                    + " isResume=" + isResume
                    + " downloadedLength=" + downloadedLength);

            httpResponse = makeRequest(context, downloadUrl, isResume);

            int status = httpResponse.code();
            Log.d(TAG, "execute: response status=" + status
                    + " contentLength=" + httpResponse.body().contentLength()
                    + " contentRange=" + httpResponse.header("Content-Range")
                    + " acceptRanges=" + httpResponse.header("Accept-Ranges")
                    + " transferEncoding=" + httpResponse.header("Transfer-Encoding")
                    + " contentEncoding=" + httpResponse.header("Content-Encoding"));

            if (status >= HttpURLConnection.HTTP_BAD_REQUEST
                    && status <= HttpURLConnection.HTTP_VERSION) {
                Log.w(TAG, "execute: HTTP error status=" + status + ", aborting");
                callback.onError(status);
                return;
            }

            body = httpResponse.body();
            long totalLength = body.contentLength() + downloadedLength;

            // ICY live stream detection
            if (isIcyStream(httpResponse)) {
                totalLength = -1;
                callback.onLiveStream(true);
            }

            // Content-Disposition name
            String location = httpResponse.header(BrowserHeaders.LOCATION);
            if (!TextUtils.isEmpty(location)) {
                callback.onNameResolved(WebUtils.getFileNameFromURL(location));
            }

            String contentDisposition = httpResponse.header(BrowserHeaders.CONTENT_DISPOSITION);
            if (contentDisposition != null && !request.isFileNameForced()) {
                String dispositionName = WebUtils.getFileNameFromDisposition(contentDisposition);
                if (dispositionName != null) {
                    callback.onNameResolved(FileUriHelper.sanitizeFileName(dispositionName));
                }
            }

            // Mime type
            String mimeType = request.getMimeType();
            if (TextUtils.isEmpty(mimeType)) {
                mimeType = WebUtils.getMimeType(body);
            }
            callback.onMimeResolved(mimeType);

            // Description
            if (TextUtils.isEmpty(request.getDescription())) {
                callback.onDescriptionResolved(WebUtils.getTitle(request.getOrigin()));
            }

            // File extension
            if (!isResume) {
                String ext = FileUriHelper.isMimeTypeForced(mimeType)
                        ? FilenameUtils.getExtension(file.getName())
                        : FileUriHelper.getFileExtensionFromMimeType(mimeType);
                if (TextUtils.isEmpty(ext)) {
                    ext = FileUriHelper.getFileExtensionFromMimeType(mimeType);
                }
                file = new File(file.getParent(), FilenameUtils.getBaseName(file.getName()) + "." + ext);
                String resolvedPath = callback.onFilePathResolved(file.getAbsolutePath());
                file = new File(resolvedPath);
            }

            // Download.
            //
            // Append mode is derived from downloadedLength, NOT the original
            // isResume snapshot. makeRequest may internally fall back from a
            // 416 (e.g. resume against a file that's already complete on
            // disk, or whose stored partial length exceeds the server's
            // current size) by stripping the Range header and resetting
            // downloadedLength to 0. If we still opened the output in append
            // mode here, we'd write a fresh full body on top of the existing
            // partial bytes and double the file. Keying off downloadedLength
            // makes the 416 fallback truncate-and-rewrite, which is what we
            // actually want.
            boolean appendMode = downloadedLength > 0;

            Log.d(TAG, "execute: starting copy file=" + file.getAbsolutePath()
                    + " appendMode=" + appendMode
                    + " startAt=" + downloadedLength
                    + " totalLength=" + totalLength);

            input = new BufferedInputStream(body.byteStream());
            output = new BufferedOutputStream(new FileOutputStream(file, appendMode));

            byte[] data = new byte[BYTE_SIZE];
            int count;
            long readCalls = 0;
            while ((count = input.read(data)) != -1) {
                if (stopped || context.isInterrupted()) {
                    Log.w(TAG, "execute: loop aborted"
                            + " stopped=" + stopped
                            + " interrupted=" + context.isInterrupted()
                            + " downloadedLength=" + downloadedLength
                            + " totalLength=" + totalLength
                            + " readCalls=" + readCalls);
                    return;
                }
                downloadedLength += count;
                readCalls++;
                output.write(data, 0, count);
                reportProgress(callback, downloadedLength, totalLength);
            }

            Log.d(TAG, "execute: read loop ended (EOF)"
                    + " downloadedLength=" + downloadedLength
                    + " totalLength=" + totalLength
                    + " readCalls=" + readCalls
                    + " truncated=" + (totalLength > 0 && downloadedLength < totalLength));

            output.flush();
            output.close();
            output = null; // prevent double-close in finally

            long onDiskLen = file.length();
            Log.d(TAG, "execute: finished file=" + file.getAbsolutePath()
                    + " onDiskLen=" + onDiskLen
                    + " expected=" + totalLength
                    + " match=" + (totalLength <= 0 || onDiskLen == totalLength));

            String fileMime = request.getMimeType();
            if (FileUriHelper.isVideo(fileMime) || FileUriHelper.isImage(fileMime)) {
                callback.onImgResolved(file.getAbsolutePath());
            }

            callback.onFileSizeKnown(onDiskLen);
            callback.onStatusChanged(Download.FINISHED);

        } catch (IOException e) {
            Log.e(TAG, "execute: IOException at downloadedLength=" + downloadedLength, e);
            throw e;
        } finally {
            closeQuietly(input, output, body, httpResponse);
        }
    }

    @Override
    public void stop() {
        Log.d(TAG, "stop: invoked at downloadedLength=" + downloadedLength
                + " by " + Thread.currentThread().getName(), new Throwable("stop trace"));
        stopped = true;
    }

    /**
     * Issue the request.
     *
     * <p>Previously this method mutated {@code context.getHeaders()} by
     * adding/removing the {@code Range} header in-place. That was a source
     * of bugs when the context was reused (e.g. on retry) — a stale Range
     * header could persist into a non-resume call. We now build a per-call
     * header map so the context stays untouched.
     *
     * <p>On 416 Range Not Satisfiable, we retry without the Range header
     * and reset {@link #downloadedLength} so the caller restarts from byte 0.
     */
    private Response makeRequest(DownloadContext context, String url, boolean isResume) throws IOException {
        OkHttpClient client = context.getOkHttpClient();

        Map<String, String> perCallHeaders = new HashMap<>(context.getHeaders());
        // Strip any inherited Range header. context.getHeaders() carries the
        // headers from the original request that GeckoView intercepted, which
        // for a video element is typically a partial-range request from the
        // player (e.g. bytes=4068494-4358829). Without this we'd download the
        // tiny slice the player was streaming and report success at EOF —
        // exactly the "first bytes only" truncation we were chasing.
        perCallHeaders.remove(BrowserHeaders.RANGES);
        if (isResume) {
            perCallHeaders.put(BrowserHeaders.RANGES, "bytes=" + downloadedLength + "-");
        }

        Request request = new Request.Builder()
                .url(url)
                .headers(SafeHeaders.of(perCallHeaders))
                .build();
        Log.d(TAG, "makeRequest: url=" + url
                + " isResume=" + isResume
                + " range=" + perCallHeaders.get(BrowserHeaders.RANGES));
        Response response = client.newCall(request).execute();

        if (response.code() == 416) {
            Log.w(TAG, "makeRequest: 416 Range Not Satisfiable, retrying without Range"
                    + " (was bytes=" + downloadedLength + "-)");
            response.close();
            downloadedLength = 0;
            perCallHeaders.remove(BrowserHeaders.RANGES);
            Request retry = new Request.Builder()
                    .url(url)
                    .headers(SafeHeaders.of(perCallHeaders))
                    .build();
            return client.newCall(retry).execute();
        }

        return response;
    }

    private void handleDataUrl(DownloadContext downloadContext, String dataUrl, DownloadCallback callback) throws IOException {
        String extension = FileUriHelper.getFileExtensionFromData(dataUrl);
        File file = new File(StoragePaths.getDownloadPath(downloadContext.getContext()), UUID.randomUUID() + "." + extension);
        byte[] data = Base64.decode(dataUrl.split(",")[1], 0);

        try (FileOutputStream os = new FileOutputStream(file, false)) {
            os.write(data);
            os.flush();
        }

        callback.onFileSizeKnown(data.length);
        callback.onNameResolved(file.getName());
        String resolvedPath = callback.onFilePathResolved(file.getAbsolutePath());
        callback.onMimeResolved(FileUriHelper.getMimeTypeFromFile(resolvedPath));
        callback.onStatusChanged(Download.FINISHED);
    }

    private void reportProgress(DownloadCallback callback, long downloaded, long total) {
        long now = System.currentTimeMillis();
        if (now - lastUpdated > UPDATE_RATE) {
            lastUpdated = now;
            int percent = total > 0 ? (int) ((downloaded * 100) / total) : 0;
            callback.onProgress(percent, downloaded, total);
        }
    }

    private static boolean isIcyStream(Response response) {
        for (int i = 0; i < response.headers().size(); i++) {
            if (response.headers().name(i).toLowerCase(Locale.ROOT).startsWith("icy-")) return true;
        }
        return false;
    }

    private static void closeQuietly(InputStream in, OutputStream out, ResponseBody body, Response response) {
        try { if (in != null) in.close(); } catch (IOException ignored) {}
        // Separate try blocks: if flush() throws (disk full mid-write — exactly
        // when this finally matters), the previous single-try variant skipped
        // close() and leaked the underlying FileOutputStream's file descriptor
        // until GC finalised it.
        try { if (out != null) out.flush(); } catch (IOException ignored) {}
        try { if (out != null) out.close(); } catch (IOException ignored) {}
        if (body != null) body.close();
        if (response != null) response.close();
    }
}