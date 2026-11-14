package com.solarized.firedown.manager;

import android.text.TextUtils;
import android.util.Log;

import com.solarized.firedown.BuildConfig;
import com.solarized.firedown.data.Download;
import com.solarized.firedown.data.di.NetworkModule;
import com.solarized.firedown.utils.BrowserHeaders;
import com.solarized.firedown.utils.DataUris;
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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import com.solarized.firedown.okhttp.SafeHeaders;
import okhttp3.Headers;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Direct HTTP byte-copy download with resume support.
 * Handles regular MP4 and other direct file URLs.
 */
public class HttpDownloadStrategy implements DownloadStrategy {

    private static final String TAG = HttpDownloadStrategy.class.getSimpleName();
    // A literal tag: R8 renames this class in the diagnostic build, and the
    // exchange trace below is what a refused download is debugged from.
    private static final String TRACE_TAG = "DownloadTrace";
    private static final boolean TRACE = BuildConfig.DEBUG || BuildConfig.DIAGNOSTIC;
    private static final long TRACE_BODY_BYTES = 512;
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
            // request: the browser plays it with "Range: bytes=0-" and gets
            // 206, while a no-Range GET is refused (403/404/416). makeRequest
            // sends no Range on
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
                if (TRACE) {
                    traceRefusal(httpResponse);
                }
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
            // not to any host, and recovers from CDN anti-leech truncation,
            // chunked short reads, and mid-stream disconnects. A clean short
            // read is never reported as FINISHED.
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
            // at downloadedLength=1048576" is often a CDN truncating because
            // Referer is missing/wrong (see the makeRequest header log above).
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
        Response response = client.newCall(request).execute();
        if (TRACE) {
            traceExchange(response, isResume, forceRange);
        }

        // 416 is interpreted by the caller (execute): on a resume request it
        // means our offset is past the end — the file is already complete.
        return response;
    }

    /**
     * Debug and diagnostic builds: one line per hop of the exchange, with the
     * headers as they LEFT the phone (the network request, after every
     * interceptor, so Host, a filled-in User-Agent and Accept-Encoding are in
     * it) and the answer. A redirect is its own hop. Cookie and authorization
     * values are never printed, only cookie names and a short hint of
     * tt_chain_token, enough to tell two tokens apart.
     */
    private static void traceExchange(Response response, boolean isResume, boolean forceRange) {
        ArrayList<Response> hops = new ArrayList<>();
        for (Response r = response; r != null; r = r.priorResponse()) {
            hops.add(0, r);
        }
        for (int i = 0; i < hops.size(); i++) {
            Response hop = hops.get(i);
            Response network = hop.networkResponse() != null ? hop.networkResponse() : hop;
            Log.d(TRACE_TAG, "hop " + (i + 1) + "/" + hops.size()
                    + " " + network.request().method() + " " + shortUrl(network.request().url().toString())
                    + " resume=" + isResume + " forceRange=" + forceRange
                    + " -> " + hop.code() + " " + hop.protocol()
                    + " sent=[" + describeHeaders(network.request().headers()) + "]"
                    + " got=[contentType=" + hop.header("Content-Type")
                    + " contentLength=" + hop.header("Content-Length")
                    + " contentRange=" + hop.header("Content-Range")
                    + " location=" + shortUrl(hop.header("Location"))
                    + " server=" + hop.header("Server") + "]");
        }
    }

    /** Every response header of a refused download, and the start of its body. */
    private static void traceRefusal(Response response) {
        StringBuilder headers = new StringBuilder();
        for (int i = 0; i < response.headers().size(); i++) {
            String name = response.headers().name(i);
            String value = name.equalsIgnoreCase("set-cookie")
                    ? "<" + response.headers().value(i).length() + " chars>"
                    : response.headers().value(i);
            headers.append(name).append('=').append(value).append("; ");
        }
        String bodyHead;
        try {
            bodyHead = response.peekBody(TRACE_BODY_BYTES).string().replace('\n', ' ');
        } catch (IOException | RuntimeException e) {
            bodyHead = "<unreadable: " + e.getMessage() + ">";
        }
        Log.w(TRACE_TAG, "refused " + response.code() + " " + shortUrl(response.request().url().toString())
                + " headers=[" + headers + "] body=" + bodyHead);
    }

    private static String describeHeaders(Headers headers) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < headers.size(); i++) {
            String name = headers.name(i);
            String value = headers.value(i);
            out.append(name).append('=');
            if (name.equalsIgnoreCase("cookie")) {
                out.append(cookieSummary(value));
            } else if (name.equalsIgnoreCase("authorization")) {
                out.append('<').append(value.length()).append(" chars>");
            } else {
                out.append(value);
            }
            out.append("; ");
        }
        return out.toString();
    }

    /** Cookie names, and tt_chain_token's length and first characters. */
    private static String cookieSummary(String cookie) {
        StringBuilder names = new StringBuilder("{");
        String chain = null;
        for (String part : cookie.split(";")) {
            int eq = part.indexOf('=');
            String name = (eq < 0 ? part : part.substring(0, eq)).trim();
            if (name.isEmpty()) {
                continue;
            }
            names.append(name).append(' ');
            if (eq >= 0 && name.equals("tt_chain_token")) {
                String value = part.substring(eq + 1).trim();
                chain = value.length() + " chars, " + value.substring(0, Math.min(6, value.length())) + "...";
            }
        }
        names.append('}');
        if (chain != null) {
            names.append(" tt_chain_token=").append(chain);
        }
        return names.toString();
    }

    /** The host, path and parameter names: the signature stays out of the log. */
    private static String shortUrl(String url) {
        if (url == null) {
            return null;
        }
        HttpUrl parsed = HttpUrl.parse(url);
        if (parsed == null) {
            return url.length() > 120 ? url.substring(0, 120) + "..." : url;
        }
        return parsed.host() + parsed.encodedPath() + " params=" + parsed.queryParameterNames()
                + (parsed.queryParameter("tk") != null ? " tk=" + parsed.queryParameter("tk") : "");
    }

    /**
     * A data: URL is its own bytes: decoded and written, no request made. The
     * row keeps only the URL's header (DownloadTask), so a retry arrives with
     * no payload and is told the file is gone rather than written empty.
     */
    private void handleDataUrl(DownloadContext downloadContext, String dataUrl, DownloadCallback callback) throws IOException {
        if (!DataUris.hasPayload(dataUrl)) {
            callback.onError(MessageHelper.FILE_NOT_FOUND);
            return;
        }
        byte[] data;
        try {
            data = DataUris.decode(dataUrl);
        } catch (IllegalArgumentException e) {
            Log.w(TAG, "handleDataUrl: payload is not the base64 it claims to be");
            callback.onError(MessageHelper.IOEXCEPTION);
            return;
        }

        // The engine named the file from the request; the URL's media type
        // decides the extension when the name carries another.
        File file = downloadContext.getOutputFile();
        String extension = FileUriHelper.getFileExtensionFromData(dataUrl);
        if (!TextUtils.isEmpty(extension)
                && !extension.equalsIgnoreCase(FilenameUtils.getExtension(file.getName()))) {
            file = new File(file.getParent(), FilenameUtils.getBaseName(file.getName()) + "." + extension);
        }
        String resolvedPath = callback.onFilePathResolved(file.getAbsolutePath());
        file = new File(resolvedPath);

        try (FileOutputStream os = new FileOutputStream(file, false)) {
            os.write(data);
            os.flush();
        }

        callback.onFileSizeKnown(data.length);
        callback.onNameResolved(file.getName());
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