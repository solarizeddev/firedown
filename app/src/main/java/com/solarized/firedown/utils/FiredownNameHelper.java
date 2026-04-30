package com.solarized.firedown.utils;

import android.text.TextUtils;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Pattern;

/**
 * Builds clean, human-readable filenames for assets whose source URL
 * carries no usable name (empty paths, opaque numeric IDs).
 *
 * Format: [Firedown]_YYYYMMDD_brand_NNNNNN.ext
 * Example: [Firedown]_20260429_primal_001449.jpg
 *
 * The {@code brand} segment is derived from the <b>origin URL</b> — the page
 * the user was actually on when the download was captured — not from the
 * file URL's CDN host. Origin gives us the user-facing brand for free
 * ({@code primal.net}, {@code twitter.com}, {@code instagram.com}) without
 * any mapping table to maintain.
 *
 * Used as a fallback when no parser-supplied name (Twitter screenName,
 * Vimeo title, etc.) is available and the URL-derived name is empty or
 * a pure numeric ID.
 */
public final class FiredownNameHelper {

    private static final String PREFIX = "[Firedown]";

    // Pure numeric IDs (Twitter media IDs, Instagram IDs, etc.) — unique
    // but tell the user nothing about the source. Hex content hashes are
    // intentionally NOT matched: they're stable identifiers and more useful
    // for the user's own dedupe than a randomized rename.
    private static final Pattern NUMERIC_ID = Pattern.compile("^[0-9]{8,}$");

    private FiredownNameHelper() {}

    // ========================================================================
    // Public API
    // ========================================================================

    /**
     * Returns true when {@code fileName} should be replaced with a generated
     * name. Triggers on:
     * <ul>
     *   <li><b>Empty / null</b> — no usable name from the URL at all</li>
     *   <li><b>Pure numeric IDs</b> — e.g. {@code 1776676209.jpg} from twimg,
     *       unique but opaque; prefixing with the source brand makes them
     *       useful</li>
     * </ul>
     *
     * Hex content hashes (e.g. {@code cdfb8b6e17...158e.jpg}) are NOT matched.
     * They're stable identifiers — useful for dedupe and matching files back
     * to source URLs — and replacing them with a randomized name destroys that.
     */
    public static boolean looksLikeHash(String fileName) {
        if (TextUtils.isEmpty(fileName)) return true;
        int dot = fileName.lastIndexOf('.');
        String stem = (dot > 0) ? fileName.substring(0, dot) : fileName;
        return NUMERIC_ID.matcher(stem).matches();
    }

    /**
     * Builds a Firedown-formatted filename. The brand is taken from the
     * origin URL (the page the user was on); when origin is unavailable
     * we fall back to the file URL's host. The extension is taken from
     * the file URL.
     *
     * @param fileUrl   the asset URL being downloaded (used for extension
     *                  and as a brand fallback)
     * @param originUrl the page the user was on when this was captured;
     *                  primary source of the brand. May be null.
     * @return a name like {@code [Firedown]_20260429_primal_001449.jpg}
     */
    public static String buildName(String fileUrl, String originUrl) {
        String brand = extractBrand(originUrl);
        if ("unknown".equals(brand)) {
            // Origin missing or unparseable — fall back to file URL's host.
            // This catches direct downloads where there was no referring page.
            brand = extractBrand(fileUrl);
        }

        String date = today();
        String id = randomId();
        String ext = extractExtension(fileUrl);

        StringBuilder sb = new StringBuilder(48);
        sb.append(PREFIX).append('_')
          .append(date).append('_')
          .append(brand).append('_')
          .append(id);
        if (!TextUtils.isEmpty(ext)) {
            sb.append('.').append(ext);
        }
        return sb.toString();
    }

    /**
     * Convenience: rename only if {@code currentName} looks like a hash.
     * Returns {@code currentName} unchanged otherwise.
     */
    public static String maybeRename(String currentName, String fileUrl, String originUrl) {
        if (looksLikeHash(currentName)) {
            return buildName(fileUrl, originUrl);
        }
        return currentName;
    }

    // ========================================================================
    // Helpers
    // ========================================================================

    /**
     * Extracts a short brand identifier from a URL's host: lowercased,
     * leading {@code www.} stripped, second-to-last domain segment.
     *
     * Examples:
     *   https://primal.net/...              → primal
     *   https://www.twitter.com/...         → twitter
     *   https://r2.primal.net/...           → primal      (CDN, used as fallback)
     *   https://pbs.twimg.com/...           → twimg       (CDN, but only hit when
     *                                                      origin was missing — in
     *                                                      practice origin will be
     *                                                      twitter.com or x.com)
     *   localhost / null / ""               → unknown
     *
     * Edge case: {@code example.co.uk} → {@code co}. Rare for the URLs
     * Firedown actually downloads from. If it becomes a real problem the
     * correct fix is a public-suffix list, not a hardcoded alias table.
     */
    static String extractBrand(String url) {
        if (TextUtils.isEmpty(url)) return "unknown";

        String host;
        try {
            host = new java.net.URL(url).getHost();
        } catch (Exception e) {
            return "unknown";
        }
        if (TextUtils.isEmpty(host)) return "unknown";

        host = host.toLowerCase(Locale.ROOT);
        if (host.startsWith("www.")) host = host.substring(4);

        String[] parts = host.split("\\.");
        if (parts.length >= 2) return parts[parts.length - 2];
        if (parts.length == 1) return parts[0];
        return "unknown";
    }

    /**
     * Returns the extension from a URL's path, lowercased, without the dot.
     * Returns empty string if no extension or if it doesn't look real
     * (length outside 2-5, non-alphanumeric).
     */
    static String extractExtension(String url) {
        if (TextUtils.isEmpty(url)) return "";
        try {
            String path = new java.net.URL(url).getPath();
            int slash = path.lastIndexOf('/');
            String last = (slash >= 0) ? path.substring(slash + 1) : path;
            int dot = last.lastIndexOf('.');
            if (dot < 0 || dot == last.length() - 1) return "";

            String ext = last.substring(dot + 1).toLowerCase(Locale.ROOT);
            if (ext.length() < 2 || ext.length() > 5) return "";
            for (int i = 0; i < ext.length(); i++) {
                if (!Character.isLetterOrDigit(ext.charAt(i))) return "";
            }
            return ext;
        } catch (Exception e) {
            return "";
        }
    }

    /** {@code yyyyMMdd}; SimpleDateFormat is not thread-safe so we instantiate per call. */
    private static String today() {
        return new SimpleDateFormat("yyyyMMdd", Locale.ROOT).format(new Date());
    }

    /**
     * 6-digit random ID, zero-padded. Stateless; collisions within the same
     * day+brand are 1-in-a-million per pair and the file system already
     * de-duplicates with numeric suffixes.
     */
    private static String randomId() {
        int n = ThreadLocalRandom.current().nextInt(0, 1_000_000);
        return String.format(Locale.ROOT, "%06d", n);
    }
}
