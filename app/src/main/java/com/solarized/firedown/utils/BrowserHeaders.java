package com.solarized.firedown.utils;


import org.mozilla.geckoview.BuildConfig;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

import okhttp3.HttpUrl;
import okhttp3.Request;

public class BrowserHeaders {

    private static final String TAG = BrowserHeaders.class.getSimpleName();


    /**
     * Desktop-Firefox shape on purpose — it carries no Android version or
     * device model, so the media/update requests that send it leak nothing
     * device-identifying. The version is derived from the bundled GeckoView
     * (MOZ_APP_VERSION, e.g. "151.0") so the rv:/Firefox pair always matches
     * the real engine and can't drift: the previous hardcoded string had
     * aged into "rv:122.0 … Firefox/149.0", a mismatched pair no real
     * browser ever sends — itself a bot tell to header-checking CDNs.
     */
    private static final String USER_AGENT_STRING = buildDefaultUserAgentString();

    private static String buildDefaultUserAgentString() {
        String version = BuildConfig.MOZ_APP_VERSION;
        int dot = version.indexOf('.');
        String major = (dot > 0) ? version.substring(0, dot) : version;
        return "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:" + major + ".0)"
                + " Gecko/20100101 Firefox/" + major + ".0";
    }

    public static final String ACCEPT = "Accept";

    public static final String ACCEPT_ENCODING = "Accept-Encoding";

    public static final String ACCEPT_LANGUAGE = "Accept-Language";
    public static final String ACCEPT_RANGES = "Accept-Ranges";

    public static final String RANGES = "Range";

    public static final String COOKIE = "Cookie";

    public static final String LOCATION = "Location";

    public static final String CONNECTION = "Connection";

    public static final String CONTENT_ENCODING = "Content-Encoding";

    public static final String CONTENT_LENGTH = "Content-Length";

    public static final String CONTENT_DISPOSITION = "Content-Disposition";

    public static final String CONTENT_TYPE = "Content-Type";

    public static final String HOST = "Host";

    public static final String REFERER = "Referer";

    public static final String ORIGIN = "Origin";

    public static final String USER_AGENT = "User-Agent";

    public static final String X_REQUEST_ID = "X-Request-ID";

    public static final String X_APP_VERSION = "X-App-Version-Code";
    public static final String SEC_FETCH_SITE = "Sec-Fetch-Site";


    public static boolean isSecSameSite(Request request){
        return Objects.equals(request.header(BrowserHeaders.SEC_FETCH_SITE), "same-origin");
    }

    /**
     * Build a serialized header string (the {@code "key=value&…"} form
     * {@link com.solarized.firedown.manager.DownloadRequest} carries and
     * {@code DownloadContext} parses back via {@code Utils.stringToMap}) that
     * carries just a {@code Referer}, trimmed to {@code pageUrl}'s ORIGIN —
     * {@code https://host[:port]}, no path/query.
     *
     * <p>This is what a browser sends cross-origin under
     * {@code strict-origin-when-cross-origin}, and it's what a native
     * re-download of a page image must reproduce: hotlink-protecting CDNs
     * (pixiv's {@code i.pximg.net} is the canonical case) 403 a bare GET with
     * no Referer. Origin-only (not the full page URL) both satisfies the CDN
     * and avoids leaking the visited path to it. {@code OriginInterceptor}
     * will NOT promote this to an {@code Origin} header (no
     * {@code Sec-Fetch-Site: same-origin} is present), matching the browser's
     * Referer-only image GET.
     *
     * @return the encoded header string, or {@code ""} when {@code pageUrl}
     *         isn't a parseable http(s) URL (nothing to add).
     */
    public static String refererOriginHeaders(String pageUrl) {
        if (pageUrl == null || pageUrl.isEmpty()) {
            return "";
        }
        HttpUrl url = HttpUrl.parse(pageUrl);
        if (url == null) {
            return "";
        }
        StringBuilder origin = new StringBuilder(pageUrl.length());
        origin.append(url.scheme()).append("://").append(url.host());
        int port = url.port();
        if (port != HttpUrl.defaultPort(url.scheme())) {
            origin.append(':').append(port);
        }
        // Trailing slash: a browser trimming a cross-site Referer to origin
        // sends "https://host/" (with the slash), so match it byte-for-byte.
        origin.append('/');
        Map<String, String> headers = new HashMap<>(1);
        headers.put(REFERER, origin.toString());
        return Utils.mapToString(headers);
    }

    public static boolean hasHeader(Request request, String value){
        return request.header(value) != null;
    }

    public static String getDefaultUserAgentString() {
            return USER_AGENT_STRING;
    }


}