package com.solarized.firedown.okhttp;

import androidx.annotation.NonNull;

import java.io.IOException;

import okhttp3.Interceptor;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Gives a request that carries no User-Agent the app's own: the Firefox one
 * built from the bundled GeckoView (BrowserHeaders.getDefaultUserAgentString).
 *
 * Without it OkHttp fills the header itself with "okhttp/<version>", which is
 * what every Glide thumbnail, master-playlist fetch, Deezer/Mega API call and
 * first-party request sent: a library string no browser sends, and one some
 * CDNs refuse on sight. A request that already names a User-Agent keeps it
 * (the page's captured headers, YouTube's client UA in SabrDownloader), so
 * this only replaces OkHttp's fallback. An application interceptor runs
 * before OkHttp's BridgeInterceptor, which adds its default only when the
 * header is missing.
 *
 * The string is passed in rather than read here so a client built without the
 * app's other interceptors (WebDavClient) can keep this one alone.
 */
public class UserAgentInterceptor implements Interceptor {

    private static final String USER_AGENT = "User-Agent";

    private final String mUserAgent;

    public UserAgentInterceptor(@NonNull String userAgent) {
        mUserAgent = userAgent;
    }

    @NonNull
    @Override
    public Response intercept(@NonNull Interceptor.Chain chain) throws IOException {
        Request request = chain.request();
        if (request.header(USER_AGENT) != null) {
            return chain.proceed(request);
        }
        Request withAgent = request.newBuilder()
                .header(USER_AGENT, mUserAgent)
                .build();
        return chain.proceed(withAgent);
    }
}
