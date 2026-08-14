package com.solarized.firedown.sabr;

import android.util.Log;
import okhttp3.*;
import java.io.File;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Drives the REAL SabrDownloader against a scripted SABR server. */
public class SabrHarness {
    static int pass=0, fail=0;
    interface Sec { void run() throws Exception; }
    /** Runs one case; a throw is a FAILED case, never an aborted suite —
     *  otherwise the first regression hides every case after it. */
    static void sec(String id, Sec s){
        try { s.run(); }
        catch (Throwable t){ fail++; System.out.println("FAIL  "+id+"  threw "+t); }
    }

    static void check(String n, boolean ok, String d){
        if(ok){pass++;System.out.println("PASS  "+n+(d.isEmpty()?"":"  ["+d+"]"));}
        else  {fail++;System.out.println("FAIL  "+n+"  ["+d+"]");}
    }

    static final String VID = "testVideo01";
    static final int V_ITAG = 401, A_ITAG = 140;
    static final long V_LMT = 1786005459717620L, A_LMT = 1786001616122110L;
    static final long DUR = 60_000;          // 60s of media
    static final long SEG = 5_000;           // 5s per segment

    /** PO tokens are base64url on the wire — setPoToken decodes them. */
    static String tok(String s){
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(s.getBytes());
    }

    static File tmpDir() throws Exception {
        File d = Files.createTempDirectory("sabr").toFile(); d.deleteOnExit(); return d;
    }

    static SabrDownloader newDownloader(String token, SabrDownloader.PoTokenRefresher refresher) {
        OkHttpClient.reset(); Log.clear();
        SabrDownloader d = new SabrDownloader(new OkHttpClient());
        d.setStreamingUrl("https://rr1---test.googlevideo.com/videoplayback?x=1");
        d.setUstreamerConfig("");
        d.setVideoFormat(new SabrMessages.FormatId(V_ITAG, V_LMT, ""));
        d.setAudioFormat(new SabrMessages.FormatId(A_ITAG, A_LMT, ""));
        d.setDurationMs(DUR);
        d.setClientInfo(2, "2.20260812.00.00");
        if (token != null) d.setPoToken(token);
        if (refresher != null) d.setPoTokenRefresher(refresher);
        return d;
    }

    /** Both formats' init metadata. */
    static byte[] inits() {
        return SabrTestServer.concat(
            SabrTestServer.formatInit(VID, V_ITAG, V_LMT, "", "video/mp4", DUR, 1000),
            SabrTestServer.formatInit(VID, A_ITAG, A_LMT, "", "audio/mp4", DUR, 1000));
    }
    /** One video + one audio segment at sequence `seq`. */
    static byte[] segsAt(int seq) {
        return SabrTestServer.concat(
            SabrTestServer.segment(1, VID, V_ITAG, V_LMT, "", seq, seq*SEG, SEG, 1024),
            SabrTestServer.segment(2, VID, A_ITAG, A_LMT, "", seq, seq*SEG, SEG, 256));
    }

    /** A server that serves the whole video, optionally injecting parts per call. */
    interface Injector { byte[] extra(int call); }
    static void serve(Injector inj) {
        final int[] seq = {0};
        OkHttpClient.responder = (req, call) -> {
            List<byte[]> parts = new ArrayList<>();
            if (call == 0) parts.add(inits());
            byte[] extra = inj == null ? null : inj.extra(call);
            if (extra != null) parts.add(extra);
            // Serve two segments per response until the media runs out.
            for (int i = 0; i < 2 && seq[0] * SEG < DUR; i++) parts.add(segsAt(seq[0]++));
            return Response.ok(SabrTestServer.concat(parts.toArray(new byte[0][])));
        };
    }

    public static void main(String[] a) throws Exception {
        // ── 0. the fake server's UMP framing must satisfy the REAL reader ──
        sec("0", () -> {
            List<Integer> seen = new ArrayList<>();
            byte[] wire = SabrTestServer.concat(
                    SabrTestServer.streamProtection(3),
                    SabrTestServer.part(999, new byte[5000]),      // 2-byte varint size
                    SabrTestServer.part(1000000, new byte[3]));    // 3-byte varint type
            UmpReader.readStream(new java.io.ByteArrayInputStream(wire),
                    (t, d, o, l) -> { seen.add(t); return false; });
            check("0  the harness's UMP encoder round-trips through the real UmpReader",
                    seen.equals(Arrays.asList(UmpReader.STREAM_PROTECTION_STATUS, 999, 1000000)),
                    "parts=" + seen);
        });

        // ── 1. happy path ─────────────────────────────────────────────────
        sec("1", () -> {
            SabrDownloader d = newDownloader(tok("TOKEN"), null);
            serve(null);
            SabrDownloader.Result r = d.download(tmpDir());
            check("1  a clean stream downloads to completion",
                    r != null && r.videoSegments > 0 && r.audioSegments > 0,
                    r == null ? "null" : r.videoSegments + "v/" + r.audioSegments + "a");
        });

        // ── 2. status=3 recovered by a fresh token ────────────────────────
        sec("2", () -> {
            AtomicInteger mints = new AtomicInteger();
            SabrDownloader d = newDownloader(tok("OLD"), () -> tok("FRESH" + mints.incrementAndGet()));
            serve(call -> call == 1 ? SabrTestServer.streamProtection(3) : null);
            SabrDownloader.Result r = d.download(tmpDir());
            check("2  status=3 recovers via one re-mint and finishes",
                    r != null && mints.get() == 1, "mints=" + mints.get());
        });

        // ── 3. the same token refused twice, no progress → honest failure ─
        sec("3", () -> {
            AtomicInteger mints = new AtomicInteger();
            SabrDownloader d = newDownloader(tok("OLD"), () -> tok("STILL-REFUSED" + mints.incrementAndGet()));
            // After the first response, every response is status=3 and NO segments.
            final int[] seq = {0};
            OkHttpClient.responder = (req, call) -> {
                if (call == 0) return Response.ok(SabrTestServer.concat(inits(), segsAt(seq[0]++)));
                return Response.ok(SabrTestServer.streamProtection(3));
            };
            String msg = null;
            try { d.download(tmpDir()); } catch (SabrDownloader.SabrException e) { msg = e.getMessage(); }
            check("3  a token refused twice with no progress fails the download",
                    msg != null && msg.contains("rejected"), String.valueOf(msg));
            check("3b ...after exactly MAX_ATTESTATION_REFRESHES mints", mints.get() == 2, "mints=" + mints.get());
            check("3c ...and reports it as attestation-rejected to the strategy",
                    d.isAttestationRejected(), "");
        });

        // ── 4. a mint that CANNOT complete is a connection error, not a refusal
        sec("4", () -> {
            AtomicInteger mints = new AtomicInteger();
            SabrDownloader d = newDownloader(tok("OLD"), () -> { mints.incrementAndGet(); return null; });
            final int[] seq = {0};
            OkHttpClient.responder = (req, call) -> {
                if (call == 0) return Response.ok(SabrTestServer.concat(inits(), segsAt(seq[0]++)));
                return Response.ok(SabrTestServer.streamProtection(3));
            };
            String msg = null;
            try { d.download(tmpDir()); } catch (SabrDownloader.SabrException e) { msg = e.getMessage(); }
            check("4  a mint returning nothing fails as a network error, not 'rejected'",
                    msg != null && msg.contains("Could not mint") && !msg.contains("rejected"),
                    String.valueOf(msg));
            check("4b ...on the FIRST attempt, without burning the second",
                    mints.get() == 1, "mints=" + mints.get());
            check("4c ...and still reports attestation-wall death, so the stale token is dropped",
                    d.isAttestationRejected(), "");
        });

        // ── 5. repeated demands, each recovered → download still completes ─
        //     (the consecutive-vs-lifetime fix: >2 demands in one download)
        sec("5", () -> {
            AtomicInteger mints = new AtomicInteger();
            SabrDownloader d = newDownloader(tok("OLD"), () -> tok("FRESH" + mints.incrementAndGet()));
            serve(call -> (call >= 1 && call <= 5) ? SabrTestServer.streamProtection(3) : null);
            SabrDownloader.Result r = d.download(tmpDir());
            check("5  five separate demands, each recovered, still completes",
                    r != null && mints.get() == 5, "mints=" + mints.get()
                            + " result=" + (r != null));
            check("5b ...because progress resets the consecutive budget",
                    Log.count("attestation budget reset") >= 1,
                    "resets=" + Log.count("attestation budget reset"));
        });

        // ── 6. loop backstop: segments AND status=3 on every response ─────
        sec("6", () -> {
            AtomicInteger mints = new AtomicInteger();
            SabrDownloader d = newDownloader(tok("OLD"), () -> tok("FRESH" + mints.incrementAndGet()));
            d.setDurationMs(6L * 60 * 60 * 1000);      // 6h → budget = 72
            final int[] seq = {0};
            OkHttpClient.responder = (req, call) -> {
                List<byte[]> p = new ArrayList<>();
                if (call == 0) p.add(inits());
                p.add(SabrTestServer.segment(1, VID, V_ITAG, V_LMT, "", seq[0], seq[0]*SEG, SEG, 64));
                seq[0]++;
                p.add(SabrTestServer.streamProtection(3));   // …and demand every time
                return Response.ok(SabrTestServer.concat(p.toArray(new byte[0][])));
            };
            String msg = null;
            try { d.download(tmpDir()); } catch (SabrDownloader.SabrException e) { msg = e.getMessage(); }
            check("6  segments+status3 every pass terminates instead of looping",
                    msg != null, String.valueOf(msg));
            check("6b ...bounded by the scaled total budget (72 for 6h), not the media length",
                    mints.get() <= 72 && mints.get() >= 8, "mints=" + mints.get());
        });

        // ── 7. redirects ──────────────────────────────────────────────────
        sec("7", () -> {
            SabrDownloader d = newDownloader(tok("TOKEN"), null);
            final int[] seq = {0};
            OkHttpClient.responder = (req, call) -> {
                if (call == 0) return Response.ok(SabrTestServer.concat(inits(),
                        SabrTestServer.sabrRedirect("https://rr9---moved.googlevideo.com/videoplayback?x=2")));
                List<byte[]> p = new ArrayList<>();
                for (int i = 0; i < 2 && seq[0]*SEG < DUR; i++) p.add(segsAt(seq[0]++));
                return Response.ok(SabrTestServer.concat(p.toArray(new byte[0][])));
            };
            SabrDownloader.Result r = d.download(tmpDir());
            check("7  a SABR_REDIRECT is followed and the download completes",
                    r != null && Log.saw("SABR redirect to: https://rr9---moved"), "");
        });

        // ── 8. an endless redirect chain is capped ────────────────────────
        sec("8", () -> {
            SabrDownloader d = newDownloader(tok("TOKEN"), null);
            OkHttpClient.responder = (req, call) -> Response.ok(SabrTestServer.concat(
                    call == 0 ? inits() : new byte[0],
                    SabrTestServer.sabrRedirect("https://rr" + call + "---loop.googlevideo.com/v")));
            d.download(tmpDir());
            check("8  an endless redirect chain aborts instead of spinning",
                    Log.saw("Too many redirects"), "calls=" + OkHttpClient.calls.get());
        });

        // ── 9. progress is monotonic and never exceeds the total ──────────
        sec("9", () -> {
            SabrDownloader d = newDownloader(tok("TOKEN"), null);
            List<long[]> seen = new ArrayList<>();
            d.setProgressListener((done, total, v, au) -> seen.add(new long[]{done, total}));
            serve(null);
            d.download(tmpDir());
            boolean mono = true, bounded = true;
            long last = -1;
            for (long[] p : seen) { if (p[0] < last) mono = false; if (p[0] > p[1]) bounded = false; last = p[0]; }
            check("9  reported progress never goes backwards", mono, "ticks=" + seen.size());
            check("9b ...and never exceeds the total", bounded, "");
        });

        // ── 10. an HTTP error surfaces, it does not finalize a partial ────
        sec("10", () -> {
            SabrDownloader d = newDownloader(tok("TOKEN"), null);
            OkHttpClient.responder = (req, call) -> call == 0
                    ? Response.ok(SabrTestServer.concat(inits(), segsAt(0)))
                    : new Response(403, "Forbidden", new byte[0], null);
            boolean threw = false;
            SabrDownloader.Result r = null;
            try { r = d.download(tmpDir()); } catch (Exception e) { threw = true; }
            check("10 a mid-stream HTTP failure does not finalize as success",
                    threw || r == null || r.videoSegments < 12,
                    "threw=" + threw + " segs=" + (r == null ? "-" : r.videoSegments));
        });

        System.out.println("\n"+pass+" passed, "+fail+" failed");
        System.exit(fail==0?0:1);
    }
}
