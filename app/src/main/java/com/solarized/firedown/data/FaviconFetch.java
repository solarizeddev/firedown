package com.solarized.firedown.data;

import android.text.TextUtils;

import androidx.annotation.Nullable;

import com.solarized.firedown.utils.BrowserHeaders;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * The one favicon network request, shared by the Glide favicon loader (a store
 * miss) and the visit refresh ({@code IconsRepository}). It asks like a browser
 * would: the page that declared the icon as Referer (a hotlink-gated CDN 403s a
 * bare request) and an image Accept. The body read is bounded by
 * {@link FaviconStore#MAX_ICON_BYTES} while it streams, never buffered first
 * and measured after.
 */
public final class FaviconFetch {

    private FaviconFetch() {
    }

    /** True for an icon this fetch can request (http/https). */
    public static boolean isFetchable(@Nullable String iconUrl) {
        return iconUrl != null
                && (iconUrl.regionMatches(true, 0, "https://", 0, 8)
                || iconUrl.regionMatches(true, 0, "http://", 0, 7));
    }

    /** The request for an icon; throws IllegalArgumentException for a URL OkHttp
     *  can't parse (callers gate on {@link #isFetchable} and catch). */
    public static Request request(String iconUrl, @Nullable String pageUrl) {
        Request.Builder builder = new Request.Builder()
                .url(iconUrl)
                .header(BrowserHeaders.USER_AGENT, BrowserHeaders.getDefaultUserAgentString())
                .header(BrowserHeaders.ACCEPT_LANGUAGE, "en-US,en;q=0.5")
                .header(BrowserHeaders.ACCEPT,
                        "image/avif,image/webp,image/png,image/svg+xml,image/*,*/*;q=0.8");
        if (!TextUtils.isEmpty(pageUrl)) {
            builder.header(BrowserHeaders.REFERER, pageUrl);
        }
        return builder.build();
    }

    /** The body of a successful response, or null when the response failed or
     *  the body exceeds {@link FaviconStore#MAX_ICON_BYTES}. Does not close the
     *  response. */
    @Nullable
    public static byte[] readBounded(Response response) throws IOException {
        if (!response.isSuccessful()) {
            return null;
        }
        ResponseBody body = response.body();
        if (body == null) {
            return null;
        }
        if (body.contentLength() > FaviconStore.MAX_ICON_BYTES) {
            return null;
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        try (InputStream in = body.byteStream()) {
            int read = in.read(buffer);
            while (read != -1) {
                if (out.size() + read > FaviconStore.MAX_ICON_BYTES) {
                    return null;
                }
                out.write(buffer, 0, read);
                read = in.read(buffer);
            }
        }
        return out.toByteArray();
    }
}
