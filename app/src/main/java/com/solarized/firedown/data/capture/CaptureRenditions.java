package com.solarized.firedown.data.capture;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.solarized.firedown.data.entity.BrowserDownloadEntity;
import com.solarized.firedown.ffmpegutils.FFmpegEntity;
import com.solarized.firedown.manager.UrlType;
import com.solarized.firedown.utils.FileUriHelper;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * When two captures are ONE video at two resolutions. The Captured sheet's
 * quality ⋮ only exists on a capture that carries more than one rendition,
 * so a video whose renditions arrived as separate captures had no picker at
 * all: two bare tiles, and (a 1.2.2 report) "I'll need to choose one at
 * random or all of them to download". The repository uses these rules to
 * keep one capture per clip:
 *
 * <ul>
 *   <li>{@link #listsUrl} — a capture of a URL another capture already lists
 *       as a rendition is that capture again (a duplicate);</li>
 *   <li>{@link #covers} — a capture with renditions takes the place of the
 *       single captures of its own renditions, whichever landed first (the
 *       page-state bridge reads a player's whole source list, the content
 *       script reports the same &lt;source&gt;s one by one);</li>
 *   <li>{@link #otherRendition} — two single progressive captures of one clip
 *       fold into one capture with both renditions. Deliberately narrow, the
 *       evidence must all agree: same tab, same page, same host, the URLs
 *       differ ONLY in a resolution token of the path ("…/clip_720p.mp4" /
 *       "…/clip_1080p.mp4", "…/720/clip.mp4", "…/1280x720/…"), the frame
 *       heights differ, the shape is the same and the durations match. Two
 *       copies whose URLs share no such token (hash-named renditions) stay two
 *       captures — each tile states its quality and size.</li>
 * </ul>
 *
 * <p>Pure: no Android, so the capture harness runs it.
 */
public final class CaptureRenditions {

    private CaptureRenditions() {
    }

    /** "1280x720", "720x1280". */
    private static final Pattern FRAME_SIZE = Pattern.compile(
            "(?<![a-z0-9])\\d{2,5}x\\d{2,5}(?![a-z0-9])");

    /** "720p", "1080p60", "2160p". */
    private static final Pattern HEIGHT_P = Pattern.compile(
            "(?<![a-z0-9])\\d{3,4}p(?:\\d{2})?(?![a-z0-9])");

    /** A bare number that is a frame dimension of the common ladders
     *  ("/720/", "_1080.", "-480-"). Only these values: an arbitrary 3-4
     *  digit number is just as often a clip's id. */
    private static final Pattern DIMENSION = Pattern.compile(
            "(?<![a-z0-9])(?:144|180|240|270|288|360|368|432|480|540|576|640|720|768|854|900"
            + "|960|1024|1080|1152|1280|1440|1600|1920|2048|2160|2560|2880|3840|4096|4320)"
            + "(?![a-z0-9])");

    /** Named rungs ("video_hd.mp4" / "video_sd.mp4"). */
    private static final Pattern NAMED_RUNG = Pattern.compile(
            "(?<![a-z0-9])(?:sd|hd|fhd|uhd|hq|lq|4k)(?![a-z0-9])");

    private static final String TOKEN = "\u0000";

    /** Durations closer than this always match (encoder priming, frame
     *  rounding between two encodes of one clip). */
    private static final long DURATION_FLOOR_US = 250_000L;

    /** ...and never further apart than this, however long the clip. */
    private static final long DURATION_CEILING_US = 1_000_000L;

    /** Frame shapes further apart than this are different clips. */
    private static final double ASPECT_TOLERANCE = 0.03;

    /**
     * Whether {@code url} is one of the renditions {@code listed} already
     * offers — its own URL is the repository's ordinary duplicate test.
     */
    public static boolean listsUrl(@NonNull BrowserDownloadEntity listed, @Nullable String url) {
        if (url == null) {
            return false;
        }
        String key = trivial(url);
        List<FFmpegEntity> streams = listed.getStreams();
        if (streams == null) {
            return false;
        }
        for (FFmpegEntity stream : streams) {
            String streamUrl = stream != null ? stream.getStreamUrl() : null;
            if (streamUrl != null && !streamUrl.isEmpty() && trivial(streamUrl).equals(key)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether {@code rich}, a capture with renditions, makes {@code single}
     * redundant: same tab, and single's URL is rich's own or one of its
     * renditions'. A capture that offers renditions itself is never covered.
     */
    public static boolean covers(@NonNull BrowserDownloadEntity rich, @NonNull BrowserDownloadEntity single) {
        if (rich == single || !rich.getHasVariants() || single.getHasVariants()) {
            return false;
        }
        if (rich.getTabId() != single.getTabId()) {
            return false;
        }
        String url = single.getFileUrl();
        if (url == null) {
            return false;
        }
        String own = rich.getFileUrl();
        if (own != null && trivial(own).equals(trivial(url))) {
            return true;
        }
        return listsUrl(rich, url);
    }

    /**
     * Whether {@code incoming}, a single progressive video capture, is another
     * rendition of the clip {@code listed} holds (see the class comment for
     * every condition).
     */
    public static boolean otherRendition(@NonNull BrowserDownloadEntity listed,
                                         @NonNull BrowserDownloadEntity incoming) {
        if (listed == incoming || listed.getTabId() != incoming.getTabId()) {
            return false;
        }
        if (!isProgressiveVideo(listed) || !isProgressiveVideo(incoming) || incoming.getHasVariants()) {
            return false;
        }
        String listedPage = listed.getFileOrigin();
        String incomingPage = incoming.getFileOrigin();
        if (listedPage == null || incomingPage == null || listedPage.isEmpty()
                || !trivial(listedPage).equals(trivial(incomingPage))) {
            return false;
        }
        if (!closeDurations(listed.getDurationTime(), incoming.getDurationTime())) {
            return false;
        }
        FFmpegEntity rendition = onlyVideoStream(incoming);
        if (rendition == null || rendition.getHeight() <= 0) {
            return false;
        }
        List<FFmpegEntity> listedStreams = listed.getStreams();
        if (listedStreams == null || listedStreams.isEmpty()) {
            return false;
        }
        // Every rendition the listed capture already has must be a known
        // frame of the same shape, none of them at this height; and a capture
        // that holds several must name each one by URL (a fold, or the
        // bridge's source list) — a probed HLS master's streams don't.
        boolean several = listedStreams.size() > 1;
        for (FFmpegEntity stream : listedStreams) {
            if (stream == null || stream.isAudioOnly() || stream.getHeight() <= 0) {
                return false;
            }
            if (stream.getHeight() == rendition.getHeight() || !sameShape(stream, rendition)) {
                return false;
            }
            if (several && isBlank(stream.getStreamUrl())) {
                return false;
            }
        }
        String incomingUrl = incoming.getFileUrl();
        if (onlyResolutionDiffers(listed.getFileUrl(), incomingUrl)) {
            return true;
        }
        for (FFmpegEntity stream : listedStreams) {
            if (onlyResolutionDiffers(stream.getStreamUrl(), incomingUrl)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether two URLs name the same file but for a resolution token of the
     * path: same host (www folded), paths that differ, and are equal once
     * every resolution token is masked. The query is not compared — signed
     * URLs carry a different signature per file.
     */
    public static boolean onlyResolutionDiffers(@Nullable String a, @Nullable String b) {
        String[] pa = hostAndPath(a);
        String[] pb = hostAndPath(b);
        if (pa == null || pb == null || !pa[0].equals(pb[0]) || pa[1].equals(pb[1])) {
            return false;
        }
        String ma = maskResolution(pa[1]);
        String mb = maskResolution(pb[1]);
        return ma.contains(TOKEN) && ma.equals(mb);
    }

    /** Durations of one clip in two encodes (µs): both known and within
     *  1% of the longer, at least 0.25 s and at most 1 s apart. */
    public static boolean closeDurations(long aUs, long bUs) {
        if (aUs <= 0 || bUs <= 0) {
            return false;
        }
        long tolerance = Math.max(DURATION_FLOOR_US,
                Math.min(DURATION_CEILING_US, Math.max(aUs, bUs) / 100));
        return Math.abs(aUs - bUs) <= tolerance;
    }

    @NonNull
    static String maskResolution(@NonNull String path) {
        String masked = FRAME_SIZE.matcher(path).replaceAll(TOKEN);
        masked = HEIGHT_P.matcher(masked).replaceAll(TOKEN);
        masked = DIMENSION.matcher(masked).replaceAll(TOKEN);
        return NAMED_RUNG.matcher(masked).replaceAll(TOKEN);
    }

    /** {host without www., path} lowercased, or null for a non-http URL. */
    @Nullable
    private static String[] hostAndPath(@Nullable String url) {
        if (url == null) {
            return null;
        }
        String lower = url.toLowerCase(Locale.ROOT);
        int scheme = lower.indexOf("://");
        if (scheme <= 0 || !lower.startsWith("http")) {
            return null;
        }
        int hostStart = scheme + 3;
        int end = lower.length();
        int query = lower.indexOf('?', hostStart);
        if (query >= 0) {
            end = query;
        }
        int fragment = lower.indexOf('#', hostStart);
        if (fragment >= 0 && fragment < end) {
            end = fragment;
        }
        int slash = lower.indexOf('/', hostStart);
        if (slash < 0 || slash > end) {
            slash = end;
        }
        String host = lower.substring(hostStart, slash);
        if (host.startsWith("www.")) {
            host = host.substring(4);
        }
        return new String[] {host, lower.substring(slash, end)};
    }

    private static boolean isProgressiveVideo(@NonNull BrowserDownloadEntity entity) {
        return entity.getType() == UrlType.FILE.getValue()
                && !entity.isAudio()
                && FileUriHelper.isVideo(entity.getMimeType());
    }

    @Nullable
    private static FFmpegEntity onlyVideoStream(@NonNull BrowserDownloadEntity entity) {
        List<FFmpegEntity> streams = entity.getStreams();
        if (streams == null || streams.size() != 1) {
            return null;
        }
        FFmpegEntity stream = streams.get(0);
        return stream != null && !stream.isAudioOnly() ? stream : null;
    }

    /** Same frame shape, when both sizes are known; unknown widths pass. */
    private static boolean sameShape(@NonNull FFmpegEntity a, @NonNull FFmpegEntity b) {
        if (a.getWidth() <= 0 || b.getWidth() <= 0) {
            return true;
        }
        double ra = (double) a.getWidth() / a.getHeight();
        double rb = (double) b.getWidth() / b.getHeight();
        return Math.abs(ra - rb) <= ASPECT_TOLERANCE * Math.max(ra, rb);
    }

    /**
     * A URL with its trivial differences removed — the repository's duplicate
     * test compares these: no fragment, no trailing slash, and no leading
     * "www." on the host. The same media file is routinely reachable from both
     * the apex and the www host, and a page can mix the two for one video: its
     * &lt;source&gt;/relative URL (and the actual on-wire fetch) resolve to
     * whatever host the page loaded from, while its og:video / JSON-LD
     * contentUrl hardcode the www host. An honest cross-host duplicate is not
     * a case worth preserving on the capture sheet.
     */
    @NonNull
    public static String trivial(@NonNull String url) {
        String out = url;
        int hash = out.indexOf('#');
        if (hash >= 0) {
            out = out.substring(0, hash);
        }
        if (out.endsWith("/")) {
            out = out.substring(0, out.length() - 1);
        }
        int scheme = out.indexOf("://");
        if (scheme >= 0) {
            int hostStart = scheme + 3;
            if (out.regionMatches(true, hostStart, "www.", 0, 4)) {
                out = out.substring(0, hostStart) + out.substring(hostStart + 4);
            }
        }
        return out;
    }

    private static boolean isBlank(@Nullable String s) {
        return s == null || s.isEmpty();
    }
}
