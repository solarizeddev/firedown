package com.solarized.firedown.data.di;

import com.solarized.firedown.okhttp.GzipInterceptor;
import com.solarized.firedown.okhttp.OriginInterceptor;
import com.solarized.firedown.okhttp.RateLimitInterceptor;
import com.solarized.firedown.okhttp.TSInterceptor;

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
    public OkHttpClient provideOkHttpClient() {

        List<Protocol> protocols = new ArrayList<>();
        protocols.add(Protocol.HTTP_1_1);
        protocols.add(Protocol.HTTP_2);

        /*
         * Interceptor order matters. On requests, interceptors run top-down;
         * on responses, bottom-up. We want:
         *
         *   Request  : Origin → Gzip → TS → RateLimit → network
         *   Response : network → RateLimit → TS → Gzip → Origin
         *
         * Rationale:
         *   - OriginInterceptor shapes outgoing request headers (outermost).
         *   - GzipInterceptor handles transparent compression next so TS
         *     sees the *decoded* body.
         *   - TSInterceptor transforms PNG-disguised TS payloads after gzip
         *     has been undone.
         *   - RateLimitInterceptor is innermost so it retries the fully
         *     prepared request and only fires once per physical call.
         */
        OkHttpClient client = new OkHttpClient.Builder()
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
}