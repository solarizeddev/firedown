package com.solarized.firedown.sync;

import androidx.annotation.NonNull;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.util.concurrent.TimeUnit;

import okhttp3.Interceptor;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.Buffer;
import okio.ForwardingSource;
import okio.Okio;
import okio.Source;

/**
 * Fails a response body that stops making progress. OkHttp's read timeout
 * is per READ, so a server that sends one byte every few seconds holds a
 * call open for as long as it likes: a listing, a sidecar, a ranged chunk,
 * a restore stream, an error body, the answer to an upload. The backup
 * worker, its notification and the Backups list would wait with it, and a
 * cancel cannot reach a thread blocked in a read.
 *
 * <p>Every response of the two remote stores goes through
 * {@link #INTERCEPTOR} ({@link ServerTrust#newBuilder} installs it): its body
 * must deliver {@link #MIN_BYTES} within each {@link #windowMs} of waiting on
 * the server (16 KB in two minutes, about 140 bytes a second, far below any
 * link that works), or the next read throws {@link SocketTimeoutException},
 * which every caller already treats as a transient failure. Only time spent
 * INSIDE a read counts, so a reader that pauses between reads (a slow disk, a
 * process the system froze) is never taken for a slow server. A body smaller
 * than that only has to finish within the window. A server that sends nothing
 * at all is still the read timeout's; uploads are bounded by the write
 * timeout, since a write blocks until the server drains it.
 */
final class StallGuard {

    static final long MIN_BYTES = 16 * 1024;
    /** The progress window. A harness shortens it. */
    static volatile long windowMs = 120_000;

    static final Interceptor INTERCEPTOR = chain -> {
        Response response = chain.proceed(chain.request());
        ResponseBody body = response.body();
        ResponseBody guarded = ResponseBody.create(Okio.buffer(new Guarded(body.source())),
                body.contentType(), body.contentLength());
        return response.newBuilder().body(guarded).build();
    };

    private StallGuard() {
    }

    private static final class Guarded extends ForwardingSource {
        private final long window = TimeUnit.MILLISECONDS.toNanos(windowMs);
        /** Time spent waiting on the server since the last MIN_BYTES. */
        private long waited;
        private long since;

        Guarded(@NonNull Source delegate) {
            super(delegate);
        }

        @Override
        public long read(@NonNull Buffer sink, long byteCount) throws IOException {
            long start = System.nanoTime();
            long n = super.read(sink, byteCount);
            if (n > 0) {
                since += n;
                waited += System.nanoTime() - start;
                if (since >= MIN_BYTES) {
                    since = 0;
                    waited = 0;
                } else if (waited > window) {
                    throw new SocketTimeoutException("server too slow: under " + MIN_BYTES
                            + " bytes in " + TimeUnit.NANOSECONDS.toSeconds(window) + " s");
                }
            }
            return n;
        }
    }
}
