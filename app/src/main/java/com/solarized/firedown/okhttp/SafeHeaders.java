package com.solarized.firedown.okhttp;

import android.util.Log;

import androidx.annotation.NonNull;

import java.util.Map;

import okhttp3.Headers;

/**
 * Drop-in replacement for {@link Headers#of(Map)} that never throws.
 *
 * <p>okhttp's {@code Headers.of} enforces RFC 7230 token grammar on names
 * and a printable-ASCII (plus TAB) rule on values; any violation raises
 * {@link IllegalArgumentException}. Header data flowing through this app
 * arrives from three places we don't fully control:
 *
 * <ul>
 *   <li>the GeckoView extension capture (web pages can return arbitrary
 *       response header bytes — non-ASCII filenames, stray CR/LF from a
 *       buggy origin),</li>
 *   <li>persisted {@code BrowserDownloadEntity.fileHeaders} that were
 *       URL-encoded then decoded back into a map (encoding round-trips
 *       have hit malformed entries before), and</li>
 *   <li>the ffmpeg native side, which serialises its option dict into a
 *       string and parses it back ({@link com.solarized.firedown.ffmpegutils.FFmpegOkhttp}).</li>
 * </ul>
 *
 * <p>A single bad entry would otherwise kill the whole download (the
 * exception escapes the strategy's IO catch and propagates as an
 * uncaught error on the executor thread). Here we strip invalid bytes
 * from values, skip entries whose name can't be repaired, and log each
 * drop so the underlying source can be tightened later.
 */
public final class SafeHeaders {

    private static final String TAG = "SafeHeaders";

    private SafeHeaders() {}

    public static Headers of(@NonNull Map<String, String> headers) {
        Headers.Builder builder = new Headers.Builder();
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            String name = entry.getKey();
            String value = entry.getValue();
            if (name == null || value == null) continue;

            if (!isValidName(name)) {
                Log.w(TAG, "skip header with invalid name: " + name);
                continue;
            }

            String safeValue = sanitizeValue(value);

            try {
                builder.add(name, safeValue);
            } catch (IllegalArgumentException ex) {
                // Belt and braces — okhttp may still reject something
                // sanitizeValue let through (e.g. unicode bidi controls in
                // 0x80-0xff that aren't strictly forbidden by RFC 7230 but
                // are rejected by okhttp's stricter check). Drop the entry
                // rather than tearing down the request.
                Log.w(TAG, "skip header rejected by okhttp: " + name, ex);
            }
        }
        return builder.build();
    }

    /**
     * RFC 7230 token: 1+ chars from
     * {@code !#$%&'*+-.^_`|~0-9A-Za-z}. okhttp accepts the same set;
     * anything else (whitespace, control chars, : etc.) is rejected.
     */
    private static boolean isValidName(String name) {
        if (name.isEmpty()) return false;
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c <= 0x20 || c >= 0x7f) return false;
            if (c == ':' || c == '(' || c == ')' || c == ',' || c == '/'
                    || c == ';' || c == '<' || c == '=' || c == '>'
                    || c == '?' || c == '@' || c == '[' || c == '\\'
                    || c == ']' || c == '{' || c == '}' || c == '"') {
                return false;
            }
        }
        return true;
    }

    /**
     * Strip everything okhttp's {@code Headers#checkValue} rejects:
     * control chars below 0x20 except TAB, and DEL (0x7f). Non-ASCII
     * bytes are left in place — okhttp tolerates them on the JVM, and
     * stripping would mangle e.g. RFC 8187 filenames that the server
     * deliberately sent in UTF-8.
     */
    private static String sanitizeValue(String value) {
        StringBuilder sb = null;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            boolean ok = (c == '\t') || (c >= 0x20 && c != 0x7f);
            if (!ok) {
                if (sb == null) {
                    sb = new StringBuilder(value.length());
                    sb.append(value, 0, i);
                }
                continue;
            }
            if (sb != null) sb.append(c);
        }
        return sb == null ? value : sb.toString();
    }
}
