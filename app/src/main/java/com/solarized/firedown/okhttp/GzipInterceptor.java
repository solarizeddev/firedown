package com.solarized.firedown.okhttp;


import android.util.Log;

import androidx.annotation.NonNull;

import com.solarized.firedown.utils.BrowserHeaders;

import java.io.IOException;
import java.util.Objects;

import okhttp3.Headers;
import okhttp3.Interceptor;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.GzipSource;
import okio.Okio;

public class GzipInterceptor implements Interceptor {

    private static final String TAG = GzipInterceptor.class.getName();

    @NonNull
    @Override
    public Response intercept(@NonNull Chain chain) throws IOException {
        Request original = chain.request();

        // Respect an explicit `Accept-Encoding: identity` from the caller.
        // FFmpegOkhttp.sanitizeHeaders deliberately preserves `identity` for
        // video streams where we want raw bytes; blindly replacing it with
        // `gzip` here silently defeats that contract. For normal (video) CDN
        // responses this is academic — servers don't gzip video/* — but for
        // any text/JSON sidecar (subtitle tracks, DASH manifests) the caller's
        // preference must win.
        String existing = original.header(BrowserHeaders.ACCEPT_ENCODING);
        if ("identity".equalsIgnoreCase(existing)) {
            return chain.proceed(original);
        }

        Request request = original.newBuilder()
                // Use header() (not addHeader) so we replace any existing value
                // rather than appending a duplicate Accept-Encoding header.
                .header(BrowserHeaders.ACCEPT_ENCODING, "gzip")
                .build();
        Response response = chain.proceed(request);
        if (isGzipped(response)) {
            return unzip(response);
        } else {
            return response;
        }
    }

    private Response unzip(final Response response) {
        ResponseBody body = response.body();
        if (body == null) {
            // 204/304/HEAD etc. — nothing to decompress.
            return response;
        }

        try {
            GzipSource gzipSource = new GzipSource(body.source());

            ResponseBody responseBody = ResponseBody.create(
                    Okio.buffer(gzipSource),
                    body.contentType(),
                    -1);

            Headers strippedHeaders = response.headers().newBuilder()
                    .removeAll(BrowserHeaders.CONTENT_ENCODING)
                    .removeAll(BrowserHeaders.CONTENT_LENGTH)
                    .build();

            return response.newBuilder()
                    .headers(strippedHeaders)
                    .body(responseBody)
                    .message(response.message())
                    .build();
        } catch (Exception e) {
            // Previous version only caught IllegalStateException, which isn't
            // what GzipSource throws. Catch broadly so a malformed encoding
            // header doesn't crash the whole call chain — fall back to the
            // raw response.
            Log.d(TAG, "unzip failed, returning raw response", e);
            return response;
        }
    }

    private boolean isGzipped(Response response) {
        String enc = response.header(BrowserHeaders.CONTENT_ENCODING);
        return enc != null && Objects.equals(enc, "gzip");
    }
}