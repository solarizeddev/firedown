package com.solarized.firedown.okhttp;

import android.util.Log;

import androidx.annotation.NonNull;

import com.solarized.firedown.ffmpegutils.FFmpegConstants;

import java.io.IOException;

import okhttp3.Interceptor;
import okhttp3.Response;

public class RateLimitInterceptor implements Interceptor {

    private static final String TAG = RateLimitInterceptor.class.getSimpleName();

    private static final int MAX_TRY_COUNT = 3;
    private static final long RETRY_BACKOFF_DELAY = 1500L;
    /** Upper bound on how long we'll honor a Retry-After value (seconds). */
    private static final long MAX_RETRY_AFTER_MS = 30_000L;

    @NonNull
    @Override
    public Response intercept(@NonNull Interceptor.Chain chain) throws IOException {

        int tryCount = 1;
        Response response = chain.proceed(chain.request());
        int statusCode = response.code();

        while (tryCount <= MAX_TRY_COUNT
                && !response.isSuccessful()
                && statusCode == FFmpegConstants.HTTP_RATE_LIMITING) {

            Log.d(TAG, "Rate limited: " + response.message()
                    + " url: " + chain.request().url()
                    + " attempt: " + tryCount);

            long delay = computeBackoff(response, tryCount);

            // Close the rate-limited response BEFORE sleeping so the
            // underlying connection can return to the pool.
            response.close();

            try {
                Thread.sleep(delay);
            } catch (InterruptedException e) {
                // Restore the interrupt flag so callers higher up can detect
                // cancellation. Swallowing it silently breaks cooperative
                // cancellation of downloads.
                Thread.currentThread().interrupt();
                Log.d(TAG, "Rate limiter interrupted; aborting retries");
                // Re-issue as an IOException so OkHttp treats this as a
                // normal cancelled call rather than returning a closed body.
                throw new IOException("Interrupted while waiting for rate-limit backoff", e);
            }

            response = chain.proceed(chain.request());
            // CRITICAL: refresh statusCode each iteration. The previous code
            // read it once before the loop, so a retry that returned, say,
            // 500 would keep looping as if it were still 429.
            statusCode = response.code();
            tryCount++;
        }

        return response;
    }

    /**
     * Compute a backoff delay. Prefer the server's {@code Retry-After} hint
     * if present, otherwise fall back to linear backoff.
     */
    private long computeBackoff(Response response, int tryCount) {
        String retryAfter = response.header("Retry-After");
        if (retryAfter != null && !retryAfter.isEmpty()) {
            try {
                long seconds = Long.parseLong(retryAfter.trim());
                if (seconds > 0) {
                    return Math.min(seconds * 1000L, MAX_RETRY_AFTER_MS);
                }
            } catch (NumberFormatException ignored) {
                // Retry-After may also be an HTTP-date; we don't parse those
                // here. Fall through to linear backoff.
            }
        }
        return RETRY_BACKOFF_DELAY * tryCount;
    }
}