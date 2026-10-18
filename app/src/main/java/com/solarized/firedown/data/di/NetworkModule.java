package com.solarized.firedown.data.di;

import android.content.SharedPreferences;

import com.solarized.firedown.okhttp.DohDns;
import com.solarized.firedown.okhttp.GzipInterceptor;
import com.solarized.firedown.okhttp.OriginInterceptor;
import com.solarized.firedown.okhttp.RateLimitInterceptor;
import com.solarized.firedown.okhttp.TSInterceptor;
import com.solarized.firedown.okhttp.UserAgentInterceptor;
import com.solarized.firedown.utils.BrowserHeaders;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import javax.inject.Singleton;

import dagger.Module;
import dagger.Provides;
import dagger.hilt.InstallIn;
import dagger.hilt.components.SingletonComponent;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;

@Module
@InstallIn(SingletonComponent.class)
public class NetworkModule {

    /**
     * Static reference kept for JNI / static access from FFmpegOkhttp.
     *
     * NOTE: This is a pragmatic escape hatch — DI is the right answer for
     * everything else, but native code called from FFmpeg threads has no
     * Hilt entry point available. The field is volatile so the assignment
     * in {@link #provideOkHttpClient()} publishes safely to other threads.
     * OkHttpClient itself is thread-safe, so reading the reference and
     * using it concurrently is safe.
     */
    public static volatile OkHttpClient globalClient;

    @Provides
    @Singleton
    public OkHttpClient provideOkHttpClient(SharedPreferences prefs) {

        List<Protocol> protocols = new ArrayList<>();
        protocols.add(Protocol.HTTP_1_1);
        protocols.add(Protocol.HTTP_2);

        // Bootstrap client for DoH: a vanilla client (system DNS, none of
        // the media interceptors) used solely to reach the DoH endpoint and
        // to resolve its own host. Kept separate from the main client below
        // so DohDns never recurses into itself.
        UserAgentInterceptor userAgent =
                new UserAgentInterceptor(BrowserHeaders.getDefaultUserAgentString());
        OkHttpClient bootstrapClient = new OkHttpClient.Builder()
                .addInterceptor(userAgent)
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .build();

        // Mirror the browser's DoH setting on the app's own networking:
        // when the DoH toggle is on, resolve through the configured server
        // and fall back to system DNS on error; when off, straight
        // system-DNS pass-through (no added latency). See DohDns.
        DohDns dns = new DohDns(prefs, bootstrapClient);

        /*
         * Interceptor order matters. On requests, interceptors run top-down;
         * on responses, bottom-up. We want:
         *
         *   Request  : UserAgent → Origin → Gzip → TS → RateLimit → network
         *   Response : network → RateLimit → TS → Gzip → Origin → UserAgent
         *
         * Rationale:
         *   - UserAgentInterceptor fills a missing User-Agent with the app's
         *     Firefox one, so no request goes out as OkHttp's "okhttp/x.y".
         *   - OriginInterceptor shapes outgoing request headers (outermost).
         *   - GzipInterceptor handles transparent compression next so TS
         *     sees the *decoded* body.
         *   - TSInterceptor transforms PNG-disguised TS payloads after gzip
         *     has been undone.
         *   - RateLimitInterceptor is innermost so it retries the fully
         *     prepared request and only fires once per physical call.
         */
        OkHttpClient client = new OkHttpClient.Builder()
                .dns(dns)
                .addInterceptor(userAgent)
                .addInterceptor(new OriginInterceptor())
                .addInterceptor(new GzipInterceptor())
                .addInterceptor(new TSInterceptor())
                .addInterceptor(new RateLimitInterceptor())
                .retryOnConnectionFailure(true)
                .protocols(protocols)
                // Tuned for video streaming: generous read timeout so slow
                // segments don't abort mid-download; no overall call timeout
                // so long downloads can complete.
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .callTimeout(0, TimeUnit.MILLISECONDS)
                .build();
        globalClient = client;
        return client;
    }

    public static OkHttpClient requireClient() {
        OkHttpClient c = globalClient;
        if (c == null) {
            throw new IllegalStateException(
                    "OkHttpClient not initialized — @HiltAndroidApp application not started?");
        }
        return c;
    }

    /**
     * Close every idle pooled connection NOW instead of after the pool's
     * 5-minute keep-alive.
     *
     * Why this exists: cancelling a download mid-stream only closes the
     * response body, which on HTTP/2 resets the *stream* (RST_STREAM) but
     * keeps the pooled *connection* open. A server that keeps pushing data
     * for the dead stream — a live-stream CDN is the worst case — then traps
     * OkHttp in a discard loop: one received DATA frame → one queued
     * writeSynReset task → connection-level window replenished → server
     * sends more, thousands of times per second, until the pool's idle
     * eviction finally sends GOAWAY five minutes later. Observed on-device
     * as a cancelled Kick live capture saturating two TaskRunner threads,
     * GC, and bandwidth for 312 s ("finished run in 312 s : OkHttp
     * stream.kick.com"), starving Gecko page loads.
     *
     * evictAll() closes only IDLE connections, so callers must invoke it
     * AFTER releasing their body/response (the cancelled connection becomes
     * idle at that point). Active downloads on other connections are
     * untouched; the only cost is a fresh TCP+TLS handshake for the next
     * request to a previously-pooled host — negligible against a
     * user-initiated cancel. Call this only on the cancel/stop path, never
     * on normal completion or chunk reconnects.
     */
    public static void evictIdleConnections() {
        OkHttpClient c = globalClient;
        if (c == null) {
            return;
        }
        c.connectionPool().evictAll();
    }
}