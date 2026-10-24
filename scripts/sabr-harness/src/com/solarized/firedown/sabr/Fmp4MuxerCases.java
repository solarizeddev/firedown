package com.solarized.firedown.sabr;

import static com.solarized.firedown.sabr.Fmp4Fixtures.*;
import static com.solarized.firedown.sabr.SabrHarness.check;
import static com.solarized.firedown.sabr.SabrHarness.sec;

import android.util.Log;
import okhttp3.OkHttpClient;
import okhttp3.Response;
import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The inline fMP4 muxer over real ISO-BMFF fixtures. The muxer's output is
 * the ONLY copy of a SABR download, so what it writes is parsed back box by
 * box and compared with the 14496-12 layouts, never with its own log lines.
 */
final class Fmp4MuxerCases {

    /** The inits' OWN track ids — the muxer must renumber them to 1 (video) and 2 (audio). */
    static final int V_ID = 7, A_ID = 9;
    static final long MOVIE_TS = 1000, V_TS = 90_000, A_TS = 44_100;
    static final long SEG_MS = 5_000;
    static final long FIRST_VIDEO_TFDT = 9000;

    static byte[] videoInit() { return init(V_ID, true, MOVIE_TS, V_TS); }
    static byte[] audioInit() { return init(A_ID, false, MOVIE_TS, A_TS); }
    static byte[] vfrag(int seq, int bytes) {
        return fragment(V_ID, seq, FIRST_VIDEO_TFDT + (seq - 1) * SEG_MS * 90, SEG_MS * 90, payload(seq, bytes));
    }
    static byte[] afrag(int seq, int bytes) {
        return fragment(A_ID, seq, (seq - 1) * SEG_MS * 441 / 10, SEG_MS * 441 / 10, payload(100 + seq, bytes));
    }

    static File out() throws Exception {
        File f = Files.createTempFile("mux", ".mp4").toFile();
        f.delete();
        f.deleteOnExit();
        return f;
    }

    /** Feeds a scripted sequence to a fresh muxer; returns the file bytes after close(). */
    static final class Run {
        final Fmp4Muxer muxer;
        final File file;
        Run(long expectedMs) throws Exception { file = out(); muxer = new Fmp4Muxer(file, expectedMs); }
        Run init(boolean audio, byte[] data) { muxer.onSegment(audio, true, 0, data); return this; }
        Run media(boolean audio, byte[] data) { muxer.onSegment(audio, false, SEG_MS, data); return this; }
        Run video(int seq) { return media(false, vfrag(seq, 64)); }
        Run audio(int seq) { return media(true, afrag(seq, 32)); }
        byte[] close() throws Exception {
            muxer.close();
            return file.exists() ? Files.readAllBytes(file.toPath()) : new byte[0];
        }
    }

    static boolean eq(byte[] a, byte[] b) { return Arrays.equals(a, b); }

    static void run() {
        // ── M1 header: ftyp, one moov with both traks renumbered, the sidx slot ──
        sec("M1", () -> {
            Run r = new Run(60_000).init(false, videoInit()).init(true, audioInit());
            r.video(1).audio(1).video(2).audio(2).video(3).audio(3);
            byte[] f = r.close();
            List<Box> top = topLevel(f);
            check("M1 the file is ftyp moov sidx free, then moof/mdat pairs in arrival order",
                    types(top).equals("ftyp moov sidx free moof mdat moof mdat moof mdat moof mdat moof mdat moof mdat"),
                    types(top));
            Box moov = first(top, "moov");
            List<Box> mc = children(f, moov);
            check("M1b the moov is mvhd + video trak + audio trak + mvex", types(mc).equals("mvhd trak trak mvex"), types(mc));
            List<Box> traks = all(mc, "trak");
            check("M1c tkhd track_IDs are renumbered to 1 (video) and 2 (audio)",
                    traks.size() == 2 && tkhdTrackId(f, traks.get(0)) == 1 && tkhdTrackId(f, traks.get(1)) == 2,
                    traks.size() == 2 ? tkhdTrackId(f, traks.get(0)) + "/" + tkhdTrackId(f, traks.get(1)) : "traks=" + traks.size());
            List<Box> trex = all(children(f, first(mc, "mvex")), "trex");
            check("M1d the mvex carries both trex, renumbered the same way",
                    trex.size() == 2 && trexTrackId(f, trex.get(0)) == 1 && trexTrackId(f, trex.get(1)) == 2, "trex=" + trex.size());
            check("M1e mvhd next_track_ID is 3", mvhdNextTrackId(f, moov) == 3, "" + mvhdNextTrackId(f, moov));
            check("M1f the muxer reports a written header and no failure",
                    r.muxer.isHeaderWritten() && r.muxer.hasHeader() && !r.muxer.isFailed(), "");
        });

        // ── M2 fragments: tfhd renumbered, mdat verbatim, styp + per-segment sidx dropped ──
        sec("M2", () -> {
            Run r = new Run(60_000).init(false, videoInit()).init(true, audioInit());
            r.video(1).audio(1).video(2).audio(2);
            byte[] f = r.close();
            List<Box> top = topLevel(f);
            List<Box> moofs = all(top, "moof");
            List<Box> mdats = all(top, "mdat");
            boolean ids = moofs.size() == 4
                    && moofTrackId(f, moofs.get(0)) == 1 && moofTrackId(f, moofs.get(1)) == 2
                    && moofTrackId(f, moofs.get(2)) == 1 && moofTrackId(f, moofs.get(3)) == 2;
            check("M2 every moof's tfhd track_ID is rewritten: video 1, audio 2", ids,
                    moofs.size() == 4 ? moofTrackId(f, moofs.get(0)) + "," + moofTrackId(f, moofs.get(1)) : "moofs=" + moofs.size());
            boolean payloads = mdats.size() == 4
                    && eq(mdatPayload(f, mdats.get(0)), payload(1, 64)) && eq(mdatPayload(f, mdats.get(1)), payload(101, 32))
                    && eq(mdatPayload(f, mdats.get(2)), payload(2, 64)) && eq(mdatPayload(f, mdats.get(3)), payload(102, 32));
            check("M2b the mdat bytes are the segments' own, in arrival order", payloads, "mdats=" + mdats.size());
            check("M2c the per-segment styp and sidx boxes are not written (one whole-file sidx)",
                    all(top, "styp").isEmpty() && all(top, "sidx").size() == 1, types(top));
        });

        // ── M3 the whole-file sidx ──
        sec("M3", () -> {
            Run r = new Run(60_000).init(false, videoInit()).init(true, audioInit());
            r.video(1).audio(1).video(2).audio(2).video(3).audio(3);
            byte[] f = r.close();
            List<Box> top = topLevel(f);
            Box ftyp = first(top, "ftyp"), moov = first(top, "moov"), sidx = first(top, "sidx"), free = first(top, "free");
            check("M3 the sidx sits right after the moov", sidx != null && sidx.off == ftyp.size + moov.size, String.valueOf(sidx));
            Sidx s = sidx(f, sidx);
            check("M3b it indexes track 1 in the video timescale, from the first video tfdt",
                    s.referenceId == 1 && s.timescale == V_TS && s.ept == FIRST_VIDEO_TFDT,
                    "ref=" + s.referenceId + " ts=" + s.timescale + " ept=" + s.ept);
            List<Box> vmoofs = new ArrayList<>();
            for (Box m : all(top, "moof")) if (moofTrackId(f, m) == 1) vmoofs.add(m);
            check("M3c one reference per video fragment", s.count == 3 && vmoofs.size() == 3, "count=" + s.count);
            boolean sizes = s.count == 3
                    && s.sizes[0] == vmoofs.get(1).off - vmoofs.get(0).off
                    && s.sizes[1] == vmoofs.get(2).off - vmoofs.get(1).off
                    && s.sizes[2] == f.length - vmoofs.get(2).off;
            check("M3d each referenced_size spans from one video moof to the next (the last to EOF)", sizes,
                    s.count == 3 ? Arrays.toString(s.sizes) : "");
            boolean durs = s.count == 3 && s.durations[0] == SEG_MS * 90 && s.durations[1] == SEG_MS * 90 && s.durations[2] == SEG_MS * 90;
            check("M3e each subsegment_duration is the fragment's duration in video ticks", durs, Arrays.toString(s.durations));
            check("M3f every reference starts with a SAP of type 1", s.count == 3 && s.flags[0] == 0x90000000L && s.flags[2] == 0x90000000L, "");
            check("M3g first_offset points at the first video moof, past the free pad",
                    sidx.end() + s.firstOffset == vmoofs.get(0).off && free.off == sidx.end() && free.end() == vmoofs.get(0).off,
                    "firstOffset=" + s.firstOffset + " free=" + free);
            check("M3h sidx + pad fill exactly the reserved slot", sidx.size + free.size == Fmp4Muxer.reserveFor(60_000),
                    (sidx.size + free.size) + " vs " + Fmp4Muxer.reserveFor(60_000));
        });

        // ── M4 durations patched to what was downloaded ──
        sec("M4", () -> {
            Run r = new Run(60_000).init(false, videoInit()).init(true, audioInit());
            r.video(1).audio(1).video(2).audio(2).video(3).audio(3);
            byte[] f = r.close();
            Box moov = first(topLevel(f), "moov");
            List<Box> traks = all(children(f, moov), "trak");
            check("M4 mvhd duration is the downloaded length in the movie timescale (not the init's placeholder)",
                    mvhdDuration(f, moov) == 15_000, "" + mvhdDuration(f, moov));
            check("M4b both tkhd durations are patched (movie timescale)",
                    tkhdDuration(f, traks.get(0)) == 15_000 && tkhdDuration(f, traks.get(1)) == 15_000,
                    tkhdDuration(f, traks.get(0)) + "/" + tkhdDuration(f, traks.get(1)));
            check("M4c both mdhd durations are patched in their own media timescale",
                    mdhdDuration(f, traks.get(0)) == 15_000 * 90 && mdhdDuration(f, traks.get(1)) == 15_000 * 441 / 10,
                    mdhdDuration(f, traks.get(0)) + "/" + mdhdDuration(f, traks.get(1)));

            Run u = new Run(60_000).init(false, videoInit()).init(true, audioInit());
            u.video(1).audio(1).video(2).audio(2).audio(3);     // audio ran 5 s longer
            byte[] g = u.close();
            Box moov2 = first(topLevel(g), "moov");
            List<Box> t2 = all(children(g, moov2), "trak");
            check("M4d uneven tracks: mvhd takes the longer, each tkhd its own",
                    mvhdDuration(g, moov2) == 15_000 && tkhdDuration(g, t2.get(0)) == 10_000 && tkhdDuration(g, t2.get(1)) == 15_000
                            && mdhdDuration(g, t2.get(0)) == 10_000 * 90,
                    mvhdDuration(g, moov2) + " " + tkhdDuration(g, t2.get(0)) + " " + tkhdDuration(g, t2.get(1)));
        });

        // ── M5 media that arrives before both inits is buffered, then written in order ──
        sec("M5", () -> {
            Run r = new Run(60_000);
            r.video(1).audio(1).init(false, videoInit()).init(true, audioInit()).video(2).audio(2);
            byte[] f = r.close();
            List<Box> top = topLevel(f);
            List<Box> mdats = all(top, "mdat");
            check("M5 fragments seen before the inits land after the header, in arrival order",
                    types(top).equals("ftyp moov sidx free moof mdat moof mdat moof mdat moof mdat")
                            && mdats.size() == 4
                            && eq(mdatPayload(f, mdats.get(0)), payload(1, 64)) && eq(mdatPayload(f, mdats.get(1)), payload(101, 32))
                            && eq(mdatPayload(f, mdats.get(2)), payload(2, 64)),
                    types(top));
            check("M5b ...and the muxer did not fail", r.muxer.isHeaderWritten() && !r.muxer.isFailed(), "");
        });

        // ── M6 the sidx slot is sized from the clip's declared length ──
        sec("M6", () -> {
            check("M6 no declared length → the 128 KB floor", Fmp4Muxer.reserveFor(0) == 128 * 1024, "" + Fmp4Muxer.reserveFor(0));
            check("M6b a 60 s clip → the floor", Fmp4Muxer.reserveFor(60_000) == 128 * 1024, "" + Fmp4Muxer.reserveFor(60_000));
            long twelveHours = 12L * 3600 * 1000;
            check("M6c a 12 h clip → room for one reference per second (518 452 bytes)",
                    Fmp4Muxer.reserveFor(twelveHours) == 32 + (twelveHours / 1000 + 1) * 12 + 8, "" + Fmp4Muxer.reserveFor(twelveHours));
            check("M6d a bogus length is capped at 16 MB", Fmp4Muxer.reserveFor(Long.MAX_VALUE / 4) == 16 * 1024 * 1024,
                    "" + Fmp4Muxer.reserveFor(Long.MAX_VALUE / 4));
            Run r = new Run(twelveHours).init(false, videoInit()).init(true, audioInit());
            r.video(1).audio(1);
            byte[] f = r.close();
            List<Box> top = topLevel(f);
            Box sidx = first(top, "sidx"), free = first(top, "free");
            check("M6e the slot written for a 12 h clip is that size",
                    sidx != null && free != null && sidx.size + free.size == Fmp4Muxer.reserveFor(twelveHours),
                    sidx == null ? "no sidx" : "" + (sidx.size + free.size));
        });

        // ── M7 a long clip with short fragments is indexed (the fixed 128 KB slot overflowed at ~10 900) ──
        sec("M7", () -> {
            int frags = 11_000;
            Run r = new Run(frags * 1000L).init(false, videoInit()).init(true, audioInit());
            for (int i = 1; i <= frags; i++) {
                r.muxer.onSegment(false, false, 1000, moofMdat(V_ID, i, (i - 1) * 90_000L, 90_000, payload(i, 8)));
                if (i % 2200 == 0) r.muxer.onSegment(true, false, 2200_000, moofMdat(A_ID, i / 2200, 0, 1, payload(i, 4)));
            }
            byte[] f = r.close();
            List<Box> top = topLevel(f);
            Box sidx = first(top, "sidx");
            check("M7 11 000 one-second fragments: the muxer does not give up",
                    r.muxer.isHeaderWritten() && !r.muxer.isFailed(), "failed=" + r.muxer.isFailed());
            check("M7b ...the sidx holds one reference per fragment",
                    sidx != null && sidx(f, sidx).count == frags, sidx == null ? "no sidx" : "" + sidx(f, sidx).count);
            check("M7c ...and the mvhd says 11 000 s", mvhdDuration(f, first(top, "moov")) == frags * 1000L,
                    "" + mvhdDuration(f, first(top, "moov")));
        });

        // ── M7d more fragments than a 16-bit reference_count holds → give up, not a corrupt sidx ──
        sec("M7d", () -> {
            int frags = 65_600;
            Run r = new Run(frags * 1000L).init(false, videoInit()).init(true, audioInit());
            r.audio(1);
            for (int i = 1; i <= frags; i++) {
                r.muxer.onSegment(false, false, 1000, moofMdat(V_ID, i, (i - 1) * 90_000L, 90_000, payload(i, 4)));
            }
            byte[] f = r.close();
            List<Box> top = topLevel(f);
            check("M7d 65 600 fragments: the muxer gives up at close rather than truncate the count",
                    r.muxer.isFailed() && r.muxer.hasHeader() && !r.muxer.isHeaderWritten(), "failed=" + r.muxer.isFailed());
            check("M7e ...leaving the slot a free box (no sidx) and the placeholder duration — a file FFmpeg can still index",
                    first(top, "sidx") == null && top.get(2).type.equals("free")
                            && mvhdDuration(f, first(top, "moov")) == PLACEHOLDER_DURATION && all(top, "moof").size() == frags + 1,
                    types(top.subList(0, Math.min(4, top.size()))));
        });

        // ── M8 refusals: each sets isFailed and writes no header ──
        sec("M8", () -> {
            Run enc = new Run(60_000).init(false, initEncrypted(V_ID, V_TS)).init(true, audioInit());
            enc.video(1); enc.close();
            check("M8 an encrypted init (pssh) is refused before any byte is written",
                    enc.muxer.isFailed() && !enc.muxer.hasHeader() && !enc.file.exists(), "");
            Run webm = new Run(60_000).init(false, webmInit()).init(true, audioInit());
            webm.close();
            check("M8b a non-ISO-BMFF init is refused", webm.muxer.isFailed() && !webm.muxer.hasHeader(), "");
            Run two = new Run(60_000).init(false, initTwoTraks(V_ID, V_TS)).init(true, audioInit());
            two.close();
            check("M8c an init carrying two traks is refused", two.muxer.isFailed() && !two.muxer.hasHeader(), "");
            Run notrex = new Run(60_000).init(false, initNoTrex(V_ID, V_TS)).init(true, audioInit());
            notrex.close();
            check("M8d an init with no trex is refused", notrex.muxer.isFailed() && !notrex.muxer.hasHeader(), "");
            Run late = new Run(60_000);
            for (int i = 1; i <= 17; i++) late.video(i);
            late.init(false, videoInit()).init(true, audioInit());
            late.close();
            check("M8e seventeen fragments before any init overflow the buffer and are refused",
                    late.muxer.isFailed() && !late.muxer.hasHeader(), "");
            Run large = new Run(60_000).init(false, videoInit()).init(true, audioInit());
            large.video(1).audio(1).media(false, fragmentLargesize(V_ID)).video(2);
            byte[] f = large.close();
            List<Box> top = topLevel(f);
            check("M8f a 64-bit box size after the header fails the muxer but keeps the header",
                    large.muxer.isFailed() && large.muxer.hasHeader() && !large.muxer.isHeaderWritten(), "");
            check("M8g ...close() then writes no sidx and patches nothing: the slot stays a free box of the full reserve",
                    first(top, "sidx") == null && top.size() >= 3 && top.get(2).type.equals("free")
                            && top.get(2).size == Fmp4Muxer.reserveFor(60_000)
                            && mvhdDuration(f, first(top, "moov")) == PLACEHOLDER_DURATION,
                    types(top));
            check("M8h ...and the fragments up to the failure are on disk", all(top, "moof").size() == 2, types(top));
            Run again = new Run(60_000).init(false, videoInit()).init(true, audioInit());
            again.video(1).init(false, videoInit()).audio(1).video(2);
            byte[] g = again.close();
            check("M8i a second init for a track is ignored: one moov, later fragments still written",
                    !again.muxer.isFailed() && all(topLevel(g), "moov").size() == 1 && all(topLevel(g), "moof").size() == 3,
                    types(topLevel(g)));
        });

        // ── M9 end to end: the real downloader feeds the real muxer; the file is the download ──
        sec("M9", () -> {
            OkHttpClient.reset(); Log.clear();
            SabrDownloader d = SabrHarness.newDownloader(SabrHarness.tok("TOKEN"), null);
            File file = out();
            Fmp4Muxer muxer = new Fmp4Muxer(file, SabrHarness.DUR);
            d.setMuxSink(muxer);
            byte[] vInit = videoInit(), aInit = audioInit();
            int segs = (int) (SabrHarness.DUR / SabrHarness.SEG);
            final int[] seq = {1};
            OkHttpClient.responder = (req, call) -> {
                List<byte[]> parts = new ArrayList<>();
                if (call == 0) {
                    parts.add(SabrHarness.inits());
                    parts.add(SabrTestServer.initSegment(1, SabrHarness.VID, SabrHarness.V_ITAG, SabrHarness.V_LMT, "", vInit));
                    parts.add(SabrTestServer.initSegment(2, SabrHarness.VID, SabrHarness.A_ITAG, SabrHarness.A_LMT, "", aInit));
                }
                for (int i = 0; i < 2 && seq[0] <= segs; i++, seq[0]++) {
                    long start = (seq[0] - 1) * SabrHarness.SEG;
                    parts.add(SabrTestServer.segment(1, SabrHarness.VID, SabrHarness.V_ITAG, SabrHarness.V_LMT, "",
                            seq[0], start, SabrHarness.SEG, vfrag(seq[0], 64)));
                    parts.add(SabrTestServer.segment(2, SabrHarness.VID, SabrHarness.A_ITAG, SabrHarness.A_LMT, "",
                            seq[0], start, SabrHarness.SEG, afrag(seq[0], 32)));
                }
                return Response.ok(SabrTestServer.concat(parts.toArray(new byte[0][])));
            };
            SabrDownloader.Result r = d.download();
            muxer.close();
            byte[] f = Files.readAllBytes(file.toPath());
            List<Box> top = topLevel(f);
            check("M9 the real downloader hands init + " + segs + " segments per track to the sink",
                    r.videoSegments == segs + 1 && r.audioSegments == segs + 1
                            && r.videoBytes == vInit.length + segs * (long) vfrag(1, 64).length
                            && r.audioBytes == aInit.length + segs * (long) afrag(1, 32).length,
                    r.videoSegments + "v/" + r.audioSegments + "a " + r.videoBytes + "/" + r.audioBytes + " bytes");
            check("M9b the output is one playable layout: ftyp moov sidx free, then " + (2 * segs) + " moof/mdat pairs",
                    muxer.isHeaderWritten() && !muxer.isFailed() && all(top, "moof").size() == 2 * segs
                            && all(top, "mdat").size() == 2 * segs && types(top).startsWith("ftyp moov sidx free moof mdat"),
                    types(top.subList(0, Math.min(6, top.size()))) + " moofs=" + all(top, "moof").size());
            int v = 0, a = 0;
            for (Box m : all(top, "moof")) { long id = moofTrackId(f, m); if (id == 1) v++; else if (id == 2) a++; }
            check("M9c every fragment is renumbered onto track 1 or 2", v == segs && a == segs, v + "/" + a);
            Box sidx = first(top, "sidx");
            check("M9d the sidx indexes every video fragment and the mvhd says 60 s",
                    sidx != null && sidx(f, sidx).count == segs && mvhdDuration(f, first(top, "moov")) == SabrHarness.DUR,
                    (sidx == null ? "no sidx" : "" + sidx(f, sidx).count) + " dur=" + mvhdDuration(f, first(top, "moov")));
            check("M9e the file is the download — no other file was written beside it",
                    file.getParentFile().listFiles((dir, n) -> n.startsWith(file.getName()) && !n.equals(file.getName())).length == 0, "");
        });
    }
}
