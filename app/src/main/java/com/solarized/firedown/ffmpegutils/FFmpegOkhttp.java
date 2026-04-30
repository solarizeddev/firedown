package com.solarized.firedown.ffmpegutils;

import static com.solarized.firedown.ffmpegutils.FFmpegConstants.FFMPEG_AVERROR_EINVAL;
import static com.solarized.firedown.ffmpegutils.FFmpegConstants.FFMPEG_AVERROR_ENOSYS;
import static com.solarized.firedown.ffmpegutils.FFmpegConstants.FFMPEG_AVERROR_EOF;
import static com.solarized.firedown.ffmpegutils.FFmpegConstants.FFMPEG_AVERROR_OK;

import android.util.Log;

import androidx.annotation.Keep;

import com.solarized.firedown.BuildConfig;
import com.solarized.firedown.data.di.NetworkModule;
import com.solarized.firedown.utils.BrowserHeaders;
import com.solarized.firedown.utils.FileUriHelper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;

import okhttp3.Headers;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

public class FFmpegOkhttp {

    private static final String TAG = FFmpegOkhttp.class.getSimpleName();
    private static final long MAX_SKIP_SIZE = 256 * 1024;

    // ── Range chunking config ───────────────────────────────────────────
    private static final long RANGE_CHUNK_SIZE = 10 * 1024 * 1024;       // 10 MB per chunk
    private static final long RANGE_CHUNK_THRESHOLD = 2 * 1024 * 1024;   // only chunk files > 2 MB

    private final OkHttpClient okHttpClient;
    private final String mUrl;
    private final String mHeaders;
    private Response httpResponse;
    private ResponseBody responseBody;
    private InputStream inputStream;
    private byte[] mReadTmp;
    private String mimeType;
    private long mReadPosition;
    private long mStreamLength;
    private boolean seekable = true;

    // ── Range chunking state ────────────────────────────────────────────
    private boolean useRangeChunking = false;
    private boolean rangeChunkingProbed = false;
    private long chunkBytesRead = 0;

    public FFmpegOkhttp(String url, String headers) {
        this.mUrl = url;
        this.mHeaders = headers;
        this.mReadPosition = 0L;
        this.mStreamLength = Long.MAX_VALUE;
        this.okHttpClient = NetworkModule.globalClient;
    }

    @Keep
    public String okhttpGetMime() {
        return mimeType != null ? mimeType : FileUriHelper.MIMETYPE_UNKNOWN;
    }

    /**
     * Sanitize headers for OkHttp requests.
     *
     * OkHttp manages certain headers internally. If the caller (FFmpeg, WebExtension)
     * includes them in the header string, they cause duplicates, wrong-host errors,
     * or silent failures:
     *
     *   Host           — OkHttp derives from URL. A stale Host from redirects
     *                    causes "wrong host" / 400 errors on some servers.
     *   Connection     — OkHttp manages keep-alive internally. Sending
     *                    "Connection: close" kills connection pooling.
     *   Content-Type   — GET requests have no body; stale value confuses servers.
     *   Content-Length — Same — no body on GET, stale value causes 400.
     *   Transfer-Encoding — OkHttp manages chunked encoding internally.
     *
     * Accept-Encoding is special: for video streams we want "identity" (no
     * compression) to get raw bytes. If explicitly set to "identity", keep it.
     * Otherwise remove it — OkHttp/GzipInterceptor handles gzip transparently.
     *
     * This replaces the header stripping previously done in FFmpegUtils.setHeaders()
     * for the native FFmpeg HTTP protocol, adapted for OkHttp's requirements.
     */
    private static void sanitizeHeaders(Map<String, String> headers) {
        // Use iterator for safe removal during iteration
        Iterator<Map.Entry<String, String>> it = headers.entrySet().iterator();
        boolean hasAcceptEncodingIdentity = false;

        while (it.hasNext()) {
            Map.Entry<String, String> entry = it.next();
            String key = entry.getKey();
            String keyLower = key.toLowerCase(Locale.ROOT);

            switch (keyLower) {
                case "host":
                case "connection":
                case "content-type":
                case "content-length":
                case "transfer-encoding":
                    it.remove();
                    break;

                case "accept-encoding":
                    if ("identity".equals(entry.getValue())) {
                        hasAcceptEncodingIdentity = true;
                    } else {
                        // Remove gzip/deflate/br — let OkHttp handle compression
                        it.remove();
                    }
                    break;
            }
        }

        // Ensure Accept-Encoding: identity is present for video streams
        // if it was explicitly set by the caller
        if (hasAcceptEncodingIdentity) {
            headers.put("Accept-Encoding", "identity");
        }

        // Ensure a User-Agent is always present — some CDNs reject requests
        // without one. Use the default browser UA if the caller didn't set it.
        if (!headers.containsKey("User-Agent") && !headers.containsKey("user-agent")) {
            headers.put("User-Agent", BrowserHeaders.getDefaultUserAgentString());
        }
    }

    private void setOptions(Map<String, String> options, Map<String, String> headers) {
        if (options == null) return;

        if (BuildConfig.DEBUG) {
            for (Map.Entry<String, String> entry : headers.entrySet()) {
                Log.d(TAG, " setOptions: " + (entry.getKey() + ":" + entry.getValue()));
            }
        }

        if (options.containsKey("seekable")) {
            String seek = options.get("seekable");
            this.seekable = seek != null && !seek.equals("-1");
        }

        if (options.containsKey("offset")) {
            String offset = options.get("offset");
            String bytes = "bytes=" + offset + "-";
            if (options.containsKey("end_offset")) {
                bytes += options.get("end_offset");
            }
            headers.put(BrowserHeaders.RANGES, bytes);
        }
    }

    @Keep
    private int okhttpOpen(Map<String, String> options) {
        try {
            /* Fail fast if the thread was interrupted (user stop/delete).
             * Without this, FFmpeg's HLS demuxer retries segment fetches
             * indefinitely — each retry opens a new connection that succeeds,
             * then reads EOF, then retries again. */
            if (Thread.currentThread().isInterrupted()) {
                return FFMPEG_AVERROR_EOF;
            }

            if (BuildConfig.DEBUG) Log.d(TAG, "okhttpOpen : " + mUrl);

            Map<String, String> headers = FFmpegUtils.stringToMap(mHeaders);

            // Strip headers that OkHttp manages internally to prevent
            // duplicates, wrong-host errors, and encoding conflicts.
            sanitizeHeaders(headers);

            if (seekable && mReadPosition > 0) {
                headers.put(BrowserHeaders.RANGES, "bytes=" + mReadPosition + "-");
            }

            setOptions(options, headers);

            // ── Range chunking: set bounded range ───────────────────
            if (useRangeChunking && seekable) {
                long chunkEnd = mReadPosition + RANGE_CHUNK_SIZE - 1;
                if (mStreamLength != Long.MAX_VALUE && chunkEnd >= mStreamLength) {
                    chunkEnd = mStreamLength - 1;
                }
                headers.put(BrowserHeaders.RANGES, "bytes=" + mReadPosition + "-" + chunkEnd);
                chunkBytesRead = 0;

                if (BuildConfig.DEBUG) {
                    Log.d(TAG, "Range chunking: bytes=" + mReadPosition + "-" + chunkEnd);
                }
            }

            Request request = new Request.Builder()
                    .headers(Headers.of(headers))
                    .url(mUrl)
                    .build();

            if (this.okHttpClient == null) {
                Log.e(TAG, "OkHttpClient not initialized yet!");
                return FFMPEG_AVERROR_ENOSYS;
            }

            // Debug: dump final request headers
            if (BuildConfig.DEBUG) {
                Log.d(TAG, ">>> Request to: " + request.url().host());
                for (int i = 0; i < request.headers().size(); i++) {
                    Log.d(TAG, ">>> " + request.headers().name(i) + ": " + request.headers().value(i));
                }
            }

            httpResponse = okHttpClient.newCall(request).execute();
            int statusCode = httpResponse.code();

            if (BuildConfig.DEBUG) {
                Log.d(TAG, "okhttpOpen response from: " + httpResponse.request().url().host()
                        + " protocol=" + httpResponse.protocol()
                        + " code=" + httpResponse.code());

                // Debug: dump response headers
                for (int i = 0; i < httpResponse.headers().size(); i++) {
                    Log.d(TAG, "<<< " + httpResponse.headers().name(i) + ": " + httpResponse.headers().value(i));
                }
            }

            // Handle 416 Range Not Satisfiable
            //
            // The server rejected our Range request. Fall back to a ranged-less
            // request from byte 0. We MUST reset mReadPosition here — otherwise
            // the server returns bytes [0..N] but our internal position counter
            // still reads 10MB (or wherever we were), and ffmpeg interprets the
            // incoming bytes as starting at that offset → silent data corruption
            // manifesting as 'moov atom not found' mid-stream or garbage frames.
            //
            // The caller (ffmpeg) will notice position jumped backwards through
            // its own AVIOContext bookkeeping only if it performs a seek; for
            // a plain sequential read the reset is invisible to ffmpeg.
            if (statusCode == FFmpegConstants.HTTP_RANGE_NOT_SATISFIABLE && seekable) {
                okhttpClose();
                this.seekable = false;
                this.useRangeChunking = false;
                this.rangeChunkingProbed = true;
                this.mReadPosition = 0;
                this.chunkBytesRead = 0;
                return okhttpOpen(options);
            }

            if (!httpResponse.isSuccessful()) {
                okhttpClose();
                return mapErrorCode(statusCode);
            }

            responseBody = httpResponse.body();
            inputStream = responseBody.byteStream();
            mimeType = parseMimeType();

            // ── Resolve total stream length ─────────────────────────
            if (mStreamLength == Long.MAX_VALUE) {
                long totalFromRange = parseTotalFromContentRange();
                if (totalFromRange > 0) {
                    mStreamLength = totalFromRange;
                } else {
                    long cl = responseBody.contentLength();
                    if (cl > 0 && mReadPosition == 0 && !useRangeChunking) {
                        mStreamLength = cl;
                    }
                }
            }

            // ── Detect ICY live streams ─────────────────────────────
            if (isIcyStream(httpResponse)) {
                if (BuildConfig.DEBUG) Log.d(TAG, "ICY live stream detected");
                seekable = false;
                useRangeChunking = false;
                rangeChunkingProbed = true;
                mStreamLength = Long.MAX_VALUE;
            }

            // ── Probe: should we enable range chunking? ─────────────
            if (!rangeChunkingProbed) {
                rangeChunkingProbed = true;
                useRangeChunking = false;

                if (seekable) {
                    boolean serverSupportsRanges = supportsRangeRequests(httpResponse);
                    long totalSize = mStreamLength != Long.MAX_VALUE ? mStreamLength
                            : responseBody.contentLength();

                    if (serverSupportsRanges && totalSize > RANGE_CHUNK_THRESHOLD) {
                        useRangeChunking = true;
                        if (mStreamLength == Long.MAX_VALUE && totalSize > 0) {
                            mStreamLength = totalSize;
                        }
                        if (BuildConfig.DEBUG) {
                            Log.d(TAG, "Range chunking ENABLED: server supports ranges, size="
                                    + totalSize + " (>" + RANGE_CHUNK_THRESHOLD + ")");
                        }

                        // Reconnect with a bounded range for the first chunk
                        closeConnection();
                        return okhttpOpen(options);
                    } else {
                        if (BuildConfig.DEBUG) {
                            Log.d(TAG, "Range chunking DISABLED: ranges="
                                    + serverSupportsRanges + " size=" + totalSize);
                        }
                    }
                }
            }

            if (BuildConfig.DEBUG) {
                Log.d(TAG, "StreamLength: " + mStreamLength + " seekable: " + seekable
                        + " chunking: " + useRangeChunking + " pos: " + mReadPosition + " url: " + mUrl);
            }

            if (mStreamLength <= 0)
                mStreamLength = Long.MAX_VALUE;

            return FFMPEG_AVERROR_OK;

        } catch (IOException e) {
            Log.e(TAG, "okhttpOpen failed: " + mUrl, e);
            okhttpClose();
            return FFMPEG_AVERROR_EOF;
        }
    }

    /**
     * Check if the server supports byte range requests.
     */
    private boolean supportsRangeRequests(Response response) {
        String acceptRanges = response.header("Accept-Ranges");
        if (acceptRanges != null) {
            return acceptRanges.toLowerCase(Locale.ROOT).contains("bytes");
        }
        if (response.code() == 206) {
            return true;
        }
        return response.header("Content-Range") != null;
    }

    /**
     * Parse total file size from Content-Range header.
     * Format: "bytes START-END/TOTAL"
     */
    private long parseTotalFromContentRange() {
        if (httpResponse == null) return -1;
        String cr = httpResponse.header("Content-Range");
        if (cr == null) return -1;
        int slashIdx = cr.indexOf('/');
        if (slashIdx < 0) return -1;
        String totalStr = cr.substring(slashIdx + 1).trim();
        if (totalStr.equals("*")) return -1;
        try {
            return Long.parseLong(totalStr);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    @Keep
    public int okhttpRead(ByteBuffer byteBuffer, int size) {
        try {
            if (inputStream == null)
                return FFMPEG_AVERROR_ENOSYS;

            /* Fail fast if the thread was interrupted. */
            if (Thread.currentThread().isInterrupted()) {
                return FFMPEG_AVERROR_EOF;
            }

            /* NewDirectByteBuffer on the C side was called with exactly `size`
             * bytes, so byteBuffer.capacity() == size here. No min() needed. */
            int limit = size;
            byteBuffer.clear();
            byteBuffer.limit(limit);

            byte[] tmp = mReadTmp;
            if (tmp == null || tmp.length < limit) {
                tmp = new byte[limit];
                mReadTmp = tmp;
            }

            // Allow one reconnect retry if the current chunk returns immediate
            // EOF but we haven't read the whole file yet. We cap this to prevent
            // an infinite reconnect loop if the server is misbehaving.
            int reconnectAttempts = 0;
            final int MAX_RECONNECT_ATTEMPTS = 1;

            while (true) {
                int totalRead = 0;
                while (totalRead < limit) {
                    int toRead = Math.min(limit - totalRead, tmp.length);
                    int n = inputStream.read(tmp, 0, toRead);
                    if (n < 0) break;
                    if (n == 0) continue;
                    byteBuffer.put(tmp, 0, n);
                    totalRead += n;
                }

                if (totalRead > 0) {
                    mReadPosition += totalRead;
                    chunkBytesRead += totalRead;

                    // ── Range chunking: reconnect at chunk boundary ─────
                    // Triggered AFTER returning bytes, so the next okhttpRead
                    // call will hit the fresh connection. No data loss.
                    if (useRangeChunking && chunkBytesRead >= RANGE_CHUNK_SIZE) {
                        if (mStreamLength != Long.MAX_VALUE && mReadPosition >= mStreamLength) {
                            if (BuildConfig.DEBUG) {
                                Log.d(TAG, "Range chunking: reached EOF at " + mReadPosition);
                            }
                        } else {
                            if (BuildConfig.DEBUG) {
                                Log.d(TAG, "Range chunking: reconnecting at pos=" + mReadPosition);
                            }
                            closeConnection();
                            int res = okhttpOpen(null);
                            if (res != FFMPEG_AVERROR_OK) {
                                Log.e(TAG, "Range chunking reconnect failed: " + res);
                                return FFMPEG_AVERROR_EOF;
                            }
                        }
                    }

                    return totalRead;
                }

                // totalRead == 0 → server-side EOF on this connection
                //
                // If we're chunking and haven't reached the logical end of the
                // file, reconnect and loop back to actually perform the read.
                //
                // CRITICAL: returning 0 here is interpreted by FFmpeg's custom
                // URL protocol as "end of stream", NOT as "try again". The old
                // code returned 0 after a successful reconnect, which caused
                // ffmpeg to truncate large files at every chunk boundary where
                // the server closed the stream early. Must return real bytes.
                if (useRangeChunking && mStreamLength != Long.MAX_VALUE
                        && mReadPosition < mStreamLength
                        && reconnectAttempts < MAX_RECONNECT_ATTEMPTS) {
                    if (BuildConfig.DEBUG) {
                        Log.d(TAG, "Range chunking: chunk EOF at pos=" + mReadPosition
                                + ", reconnecting (total=" + mStreamLength + ")");
                    }
                    closeConnection();
                    int res = okhttpOpen(null);
                    if (res != FFMPEG_AVERROR_OK) {
                        return FFMPEG_AVERROR_EOF;
                    }
                    reconnectAttempts++;
                    // Reset buffer for the retry
                    byteBuffer.clear();
                    byteBuffer.limit(limit);
                    continue;
                }

                return FFMPEG_AVERROR_EOF;
            }

        } catch (IOException e) {
            return FFMPEG_AVERROR_EOF;
        }
    }

    @Keep
    private long okhttpSeek(long seekPos, int whence) {
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "okhttpSeek seekPos: " + seekPos + " whence: " + whence);
        }
        if (whence == FFmpegConstants.AVSEEK_SIZE) {
            return mStreamLength != Long.MAX_VALUE ? mStreamLength : FFMPEG_AVERROR_EOF;
        }

        long targetPos = mReadPosition;
        switch (whence) {
            case FFmpegConstants.SEEK_SET: targetPos = seekPos; break;
            case FFmpegConstants.SEEK_CUR: targetPos += seekPos; break;
            case FFmpegConstants.SEEK_END:
                if (mStreamLength == Long.MAX_VALUE) return FFMPEG_AVERROR_ENOSYS;
                targetPos = mStreamLength + seekPos;
                break;
            default: return FFMPEG_AVERROR_EINVAL;
        }

        return performSeek(targetPos);
    }

    private long performSeek(long targetPos) {
        long diff = targetPos - mReadPosition;
        if (inputStream != null && diff > 0 && diff < MAX_SKIP_SIZE) {
            try {
                long totalSkipped = 0;
                int retries = 0;
                while (totalSkipped < diff && retries < 10) {
                    long skipped = inputStream.skip(diff - totalSkipped);
                    if (skipped < 0) break;
                    if (skipped == 0) {
                        retries++;
                        continue;
                    }
                    totalSkipped += skipped;
                    retries = 0;
                }
                mReadPosition += totalSkipped;
                chunkBytesRead += totalSkipped;
                if (totalSkipped == diff) return mReadPosition;
            } catch (IOException ignored) {}
        }

        okhttpClose();
        mReadPosition = targetPos;
        chunkBytesRead = 0;
        int res = okhttpOpen(null);
        return (res == FFMPEG_AVERROR_OK) ? mReadPosition : FFMPEG_AVERROR_EOF;
    }

    /**
     * Close HTTP connection without resetting position state.
     */
    private void closeConnection() {
        try {
            if (inputStream != null) inputStream.close();
            if (responseBody != null) responseBody.close();
            if (httpResponse != null) httpResponse.close();
        } catch (IOException e) {
            Log.e(TAG, "Error closing connection", e);
        } finally {
            inputStream = null;
            responseBody = null;
            httpResponse = null;
        }
    }

    @Keep
    private void okhttpClose() {
        closeConnection();
    }

    private String parseMimeType() {
        if (responseBody == null) return null;
        MediaType type = responseBody.contentType();
        if (type == null) return null;
        return type.type() + "/" + type.subtype();
    }

    private boolean isIcyStream(Response response) {
        Headers headers = response.headers();
        for (int i = 0; i < headers.size(); i++) {
            if (headers.name(i).toLowerCase(Locale.ROOT).startsWith("icy-")) {
                return true;
            }
        }
        return false;
    }

    private int mapErrorCode(int statusCode) {
        switch (statusCode) {
            case 400: return FFmpegConstants.FFMPEG_AVERROR_BAD_REQUEST;
            case 401: return FFmpegConstants.FFMPEG_AVERROR_UNAUTHORIZED;
            case 403: return FFmpegConstants.FFMPEG_AVERROR_FORBIDDEN;
            case 404: return FFmpegConstants.FFMPEG_AVERROR_NOT_FOUND;
            case 429: return FFmpegConstants.FFMPEG_AVERROR_TOO_MANY_REQUESTS;
            default:
                if (statusCode >= 500) return FFmpegConstants.FFMPEG_AVERROR_SERVER_ERROR;
                if (statusCode >= 400) return FFmpegConstants.FFMPEG_AVERROR_OTHER_4XX;
                return FFMPEG_AVERROR_EINVAL;
        }
    }
}