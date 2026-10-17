package com.solarized.firedown.data.capture;

import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.solarized.firedown.data.entity.BrowserDownloadEntity;
import com.solarized.firedown.data.entity.CaptureDownloadState;
import com.solarized.firedown.ffmpegutils.FFmpegEntity;
import com.solarized.firedown.utils.FileUriHelper;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * The Captured sheet's section rules, as pure functions the view model calls
 * on every emission (see {@link CaptureItem} for the layout they produce).
 */
public final class CaptureSections {

    /**
     * Ceiling on the origins one status query binds. SQLite before 3.32 (every
     * Android below 11) refuses more than 999 bound variables, and a single
     * statement with more fails outright rather than degrading. Past this many
     * distinct pages in one tab's capture list the marks simply stop for the
     * oldest ones.
     */
    public static final int MAX_ORIGINS = 900;

    private CaptureSections() {
    }

    /** An image capture renders as a square, GIF and SVG included: they sit
     *  among the images, labelled on their square. */
    public static boolean isSquare(@NonNull BrowserDownloadEntity e) {
        return FileUriHelper.isImage(e.getMimeType());
    }

    /** Everything else renders as a tile: the Downloads grid tile, for a
     *  video, its audio, a subtitle track or any other file alike. */
    public static boolean isTile(@NonNull BrowserDownloadEntity e) {
        return !isSquare(e);
    }

    /** A video (HLS/DASH manifests included: isVideo knows their mime types).
     *  Not an audio capture in a video container (the entity's audio flag, an
     *  m4a served as video/mp4) nor an audio playlist (audio/mpegurl, which
     *  isVideo also accepts): those rank with the audio, carry no caption
     *  count and are named "Audio on …" when their name is a filename. */
    public static boolean isVideo(@NonNull BrowserDownloadEntity e) {
        return !isAudioCapture(e) && FileUriHelper.isVideo(e.getMimeType());
    }

    public static boolean isAudioCapture(@NonNull BrowserDownloadEntity e) {
        return e.isAudio() || FileUriHelper.isAudio(e.getMimeType());
    }

    /**
     * Order inside a group: the video first, then its audio, its subtitles,
     * any other file, and the images last — the media are what a visit to the
     * sheet is usually for, and the images sit as one block below them instead
     * of interleaving. Ranks 0–3 are all tiles, so a video and its audio sit
     * side by side on one line (its subtitles too, when they have no door:
     * see {@link #ridesWithVideo}).
     */
    public static int typeRank(@NonNull BrowserDownloadEntity e) {
        String mime = e.getMimeType();
        if (isVideo(e)) return 0;
        if (isAudioCapture(e)) return 1;
        if (FileUriHelper.isSubtitle(mime)) return 2;
        if (FileUriHelper.isImage(mime)) return 4;
        return 3;
    }

    /**
     * The sheet's items for an already sorted and limited capture list: the
     * current page's tiles, then an "Images" header and its squares, then the
     * FOLD row for the earlier pages, followed by their captures in the same
     * shape only while it is open.
     *
     * @param shown          captures to show, sorted group-first then by
     *                       {@link #typeRank}, already cut to the list limit
     * @param isCurrent      whether a capture belongs to the page shown now
     * @param currentImages  images in the current group BEFORE the limit, so
     *                       the header's count doesn't grow as the user
     *                       scrolls more in
     * @param earlierImages  the same for the earlier group
     * @param earlier        the fold: the earlier group's count, sites and
     *                       preview, before the limit, and whether it is open
     * @param marks          download marks by capture uid
     * @param captionCounts  subtitle tracks per page origin, over the tab's
     *                       captures, the tracks left to their video
     *                       included (see {@link #captionCounts})
     */
    @NonNull
    public static List<CaptureItem> build(@NonNull List<BrowserDownloadEntity> shown,
                                          @NonNull Predicate<BrowserDownloadEntity> isCurrent,
                                          int currentImages,
                                          int earlierImages,
                                          @NonNull Earlier earlier,
                                          @NonNull Map<Integer, CaptureMark> marks,
                                          @NonNull Map<String, Integer> captionCounts) {
        List<CaptureItem> out = new ArrayList<>(shown.size() + 3);
        boolean multiSite = earlier.domains.size() > 1;
        // The page's videos, counted in what is shown: the current group comes
        // first and its videos lead it (typeRank), so a list limit cuts them
        // only past the limit itself, never down to a misleading one.
        int currentVideos = 0;
        for (BrowserDownloadEntity e : shown) {
            if (e != null && isCurrent.test(e) && isVideo(e)) {
                currentVideos++;
            }
        }
        boolean foldAdded = false;
        int group = -1;
        boolean headerAdded = false;
        for (BrowserDownloadEntity e : shown) {
            if (e == null) continue;
            int g = isCurrent.test(e) ? CaptureItem.GROUP_CURRENT : CaptureItem.GROUP_EARLIER;
            if (g == CaptureItem.GROUP_EARLIER) {
                if (!foldAdded) {
                    out.add(earlier.toItem());
                    foldAdded = true;
                }
                if (!earlier.open) {
                    continue;
                }
            }
            if (g != group) {
                group = g;
                headerAdded = false;
            }
            CaptureMark mark = marks.get(e.getUid());
            if (isSquare(e)) {
                if (!headerAdded) {
                    int count = g == CaptureItem.GROUP_CURRENT ? currentImages : earlierImages;
                    out.add(CaptureItem.header(g, count));
                    headerAdded = true;
                }
                out.add(CaptureItem.square(e, g, mark));
            } else {
                boolean showDomain = g == CaptureItem.GROUP_EARLIER && multiSite;
                boolean onlyVideo = g == CaptureItem.GROUP_CURRENT && currentVideos == 1
                        && isVideo(e);
                out.add(CaptureItem.tile(e, g, showDomain, onlyVideo,
                        captionCountFor(e, captionCounts), mark));
            }
        }
        // The limit can cut the list before any earlier capture: the fold
        // still closes the current page, so the earlier ones stay one tap (and
        // a scroll) away rather than appearing under no heading.
        if (!foldAdded && earlier.total > 0) {
            out.add(earlier.toItem());
        }
        return out;
    }

    /**
     * The positions of the squares an "Images" header heads: every square
     * that follows it until the next row that is not one (the fold, or the
     * end). Empty for anything but a header. The header's select check
     * selects exactly these, and a square's own toggle repaints the header it
     * finds through {@link #headerAbove}.
     */
    @NonNull
    public static List<Integer> squaresUnder(@NonNull List<CaptureItem> items, int header) {
        if (header < 0 || header >= items.size()
                || items.get(header).type != CaptureItem.TYPE_HEADER) {
            return Collections.emptyList();
        }
        List<Integer> out = new ArrayList<>();
        for (int i = header + 1; i < items.size(); i++) {
            if (items.get(i).type != CaptureItem.TYPE_SQUARE) break;
            out.add(i);
        }
        return out;
    }

    /** The header heading a square, or -1: the nearest header above it with
     *  nothing but squares between them. */
    public static int headerAbove(@NonNull List<CaptureItem> items, int position) {
        if (position < 0 || position >= items.size()
                || items.get(position).type != CaptureItem.TYPE_SQUARE) {
            return -1;
        }
        for (int i = position - 1; i >= 0; i--) {
            int type = items.get(i).type;
            if (type == CaptureItem.TYPE_HEADER) return i;
            if (type != CaptureItem.TYPE_SQUARE) return -1;
        }
        return -1;
    }

    /**
     * Up to three earlier captures for the fold's stacked thumbnails, in list
     * order: pictures first (a video's frame, an image), so a page whose first
     * capture is a subtitle track still previews as its video; anything else
     * only to make up the count.
     */
    @NonNull
    public static List<BrowserDownloadEntity> preview(@NonNull List<BrowserDownloadEntity> earlier) {
        List<BrowserDownloadEntity> out = new ArrayList<>(3);
        for (BrowserDownloadEntity e : earlier) {
            if (out.size() == 3) return out;
            if (e != null && (isVideo(e) || isSquare(e))) out.add(e);
        }
        for (BrowserDownloadEntity e : earlier) {
            if (out.size() == 3) break;
            if (e != null && !out.contains(e)) out.add(e);
        }
        return out;
    }

    /** A media file extension, as the catcher names a capture after its URL. */
    private static final Pattern MEDIA_EXTENSION = Pattern.compile(
            "\\.(?:mp4|m4v|mov|webm|mkv|avi|m3u8|mpd|ts|mp3|m4a|aac|wav|flac|ogg|opus)$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern BARE_TOKEN = Pattern.compile("[a-z0-9]{1,16}");
    private static final Pattern WHITESPACE = Pattern.compile("\\s");

    /**
     * Whether a capture's name is a filename rather than a title: what an
     * embedded player's capture is called when the page names nothing better
     * ("src", "master.m3u8", "6aaaf5204d3859992c30a380.mp4", "videoplayback").
     * Such a tile reads "Video on news.example" instead. The rule after
     * stripping a media extension: no whitespace, and either a long token with
     * digits in it (a hash or an id — the catcher's isFilenameLikeTitle test)
     * or one short lowercase word (a URL segment). A name with a space, a
     * capital or a separator is somebody's title and is kept.
     */
    public static boolean isFilenameLike(@Nullable String name) {
        if (name == null) return true;
        String s = MEDIA_EXTENSION.matcher(name.trim()).replaceFirst("").trim();
        if (s.isEmpty()) return true;
        if (WHITESPACE.matcher(s).find()) return false;
        int digits = 0;
        for (int i = 0; i < s.length(); i++) {
            if (Character.isDigit(s.charAt(i))) digits++;
        }
        if (s.length() >= 12 && digits >= 3) return true;
        return BARE_TOKEN.matcher(s).matches();
    }

    /** The fold row's facts, taken from the earlier group BEFORE the limit. */
    public static final class Earlier {
        public final int total;
        public final boolean open;
        public final boolean collapsible;
        @NonNull public final List<String> domains;
        @NonNull public final List<BrowserDownloadEntity> preview;

        public Earlier(int total, boolean open, boolean collapsible,
                       @NonNull List<String> domains,
                       @NonNull List<BrowserDownloadEntity> preview) {
            this.total = total;
            this.open = open;
            this.collapsible = collapsible;
            this.domains = domains;
            this.preview = preview;
        }

        public static final Earlier NONE = new Earlier(0, false, false,
                Collections.emptyList(), Collections.emptyList());

        CaptureItem toItem() {
            return CaptureItem.fold(total, open, collapsible, domains, preview);
        }
    }

    /**
     * Whether the fold shows the earlier captures. Open whenever nothing of
     * the page shown now is listed (no page, or nothing caught yet): the
     * earlier captures are then all the list holds, and a closed fold over an
     * empty page read as a lone card on a blank sheet (on-device, when the
     * sheet still had filter chips). Otherwise the user's choice, closed
     * until they make one.
     *
     * @param choice       the user's last tap, or null before any
     * @param currentTotal the page's captures in the list
     */
    public static boolean earlierOpen(@Nullable Boolean choice, int currentTotal) {
        return currentTotal == 0 || Boolean.TRUE.equals(choice);
    }

    /**
     * Subtitle captures per page origin. The count lands on the VIDEO of that
     * page only ({@link #captionCountFor}): captions belong to a video,
     * and every other capture of the page (thumbnail sprites, a player-logo
     * SVG, posters, the audio) shares the origin and would otherwise inherit
     * it — the "CC 1 on a PNG" bug.
     */
    @NonNull
    public static Map<String, Integer> captionCounts(@Nullable Collection<BrowserDownloadEntity> captures) {
        if (captures == null || captures.isEmpty()) return Collections.emptyMap();
        Map<String, Integer> counts = new HashMap<>();
        for (BrowserDownloadEntity e : captures) {
            if (e == null || !FileUriHelper.isSubtitle(e.getMimeType())) continue;
            String origin = e.getFileOrigin();
            if (!TextUtils.isEmpty(origin)) {
                counts.merge(origin, 1, Integer::sum);
            }
        }
        return counts;
    }

    /**
     * The pages whose video is a door to its own captions: a video capture
     * with quality variants, so its tile wears the ⋮ and the ⋮ opens the
     * picker, whose Captions section lists every subtitle of that page (same
     * tab, same origin: {@code BrowserDownloadViewModel.subtitlesForOrigin}).
     * Keyed by origin like {@link #captionCounts}, so the tracks a door hides
     * are exactly the ones its CC count counts.
     */
    @NonNull
    public static Set<String> captionDoors(@Nullable Collection<BrowserDownloadEntity> captures) {
        if (captures == null || captures.isEmpty()) return Collections.emptySet();
        Set<String> doors = new HashSet<>();
        for (BrowserDownloadEntity e : captures) {
            if (e == null || !isVideo(e) || !e.getHasVariants()) continue;
            String origin = e.getFileOrigin();
            if (!TextUtils.isEmpty(origin)) {
                doors.add(origin);
            }
        }
        return doors;
    }

    /**
     * A subtitle track its video already offers: not an item of the sheet,
     * where it was a second door to the same file and the one that cost the
     * most room (a whole tile for a glyph, and under a page's only video the
     * half-empty row the wide tile removes). The video's tile counts it
     * ("CC 1") and its ⋮ picks it; nothing else in the sheet lists it (the
     * sheet had a Subtitle chip that did, and a chip showing what the list
     * leaves out read as the list having lost it). A track with no door
     * keeps its tile: no video of its page was captured, or that video has
     * one quality and so no ⋮.
     */
    public static boolean ridesWithVideo(@NonNull BrowserDownloadEntity e,
                                         @NonNull Set<String> doors) {
        if (doors.isEmpty() || !FileUriHelper.isSubtitle(e.getMimeType())) return false;
        String origin = e.getFileOrigin();
        return !TextUtils.isEmpty(origin) && doors.contains(origin);
    }

    private static int captionCountFor(@NonNull BrowserDownloadEntity e,
                                       @NonNull Map<String, Integer> counts) {
        if (!isVideo(e)) return 0;
        String origin = e.getFileOrigin();
        if (TextUtils.isEmpty(origin)) return 0;
        Integer count = counts.get(origin);
        return count != null ? count : 0;
    }

    /** Distinct non-empty origins of the given captures, in order, capped at
     *  {@link #MAX_ORIGINS}: the status query's bound list. */
    @NonNull
    public static List<String> origins(@Nullable Collection<BrowserDownloadEntity> captures) {
        if (captures == null || captures.isEmpty()) return Collections.emptyList();
        Set<String> origins = new LinkedHashSet<>();
        for (BrowserDownloadEntity e : captures) {
            if (e == null) continue;
            String origin = e.getFileOrigin();
            if (!TextUtils.isEmpty(origin)) {
                origins.add(origin);
                if (origins.size() >= MAX_ORIGINS) break;
            }
        }
        return new ArrayList<>(origins);
    }

    /** The status rows as one mark per download URL; two rows for one URL
     *  (a retry beside the failed attempt) keep the stronger mark. */
    @NonNull
    public static Map<String, CaptureMark> marksByUrl(@Nullable List<CaptureDownloadState> rows) {
        if (rows == null || rows.isEmpty()) return Collections.emptyMap();
        Map<String, CaptureMark> byUrl = new HashMap<>();
        for (CaptureDownloadState row : rows) {
            if (row == null || TextUtils.isEmpty(row.url)) continue;
            CaptureMark mark = CaptureMark.fromRow(row.status, row.progress, row.live, row.errorType,
                    row.resolution);
            if (mark != null) {
                byUrl.put(row.url, CaptureMark.stronger(byUrl.get(row.url), mark));
            }
        }
        return byUrl;
    }

    /**
     * The mark of each capture, by uid. A download stores the URL it fetched,
     * which is the capture's own URL or, for a capture with quality variants,
     * the URL of the variant that was picked ({@code DownloadRequest.from}
     * swaps it in). So a capture matches on its URL plus every variant's; a
     * YouTube download keeps the capture URL (its variants carry no URL of
     * their own) and matches on the first.
     */
    @NonNull
    public static Map<Integer, CaptureMark> marksByUid(@Nullable Collection<BrowserDownloadEntity> captures,
                                                       @NonNull Map<String, CaptureMark> byUrl) {
        if (captures == null || captures.isEmpty() || byUrl.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<Integer, CaptureMark> byUid = new HashMap<>();
        for (BrowserDownloadEntity e : captures) {
            if (e == null) continue;
            CaptureMark mark = byUrl.get(e.getFileUrl());
            List<FFmpegEntity> streams = e.getStreams();
            if (streams != null) {
                for (FFmpegEntity stream : streams) {
                    String url = stream != null ? stream.getStreamUrl() : null;
                    if (!TextUtils.isEmpty(url)) {
                        mark = CaptureMark.stronger(mark, byUrl.get(url));
                    }
                }
            }
            if (mark != null) {
                byUid.put(e.getUid(), mark);
            }
        }
        return byUid;
    }

    /**
     * Counts for the batch summary card. A capture with no row yet is pending:
     * the engine inserts its row a moment after the dispatch.
     */
    @NonNull
    public static BatchSummary summarize(@NonNull Collection<Integer> uids,
                                         @NonNull Map<Integer, CaptureMark> marks) {
        int saved = 0;
        int downloading = 0;
        int pending = 0;
        int failed = 0;
        int percentSum = 0;
        for (Integer uid : uids) {
            CaptureMark mark = marks.get(uid);
            if (mark == null || mark.state == CaptureMark.PENDING) {
                pending++;
            } else if (mark.state == CaptureMark.PROGRESS) {
                downloading++;
                percentSum += mark.indeterminate ? 0 : mark.percent;
            } else if (mark.state == CaptureMark.SAVED) {
                saved++;
                percentSum += 100;
            } else {
                failed++;
                percentSum += 100;
            }
        }
        int total = uids.size();
        int percent = total > 0 ? percentSum / total : 0;
        return new BatchSummary(total, saved, downloading, pending, failed, percent);
    }

    /** What the batch card says: "Saved 3 of 20 · 4 downloading · 13 pending". */
    public static final class BatchSummary {
        public final int total;
        public final int saved;
        public final int downloading;
        public final int pending;
        public final int failed;
        /** Overall progress for the card's bar, 0–100: each finished item
         *  (saved or failed) counts whole, each running one by its percent. */
        public final int percent;

        BatchSummary(int total, int saved, int downloading, int pending, int failed, int percent) {
            this.total = total;
            this.saved = saved;
            this.downloading = downloading;
            this.pending = pending;
            this.failed = failed;
            this.percent = percent;
        }

        public boolean isDone() {
            return downloading == 0 && pending == 0;
        }
    }
}
