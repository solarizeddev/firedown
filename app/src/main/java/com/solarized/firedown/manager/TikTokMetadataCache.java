package com.solarized.firedown.manager;

import androidx.annotation.Nullable;

import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Process-wide cache that lets the parser extension and the generic
 * webrequests content-script collaborate on TikTok captures.
 *
 * <p>The TikTok scrape problem is split across two browser-side events:
 * <ul>
 *   <li>The parser sees the {@code /api/*list*/} JSON response and
 *       knows the human metadata for every video on the page
 *       (caption, author, thumbnail, duration), plus every variant URL
 *       from {@code bitrateInfo[]} — but URLs it surfaces to native
 *       downloads return 403, because they were minted in the JSON-
 *       fetch context and not in the video-fetch context.</li>
 *   <li>The generic webrequests script sees the *real* video fetch the
 *       page just performed (the one that returned HTTP 200 and played
 *       in the browser), so the URL is known-good for replay — but the
 *       message it forwards has no metadata, since TikTok's SPA exposes
 *       no per-video og:title.</li>
 * </ul>
 *
 * <p>This cache bridges the two. The parser handler ships a
 * {@code tiktok-meta-cache} native message that calls {@link #put} for
 * every variant URL. When the webrequests message later arrives for the
 * URL the user actually fetched, {@link com.solarized.firedown.utils
 * .JsonHelper} (or downstream code) calls {@link #get} and stamps the
 * cached caption / author / thumbnail / duration onto the entity before
 * it goes to {@link GeckoInspectTask}.</p>
 *
 * <p>Keyed on the URL with the query string stripped — TikTok's signed
 * playAddrs change their {@code signature}/{@code expire}/{@code l}
 * params per request but the path component is stable across the JSON
 * variants and the actual fetch.</p>
 */
public final class TikTokMetadataCache {

    /** 30-minute TTL. The signed URLs themselves expire ~1–2h; this
     *  is shorter so a stale entry doesn't outlive the playback
     *  authorization. Re-evicted on each {@link #put}. */
    private static final long TTL_MS = 30L * 60L * 1000L;

    private static final Map<String, Entry> CACHE = new ConcurrentHashMap<>();

    private TikTokMetadataCache() {}

    public static final class Entry {
        public final String name;
        public final String description;
        public final String img;
        public final String origin;
        public final long durationMs;
        final long cachedAt;

        public Entry(String name, String description, String img,
                     String origin, long durationMs) {
            this.name = name;
            this.description = description;
            this.img = img;
            this.origin = origin;
            this.durationMs = durationMs;
            this.cachedAt = System.currentTimeMillis();
        }
    }

    public static void put(String url, Entry entry) {
        if (url == null || entry == null) return;
        CACHE.put(stripQuery(url), entry);
        evictExpired();
    }

    @Nullable
    public static Entry get(String url) {
        if (url == null) return null;
        Entry e = CACHE.get(stripQuery(url));
        if (e == null) return null;
        if (System.currentTimeMillis() - e.cachedAt > TTL_MS) {
            CACHE.remove(stripQuery(url));
            return null;
        }
        return e;
    }

    /** True if the URL belongs to a TikTok video CDN we might have
     *  metadata for. Cheap pre-check to avoid a hash lookup on every
     *  download message. {@code v\d+-webapp-prime.tiktok.com} is the
     *  primary; the {@code tiktokcdn} families cover regional Akamai-
     *  style edges. All serve mp4 video bytes and appear in
     *  {@code bitrateInfo[].PlayAddr.UrlList[]}. */
    public static boolean isTikTokVideoUrl(String url) {
        if (url == null) return false;
        return url.contains("-webapp-prime.tiktok.com/video/")
                || url.contains(".tiktokcdn.com/")
                || url.contains(".tiktokcdn-eu.com/")
                || url.contains(".tiktokcdn-us.com/");
    }

    private static String stripQuery(String url) {
        int q = url.indexOf('?');
        return q < 0 ? url : url.substring(0, q);
    }

    /** Best-effort eviction. O(n) over the map, only runs on writes
     *  (which are rare — a few per JSON response). Acceptable for a
     *  cache that holds at most a few hundred entries. */
    private static void evictExpired() {
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<String, Entry>> it = CACHE.entrySet().iterator();
        while (it.hasNext()) {
            if (now - it.next().getValue().cachedAt > TTL_MS) it.remove();
        }
    }
}
