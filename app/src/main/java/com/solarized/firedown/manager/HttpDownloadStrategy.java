package com.solarized.firedown.manager;

import android.text.TextUtils;
import android.util.Base64;
import android.util.Log;

import com.solarized.firedown.BuildConfig;
import com.solarized.firedown.StoragePaths;
import com.solarized.firedown.data.Download;
import com.solarized.firedown.data.di.NetworkModule;
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
    // Set when the response turns out to be an HLS/DASH manifest and we hand off
    // to ffmpeg mid-execute; stop() must forward cancellation to it.
    private volatile DownloadStrategy mDelegate;

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

            httpResponse = makeRequest(context, downloadUrl, isResume, false);

            int status = httpResponse.code();
            Log.d(TAG, "execute: response status=" + status
                    + " contentLength=" + httpResponse.body().contentLength()
                    + " contentRange=" + httpResponse.header("Content-Range")
                    + " acceptRanges=" + httpResponse.header("Accept-Ranges")
                    + " transferEncoding=" + httpResponse.header("Transfer-Encoding")
                    + " contentEncoding=" + httpResponse.header("Content-Encoding"));

            // A 416 with bytes already on disk means we asked to resume past the
            // end of the resource — the local file is already complete.
            if (status == 416 && downloadedLength > 0) {
                Log.d(TAG, "execute: 416 on resume — file already complete at " + downloadedLength);
                callback.onFileSizeKnown(file.length());
                callback.onStatusChanged(Download.FINISHED);
                return;
            }

            // Some streaming endpoints reject a plain GET and ONLY serve a ranged
            // request — e.g. krakencloud's /play/video/<token> (series.ly): the
            // browser plays it with "Range: bytes=0-" and gets 206, while a
            // no-Range GET is refused (403/404/416). makeRequest sends no Range on
            // a fresh request by design (some servers reject one), so retry ONCE
            // with a zero-offset Range before giving up. Reactive — it fires only
            // after the plain GET was rejected, so a range-HOSTILE server (which
            // answered the plain GET) is never sent a Range.
            if (!isResume && downloadedLength == 0
                    && (status == 403 || status == 404 || status == 416)) {
                Log.w(TAG, "execute: plain GET rejected status=" + status
                        + " — retrying with Range: bytes=0-");
                closeQuietly(null, null, null, httpResponse);
                httpResponse = makeRequest(context, downloadUrl, false, true);
                status = httpResponse.code();
                Log.d(TAG, "execute: range-retry status=" + status
                        + " contentRange=" + httpResponse.header("Content-Range"));
            }

            if (status >= HttpURLConnection.HTTP_BAD_REQUEST
                    && status <= HttpURLConnection.HTTP_VERSION) {
                Log.w(TAG, "execute: HTTP error status=" + status + ", aborting");
                callback.onError(status);
                return;
            }

            body = httpResponse.body();
            long totalLength = body.contentLength() + downloadedLength;

            // Backstop: if the body is actually an HLS/DASH manifest, this URL is
            // not a progressive file — saving the playlist text as the "video"
            // would produce a tiny broken file. Hand off to ffmpeg instead. This
            // inspects ground truth (the bytes the server returned), so it catches
            // anything that reached this raw path misclassified — most importantly
            // the generic catcher's obfuscated/tokenized manifests that carry no
            // .m3u8/.mpd extension to classify by. Only on a fresh request: a
            // resume body is a byte range, not the document head.
            if (!isResume && looksLikeManifest(httpResponse)) {
                Log.w(TAG, "execute: body is an HLS/DASH manifest — handing off to ffmpeg: " + downloadUrl);
                closeQuietly(null, null, body, httpResponse);
                body = null;
                httpResponse = null;
                mDelegate = new FFmpegMuxStrategy();
                mDelegate.execute(request, context, callback);
                return;
            }

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

            // Resume-on-partial download loop.
            //
            // The first request carried no Range (makeRequest). If the body ends
            // short of Content-Length — whether by a thrown "unexpected end of
            // stream" or a clean early EOF — we re-request the remaining bytes
            // with "Range: bytes=<downloadedLength>-" and keep appending. This is
            // fully site-agnostic: it reacts to "received < advertised length",
            // not to any host, and recovers from CDN anti-leech truncation
            // (Bilibili et al.), chunked short reads, and mid-stream disconnects.
            // It also closes the old bug where a clean short read reported the
            // partial file as FINISHED.
            Log.d(TAG, "execute: starting copy file=" + file.getAbsolutePath()
                    + " startAt=" + downloadedLength
                    + " totalLength=" + totalLength);

            output = new BufferedOutputStream(new FileOutputStream(file, downloadedLength > 0));

            final boolean knownLength = totalLength > 0;
            int resumeAttempts = 0;
            final int MAX_RESUME_ATTEMPTS = 64;

            while (true) {
                final long passStart = downloadedLength;
                try {
                    input = new BufferedInputStream(body.byteStream());
                    byte[] data = new byte[BYTE_SIZE];
                    int count;
                    while ((count = input.read(data)) != -1) {
                        if (stopped || context.isInterrupted()) {
                            Log.w(TAG, "execute: loop aborted stopped=" + stopped
                                    + " interrupted=" + context.isInterrupted()
                                    + " downloadedLength=" + downloadedLength
                                    + " totalLength=" + totalLength);
                            return;
                        }
                        downloadedLength += count;
                        output.write(data, 0, count);
                        reportProgress(callback, downloadedLength, totalLength);
                    }
                } catch (IOException e) {
                    // Premature disconnect mid-body. Resume only if we can —
                    // known length, made progress, not user-stopped; otherwise
                    // it's a genuine failure, so rethrow to the outer handler.
                    if (!knownLength || stopped || context.isInterrupted()) throw e;
                    Log.w(TAG, "execute: disconnect at " + downloadedLength + "/" + totalLength
                            + " — attempting resume", e);
                } finally {
                    closeQuietly(input, null, null, null);
                    input = null;
                }

                // Done: got everything, or a live/unknown-length stream whose EOF
                // is the natural end, or the user stopped us.
                if (!knownLength || downloadedLength >= totalLength) break;
                if (stopped || context.isInterrupted()) return;

                // Still short. Guard against stalls / pathological loops.
                if (downloadedLength <= passStart) {
                    throw new IOException("download stalled at " + downloadedLength + "/" + totalLength);
                }
                if (++resumeAttempts > MAX_RESUME_ATTEMPTS) {
                    throw new IOException("exceeded resume attempts at "
                            + downloadedLength + "/" + totalLength);
                }

                // Re-request the remainder with a Range header.
                closeQuietly(null, null, body, httpResponse);
                body = null;
                httpResponse = null;
                Log.d(TAG, "execute: resuming Range bytes=" + downloadedLength + "- (attempt "
                        + resumeAttempts + ")");
                httpResponse = makeRequest(context, downloadUrl, true, false);
                int rst = httpResponse.code();
                if (rst == 416) {
                    // Offset is past the end — we already have the whole file.
                    Log.d(TAG, "execute: resume got 416 — complete at " + downloadedLength);
                    break;
                }
                if (rst >= HttpURLConnection.HTTP_BAD_REQUEST
                        && rst <= HttpURLConnection.HTTP_VERSION) {
                    Log.w(TAG, "execute: resume HTTP error status=" + rst + ", aborting");
                    callback.onError(rst);
                    return;
                }
                body = httpResponse.body();
                if (rst == HttpURLConnection.HTTP_OK) {
                    // Server ignored the Range and is resending from byte 0.
                    // Truncate and restart so we don't append onto our bytes.
                    Log.w(TAG, "execute: resume got 200 (Range ignored) — restarting from 0");
                    output.flush();
                    output.close();
                    output = new BufferedOutputStream(new FileOutputStream(file, false));
                    downloadedLength = 0;
                    long t = body.contentLength();
                    if (t > 0) totalLength = t;
                }
                // 206 (or 200-restart): loop and append the next span.
            }

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
            // Include the URL alongside the failure: "unexpected end of stream
            // at downloadedLength=1048576" with a *.bilivideo.com URL is the
            // Bilibili CDN truncating because Referer is missing/wrong (see
            // the makeRequest header log above).
            Log.e(TAG, "execute: IOException at downloadedLength=" + downloadedLength
                    + " url=" + (request != null ? request.getUrl() : null), e);
            throw e;
        } finally {
            closeQuietly(input, output, body, httpResponse);
            // User cancel (stop()/thread interrupt) mid-body: closeQuietly only
            // reset the HTTP/2 *stream*; the pooled *connection* survives, and a
            // server still pushing the rest of a large file traps OkHttp in a
            // DATA→RST_STREAM discard loop until the pool's 5-minute idle
            // eviction (see NetworkModule.evictIdleConnections()). The
            // connection went idle on the closes above, so evict it now.
            // Normal completion (neither flag set) never touches the pool.
            if (stopped || context.isInterrupted()) {
                NetworkModule.evictIdleConnections();
            }
        }
    }

    @Override
    public void stop() {
        Log.d(TAG, "stop: invoked at downloadedLength=" + downloadedLength
                + " by " + Thread.currentThread().getName(), new Throwable("stop trace"));
        stopped = true;
        // If we handed the download off to ffmpeg (manifest backstop), forward
        // the cancellation — DownloadRunnable only holds this strategy.
        DownloadStrategy delegate = mDelegate;
        if (delegate != null) {
            delegate.stop();
        }
    }

    /**
     * Whether the response body is actually an HLS/DASH manifest rather than a
     * progressive media file. Checks the Content-Type, then peeks the first bytes
     * (without consuming the body) for the unambiguous document signatures:
     * {@code #EXTM3U} (HLS master or media playlist) or an {@code <MPD>} XML root
     * (DASH). Ground-truth detection — independent of the URL's extension.
     */
    private static boolean looksLikeManifest(Response response) {
        String contentType = response.header("Content-Type", "");
        if (contentType != null) {
            String ct = contentType.toLowerCase(Locale.ROOT);
            if (ct.contains("mpegurl") || ct.contains("dash+xml")) {
                return true;
            }
        }
        String head;
        try {
            head = response.peekBody(2048).string();
        } catch (Exception e) {
            return false;
        }
        if (head == null) {
            return false;
        }
        String h = head.trim();
        if (h.startsWith("#EXTM3U")) {
            return true;
        }
        // DASH manifest: an XML document whose root element is <MPD …>.
        return h.startsWith("<") && h.contains("<MPD");
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
     * <p>This method does NOT interpret status codes — it issues the request
     * and returns the response. All recovery lives in {@link #execute}:
     * <ul>
     *   <li>a fresh no-Range GET rejected 403/404/416 is retried ONCE with
     *       {@code Range: bytes=0-} ({@code forceRange}) — the range-REQUIRED
     *       endpoint;</li>
     *   <li>416 on a RESUME means our offset is past the end, i.e. the local
     *       file is already complete;</li>
     *   <li>200 on a resume means the server ignored the Range and is
     *       resending from byte 0, so the file is truncated and restarted.</li>
     * </ul>
     *
     * <p>(This previously claimed the method retried a 416 "without the Range
     * header" and reset {@link #downloadedLength}. It never did either — the
     * retry above adds a Range rather than dropping one, and nothing here
     * touches downloadedLength.)
     */
    private Response makeRequest(DownloadContext context, String url, boolean isResume, boolean forceRange) throws IOException {
        OkHttpClient client = context.getOkHttpClient();

        Map<String, String> perCallHeaders = new HashMap<>(context.getHeaders());
        // Strip any inherited Range header. context.getHeaders() carries the
        // headers from the original request that GeckoView intercepted, which
        // for a video element is typically a partial-range request from the
        // player (e.g. bytes=4068494-4358829). Without this we'd download the
        // tiny slice the player was streaming and report success at EOF —
        // exactly the "first bytes only" truncation we were chasing.
        perCallHeaders.remove(BrowserHeaders.RANGES);
        // Send a Range only when resuming — i.e. we already have bytes on disk,
        // or a previous pass in execute() came up short of Content-Length. A
        // FRESH download goes out with NO Range header: some servers require a
        // range, others reject or mis-handle one, so the safe, universal default
        // is a plain request, and execute() reacts to a short/aborted body by
        // re-requesting the remainder with a Range. (The inherited player Range
        // was already stripped above, so we never grab just the <video> slice.)
        if (downloadedLength > 0) {
            perCallHeaders.put(BrowserHeaders.RANGES, "bytes=" + downloadedLength + "-");
        } else if (forceRange) {
            // A fresh request the caller wants ranged: a server that rejected the
            // plain GET only serves ranged requests (see execute()'s range-retry).
            perCallHeaders.put(BrowserHeaders.RANGES, "bytes=0-");
        }

        Request request = new Request.Builder()
                .url(url)
                .headers(SafeHeaders.of(perCallHeaders))
                .build();
        if (BuildConfig.DEBUG) {
            // Log every request-header name + value (cookies/authorization
            // redacted to a length count). Bilibili / fbcdn / many CDNs cut
            // the stream short when Referer is missing — a missing or empty
            // Referer/Origin here is the smoking gun for the "1 MiB then EOF"
            // truncation symptom.
            StringBuilder hdrs = new StringBuilder();
            for (Map.Entry<String, String> e : perCallHeaders.entrySet()) {
                String k = e.getKey(), v = e.getValue();
                String klc = k.toLowerCase(Locale.ROOT);
                hdrs.append(k).append('=');
                if (klc.equals("cookie") || klc.equals("authorization")) {
                    hdrs.append('<').append(v != null ? v.length() : 0).append(" chars>");
                } else {
                    hdrs.append(v);
                }
                hdrs.append("; ");
            }
            Log.d(TAG, "makeRequest: url=" + url
                    + " isResume=" + isResume
                    + " range=" + perCallHeaders.get(BrowserHeaders.RANGES)
                    + " referer=" + perCallHeaders.get("Referer")
                    + " origin=" + perCallHeaders.get("Origin")
                    + " ua=" + perCallHeaders.get("User-Agent")
                    + " allHeaders=[" + hdrs + "]");
        }
        Response response = client.newCall(request).execute();
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "makeRequest: response code=" + response.code()
                    + " contentLength=" + response.header("Content-Length")
                    + " contentRange=" + response.header("Content-Range")
                    + " contentType=" + response.header("Content-Type")
                    + " server=" + response.header("Server")
                    + " url=" + url);
        }

        // 416 is interpreted by the caller (execute): on a resume request it
        // means our offset is past the end — the file is already complete.
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