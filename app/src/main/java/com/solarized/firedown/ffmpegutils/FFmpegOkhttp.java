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
    /** Forward seek served by discarding on the live body when we CAN range. */
    private static final long MAX_SKIP_SIZE = 256 * 1024;
    /**
     * Forward seek served by discarding when we CANNOT range — the server 416'd
     * a Range we sent, or the demuxer told us not to send one. Discarding is
     * then the only way to reach an offset at all, so the budget is far larger
     * than MAX_SKIP_SIZE: these bytes are the price of a stream that would
     * otherwise be unseekable outright.
     *
     * It is a bound, not a target. Past it we fail the seek instead of
     * downloading an arbitrary prefix of the file — a demuxer may seek
     * repeatedly, and each backward seek restarts the walk from byte 0. 8 MiB
     * covers short clips whole (where a moov at the end is otherwise fatal) and
     * refuses to spend a large file's bandwidth guessing.
     */
    private static final long MAX_NO_RANGE_SKIP_SIZE = 8 * 1024 * 1024;

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
    /**
     * Whether this connection may carry a Range header, per ffmpeg's
     * `seekable` AVOption: "0" = disable partial requests, "1" = enable,
     * "-1" = auto. See setOptions() for why "-1" must mean permitted.
     */
    private boolean seekable = true;
    /**
     * Whether the SERVER has demonstrated range support (Accept-Ranges: bytes,
     * a 206, or a Content-Range) on some response for this URL. Monotonic — it
     * is never cleared once observed, because a later Range-less reopen's 200
     * says nothing about whether ranges work.
     *
     * This is the precondition for the mid-stream resume in okhttpRead: a
     * reopen from `mReadPosition` is only correct if the server honours the
     * Range. Against a server that ignores it we would get the whole body from
     * byte 0 and append it to what we already have — silent corruption. (The
     * resume additionally verifies the reopen actually answered 206.)
     */
    private boolean serverAcceptsRanges = false;
    /**
     * The DEMUXER supplied this connection's Range itself, via the
     * `offset`/`end_offset` options — an `#EXT-X-BYTERANGE` HLS segment
     * (hls.c:1403) or a DASH SegmentBase track (dashdec.c:1738, the Bilibili.tv
     * whole-track `.m4s` path). The body is then a SLICE of the resource, and
     * two of our own behaviours become wrong on it:
     *
     *  - `mReadPosition` counts from the start of the SLICE, not of the file,
     *    so no absolute `bytes=<mReadPosition>-` we could build is correct.
     *    ffmpeg says as much at hls.c:1444: avio's "bookkeeping of file offset
     *    ... is out-of-sync with the actual offset when 'offset' AVOption is
     *    used with http protocol".
     *  - `mStreamLength` comes from Content-Range's TOTAL (the whole
     *    resource), which is larger than the slice — so a slice read to
     *    completion looks short and would trip the resume in okhttpRead.
     *
     * So a byte-range connection is delivered verbatim: no Range we invent, no
     * resume, and a 416 is a real error rather than something to fall back
     * from. Detecting a short slice is the demuxer's job, and it has the
     * offsets to do it.
     */
    private boolean demuxerRange = false;
    /**
     * A Range WE added was answered 416, so stop adding one. One-shot, and a
     * separate field rather than just clearing `seekable`, because `seekable`
     * is re-read from the options map by setOptions on every open that carries
     * one — so on the retry it comes straight back as true and the 416 branch
     * re-fires, recursing until the stack blows (one HTTP request per frame).
     */
    private boolean rangeRejected = false;

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

        /* ffmpeg's `seekable` AVOption is TRI-state, and only "0" means no:
         *   "0"  → disable partial requests (never send a Range header)
         *   "1"  → enable
         *   "-1" → auto (the default; ranges are permitted)
         *
         * This used to read `!seek.equals("-1")`, which inverted exactly the
         * two values that occur in practice:
         *   - hls.c passes its http_seekable default of -1 (FFmpeg n8.1.2,
         *     hls.c:2163), so every HLS segment was marked NOT seekable — a
         *     seek past the buffered window then reopened with NO Range while
         *     mReadPosition claimed the target offset, i.e. the bytes arrived
         *     from 0 but were accounted as if they started mid-file.
         *   - dashdec.c:2086 passes "0" for live streams, deliberately, to
         *     suppress the Range header — and we read that as "seekable" and
         *     sent one anyway. hls.c's own comment states the intent: "Some
         *     HLS servers don't like being sent the range header, in this
         *     case, we need to set http_seekable = 0 to disable the range
         *     header."
         * So the demuxer's explicit "do not range me" was honoured as "do",
         * and its "auto" as "don't". */
        if (options.containsKey("seekable")) {
            this.seekable = !"0".equals(options.get("seekable"));
        }

        if (options.containsKey("offset")) {
            String offset = options.get("offset");
            String bytes = "bytes=" + offset + "-";
            if (options.containsKey("end_offset")) {
                bytes += options.get("end_offset");
            }
            headers.put(BrowserHeaders.RANGES, bytes);
            // The demuxer owns this connection's Range — see demuxerRange.
            this.demuxerRange = true;
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
                // reads whatever offset we were at, and ffmpeg interprets the
                // incoming bytes as starting there → silent data corruption
                // manifesting as 'moov atom not found' mid-stream or garbage
                // frames.
                //
                // The caller (ffmpeg) will notice position jumped backwards
                // through its own AVIOContext bookkeeping only if it performs a
                // seek; for a plain sequential read the reset is invisible.
                //
                // Two guards, each closing a way this recursion goes wrong:
                //
                //  - `!rangeRejected` makes it ONE-SHOT. Clearing `seekable`
                //    cannot do that job: the recursive call re-runs setOptions
                //    over the same options map, which sets `seekable` straight
                //    back to true, so a still-416ing Range re-entered this
                //    branch every time — one HTTP request per stack frame until
                //    the frame's own catch(Throwable) caught the StackOverflow.
                //  - `!demuxerRange` keeps us out of it entirely when the Range
                //    is the demuxer's own `offset`/`end_offset` (see that
                //    field). The retry would re-add the identical Range anyway,
                //    and if it somehow succeeded without one we would hand the
                //    demuxer the whole resource where it asked for one slice.
                //    A 416 on a slice the caller named is a genuine error.
                if (seekable && !forceFullRange && !rangeRejected && !demuxerRange) {
                    okhttpClose();
                    this.rangeRejected = true;
                    this.seekable = false;
                    this.mReadPosition = 0;
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
                    if (cl > 0 && mReadPosition == 0) {
                        mStreamLength = cl;
                    }
                }
            }

            // Record range support monotonically — see serverAcceptsRanges.
            if (!serverAcceptsRanges && supportsRangeRequests(httpResponse)) {
                serverAcceptsRanges = true;
            }

            // ── Detect ICY live streams ─────────────────────────────
            if (isIcyStream(httpResponse)) {
                if (BuildConfig.DEBUG) Log.d(TAG, "ICY live stream detected");
                seekable = false;
                mStreamLength = Long.MAX_VALUE;
            }

            if (BuildConfig.DEBUG) {
                Log.d(TAG, "StreamLength: " + mStreamLength + " seekable: " + seekable
                        + " ranges: " + serverAcceptsRanges
                        + " demuxerRange: " + demuxerRange
                        + " rangeRejected: " + rangeRejected
                        + " pos: " + mReadPosition + " url: " + mUrl);
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
        if (response.code() == FFmpegConstants.HTTP_PARTIAL_CONTENT) {
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

            // Allow one reconnect retry if the connection returns immediate EOF
            // but we haven't read the whole file yet. We cap this to prevent an
            // infinite reconnect loop if the server is misbehaving.
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
                    return totalRead;
                }

                /* totalRead == 0 → the server closed the body early: it ended
                 * the response short of the length it declared. Reopen from
                 * mReadPosition with a Range and loop back to actually perform
                 * the read — this is the ffmpeg-side twin of
                 * HttpDownloadStrategy's resume-on-clean-EOF.
                 *
                 * CRITICAL: returning 0 here is interpreted by FFmpeg's custom
                 * URL protocol as "end of stream", NOT as "try again", so the
                 * reconnect must produce real bytes before we return. (An
                 * earlier version returned 0 straight after a successful
                 * reconnect, which truncated the file at that boundary.)
                 *
                 * The gate is exactly the conditions under which resuming is
                 * SOUND, and nothing else:
                 *   - a declared total we haven't reached, so we know bytes are
                 *     genuinely missing rather than this being real EOF;
                 *   - `!demuxerRange`, because on a byte-range slice BOTH of
                 *     those numbers mean something else: mStreamLength is the
                 *     whole resource's size while the body is one slice of it,
                 *     so a slice read to completion looks short and would trip
                 *     this on a perfectly good read — and mReadPosition is
                 *     slice-relative, so the Range we'd build is wrong anyway
                 *     (see the field);
                 *   - `seekable` and `!rangeRejected`, so neither the demuxer
                 *     nor the server has told us to stop ranging;
                 *   - `serverAcceptsRanges`, so the reopen's Range is likely to
                 *     be honoured rather than silently answered from byte 0;
                 *   - one attempt, so a server that keeps closing early fails
                 *     instead of looping.
                 * It used to be gated on the range-CHUNKING flag as well, which
                 * had nothing to do with any of that: chunking's own arming
                 * conditions (a >2 MB body, ranges supported) merely happened
                 * to overlap, so truncation recovery silently did not exist for
                 * anything chunking declined to arm for. */
                if (mStreamLength != Long.MAX_VALUE
                        && mReadPosition < mStreamLength
                        && !demuxerRange
                        && seekable
                        && !rangeRejected
                        && serverAcceptsRanges
                        && reconnectAttempts < MAX_RECONNECT_ATTEMPTS) {
                    if (BuildConfig.DEBUG) {
                        Log.d(TAG, "Early EOF at pos=" + mReadPosition
                                + ", reconnecting (total=" + mStreamLength + ")");
                    }
                    closeConnection();
                    int res = okhttpOpen(null);
                    if (res != FFMPEG_AVERROR_OK) {
                        return FFMPEG_AVERROR_EOF;
                    }
                    /* Verify the server actually honoured the Range. Without
                     * this, a server that ignores it answers 200 from byte 0
                     * and we would append the file's head to its middle —
                     * corruption that surfaces far downstream as a demuxer
                     * error, not as a transport failure. Better to end the
                     * stream short and let the caller see a truncated read. */
                    if (httpResponse == null
                            || httpResponse.code() != FFmpegConstants.HTTP_PARTIAL_CONTENT) {
                        Log.e(TAG, "Resume reopen was not answered with 206; aborting read");
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
            // (the seek reopen, server-side EOF reconnects), so
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

    /**
     * True when a reopen may carry a Range that lands exactly on a target.
     * Every reason it can be false is a reason a reopen restarts at byte 0.
     */
    private boolean canRange() {
        return !demuxerRange && seekable && !rangeRejected;
    }

    /**
     * Discard up to {@code count} bytes from the live body, advancing
     * mReadPosition by however many were actually skipped.
     *
     * Keep the EXACT partial-skip accounting — do not collapse this to a bare
     * bodySource.skip(count). okio's skip is all-or-throw: it discards the full
     * count or raises EOFException having already consumed an unknown number of
     * bytes, which would desync mReadPosition from the wire. Skipping only what
     * is already buffered avoids that: request(1) guarantees at least one byte
     * (or returns false at EOF) and skipping no more than buffer.size() cannot
     * throw, so every turn makes progress and no retry counter is needed —
     * unlike the InputStream form this replaced, where
     * RealBufferedSource.inputStream() does NOT override skip and so inherited
     * InputStream's allocate-read-discard default, which could return short.
     * This form is exact AND allocation-free.
     *
     * @return bytes skipped (short of {@code count} only at a genuine EOF), or
     *         FFMPEG_AVERROR_INTERRUPTED on a user cancel.
     */
    private long skipForward(long count) {
        long total = 0;
        try {
            while (total < count) {
                if (Thread.currentThread().isInterrupted()) {
                    mReadPosition += total;
                    return interruptedReturn();
                }
                if (bodySource == null || !bodySource.request(1)) {
                    break; // genuine EOF
                }
                long chunk = Math.min(count - total, bodySource.getBuffer().size());
                bodySource.skip(chunk);
                total += chunk;
            }
        } catch (IOException ignored) {
        }
        mReadPosition += total;
        return total;
    }

    /**
     * Move to targetPos, by whatever means actually gets there.
     *
     * The hard constraint is that we CANNOT report a position: avio_seek reads
     * only the SIGN of our return, then sets its own s->pos to the offset it
     * ASKED for (aviobuf.c — `if ((res = s->seek(...)) < 0) return res; ... 
     * s->pos = offset;`). So returning 0 after a Range-less reopen does not
     * tell ffmpeg we are at 0; it records targetPos and reads bytes that
     * actually start at 0. Only a NEGATIVE return says "I could not". Every
     * non-negative return therefore has to mean the stream really is at
     * targetPos.
     */
    private long performSeek(long targetPos) {
        long diff = targetPos - mReadPosition;
        if (diff == 0) {
            return mReadPosition;
        }

        boolean canRange = canRange();

        /* (1) Forward seek served by discarding on the LIVE connection. The
         * budget is small when we can range — a reopen is cheap and exact — and
         * large when we cannot, because discarding is then the only thing that
         * reaches the target at all, and doing it here avoids re-downloading
         * everything before mReadPosition. */
        long budget = canRange ? MAX_SKIP_SIZE : MAX_NO_RANGE_SKIP_SIZE;
        if (bodySource != null && diff > 0 && diff <= budget) {
            long skipped = skipForward(diff);
            if (skipped < 0) {
                return skipped; // interrupted
            }
            if (skipped == diff) {
                return mReadPosition;
            }
            // Short skip: the body ended early. Fall through to the reopen.
        }

        /* (2) A demuxer-owned byte range cannot be re-established by any reopen
         * we can make: this call passes no options, so offset/end_offset are
         * lost, and targetPos is slice-relative so no absolute Range is right
         * either. hls.c:1448 declines to seek http byte-range segments for the
         * same reason. Leave the connection up and report it honestly. */
        if (demuxerRange) {
            if (BuildConfig.DEBUG) {
                Log.d(TAG, "seek to " + targetPos + " on a demuxer byte range: unreachable");
            }
            return FFMPEG_AVERROR_ENOSYS;
        }

        okhttpClose();

        /* (3) Reopen. With ranging available, ask for targetPos directly. */
        if (canRange) {
            mReadPosition = targetPos;
            int res = okhttpOpen(null);
            if (res != FFMPEG_AVERROR_OK) {
                return FFMPEG_AVERROR_EOF;
            }
            if (mReadPosition == targetPos) {
                return mReadPosition; // the Range was honoured
            }
            /* mReadPosition moved out from under us: okhttpOpen's 416 branch
             * fired, reopened Range-less and reset us to byte 0. That is the
             * recovery ffmpeg itself will not do (vanilla http_seek_internal
             * restores the old connection and returns the error), but it lands
             * at 0, not at targetPos — so walk the rest of the way below. */
        } else {
            /* Ranging is off — the server 416'd one of ours, or the demuxer
             * asked us not to. Reopen at byte 0 and walk. */
            mReadPosition = 0;
            int res = okhttpOpen(null);
            if (res != FFMPEG_AVERROR_OK) {
                return FFMPEG_AVERROR_EOF;
            }
        }

        /* (4) Walk from byte 0 to the target by discarding. Bounded: this is
         * bandwidth we spend to reach an offset the server would not give us
         * directly, and a demuxer can seek repeatedly. Past the bound, fail
         * honestly rather than download an arbitrary prefix of the file. */
        long remaining = targetPos - mReadPosition;
        if (remaining < 0 || remaining > MAX_NO_RANGE_SKIP_SIZE) {
            if (BuildConfig.DEBUG) {
                Log.d(TAG, "seek to " + targetPos + " needs a " + remaining
                        + " byte walk from " + mReadPosition + "; over budget");
            }
            return FFMPEG_AVERROR_ENOSYS;
        }
        long skipped = skipForward(remaining);
        if (skipped < 0) {
            return skipped; // interrupted
        }
        if (skipped != remaining) {
            return FFMPEG_AVERROR_ENOSYS; // hit EOF before the target
        }
        return mReadPosition;
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
         * normal closes — seeks, early-EOF resume reopens, end-of-download —
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