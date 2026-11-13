package com.solarized.firedown.manager;

/**
 * Says from a playlist's text whether the stream behind it is live, before
 * FFmpeg opens it.
 *
 * <p>The native downloader can only infer it: a plain live HLS playlist has no
 * duration, but an EVENT playlist still being written states the length so
 * far, and the hls demuxer refreshes that length from the master's FIRST
 * variant only. A download of another rendition through its master would then
 * keep a fixed length it outruns, and the bar would sit at 100%. The text
 * answers it directly:
 * <ul>
 *   <li>an HLS media playlist is finished when it ends with
 *       {@code #EXT-X-ENDLIST} (a VOD playlist must carry it), live otherwise;</li>
 *   <li>a master says nothing itself, but every rendition of one stream is
 *       live or finished together, so its first rendition answers for all
 *       ({@link #firstVariantUri});</li>
 *   <li>a DASH manifest is live when its MPD is {@code type="dynamic"}.</li>
 * </ul>
 * Pure Java, no Android types, so the JVM tests run it.
 */
public final class PlaylistLiveness {

    /** The answer for a text that is not a playlist this class reads. */
    public static final int UNKNOWN = 0;
    public static final int LIVE = 1;
    public static final int FINISHED = 2;
    /** An HLS master: ask {@link #firstVariantUri} and read that playlist. */
    public static final int MASTER = 3;

    private PlaylistLiveness() {
    }

    /** Whether the first bytes look like a playlist worth reading whole. */
    public static boolean looksLikePlaylist(String head) {
        if (head == null) {
            return false;
        }
        String h = stripBom(head).trim();
        return h.startsWith("#EXTM3U") || h.startsWith("<?xml") || h.startsWith("<MPD");
    }

    /** Whether the first bytes are a DASH manifest rather than HLS. */
    public static boolean looksLikeDash(String head) {
        if (head == null) {
            return false;
        }
        String h = stripBom(head).trim();
        return h.startsWith("<?xml") || h.startsWith("<MPD");
    }

    /** The answer for a whole text; HLS goes through {@link Scan}. */
    public static int of(String text) {
        if (text == null) {
            return UNKNOWN;
        }
        String t = stripBom(text);
        String trimmed = t.trim();
        if (trimmed.startsWith("#EXTM3U")) {
            Scan scan = new Scan();
            for (String line : t.split("\n")) {
                if (scan.feed(line)) {
                    break;
                }
            }
            return scan.result();
        }
        if (looksLikeDash(trimmed)) {
            return ofDash(t);
        }
        return UNKNOWN;
    }

    /**
     * An HLS playlist read one line at a time, so a caller can scan a large
     * playlist without holding it as one string.
     */
    public static final class Scan {
        private boolean segments;
        private boolean endList;
        private boolean vod;
        private boolean master;

        /** Takes one line; true once the answer cannot change (a master). */
        public boolean feed(String raw) {
            String line = raw.trim();
            if (line.startsWith("#EXT-X-STREAM-INF")) {
                master = true;
                return true;
            }
            if (line.startsWith("#EXTINF")) {
                segments = true;
            } else if (line.startsWith("#EXT-X-ENDLIST")) {
                endList = true;
            } else if (line.startsWith("#EXT-X-PLAYLIST-TYPE:")) {
                vod = line.substring("#EXT-X-PLAYLIST-TYPE:".length()).trim().equalsIgnoreCase("VOD");
            }
            return false;
        }

        public int result() {
            if (master) {
                return MASTER;
            }
            if (endList || vod) {
                return FINISHED;
            }
            return segments ? LIVE : UNKNOWN;
        }
    }

    private static int ofDash(String text) {
        String t = text.replaceAll("(?s)<!--.*?-->", "");
        int mpd = t.indexOf("<MPD");
        if (mpd < 0) {
            return UNKNOWN;
        }
        int end = t.indexOf('>', mpd);
        if (end < 0) {
            return UNKNOWN;
        }
        String tag = t.substring(mpd, end);
        if (tag.matches("(?s).*\\stype\\s*=\\s*[\"']dynamic[\"'].*")) {
            return LIVE;
        }
        return FINISHED;
    }

    /**
     * The URI of a master's first {@code #EXT-X-STREAM-INF} rendition, as
     * written (relative or absolute), or null when there is none.
     */
    public static String firstVariantUri(String text) {
        if (text == null) {
            return null;
        }
        boolean pending = false;
        for (String raw : stripBom(text).split("\n")) {
            String line = raw.trim();
            if (line.isEmpty()) {
                continue;
            }
            if (line.startsWith("#")) {
                if (line.startsWith("#EXT-X-STREAM-INF")) {
                    pending = true;
                }
                continue;
            }
            if (pending) {
                return line;
            }
        }
        return null;
    }

    private static String stripBom(String s) {
        return s.startsWith("\uFEFF") ? s.substring(1) : s;
    }
}
