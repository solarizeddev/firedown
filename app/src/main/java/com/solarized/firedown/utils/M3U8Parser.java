package com.solarized.firedown.utils;

import android.util.Log;

import com.solarized.firedown.BuildConfig;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses an HLS <b>master</b> playlist into quality variants — the Java port of
 * the parser extension's {@code parseHlsMaster} (background.js).
 *
 * <p>Text-only: it reads the master's {@code EXT-X-STREAM-INF} /
 * {@code EXT-X-MEDIA} tags and never opens a segment, so it never decrypts and
 * never fetches an AES key (unlike the ffmpeg metadatareader probe). That's what
 * lets us enumerate qualities at capture time without burning a single-use
 * key.
 *
 * <p>Output is a {@link JSONArray} of variant objects in exactly the shape
 * {@code JsonHelper.parseVariants} consumes:
 * <pre>{ "url": …, "audioUrl"?: …, "width": N, "height": N,
 *        "videoCodec"?: "h264"|"hevc", "audioCodec"?: "aac" }</pre>
 * so the result can be dropped straight into a {@code type:"variants"} message
 * (with {@code skipProbe:true}) and reuse the existing variant pipeline.
 *
 * <p>Handles:
 * <ul>
 *   <li>muxed renditions (one STREAM-INF = full A/V → single-URL variant);</li>
 *   <li>split audio ({@code EXT-X-MEDIA:TYPE=AUDIO} referenced via
 *       {@code AUDIO="group"});</li>
 *   <li>relative variant URLs (resolved against the master URL);</li>
 *   <li>I-frame trick-play streams ({@code #EXT-X-I-FRAME-STREAM-INF}) — skipped;</li>
 *   <li>proportional audio↔video tiering — a higher video never gets worse audio
 *       than a lower one, order-independent (audio ranked by the bandwidth of the
 *       streams referencing it, since EXT-X-MEDIA carries no bitrate).</li>
 * </ul>
 */
public final class M3U8Parser {

    private M3U8Parser() {
    }

    private static final String TAG = "M3U8Parser";

    // All logging is gated on the debug build (no logs ship in release).
    private static void log(String msg) {
        if (BuildConfig.DEBUG) {
            Log.d(TAG, msg);
        }
    }

    private static String head(String s) {
        if (s == null) {
            return "null";
        }
        String h = s.length() > 120 ? s.substring(0, 120) : s;
        return h.replace('\n', ' ').replace('\r', ' ');
    }

    private static final Pattern RESOLUTION = Pattern.compile("RESOLUTION=(\\d+)x(\\d+)");
    private static final Pattern AUDIO_ATTR = Pattern.compile("AUDIO=\"([^\"]+)\"");
    private static final Pattern CODECS_ATTR = Pattern.compile("CODECS=\"([^\"]+)\"");
    private static final Pattern GROUP_ID = Pattern.compile("GROUP-ID=\"([^\"]+)\"");
    private static final Pattern URI_ATTR = Pattern.compile("URI=\"([^\"]+)\"");
    // BANDWIDTH only — the leading [,:] keeps it from matching AVERAGE-BANDWIDTH
    // (which is preceded by '-').
    private static final Pattern BANDWIDTH = Pattern.compile("[,:]BANDWIDTH=(\\d+)");
    private static final Pattern AVERAGE_BANDWIDTH = Pattern.compile("AVERAGE-BANDWIDTH=(\\d+)");

    /** One parsed EXT-X-STREAM-INF entry (pre-dedup). */
    private static final class Stream {
        String url;
        int width;
        int height;
        String audioGroup;
        long bandwidth;
        long averageBandwidth;
        String codecs = "";
    }

    /**
     * @param text    the master playlist body
     * @param baseUrl the URL the master was fetched from (for relative resolution)
     * @return variant JSON array (best-first), or an empty array if {@code text}
     * is not a master playlist (no {@code EXT-X-STREAM-INF}).
     */
    public static JSONArray parseMaster(String text, String baseUrl) {
        JSONArray out = new JSONArray();
        log("parseMaster: " + (text == null ? 0 : text.length()) + "B base=" + head(baseUrl));
        if (text == null || !text.contains("#EXT-X-STREAM-INF")) {
            log("not a master (no #EXT-X-STREAM-INF); head=" + head(text));
            return out;
        }

        String[] lines = text.split("\\r?\\n");
        Map<String, String> audios = new LinkedHashMap<>();  // group-id -> resolved url
        List<Stream> streams = new ArrayList<>();             // video renditions
        List<Stream> audioOnly = new ArrayList<>();           // resolution-less (audio-only) renditions

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();

            if (line.startsWith("#EXT-X-MEDIA:") && line.contains("TYPE=AUDIO")) {
                String gid = firstGroup(GROUP_ID, line);
                String uri = firstGroup(URI_ATTR, line);
                if (gid != null && uri != null) {
                    audios.put(gid, resolveUrl(uri, baseUrl));
                }
                continue;
            }

            // Only STREAM-INF (not #EXT-X-I-FRAME-STREAM-INF, which this prefix
            // test deliberately does not match) carries a following media URL.
            if (!line.startsWith("#EXT-X-STREAM-INF:")) {
                continue;
            }

            // The variant URL is the next non-blank, non-tag line.
            String url = null;
            for (int j = i + 1; j < lines.length; j++) {
                String t = lines[j].trim();
                if (t.isEmpty()) {
                    continue;
                }
                if (t.startsWith("#")) {
                    break;
                }
                url = t;
                break;
            }
            if (url == null) {
                continue;
            }

            Stream s = new Stream();
            s.url = resolveUrl(url, baseUrl);

            Matcher res = RESOLUTION.matcher(line);
            if (res.find()) {
                s.width = parseIntSafe(res.group(1));
                s.height = parseIntSafe(res.group(2));
            }
            s.audioGroup = firstGroup(AUDIO_ATTR, line);
            String codecs = firstGroup(CODECS_ATTR, line);
            if (codecs != null) {
                s.codecs = codecs;
            }
            Matcher bw = BANDWIDTH.matcher(line);
            if (bw.find()) {
                s.bandwidth = parseLongSafe(bw.group(1));
            }
            Matcher avg = AVERAGE_BANDWIDTH.matcher(line);
            if (avg.find()) {
                s.averageBandwidth = parseLongSafe(avg.group(1));
            }

            // A resolution-less rendition (no RESOLUTION) is audio-only. Keep
            // it aside and offer it as a separate "Audio only" variant after
            // the video renditions.
            if (s.height <= 0) {
                audioOnly.add(s);
                continue;
            }
            streams.add(s);
        }

        log("parsed streams=" + streams.size() + " audioGroups=" + audios.keySet());
        if (streams.isEmpty()) {
            return out;
        }

        // Rank audio groups best-first by the highest bandwidth of the streams
        // that reference them (EXT-X-MEDIA has no bitrate of its own).
        Map<String, Long> groupMaxBw = new LinkedHashMap<>();
        for (Stream s : streams) {
            if (s.audioGroup == null || !audios.containsKey(s.audioGroup)) {
                continue;
            }
            Long prev = groupMaxBw.get(s.audioGroup);
            if (prev == null || s.bandwidth > prev) {
                groupMaxBw.put(s.audioGroup, s.bandwidth);
            }
        }
        List<String> rankedGroups = new ArrayList<>(groupMaxBw.keySet());
        rankedGroups.sort((a, b) -> Long.compare(groupMaxBw.get(b), groupMaxBw.get(a)));
        List<String> audioRanked = new ArrayList<>();
        for (String g : rankedGroups) {
            audioRanked.add(audios.get(g));
        }
        log("audio groups ranked (best-first)=" + rankedGroups);

        // Dedup videos by URL (keep the highest-bandwidth occurrence), best-first.
        Map<String, Stream> byUrl = new LinkedHashMap<>();
        for (Stream s : streams) {
            Stream prev = byUrl.get(s.url);
            if (prev == null || s.bandwidth > prev.bandwidth) {
                byUrl.put(s.url, s);
            }
        }
        List<Stream> vids = new ArrayList<>(byUrl.values());
        vids.sort((a, b) -> {
            if (b.height != a.height) {
                return Integer.compare(b.height, a.height);
            }
            return Long.compare(b.bandwidth, a.bandwidth);
        });

        int audioCount = audioRanked.size();
        int videoCount = vids.size();
        for (int i = 0; i < videoCount; i++) {
            Stream s = vids.get(i);
            try {
                JSONObject v = new JSONObject();
                v.put("url", s.url);
                v.put("width", s.width);
                v.put("height", s.height);
                // The rendition's bitrate, for the size estimate the Captured
                // sheet and the quality picker show (bitrate x duration).
                // AVERAGE-BANDWIDTH when the master states it: BANDWIDTH is the
                // PEAK, which overstates a VBR rendition's size. Both count every
                // rendition the variant plays, its audio group included.
                long bitrate = s.averageBandwidth > 0 ? s.averageBandwidth : s.bandwidth;
                if (bitrate > 0 && bitrate <= Integer.MAX_VALUE) {
                    v.put("bitrate", bitrate);
                }
                String audioGroup = null;
                if (audioCount > 0) {
                    // Proportional tiering: map video rank -> audio rank.
                    int idx = (int) Math.floor((double) (i * audioCount) / videoCount);
                    if (idx > audioCount - 1) {
                        idx = audioCount - 1;
                    }
                    v.put("audioUrl", audioRanked.get(idx));
                    audioGroup = rankedGroups.get(idx);
                }
                String videoCodec = videoCodec(s.codecs);
                if (videoCodec != null) {
                    v.put("videoCodec", videoCodec);
                }
                if (s.codecs.contains("mp4a")) {
                    v.put("audioCodec", "aac");
                }
                out.put(v);
                log("  variant " + s.height + "p bw=" + s.bandwidth
                        + " audio=" + (audioGroup != null ? audioGroup : "muxed/none"));
            } catch (JSONException ignored) {
                // Skip a variant we can't serialise rather than abort the lot.
            }
        }

        // Audio-only renditions last (dedup by URL, best bitrate). Marked
        // audioOnly so the UI labels them "Audio only" rather than "video+audio".
        Map<String, Stream> audioByUrl = new LinkedHashMap<>();
        for (Stream s : audioOnly) {
            Stream prev = audioByUrl.get(s.url);
            if (prev == null || s.bandwidth > prev.bandwidth) {
                audioByUrl.put(s.url, s);
            }
        }
        for (Stream s : audioByUrl.values()) {
            try {
                JSONObject v = new JSONObject();
                v.put("url", s.url);
                v.put("audioOnly", true);
                if (s.codecs.contains("mp4a")) {
                    v.put("audioCodec", "aac");
                } else if (s.codecs.contains("ac-3") || s.codecs.contains("ec-3")) {
                    v.put("audioCodec", "ac3");
                } else if (s.codecs.contains("opus")) {
                    v.put("audioCodec", "opus");
                }
                out.put(v);
                log("  audio-only variant bw=" + s.bandwidth + " codecs=" + s.codecs);
            } catch (JSONException ignored) {
                // skip
            }
        }

        log("parseMaster -> " + out.length() + " variant(s)");
        return out;
    }

    private static String videoCodec(String codecs) {
        if (codecs.contains("avc1")) {
            return "h264";
        }
        if (codecs.contains("hvc1") || codecs.contains("hev1")) {
            return "hevc";
        }
        return null;
    }

    private static String firstGroup(Pattern pattern, String line) {
        Matcher m = pattern.matcher(line);
        return m.find() ? m.group(1) : null;
    }

    /**
     * Resolve a (possibly relative) variant URL against the master URL. Absolute
     * http(s) URLs are returned as-is — that also avoids {@link URI} choking on
     * the unescaped characters in signed CDN URLs (~, etc.).
     */
    private static String resolveUrl(String u, String base) {
        if (u == null) {
            return null;
        }
        if (u.regionMatches(true, 0, "https://", 0, 8)
                || u.regionMatches(true, 0, "http://", 0, 7)) {
            return u;
        }
        try {
            return URI.create(base).resolve(u).toString();
        } catch (Exception e) {
            return u;
        }
    }

    private static int parseIntSafe(String s) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static long parseLongSafe(String s) {
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            return 0L;
        }
    }
}
