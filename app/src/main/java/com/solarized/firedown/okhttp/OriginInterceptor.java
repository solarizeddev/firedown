package com.solarized.firedown.okhttp;

import android.text.TextUtils;

import androidx.annotation.NonNull;

import com.solarized.firedown.utils.BrowserHeaders;

import java.io.IOException;

import okhttp3.HttpUrl;
import okhttp3.Interceptor;
import okhttp3.Request;
import okhttp3.Response;

public class OriginInterceptor implements Interceptor {

    @NonNull
    @Override
    public Response intercept(@NonNull Interceptor.Chain chain) throws IOException {

        Request request = chain.request();

        // Some servers require an Origin header that matches (or accompanies)
        // the Referer. Only inject one when:
        //   - the request already has a Referer,
        //   - no Origin was set by the caller,
        //   - the Sec-Fetch-Site metadata says same-site (i.e., this is a
        //     request the browser would've sent Origin on).
        if (BrowserHeaders.hasHeader(request, BrowserHeaders.REFERER)
                && !BrowserHeaders.hasHeader(request, BrowserHeaders.ORIGIN)
                && BrowserHeaders.isSecSameSite(request)) {

            String referer = request.header(BrowserHeaders.REFERER);
            if (!TextUtils.isEmpty(referer)) {
                String origin = deriveOrigin(referer);
                if (origin != null) {
                    Request newRequest = request.newBuilder()
                            .addHeader(BrowserHeaders.ORIGIN, origin)
                            .build();
                    return chain.proceed(newRequest);
                }
            }
        }

        return chain.proceed(request);
    }

    /**
     * Derive a valid Origin value from a Referer URL.
     *
     * Per RFC 6454, an Origin is just scheme://host[:port] — never the path
     * or query. Passing the full referer (as the previous implementation did)
     * makes servers that validate Origin strictly reject the request.
     *
     * @return origin string like {@code "https://example.com"}, or {@code null}
     *         if the referer can't be parsed as an HTTP(S) URL.
     */
    private static String deriveOrigin(String referer) {
        HttpUrl url = HttpUrl.parse(referer);
        if (url == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder(referer.length());
        sb.append(url.scheme()).append("://").append(url.host());
        int port = url.port();
        int defaultPort = HttpUrl.defaultPort(url.scheme());
        if (port != defaultPort) {
            sb.append(':').append(port);
        }
        return sb.toString();
    }
}