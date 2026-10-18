package com.solarized.firedown.harness;

import com.solarized.firedown.data.Download;
import com.solarized.firedown.data.capture.*;
import com.solarized.firedown.data.capture.CaptureSections.BatchSummary;
import com.solarized.firedown.data.entity.*;
import com.solarized.firedown.ffmpegutils.FFmpegEntity;
import com.solarized.firedown.ui.diffs.CaptureItemDiffCallback;
import com.solarized.firedown.utils.MessageHelper;
import java.util.*;

/** Drives the REAL CaptureSections / CaptureMark / CaptureItem /
 *  CaptureItemDiffCallback against a stand-in entity. */
public class CaptureHarness {
  static int pass, fail;
  static void check(String name, boolean ok) { if (ok) pass++; else { fail++; System.out.println("FAIL " + name); } }
  static BrowserDownloadEntity e(int uid, String mime, String url, String origin) { return new BrowserDownloadEntity(uid, mime, url, origin); }
  static String shape(List<CaptureItem> items) {
    StringBuilder b = new StringBuilder();
    for (CaptureItem i : items) {
      b.append(switch (i.type) {
        case CaptureItem.TYPE_TILE -> "T" + i.uid() + (i.showDomain ? "d" : "") + (i.captionCount > 0 ? "c" + i.captionCount : "") + (i.onlyVideo ? "w" : "");
        case CaptureItem.TYPE_SQUARE -> "S" + i.uid();
        case CaptureItem.TYPE_HEADER -> "H" + i.group + ":" + i.count;
        default -> "F" + i.count + (!i.collapsible ? "h" : i.open ? "o" : "c"); }).append(' ');
    }
    return b.toString().trim();
  }
  static CaptureSections.Earlier earlier(int total, boolean open, String... domains) {
    return new CaptureSections.Earlier(total, open, true, List.of(domains), List.of());
  }
  /** Nothing of the page listed: open, and a heading rather than a toggle. */
  static CaptureSections.Earlier earlierOnly(int total, String... domains) {
    return new CaptureSections.Earlier(total, true, false, List.of(domains), List.of());
  }
  static CaptureDownloadState row(String url, int status, int progress, boolean live, int err) {
    CaptureDownloadState s = new CaptureDownloadState(); s.url = url; s.status = status; s.progress = progress; s.live = live; s.errorType = err; return s;
  }

  static final String PAGE = "https://site.example/watch/42";
  static final String CDN = "https://cdn.site.example/v/42/";

  /** A probed single progressive file: one video stream, no URL on it (the
   *  capture's own URL is the file), its length from the response. */
  static BrowserDownloadEntity clip(int uid, String url, int w, int h, long durUs, long len) {
    BrowserDownloadEntity c = e(uid, "video/mp4", url, PAGE);
    c.type = 1; c.tabId = 7; c.durationUs = durUs; c.fileLength = len;
    c.streams.add(new FFmpegEntity(h + "p", w, h));
    c.tags.add(new FFmpegTagEntity(FFmpegTagEntity.TYPE_QUALITY, h + "p"));
    return c;
  }

  /** The bridge's source list: one capture, every rendition named by URL. */
  static BrowserDownloadEntity group(int uid, String primary, String[] urls, int[] heights) {
    BrowserDownloadEntity g = e(uid, "video/mp4", primary, PAGE);
    g.type = 1; g.tabId = 7; g.durationUs = 60_000_000L; g.variants = urls.length > 1;
    for (int i = 0; i < urls.length; i++) {
      FFmpegEntity r = new FFmpegEntity(heights[i] + "p", 0, heights[i]); r.setStreamUrl(urls[i]); g.streams.add(r);
    }
    return g;
  }

  static List<BrowserDownloadEntity> listed(com.solarized.firedown.data.repository.BrowserDownloadRepository repo) {
    repo.postComplete();
    List<BrowserDownloadEntity> l = repo.getData().getValue();
    return l != null ? l : List.of();
  }

  static void renditions(Comparator<FFmpegEntity> byHeight) {
    // the URL test: the two files differ only in a resolution token
    check("renditions: _720p / _1080p", CaptureRenditions.onlyResolutionDiffers(CDN + "clip_720p.mp4?s=a", CDN + "clip_1080p.mp4?s=b"));
    check("renditions: /720/ and /1080/ path segments",
        CaptureRenditions.onlyResolutionDiffers("https://cdn.x/720/clip.mp4", "https://cdn.x/1080/clip.mp4"));
    check("renditions: frame sizes and named rungs",
        CaptureRenditions.onlyResolutionDiffers("https://cdn.x/vid/1280x720/a.mp4", "https://cdn.x/vid/854x480/a.mp4")
        && CaptureRenditions.onlyResolutionDiffers("https://www.cdn.x/clip_hd.mp4", "https://cdn.x/clip_sd.mp4"));
    check("renditions: another clip's id is not a resolution",
        !CaptureRenditions.onlyResolutionDiffers("https://cdn.x/42/clip_720p.mp4", "https://cdn.x/43/clip_1080p.mp4")
        && !CaptureRenditions.onlyResolutionDiffers("https://cdn.x/1234/clip.mp4", "https://cdn.x/5678/clip.mp4"));
    check("renditions: hash names, another host, the same path are not",
        !CaptureRenditions.onlyResolutionDiffers("https://cdn.x/a1b2c3.mp4", "https://cdn.x/d4e5f6.mp4")
        && !CaptureRenditions.onlyResolutionDiffers("https://cdn.x/clip_720p.mp4", "https://cdn.y/clip_1080p.mp4")
        && !CaptureRenditions.onlyResolutionDiffers("https://cdn.x/get.mp4?q=720", "https://cdn.x/get.mp4?q=1080"));
    check("renditions: durations of two encodes match, two clips' do not",
        CaptureRenditions.closeDurations(120_000_000L, 120_400_000L) && CaptureRenditions.closeDurations(5_000_000L, 5_200_000L)
        && !CaptureRenditions.closeDurations(5_000_000L, 5_400_000L) && !CaptureRenditions.closeDurations(7_200_000_000L, 7_201_500_000L)
        && !CaptureRenditions.closeDurations(0, 0));

    BrowserDownloadEntity a = clip(101, CDN + "clip_720p.mp4", 1280, 720, 60_000_000L, 20_000_000L);
    BrowserDownloadEntity b = clip(102, CDN + "clip_1080p.mp4", 1920, 1080, 60_100_000L, 45_000_000L);
    check("renditions: another resolution of a listed clip", CaptureRenditions.otherRendition(a, b));
    BrowserDownloadEntity bTab = clip(103, CDN + "clip_1080p.mp4", 1920, 1080, 60_000_000L, 1); bTab.tabId = 8;
    BrowserDownloadEntity bPage = clip(104, CDN + "clip_1080p.mp4", 1920, 1080, 60_000_000L, 1); bPage.origin = PAGE + "/other";
    BrowserDownloadEntity bLong = clip(105, CDN + "clip_1080p.mp4", 1920, 1080, 75_000_000L, 1);
    BrowserDownloadEntity bSame = clip(106, CDN + "clip_hd.mp4", 1280, 720, 60_000_000L, 1);
    check("renditions: not across tabs, pages, durations or at the same height",
        !CaptureRenditions.otherRendition(a, bTab) && !CaptureRenditions.otherRendition(a, bPage)
        && !CaptureRenditions.otherRendition(a, bLong) && !CaptureRenditions.otherRendition(a, bSame));
    BrowserDownloadEntity bTall = clip(107, CDN + "clip_1080p.mp4", 1080, 1920, 60_000_000L, 1);
    BrowserDownloadEntity bHls = clip(108, CDN + "clip_1080p.mp4", 1920, 1080, 60_000_000L, 1); bHls.type = 3;
    BrowserDownloadEntity bAudio = clip(109, CDN + "clip_1080p.mp4", 1920, 1080, 60_000_000L, 1); bAudio.audio = true;
    BrowserDownloadEntity bNoDur = clip(110, CDN + "clip_1080p.mp4", 1920, 1080, 0, 1);
    check("renditions: not another shape, a stream, audio, or an unknown duration",
        !CaptureRenditions.otherRendition(a, bTall) && !CaptureRenditions.otherRendition(a, bHls)
        && !CaptureRenditions.otherRendition(a, bAudio) && !CaptureRenditions.otherRendition(a, bNoDur));

    String[] srcs = {CDN + "x91f.mp4", CDN + "y72c.mp4"};
    BrowserDownloadEntity g = group(120, srcs[1], srcs, new int[] {720, 1080});
    BrowserDownloadEntity sA = clip(121, srcs[0], 1280, 720, 60_000_000L, 1);
    BrowserDownloadEntity sB = clip(122, srcs[1], 1920, 1080, 60_000_000L, 1);
    BrowserDownloadEntity sOther = clip(123, CDN + "z00a.mp4", 1920, 1080, 60_000_000L, 1);
    BrowserDownloadEntity sTab = clip(124, srcs[0], 1280, 720, 60_000_000L, 1); sTab.tabId = 8;
    check("renditions: a source list covers its own URL and its renditions'",
        CaptureRenditions.covers(g, sA) && CaptureRenditions.covers(g, sB) && CaptureRenditions.listsUrl(g, srcs[0] + "#t=1"));
    check("renditions: but no other file, tab, or capture with renditions",
        !CaptureRenditions.covers(g, sOther) && !CaptureRenditions.covers(g, sTab) && !CaptureRenditions.covers(sA, g)
        && !CaptureRenditions.covers(g, group(125, srcs[0], srcs, new int[] {720, 1080})));

    com.solarized.firedown.data.repository.BrowserDownloadRepository repo =
        new com.solarized.firedown.data.repository.BrowserDownloadRepository();
    repo.addValue(a);
    repo.addValue(b);
    List<BrowserDownloadEntity> l = listed(repo);
    BrowserDownloadEntity m = l.size() == 1 ? l.get(0) : null;
    check("repo: a second resolution folds into the first capture", m != null && m.uid == 101 && m.variants
        && m.streams.size() == 2 && m != a);
    check("repo: every folded rendition names its file and size",
        m != null && (CDN + "clip_720p.mp4").equals(m.streams.get(0).getStreamUrl()) && m.streams.get(0).size == 20_000_000L
        && (CDN + "clip_1080p.mp4").equals(m.streams.get(1).getStreamUrl()) && m.streams.get(1).size == 45_000_000L);
    CaptureFacts.Size fs = m != null ? CaptureFacts.size(m, byHeight) : null;
    check("repo: the tile states the best rendition and its own size", m != null
        && "1080p".equals(CaptureFacts.quality(m, byHeight)) && fs != null && !fs.estimate && fs.bytes == 45_000_000L
        && m.fileLength == 0 && m.tags.isEmpty());
    check("repo: the listed capture on screen is left alone", a.streams.size() == 1 && "".equals(a.streams.get(0).getStreamUrl())
        && !a.variants && a.fileLength == 20_000_000L);
    repo.addValue(clip(111, CDN + "clip_480p.mp4", 854, 480, 59_900_000L, 9_000_000L));
    l = listed(repo);
    check("repo: a third resolution joins the same capture", l.size() == 1 && l.get(0).streams.size() == 3);
    BrowserDownloadEntity again = clip(112, CDN + "clip_1080p.mp4", 1920, 1080, 60_000_000L, 45_000_000L);
    check("repo: a folded rendition captured again is a duplicate", repo.contains(again, 0));
    repo.addValue(again);
    repo.addValue(clip(113, "https://cdn.site.example/v/43/clip_1080p.mp4", 1920, 1080, 60_000_000L, 1));
    l = listed(repo);
    check("repo: another clip on the page stays its own capture", l.size() == 2);

    // the scrape's single <source>s first, then the bridge's source list
    com.solarized.firedown.data.repository.BrowserDownloadRepository r2 =
        new com.solarized.firedown.data.repository.BrowserDownloadRepository();
    r2.addValue(clip(131, srcs[0], 1280, 720, 60_000_000L, 1));
    r2.addValue(clip(132, srcs[1], 1920, 1080, 60_000_000L, 1));
    BrowserDownloadEntity otherTab = clip(133, srcs[0], 1280, 720, 60_000_000L, 1); otherTab.tabId = 8;
    r2.addValue(otherTab);
    BrowserDownloadEntity g2 = group(134, srcs[1], srcs, new int[] {720, 1080});
    check("repo: a source list is probed past a single capture of its URL", !r2.contains(g2, 2) && r2.contains(g2, 0));
    r2.addValue(g2);
    l = listed(r2);
    long onTab7 = l.stream().filter(x -> x.tabId == 7).count();
    check("repo: the source list replaces both single captures", onTab7 == 1
        && l.stream().anyMatch(x -> x == g2) && l.size() == 2);
    // ...and in the other order the singles are duplicates of it
    com.solarized.firedown.data.repository.BrowserDownloadRepository r3 =
        new com.solarized.firedown.data.repository.BrowserDownloadRepository();
    r3.addValue(group(141, srcs[1], srcs, new int[] {720, 1080}));
    BrowserDownloadEntity late = clip(142, srcs[0], 1280, 720, 60_000_000L, 1);
    check("repo: a single of a listed rendition is not probed", r3.contains(late, 0));
    r3.addValue(late);
    r3.addValue(clip(143, srcs[1], 1920, 1080, 60_000_000L, 1));
    l = listed(r3);
    check("repo: the source list stays the one capture", l.size() == 1 && l.get(0).uid == 141);
  }

  public static void main(String[] a) {
    // typeRank orders video, audio, subtitle, other, image
    check("rank", CaptureSections.typeRank(e(1,"video/mp4","u","o")) == 0
        && CaptureSections.typeRank(e(1,"audio/mp4","u","o")) == 1
        && CaptureSections.typeRank(e(1,"text/vtt","u","o")) == 2
        && CaptureSections.typeRank(e(1,"application/zip","u","o")) == 3
        && CaptureSections.typeRank(e(1,"image/png","u","o")) == 4);

    // every capture but an image is a tile (the Downloads grid tile); a video
    // is told apart for the caption count and the "Video on" name, and audio
    // in a video container (the entity's audio flag) is not one
    BrowserDownloadEntity m4a = e(2, "video/mp4", "a.m4a", "o"); m4a.audio = true;
    check("tiles are everything but images", CaptureSections.isTile(e(1,"video/mp4","u","o"))
        && CaptureSections.isTile(e(1,"audio/mp4","u","o")) && CaptureSections.isTile(e(1,"text/vtt","u","o"))
        && CaptureSections.isTile(e(1,"application/zip","u","o")) && !CaptureSections.isTile(e(1,"image/gif","u","o")));
    check("audio-flagged mp4 is audio, not video", !CaptureSections.isVideo(m4a) && CaptureSections.isAudioCapture(m4a)
        && CaptureSections.typeRank(m4a) == 1);
    check("hls master is a video, an audio playlist is not", CaptureSections.isVideo(e(1,"application/x-mpegurl","u","o"))
        && !CaptureSections.isVideo(e(1,"audio/mpegurl","u","o")));

    // current page: video, audio, sub, 3 images; earlier: video, 2 images
    String P = "https://clips.example/v", Q = "https://firedown.app/s";
    List<BrowserDownloadEntity> shown = List.of(
        e(1,"video/mp4","v1",P), e(2,"audio/mp4","a1",P), e(3,"text/vtt","s1",P),
        e(4,"image/gif","g1",P), e(5,"image/png","i1",P), e(6,"image/png","i2",P),
        e(7,"video/mp4","v2",Q), e(8,"image/png","i3",Q), e(9,"image/png","i4",Q));
    Set<Integer> current = Set.of(1,2,3,4,5,6);
    Map<String,Integer> cc = CaptureSections.captionCounts(shown);
    List<CaptureItem> items = CaptureSections.build(shown, x -> current.contains(x.getUid()), 3, 40, earlier(43, false, "firedown.app"), Map.of(), cc);
    check("fold closed: " + shape(items), shape(items).equals("T1c1w T2 T3 H0:3 S4 S5 S6 F43c"));

    // open, one earlier site: the fold names it, the tiles don't repeat it
    items = CaptureSections.build(shown, x -> current.contains(x.getUid()), 3, 40, earlier(43, true, "firedown.app"), Map.of(), cc);
    check("fold open: " + shape(items), shape(items).equals("T1c1w T2 T3 H0:3 S4 S5 S6 F43o T7 H1:40 S8 S9"));

    // an "Images" header heads the squares under it, up to the next row that
    // is not a square (the fold here): its select check takes exactly those,
    // and a square finds the header to repaint. Positions in the list above:
    // 0 T1, 1 T2, 2 T3, 3 H0, 4-6 squares, 7 F, 8 T7, 9 H1, 10-11 squares.
    check("squares under the page's header", CaptureSections.squaresUnder(items, 3).equals(List.of(4, 5, 6)));
    check("squares under the earlier header", CaptureSections.squaresUnder(items, 9).equals(List.of(10, 11)));
    check("only a header heads squares", CaptureSections.squaresUnder(items, 0).isEmpty()
        && CaptureSections.squaresUnder(items, 7).isEmpty() && CaptureSections.squaresUnder(items, 4).isEmpty()
        && CaptureSections.squaresUnder(items, -1).isEmpty() && CaptureSections.squaresUnder(items, 99).isEmpty());
    check("a square's header", CaptureSections.headerAbove(items, 4) == 3 && CaptureSections.headerAbove(items, 6) == 3
        && CaptureSections.headerAbove(items, 10) == 9 && CaptureSections.headerAbove(items, 11) == 9);
    check("no header for a non-square", CaptureSections.headerAbove(items, 1) == -1
        && CaptureSections.headerAbove(items, 3) == -1 && CaptureSections.headerAbove(items, 7) == -1
        && CaptureSections.headerAbove(items, -1) == -1);

    // open, several earlier sites: each earlier tile carries its own
    items = CaptureSections.build(shown, x -> current.contains(x.getUid()), 3, 40, earlier(43, true, "firedown.app", "a.example"), Map.of(), cc);
    check("fold open, many sites: " + shape(items), shape(items).equals("T1c1w T2 T3 H0:3 S4 S5 S6 F43o T7d H1:40 S8 S9"));

    // a page of images only: the header still leads them (it carries the
    // section's select check), and the fold follows
    List<BrowserDownloadEntity> imgs = List.of(e(5,"image/png","i1",P), e(6,"image/png","i2",P), e(8,"image/png","i3",Q));
    items = CaptureSections.build(imgs, x -> current.contains(x.getUid()), 2, 1, earlier(1, true, "firedown.app"), Map.of(), Map.of());
    check("images only, open: " + shape(items), shape(items).equals("H0:2 S5 S6 F1o H1:1 S8"));
    items = CaptureSections.build(imgs, x -> current.contains(x.getUid()), 2, 1, earlier(1, false, "firedown.app"), Map.of(), Map.of());
    check("images only, closed: " + shape(items), shape(items).equals("H0:2 S5 S6 F1c"));

    // no current page (home): everything is earlier, under the fold as a heading
    items = CaptureSections.build(shown, x -> false, 0, 4, earlierOnly(9, "clips.example", "firedown.app"), Map.of(), Map.of());
    check("no anchor: " + shape(items), shape(items).equals("F9h T1d T2d T3d H1:4 S4 S5 S6 T7d S8 S9"));
    // (input not re-sorted, so two squares sit after a tile, under no header
    // of their own: the header above the tile is not theirs, and its check
    // takes only the squares right under it)
    check("a tile ends a header's squares", CaptureSections.squaresUnder(items, 4).equals(List.of(5, 6, 7))
        && CaptureSections.headerAbove(items, 9) == -1 && CaptureSections.headerAbove(items, 10) == -1
        && CaptureSections.headerAbove(items, 7) == 4);

    // the page caught nothing yet: the earlier videos under the heading,
    // never a lone fold
    List<BrowserDownloadEntity> vids = List.of(e(7,"video/mp4","v2",Q), e(10,"video/mp4","v3",Q));
    items = CaptureSections.build(vids, x -> current.contains(x.getUid()), 0, 0, earlierOnly(2, "firedown.app"), Map.of(), Map.of());
    check("page has none: " + shape(items), shape(items).equals("F2h T7 T10"));

    // the open decision: nothing of the page listed opens it whatever the
    // user chose before; otherwise the user's choice, closed until they make
    // one
    check("open: page empty, no choice", CaptureSections.earlierOpen(null, 0));
    check("open: page empty, closed before", CaptureSections.earlierOpen(Boolean.FALSE, 0));
    check("closed: page listed, no choice", !CaptureSections.earlierOpen(null, 5));
    check("closed: page listed, closed", !CaptureSections.earlierOpen(Boolean.FALSE, 5));
    check("open: page listed, opened", CaptureSections.earlierOpen(Boolean.TRUE, 5));
    // (the second video sits after the squares because the input was not
    // re-sorted for "one group" here; the builder keeps input order inside a
    // group, and the view model's sort puts the tiles first.)

    // only the current page: no fold
    items = CaptureSections.build(shown.subList(0, 6), x -> true, 3, 0, CaptureSections.Earlier.NONE, Map.of(), Map.of());
    check("current only: " + shape(items), shape(items).equals("T1w T2 T3 H0:3 S4 S5 S6"));

    // the limit cut the list before any earlier capture: the fold still ends it
    items = CaptureSections.build(shown.subList(0, 2), x -> true, 3, 0, earlier(5, false, "firedown.app"), Map.of(), Map.of());
    check("limit before the fold: " + shape(items), shape(items).equals("T1w T2 F5c"));

    // the CC count lands on the video only, never on the audio-flagged mp4
    BrowserDownloadEntity cm = e(11, "video/mp4", "m", P); cm.audio = true;
    items = CaptureSections.build(List.of(e(1,"video/mp4","v1",P), cm), x -> true, 0, 0, CaptureSections.Earlier.NONE, Map.of(), Map.of(P, 2));
    check("cc on video only: " + shape(items), shape(items).equals("T1c2w T11"));

    // the page's only video spans the row; with two, neither does (no video
    // is "the" one), and an earlier page's lone video never does
    List<BrowserDownloadEntity> twoVids = List.of(e(1,"video/mp4","v1",P), e(12,"video/mp4","v4",P), e(3,"text/vtt","s1",P));
    items = CaptureSections.build(twoVids, x -> true, 0, 0, CaptureSections.Earlier.NONE, Map.of(), Map.of());
    check("two videos, none wide: " + shape(items), shape(items).equals("T1 T12 T3"));
    items = CaptureSections.build(List.of(e(7,"video/mp4","v2",Q)), x -> false, 0, 0, earlierOnly(1, "firedown.app"), Map.of(), Map.of());
    check("earlier lone video, not wide: " + shape(items), shape(items).equals("F1h T7"));
    // the page's one video spans even with an earlier page's video open below
    items = CaptureSections.build(List.of(e(1,"video/mp4","v1",P), e(7,"video/mp4","v2",Q)), x -> x.getUid() == 1, 0, 0, earlier(1, true, "firedown.app"), Map.of(), Map.of());
    check("one video, earlier open: " + shape(items), shape(items).equals("T1w F1o T7"));

    // the fold's thumbnails: pictures first, anything else only to fill
    List<BrowserDownloadEntity> early = List.of(e(3,"text/vtt","s",Q), e(8,"image/png","i",Q), e(7,"video/mp4","v",Q),
        e(12,"application/zip","z",Q), e(9,"image/png","j",Q));
    List<Integer> pv = new ArrayList<>(); for (BrowserDownloadEntity x : CaptureSections.preview(early)) pv.add(x.getUid());
    check("preview pictures first: " + pv, pv.equals(List.of(8, 7, 9)));
    pv.clear(); for (BrowserDownloadEntity x : CaptureSections.preview(List.of(e(3,"text/vtt","s",Q), e(12,"application/zip","z",Q)))) pv.add(x.getUid());
    check("preview fills with the rest: " + pv, pv.equals(List.of(3, 12)));

    // a filename is not a title: "Video on <site>" takes its place
    for (String junk : new String[] { null, "", "src", "master.m3u8", "index.m3u8", "videoplayback",
        "6aaaf5204d3859992c30a380.mp4", "720p.mp4", "IMG_20240101_1234.mp4" }) {
      check("filename-like: " + junk, CaptureSections.isFilenameLike(junk));
    }
    for (String title : new String[] { "Northern Lights.mp4", "Coral_Reef.mp4", "coral-reef", "Interstellar",
        "Ambient visuals for focus", "MyVideo.mp4", "Episode 12" }) {
      check("a title: " + title, !CaptureSections.isFilenameLike(title));
    }

    // marks by URL: stronger wins, FILE_NOT_FOUND is no mark
    Map<String,CaptureMark> byUrl = CaptureSections.marksByUrl(List.of(
        row("v1", Download.ERROR, 0, false, MessageHelper.IOEXCEPTION),
        row("v1", Download.PROGRESS, 42, false, 0),
        row("a1", Download.FINISHED, 100, false, 0),
        row("s1", Download.ERROR, 0, false, MessageHelper.FILE_NOT_FOUND),
        row("q720", Download.QUEUED, 0, false, 0),
        row("i1", Download.PROGRESS, Download.PROCESSING_PROGRESS, false, 0),
        row("i2", Download.PROGRESS, 0, true, 0),
        row("i3", Download.ERROR, 0, false, MessageHelper.BAD_REQUEST)));
    check("progress beats error", byUrl.get("v1").state == CaptureMark.PROGRESS && byUrl.get("v1").percent == 42);
    check("finished is saved", byUrl.get("a1").state == CaptureMark.SAVED);
    check("file-not-found is no mark", !byUrl.containsKey("s1"));
    check("processing = finishing", byUrl.get("i1").finishing && byUrl.get("i1").indeterminate && byUrl.get("i1").percent == 100);
    check("live = indeterminate", byUrl.get("i2").indeterminate && !byUrl.get("i2").finishing);
    check("failed", byUrl.get("i3").state == CaptureMark.FAILED);

    // marks by uid: a variant's URL matches its capture
    BrowserDownloadEntity v = e(20, "video/mp4", "master.m3u8", P);
    v.streams.add(new FFmpegEntity("q1080")); v.streams.add(new FFmpegEntity("q720")); v.streams.add(new FFmpegEntity((String) null));
    BrowserDownloadEntity saved = e(21, "audio/mp4", "a1", P);
    BrowserDownloadEntity none = e(22, "image/png", "nothing", P);
    Map<Integer,CaptureMark> byUid = CaptureSections.marksByUid(List.of(v, saved, none), byUrl);
    check("variant url matches", byUid.get(20) != null && byUid.get(20).state == CaptureMark.PENDING);
    check("own url matches", byUid.get(21).state == CaptureMark.SAVED);
    check("unmatched has none", !byUid.containsKey(22));
    // the capture URL AND a variant both matched: the stronger mark wins
    BrowserDownloadEntity both = e(23, "video/mp4", "a1", P); both.streams.add(new FFmpegEntity("q720"));
    check("stronger across keys", CaptureSections.marksByUid(List.of(both), byUrl).get(23).state == CaptureMark.PENDING);

    // stronger ordering
    CaptureMark prog = CaptureMark.fromRow(Download.PROGRESS, 10, false, 0), pend = CaptureMark.fromRow(Download.QUEUED, 0, false, 0),
        sav = CaptureMark.fromRow(Download.FINISHED, 0, false, 0), fl = CaptureMark.fromRow(Download.ERROR, 0, false, 1);
    check("stronger", CaptureMark.stronger(sav, prog) == prog && CaptureMark.stronger(pend, sav) == pend
        && CaptureMark.stronger(fl, sav) == sav && CaptureMark.stronger(null, fl) == fl && CaptureMark.stronger(pend, null) == pend);
    check("mark equality", prog.equals(CaptureMark.fromRow(Download.PROGRESS, 10, false, 0)) && !prog.equals(CaptureMark.fromRow(Download.PROGRESS, 11, false, 0)));

    // batch summary: no row yet counts as pending
    Map<Integer,CaptureMark> bm = new HashMap<>();
    bm.put(1, sav); bm.put(2, prog); bm.put(3, pend); bm.put(4, fl);
    BatchSummary s = CaptureSections.summarize(List.of(1,2,3,4,5), bm);
    check("summary counts", s.total == 5 && s.saved == 1 && s.downloading == 1 && s.pending == 2 && s.failed == 1 && !s.isDone());
    check("summary percent", s.percent == (100 + 10 + 0 + 100 + 0) / 5);
    check("summary done", CaptureSections.summarize(List.of(1,4), bm).isDone());

    // origins: distinct, non-empty, capped
    List<BrowserDownloadEntity> many = new ArrayList<>();
    for (int i = 0; i < 1000; i++) { many.add(e(i, "image/png", "u" + i, "o" + i)); many.add(e(i, "image/png", "u", "o" + i)); }
    many.add(e(5000, "image/png", "u", null)); many.add(e(5001, "image/png", "u", ""));
    List<String> origins = CaptureSections.origins(many);
    check("origins capped", origins.size() == CaptureSections.MAX_ORIGINS && origins.get(0).equals("o0") && new HashSet<>(origins).size() == origins.size());

    // a subtitle rides with its video when the video's ⋮ offers it: a video
    // with quality variants is a caption door for its page; one quality (no
    // ⋮), an audio capture in a video container, or no origin is not
    BrowserDownloadEntity door = e(40, "video/mp4", "dv", P); door.variants = true;
    BrowserDownloadEntity oneQuality = e(41, "video/mp4", "ov", Q);
    BrowserDownloadEntity audioDoor = e(42, "video/mp4", "ad.m4a", "https://r.example/a"); audioDoor.audio = true; audioDoor.variants = true;
    BrowserDownloadEntity noOrigin = e(43, "video/mp4", "nv", null); noOrigin.variants = true;
    Set<String> doors = CaptureSections.captionDoors(List.of(door, oneQuality, audioDoor, noOrigin));
    check("caption doors: " + doors, doors.equals(Set.of(P)));
    check("track rides with its video", CaptureSections.ridesWithVideo(e(44, "text/vtt", "t1", P), doors));
    check("track with no door keeps its tile", !CaptureSections.ridesWithVideo(e(45, "text/vtt", "t2", Q), doors)
        && !CaptureSections.ridesWithVideo(e(46, "text/vtt", "t3", null), doors)
        && !CaptureSections.ridesWithVideo(e(44, "text/vtt", "t1", P), Set.of()));
    check("only tracks ride", !CaptureSections.ridesWithVideo(door, doors)
        && !CaptureSections.ridesWithVideo(e(47, "image/png", "i", P), doors));
    // what the view model does: drop the riding tracks, keep the
    // CC count (counted over the whole tab), so the page's video is the
    // page's only tile and spans; a one-quality video's track stays beside it
    BrowserDownloadEntity v1 = e(1, "video/mp4", "v1", P); v1.variants = true;
    List<BrowserDownloadEntity> tabJ = List.of(v1, e(3, "text/vtt", "s1", P),
        e(5, "image/png", "i1", P), oneQuality, e(48, "text/vtt", "s2", Q));
    Set<String> doorsJ = CaptureSections.captionDoors(tabJ);
    List<BrowserDownloadEntity> shownJ = tabJ.stream().filter(t -> !CaptureSections.ridesWithVideo(t, doorsJ)).toList();
    Set<Integer> currentJ = Set.of(1, 3, 5);
    items = CaptureSections.build(shownJ, t -> currentJ.contains(t.getUid()), 1, 0,
        earlier(2, true, "firedown.app"), Map.of(), CaptureSections.captionCounts(tabJ));
    check("track left to its video: " + shape(items), shape(items).equals("T1c1w H0:1 S5 F2o T41c1 T48"));

    // diff: a mark-only change is a payload; a presentation change is not
    CaptureItemDiffCallback diff = new CaptureItemDiffCallback();
    BrowserDownloadEntity x = e(30, "video/mp4", "v", P);
    CaptureItem a0 = CaptureItem.tile(x, 0, false, false, 0, null);
    CaptureItem a1 = CaptureItem.tile(x, 0, false, false, 0, prog);
    CaptureItem a2 = CaptureItem.tile(x, 0, true, false, 0, null);
    check("same item", diff.areItemsTheSame(a0, a1) && diff.areItemsTheSame(a0, a2));
    check("mark change differs", !diff.areContentsTheSame(a0, a1) && diff.getChangePayload(a0, a1) == CaptureItemDiffCallback.PAYLOAD_MARK);
    check("presentation change no payload", !diff.areContentsTheSame(a0, a2) && diff.getChangePayload(a0, a2) == null);
    // a second video landing un-spans the first: a full rebind, not a mark payload
    CaptureItem aw = CaptureItem.tile(x, 0, false, true, 0, null);
    check("only-video change rebinds", diff.areItemsTheSame(a0, aw) && !diff.areContentsTheSame(a0, aw) && diff.getChangePayload(a0, aw) == null);
    check("headers by group", diff.areItemsTheSame(CaptureItem.header(0, 3), CaptureItem.header(0, 4))
        && !diff.areItemsTheSame(CaptureItem.header(0, 3), CaptureItem.header(1, 3))
        && !diff.areContentsTheSame(CaptureItem.header(0, 3), CaptureItem.header(0, 4)));
    check("tile vs square never same", !diff.areItemsTheSame(a0, CaptureItem.square(x, 0, null)));
    CaptureItem f0 = CaptureItem.fold(3, false, true, List.of("a"), List.of(x)), f1 = CaptureItem.fold(3, true, true, List.of("a"), List.of(x));
    check("fold same", diff.areContentsTheSame(f0, CaptureItem.fold(3, false, true, List.of("a"), List.of(x))));
    check("fold open rebinds", diff.areItemsTheSame(f0, f1) && !diff.areContentsTheSame(f0, f1) && diff.getChangePayload(f0, f1) == null);
    check("fold count, sites, preview rebind", !diff.areContentsTheSame(f0, CaptureItem.fold(4, false, true, List.of("a"), List.of(x)))
        && !diff.areContentsTheSame(f0, CaptureItem.fold(3, false, true, List.of("a", "b"), List.of(x)))
        && !diff.areContentsTheSame(f0, CaptureItem.fold(3, false, true, List.of("a"), List.of(e(31, "video/mp4", "w", P)))));
    // a heading becoming a toggle (the page's first capture lands) is a rebind
    CaptureItem fh = CaptureItem.fold(3, true, false, List.of("a"), List.of(x));
    check("fold heading -> toggle rebinds", diff.areItemsTheSame(fh, f1) && !diff.areContentsTheSame(fh, f1));

    // ── CaptureFacts: the quality a tap downloads and the size beside it ──
    Comparator<FFmpegEntity> byHeight = (p, q) -> Integer.compare(q.height, p.height);
    BrowserDownloadEntity ladder = e(40, "video/mp4", "v", P);
    FFmpegEntity r360 = new FFmpegEntity("360p", 640, 360), r1080 = new FFmpegEntity("1080p", 1920, 1080),
        r720 = new FFmpegEntity("720p", 1280, 720);
    ladder.streams.addAll(List.of(r360, r1080, r720));
    check("facts: the quality is the rendition a tap downloads (the best)",
        "1080p".equals(CaptureFacts.quality(ladder, byHeight)) && CaptureFacts.defaultVariant(ladder, byHeight) == r1080);
    check("facts: the entity's list is left in its order", ladder.streams.get(0) == r360);
    BrowserDownloadEntity single = e(41, "video/mp4", "v", P);
    single.tags.add(new FFmpegTagEntity(FFmpegTagEntity.TYPE_QUALITY, "720p"));
    check("facts: no streams -> the quality tag", "720p".equals(CaptureFacts.quality(single, byHeight)));
    BrowserDownloadEntity audioOnly = e(42, "video/mp4", "v", P);
    FFmpegEntity ao = new FFmpegEntity("44.1 kHz", 0, 0); ao.audioOnly = true; audioOnly.streams.add(ao);
    check("facts: an audio-only rendition names no video quality", CaptureFacts.quality(audioOnly, byHeight) == null);

    // size: the rendition's exact size, else the capture's length, else bitrate x duration
    ladder.durationUs = 60_000_000L;
    r1080.bitrate = 2_000_000;
    CaptureFacts.Size est = CaptureFacts.size(ladder, byHeight);
    check("facts: bitrate x duration is an ESTIMATE (2 Mbit/s x 60 s = 15 MB)",
        est != null && est.estimate && est.bytes == 15_000_000L);
    ladder.fileLength = 99_000_000L;
    CaptureFacts.Size len = CaptureFacts.size(ladder, byHeight);
    check("facts: the capture's own length beats an estimate, and is exact",
        len != null && !len.estimate && len.bytes == 99_000_000L);
    r1080.size = 48_234_567L;
    CaptureFacts.Size exact = CaptureFacts.size(ladder, byHeight);
    check("facts: the rendition's stated size beats both", exact != null && !exact.estimate && exact.bytes == 48_234_567L);
    check("facts: a rendition with neither size nor bitrate has no size",
        CaptureFacts.variantSize(r720, ladder.durationUs) == null && CaptureFacts.size(e(43, "video/mp4", "v", P), byHeight) == null);
    r720.bitrate = 1_000_000;
    check("facts: no duration, no estimate", CaptureFacts.variantSize(r720, 0) == null
        && CaptureFacts.variantSize(r720, 8_000_000L).bytes == 1_000_000L);

    // a download row's resolution as the picker's label (its height)
    check("facts: resolution -> quality", "720p".equals(CaptureFacts.qualityOfResolution("1280x720"))
        && "1280p".equals(CaptureFacts.qualityOfResolution("720x1280")));
    check("facts: not a resolution -> null", CaptureFacts.qualityOfResolution(null) == null
        && CaptureFacts.qualityOfResolution("720p") == null && CaptureFacts.qualityOfResolution("x720") == null
        && CaptureFacts.qualityOfResolution("1280x") == null && CaptureFacts.qualityOfResolution("0x720") == null);

    // the picker names the codec only where the ladder mixes families
    FFmpegEntity c1 = new FFmpegEntity("2160p", 3840, 2160), c2 = new FFmpegEntity("1080p", 1920, 1080),
        c3 = new FFmpegEntity("720p", 1280, 720);
    c1.videoCodec = "av01.0.12M.08"; c2.videoCodec = "avc1.640028"; c3.videoCodec = "avc1.4d401f";
    check("facts: AV1 above H.264 is a mixed ladder", CaptureFacts.mixedVideoCodecs(List.of(c1, c2, c3)));
    check("facts: one family with different profiles is not", !CaptureFacts.mixedVideoCodecs(List.of(c2, c3)));
    FFmpegEntity aud = new FFmpegEntity("audio", 0, 0); aud.audioOnly = true; aud.videoCodec = "mp4a";
    check("facts: an audio-only rung never makes a ladder mixed", !CaptureFacts.mixedVideoCodecs(List.of(c2, aud)));
    check("facts: codec family", "av01".equals(CaptureFacts.codecFamily("av01.0.08M.08"))
        && "h264".equals(CaptureFacts.codecFamily("h264")) && CaptureFacts.codecFamily(" ") == null);

    // a download mark carries the row's resolution (the PICKED quality)
    CaptureDownloadState picked = row("v720", Download.PROGRESS, 40, false, 0); picked.resolution = "1280x720";
    CaptureMark pm = CaptureSections.marksByUrl(List.of(picked)).get("v720");
    check("mark: carries the download row's resolution", pm != null && "1280x720".equals(pm.resolution));
    check("mark: resolution is part of its value", !pm.equals(CaptureMark.fromRow(Download.PROGRESS, 40, false, 0))
        && pm.equals(CaptureMark.fromRow(Download.PROGRESS, 40, false, 0, "1280x720"))
        && CaptureMark.fromRow(Download.PROGRESS, 40, false, 0, "").resolution == null);

    renditions(byHeight);

    System.out.println(pass + " passed, " + fail + " failed");
    if (fail > 0) System.exit(1);
  }
}
