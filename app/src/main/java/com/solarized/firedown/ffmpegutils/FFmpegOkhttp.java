package com.solarized.firedown.ffmpegutils;

import static com.solarized.firedown.ffmpegutils.FFmpegConstants.FFMPEG_AVERROR_EINVAL;
import static com.solarized.firedown.ffmpegutils.FFmpegConstants.FFMPEG_AVERROR_ENOSYS;
import static com.solarized.firedown.ffmpegutils.FFmpegConstants.FFMPEG_AVERROR_EOF;
import static com.solarized.firedown.ffmpegutils.FFmpegConstants.FFMPEG_AVERROR_INTERRUPTED;
import static com.solarized.firedown.ffmpegutils.FFmpegConstants.FFMPEG_AVERROR_OK;

import android.util.Log;

import androidx.annotation.Keep;

import com.solarized.firedown.BuildConfig;
import com.solarized.firedown.data.di.NetworkModule;
import com.solarized.firedown.utils.BrowserHeaders;
import com.solarized.firedown.utils.FileUriHelper;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.ByteBuffer;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;

import okhttp3.Headers;
import com.solarized.firedown.okhttp.SafeHeaders;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.BufferedSource;

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
    /**
     * The body's okio source, NOT its byteStream().
     *
     * okio can copy a segment straight into a DirectByteBuffer
     * (BufferedSource.read(ByteBuffer)), so reading through the source lets
     * okhttpRead hand ffmpeg's AVIO buffer to okio directly. The byteStream()
     * form could not: InputStream.read only fills a byte[], so every byte
     * downloaded was copied TWICE in Java — segment → staging array → the
     * DirectByteBuffer — and each connection carried a 32 KB staging array
     * (`mReadTmp`) to do it. With N concurrent HLS segment connections that
     * array was N × 32 KB of heap for a copy that need not happen at all.
     */
    private BufferedSource bodySource;
    private String mimeType;
    private long mReadPosition;
    private long mStreamLength;
    private boolean seekable = true;

    // ── Range chunking state ────────────────────────────────────────────
    private boolean useRangeChunking = false;
    private boolean rangeChunkingProbed = false;
    private long chunkBytesRead = 0;

    // ── Range-required fallback state ───────────────────────────────────
    // Some CDNs (e.g. tvc1.watchsomuch.tv — IIS anti-leech) 416 a Range-LESS
    // request and only serve a ranged one, the inverse of a range-hostile
    // server. The browser's <video> always sends "Range: bytes=0-", so it
    // gets 206; our probe's initial open at pos 0 sends no Range and gets 416.
    // Once set, okhttpOpen injects "bytes=0-" when no other Range applies.
    private boolean forceFullRange = false;

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

                case "headers":
                    // Defence in depth: 'headers' is the name of the AVDictionary
                    // option ffmpeg uses to pass the joined header bag into the
                    // okhttp protocol — it is never a real HTTP header. When
                    // native serialises the option dict back into mHeaders and
                    // we split it on \r\n / =, the first chunk lands as the
                    // literal key 'headers' with the first real header as its
                    // value (e.g. 'headers: Sec-Fetch-Mode=cors'). Nginx
                    // tolerates the bogus header, but stricter origins (HTTP/2
                    // servers that enforce token grammar on header names) reject
                    // the request. Strip it unconditionally so no okhttp Request
                    // ever leaves the device carrying it.
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

    /**
     * Return value for user-initiated cancel (Stop/Delete). Restores the
     * interrupt flag so any Java-side cleanup further up the stack still sees
     * the request, then returns FFMPEG_AVERROR_INTERRUPTED — the native
     * wrapper (http.c okhttp_open / okhttp_read / okhttp_seek) maps that to
     * AVERROR_EXIT, the one code FFmpeg's HLS reload loop will NOT retry.
     *
     * Why this matters: every other negative we return collapses to AVERROR_EOF
     * or AVERROR(EIO) in http.c, and hls.c treats those as transient → it
     * re-opens the same segment forever. Each retry opens a fresh okhttp
     * connection that succeeds, reads EOF, retries again, and the worker
     * thread never exits — that is the "Stop/Delete spins on segment N" hang.
     *
     * This MUST be paired with a libavformat.so containing the matching
     * OKHTTP_AVERROR_INTERRUPTED → AVERROR_EXIT case in http.c — without it,
     * the wrapper falls into `default: ret = AVERROR(EIO)` and the HLS loop
     * still spins.
     */
    private static int interruptedReturn() {
        Thread.currentThread().interrupt();
        /* User cancel: also kick the now-idle pooled connection. Closing the
         * body only RSTs the HTTP/2 *stream*; the pooled *connection* stays
         * open, and a server that keeps pushing data for the dead stream (a
         * live-stream CDN) locks OkHttp into a DATA→RST_STREAM discard loop
         * until the pool's 5-minute idle eviction. Evicting here closes the
         * socket immediately so the server stops at TCP level. The catch
         * paths call okhttpClose() before this, so the cancelled connection
         * is already idle and eligible; for the fail-fast paths (connection
         * still held) the eviction in okhttpClose() covers it when ffmpeg
         * closes the URL. Idle-only and host-agnostic — active downloads on
         * other connections are untouched. See
         * NetworkModule.evictIdleConnections(). */
        NetworkModule.evictIdleConnections();
        return FFMPEG_AVERROR_INTERRUPTED;
    }

    @Keep
    private int okhttpOpen(Map<String, String> options) {
        try {
            /* Fail fast if the thread was interrupted (user Stop/Delete).
             * Returning FFMPEG_AVERROR_INTERRUPTED routes to AVERROR_EXIT in
             * http.c — see interruptedReturn(). */
            if (Thread.currentThread().isInterrupted()) {
                return interruptedReturn();
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

            // ── Range-required fallback ─────────────────────────────
            // A previous open got 416 on a Range-less request from this
            // CDN, which only serves ranged requests. Inject a zero-offset
            // Range when nothing above already set one.
            if (forceFullRange && !headers.containsKey(BrowserHeaders.RANGES)) {
                headers.put(BrowserHeaders.RANGES, "bytes=0-");
            }

            // Did this request carry a Range header? Drives the 416 branch
            // below: a 416 on a Range-LESS request means the server REQUIRES
            // a range; a 416 on a ranged request means the range was
            // unsatisfiable (asked past EOF).
            boolean requestHadRange = headers.containsKey(BrowserHeaders.RANGES);

            Request request = new Request.Builder()
                    .headers(SafeHeaders.of(headers))
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

            // Handle 416 Range Not Satisfiable — two opposite causes.
            if (statusCode == FFmpegConstants.HTTP_RANGE_NOT_SATISFIABLE) {

                // (B) Range-REQUIRED server: it 416s a Range-LESS request and
                // only serves a ranged one (e.g. tvc1.watchsomuch.tv — IIS
                // anti-leech). The browser's <video> always sends
                // "Range: bytes=0-" → 206; our probe's initial open sends none
                // → 416. Retry ONCE with a zero-offset range. Reactive and
                // host-agnostic, mirroring HttpDownloadStrategy's 403/404/416
                // range-retry: a range-HOSTILE server answers the Range-less
                // GET (it never 416s), so it can't reach this branch.
                if (!requestHadRange && !forceFullRange) {
                    okhttpClose();
                    this.forceFullRange = true;
                    return okhttpOpen(options);
                }

                // (A) Range-UNSATISFIABLE: we DID send a Range and it was past
                // EOF. Fall back to a Range-less sequential read from byte 0.
                // We MUST reset mReadPosition here — otherwise the server
                // returns bytes [0..N] but our internal position counter still
                // reads 10MB (or wherever we were), and ffmpeg interprets the
                // incoming bytes as starting at that offset → silent data
                // corruption manifesting as 'moov atom not found' mid-stream or
                // garbage frames.
                //
                // The caller (ffmpeg) will notice position jumped backwards
                // through its own AVIOContext bookkeeping only if it performs a
                // seek; for a plain sequential read the reset is invisible.
                if (seekable && !forceFullRange) {
                    okhttpClose();
                    this.seekable = false;
                    this.useRangeChunking = false;
                    this.rangeChunkingProbed = true;
                    this.mReadPosition = 0;
                    this.chunkBytesRead = 0;
                    return okhttpOpen(options);
                }
            }

            if (!httpResponse.isSuccessful()) {
                okhttpClose();
                return mapErrorCode(statusCode);
            }

            responseBody = httpResponse.body();
            bodySource = responseBody.source();
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
            /* okhttp surfaces a mid-flight cancel as InterruptedIOException (an
             * IOException subtype). Route it to AVERROR_EXIT via the
             * INTERRUPTED code, not the retryable EOF the catch used to return. */
            if (e instanceof InterruptedIOException || Thread.currentThread().isInterrupted()) {
                return interruptedReturn();
            }
            return FFMPEG_AVERROR_EOF;
        } catch (Throwable t) {
            // okhttp's blocking call paths (e.g.
            // FastFallbackExchangeFinder.awaitTcpConnect →
            // LinkedBlockingDeque.poll) sit on top of Kotlin code that
            // doesn't enforce checked-exception declarations at compile
            // time. When the calling thread is interrupted mid-flight
            // (DownloadRunnable.stop → Thread.interrupt() from
            // RunnableManager.finishDownloadToExecutor), poll() raises
            // InterruptedException, which propagates through okhttp's
            // Kotlin frames and out of okHttpClient.newCall(...).execute()
            // — neither declared on the Java signature nor a subtype of
            // IOException, so the IOException catch above misses it.
            //
            // Net effect of the old code: the exception bubbles out of
            // okhttpOpen back into the native JNI caller with a PENDING
            // exception set on the JNIEnv. The next native→Java call
            // (any JNI CallXMethod / GetField, including the FFmpeg
            // av_log path that calls back into our log callback) trips
            // CheckJNI's pending-exception assertion and aborts the
            // process with "JNI DETECTED ERROR IN APPLICATION:
            // JNI CallObjectMethod called with pending exception
            // java.lang.InterruptedException".
            //
            // Catch Throwable so anything that escapes okhttp (the
            // interrupt path, OOM mid-allocation, an okhttp
            // assertion, a transitive RuntimeException) is swallowed
            // here and returned as AVERROR_EOF — FFmpeg cleanly winds
            // its read loop, the download thread exits, no zombie
            // exception left on the JNIEnv.
            //
            // Restore the interrupt status so any Java-side cleanup
            // code that polls Thread.interrupted() further up still
            // sees the request.
            Log.e(TAG, "okhttpOpen aborted: " + mUrl, t);
            okhttpClose();
            if (t instanceof InterruptedException || Thread.currentThread().isInterrupted()) {
                /* Cancel mid-open: route to AVERROR_EXIT via the INTERRUPTED
                 * code, not the retryable EOF the catch used to return. */
                return interruptedReturn();
            }
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
            if (bodySource == null)
                return FFMPEG_AVERROR_ENOSYS;

            /* Fail fast on user Stop/Delete — INTERRUPTED → AVERROR_EXIT in
             * http.c, so FFmpeg's HLS loop unwinds instead of retrying the
             * segment forever (see interruptedReturn()). */
            if (Thread.currentThread().isInterrupted()) {
                return interruptedReturn();
            }

            /* Bound every write by `size` — NEVER by byteBuffer.capacity().
             *
             * This comment used to claim the two were equal ("NewDirectByteBuffer
             * was called with exactly `size` bytes, so capacity() == size, no
             * min() needed"). That stopped being true when http.c started
             * CACHING one DirectByteBuffer per URLContext instead of wrapping
             * the AVIO buffer afresh per call: it only recreates the wrapper
             * when ffmpeg's pointer moves or the request grows, so capacity()
             * is the LARGEST size asked for on this connection and can exceed
             * this call's `size`. The bytes past `size` are not ours — they may
             * sit outside a smaller reallocation of ffmpeg's buffer — so
             * writing capacity() bytes would be a native heap overflow.
             *
             * limit(size) is what enforces it, and it throws if `size` ever
             * exceeds capacity, which the catch below turns into a clean EOF
             * rather than a corrupt read. Don't "simplify" this away.
             *
             * It is doubly load-bearing now that okio writes THROUGH this
             * ByteBuffer: BufferedSource.read(sink) copies min(sink.remaining(),
             * segment bytes), so the limit is what physically bounds how far
             * into ffmpeg's memory okio may write. */
            int limit = size;
            byteBuffer.clear();
            byteBuffer.limit(limit);

            // Allow one reconnect retry if the current chunk returns immediate
            // EOF but we haven't read the whole file yet. We cap this to prevent
            // an infinite reconnect loop if the server is misbehaving.
            int reconnectAttempts = 0;
            final int MAX_RECONNECT_ATTEMPTS = 1;

            while (true) {
                int totalRead = 0;
                while (totalRead < limit) {
                    /* Segment → ffmpeg's AVIO buffer, one copy, no staging
                     * array. okio appends at the ByteBuffer's position and
                     * stops at its limit, so the loop needs no offset
                     * arithmetic; it returns one segment's worth at a time
                     * (Segment.SIZE, 8 KB), hence ~4 turns for a 32 KB
                     * IO_BUFFER_SIZE request. Read the FIELD every turn, never
                     * hoist it to a local: the server-EOF reconnect below
                     * replaces bodySource and `continue`s back into this very
                     * loop, so a hoisted reference would read the dead one. */
                    int n = bodySource.read(byteBuffer);
                    if (n < 0) break;
                    if (n == 0) continue;
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
            if (e instanceof InterruptedIOException || Thread.currentThread().isInterrupted()) {
                return interruptedReturn();
            }
            return FFMPEG_AVERROR_EOF;
        } catch (Throwable t) {
            // Same trap as okhttpOpen — see the comment there. The read
            // path can also block in okhttp's connection-related code
            // (range-chunking re-opens, server-side EOF reconnects), so
            // an interrupt mid-read manifests with the same
            // InterruptedException-leaks-to-JNI shape.
            if (t instanceof InterruptedException || Thread.currentThread().isInterrupted()) {
                // Cancel mid-read: INTERRUPTED → AVERROR_EXIT, not the
                // retryable EOF that keeps the HLS loop spinning.
                return interruptedReturn();
            }
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
        if (bodySource != null && diff > 0 && diff < MAX_SKIP_SIZE) {
            try {
                /* okio's skip is all-or-throw: it loops internally until the
                 * full count is discarded, or raises EOFException. That
                 * replaces the old InputStream retry loop, which existed only
                 * because InputStream.skip may return a short count (or 0)
                 * without failing.
                 *
                 * A partial skip before the throw leaves an unknown number of
                 * bytes consumed — which does not matter, because the failure
                 * path below tears the connection down and assigns
                 * mReadPosition ABSOLUTELY before reopening with a Range. */
                bodySource.skip(diff);
                mReadPosition += diff;
                chunkBytesRead += diff;
                return mReadPosition;
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
            if (bodySource != null) bodySource.close();
            if (responseBody != null) responseBody.close();
            if (httpResponse != null) httpResponse.close();
        } catch (IOException e) {
            Log.e(TAG, "Error closing connection", e);
        } finally {
            bodySource = null;
            responseBody = null;
            httpResponse = null;
        }
    }

    @Keep
    private void okhttpClose() {
        closeConnection();
        /* Cancel path only (interrupt flag set by interruptedReturn() or the
         * stop plumbing): the connection just released above is now idle, so
         * evict it before the server can spin the cancelled-stream discard
         * loop (see interruptedReturn()). Gated on the interrupt flag so
         * normal closes — seeks, range-chunk reconnects, end-of-download —
         * never touch the pool. */
        if (Thread.currentThread().isInterrupted()) {
            NetworkModule.evictIdleConnections();
        }
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