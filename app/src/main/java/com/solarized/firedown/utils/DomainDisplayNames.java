package com.solarized.firedown.utils;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Maps raw domain strings to user-facing platform names for headers
 * in the downloads list. Lets the domain-sort grouping read as
 * "YouTube" instead of "youtube.com", "X" instead of "x.com /
 * twitter.com", and so on.
 *
 * <p>Lookup strategy:
 * <ol>
 *   <li>Lower-case the input and strip presentational prefixes
 *       ({@code www.}, {@code m.}).</li>
 *   <li>Exact match against the known-platform table.</li>
 *   <li>Suffix match — so {@code music.youtube.com} or
 *       {@code mobile.twitter.com} still resolve to the platform
 *       name without needing every subdomain enumerated.</li>
 *   <li>Fall back to the original (cleaned) domain.</li>
 * </ol>
 *
 * <p>Adding a platform: drop a single entry into the static block.
 * Subdomains under a registered apex get the same name automatically
 * via the suffix-match pass.
 */
public final class DomainDisplayNames {

    private static final Map<String, String> NAMES = new LinkedHashMap<>();

    static {
        NAMES.put("youtube.com",      "YouTube");
        NAMES.put("youtu.be",         "YouTube");
        NAMES.put("twitter.com",      "X");
        NAMES.put("x.com",            "X");
        NAMES.put("instagram.com",    "Instagram");
        NAMES.put("tiktok.com",       "TikTok");
        NAMES.put("facebook.com",     "Facebook");
        NAMES.put("fb.com",           "Facebook");
        NAMES.put("reddit.com",       "Reddit");
        NAMES.put("twitch.tv",        "Twitch");
        NAMES.put("vimeo.com",        "Vimeo");
        NAMES.put("dailymotion.com",  "Dailymotion");
        NAMES.put("soundcloud.com",   "SoundCloud");
        NAMES.put("spotify.com",      "Spotify");
        NAMES.put("github.com",       "GitHub");
        NAMES.put("gitlab.com",       "GitLab");
        NAMES.put("imgur.com",        "Imgur");
        NAMES.put("mediafire.com",    "MediaFire");
        NAMES.put("mega.nz",          "MEGA");
        NAMES.put("mega.co.nz",       "MEGA");
        NAMES.put("rumble.com",       "Rumble");
        NAMES.put("bitchute.com",     "BitChute");
        NAMES.put("odysee.com",       "Odysee");
        NAMES.put("bandcamp.com",     "Bandcamp");
        NAMES.put("streamtheworld.com", "StreamTheWorld");
    }

    private DomainDisplayNames() {}

    /**
     * Resolve a domain string to its user-facing display name. Returns
     * the (cleaned) input unchanged if no known platform matches.
     */
    public static String displayName(String domain) {
        if (domain == null || domain.isEmpty()) return "Unknown";
        String lower = domain.toLowerCase(Locale.ROOT);
        if (lower.startsWith("www.")) lower = lower.substring(4);
        if (lower.startsWith("m."))   lower = lower.substring(2);

        String exact = NAMES.get(lower);
        if (exact != null) return exact;

        for (Map.Entry<String, String> entry : NAMES.entrySet()) {
            if (lower.endsWith("." + entry.getKey())) return entry.getValue();
        }
        return lower;
    }
}
