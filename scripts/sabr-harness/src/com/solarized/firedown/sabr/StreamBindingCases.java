package com.solarized.firedown.sabr;

import com.solarized.firedown.sabr.StreamTokenBinding.Kind;
import okhttp3.OkHttpClient;
import okhttp3.Response;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The REAL SabrDownloader and the REAL StreamTokenBinding against a server
 * that checks the token's binding the way the device logs show: a session
 * carrying a token over the wrong identifier gets its first responses with
 * status 2 ("pending"), then status 3 and no media for every such token; a
 * token over the right identifier draws no status at all.
 *
 * The refresher below is SabrStrategy.mintAfterDemand without the token
 * generator: it asks the binding which identifier to mint over, from the
 * downloader's own three facts.
 */
final class StreamBindingCases {

    /** One scripted download. */
    static final class Run {
        SabrDownloader d;
        StreamTokenBinding binding;
        final List<Kind> mints = new ArrayList<>();
        Kind carried;                 // what the token on the wire is bound to
        boolean finished;
        String error;
        boolean mintFails;            // the next re-mint returns nothing
    }

    /**
     * @param wanted     the identifier the server checks; null = none accepted
     * @param statusTwo  the server says "pending" before it refuses
     * @param firstMint  the mint before the download succeeds
     * @param demandsAt  call indices where the server demands attestation on
     *                   a token it had accepted
     * @param pendingFirst those demands carry a status 2 as well
     * @param failMintAt   the n-th re-mint (1-based) returns nothing; 0 = never
     */
    static Run play(boolean hasVisitorData, Kind wanted, boolean statusTwo, boolean firstMint,
                    List<Integer> demandsAt, boolean pendingFirst, int failMintAt) {
        Run run = new Run();
        run.binding = new StreamTokenBinding(hasVisitorData);
        String first = null;
        if (firstMint) {
            run.carried = run.binding.current();
            run.binding.onMinted();
            run.mints.add(run.carried);
            first = SabrHarness.tok("T0-" + run.carried);
        }
        final int[] remints = {0};
        run.d = SabrHarness.newDownloader(first, () -> {
            Kind kind = run.binding.onDemand(run.d.isTokenAccepted(),
                    run.d.isAttestationPending(),
                    run.d.getConsecutiveAttestationRefreshes());
            remints[0]++;
            if (remints[0] == failMintAt) {
                return null;
            }
            run.binding.onMinted();
            run.carried = kind;
            run.mints.add(kind);
            return SabrHarness.tok("T" + remints[0] + "-" + kind);
        });
        final int[] seq = {0};
        final int[] served = {0};
        final List<Integer> demands = new ArrayList<>(demandsAt);
        OkHttpClient.responder = (req, call) -> {
            List<byte[]> parts = new ArrayList<>();
            if (call == 0) parts.add(SabrHarness.inits());
            boolean right = run.carried != null && run.carried == wanted;
            boolean media;
            if (right) {
                if (!demands.isEmpty() && demands.get(0) == call) {
                    demands.remove(0);
                    if (pendingFirst) parts.add(SabrTestServer.streamProtection(2));
                    parts.add(SabrTestServer.streamProtection(3));
                    media = false;
                } else {
                    media = true;
                }
            } else if (served[0] < 2) {
                if (statusTwo) parts.add(SabrTestServer.streamProtection(2));
                media = true;
            } else {
                if (statusTwo) parts.add(SabrTestServer.streamProtection(2));
                parts.add(SabrTestServer.streamProtection(3));
                media = false;
            }
            if (media) {
                served[0]++;
                if (seq[0] * SabrHarness.SEG < SabrHarness.DUR) parts.add(SabrHarness.segsAt(seq[0]++));
            }
            return Response.ok(SabrTestServer.concat(parts.toArray(new byte[0][])));
        };
        try {
            SabrDownloader.Result r = run.d.download();
            run.finished = r != null && r.videoSegments > 0;
        } catch (Exception e) {
            run.error = String.valueOf(e.getMessage());
        } finally {
            run.binding.onEnded(run.d.isTokenAccepted());
        }
        return run;
    }

    static List<Integer> none() { return new ArrayList<>(); }

    static void check(String n, boolean ok, String d) { SabrHarness.check(n, ok, d); }

    static void run() {
        System.out.println("\n-- stream token binding (real downloader + real StreamTokenBinding) --");

        SabrHarness.sec("B1", () -> {
            // The 2026-11 device log: a visitor-bound token on a session
            // that checks the video id.
            StreamTokenBinding.resetForTest(Kind.VISITOR);
            Run r = play(true, Kind.VIDEO, true, true, none(), false, 0);
            check("B1  a visitor-bound token on a video session is corrected and the download finishes",
                    r.finished && r.mints.equals(Arrays.asList(Kind.VISITOR, Kind.VIDEO)),
                    "finished=" + r.finished + " mints=" + r.mints + " err=" + r.error);
            check("B1b ...and the next download starts video-bound",
                    StreamTokenBinding.lastAccepted() == Kind.VIDEO
                            && new StreamTokenBinding(true).current() == Kind.VIDEO, "");
        });

        SabrHarness.sec("B2", () -> {
            StreamTokenBinding.resetForTest(Kind.VIDEO);
            Run r = play(true, Kind.VISITOR, true, true, none(), false, 0);
            check("B2  a video-bound token on a visitor session is corrected the other way",
                    r.finished && r.mints.equals(Arrays.asList(Kind.VIDEO, Kind.VISITOR))
                            && StreamTokenBinding.lastAccepted() == Kind.VISITOR,
                    "finished=" + r.finished + " mints=" + r.mints + " err=" + r.error);
        });

        SabrHarness.sec("B3", () -> {
            StreamTokenBinding.resetForTest(Kind.VIDEO);
            Run r = play(true, Kind.VIDEO, true, true, none(), false, 0);
            check("B3  the right binding downloads with its one token",
                    r.finished && r.mints.size() == 1 && r.d.isTokenAccepted()
                            && !r.d.isAttestationPending(),
                    "mints=" + r.mints);
        });

        SabrHarness.sec("B4", () -> {
            for (boolean pendingFirst : new boolean[] {false, true}) {
                StreamTokenBinding.resetForTest(Kind.VIDEO);
                Run r = play(true, Kind.VIDEO, true, true, Arrays.asList(2, 5, 8), pendingFirst, 0);
                boolean allVideo = true;
                for (Kind k : r.mints) allVideo &= k == Kind.VIDEO;
                check("B4  ordinary mid-stream demands never leave the accepted binding"
                                + (pendingFirst ? " (pending sent with them)" : ""),
                        r.finished && allVideo && r.mints.size() == 4,
                        "finished=" + r.finished + " mints=" + r.mints + " err=" + r.error);
            }
        });

        SabrHarness.sec("B5", () -> {
            StreamTokenBinding.resetForTest(Kind.VIDEO);
            Run r = play(true, Kind.VISITOR, false, true, none(), false, 0);
            check("B5  a server that never says pending: same binding once, then the other",
                    r.finished && r.mints.equals(Arrays.asList(Kind.VIDEO, Kind.VIDEO, Kind.VISITOR)),
                    "finished=" + r.finished + " mints=" + r.mints + " err=" + r.error);
        });

        SabrHarness.sec("B6", () -> {
            StreamTokenBinding.resetForTest(Kind.VISITOR);
            Run r = play(true, null, true, true, none(), false, 0);
            check("B6  a session that accepts neither fails, having tried both",
                    !r.finished && r.error != null && r.error.contains("rejected")
                            && r.mints.contains(Kind.VIDEO) && r.mints.contains(Kind.VISITOR)
                            && r.mints.size() == 3,
                    "mints=" + r.mints + " err=" + r.error);
            check("B6b ...and teaches the next download nothing",
                    StreamTokenBinding.lastAccepted() == Kind.VISITOR, "");
        });

        SabrHarness.sec("B7", () -> {
            StreamTokenBinding.resetForTest(Kind.VISITOR);
            Run r = play(false, Kind.VISITOR, true, true, none(), false, 0);
            check("B7  with no visitor data every token is video-bound",
                    !r.finished && !r.mints.contains(Kind.VISITOR), "mints=" + r.mints);
            Run ok = play(false, Kind.VIDEO, true, true, none(), false, 0);
            check("B7b ...and a video session downloads",
                    ok.finished && ok.mints.equals(Arrays.asList(Kind.VIDEO)), "mints=" + ok.mints);
        });

        SabrHarness.sec("B8", () -> {
            StreamTokenBinding.resetForTest(Kind.VIDEO);
            Run same = play(true, Kind.VIDEO, true, false, none(), false, 0);
            check("B8  a download whose first mint failed mints the starting binding at the demand",
                    same.finished && same.mints.equals(Arrays.asList(Kind.VIDEO)),
                    "mints=" + same.mints + " err=" + same.error);
            StreamTokenBinding.resetForTest(Kind.VIDEO);
            Run other = play(true, Kind.VISITOR, true, false, none(), false, 0);
            check("B8b ...and still reaches the other binding when that one is refused",
                    other.finished && other.mints.equals(Arrays.asList(Kind.VIDEO, Kind.VISITOR)),
                    "mints=" + other.mints + " err=" + other.error);
        });

        SabrHarness.sec("B9", () -> {
            StreamTokenBinding.resetForTest(Kind.VISITOR);
            Run r = play(true, Kind.VIDEO, true, true, none(), false, 1);
            check("B9  a re-mint that returns nothing fails as a network error and teaches nothing",
                    !r.finished && r.error != null && r.error.contains("Could not mint")
                            && StreamTokenBinding.lastAccepted() == Kind.VISITOR,
                    "err=" + r.error + " last=" + StreamTokenBinding.lastAccepted());
        });

        SabrHarness.sec("B10", () -> {
            // Corrected, then the connection drops for good: what was
            // learned before the failure is kept.
            StreamTokenBinding.resetForTest(Kind.VISITOR);
            Run r = play(true, Kind.VIDEO, true, true, Arrays.asList(6), false, 2);
            check("B10 a binding accepted before a later failure is still remembered",
                    !r.finished && StreamTokenBinding.lastAccepted() == Kind.VIDEO,
                    "mints=" + r.mints + " err=" + r.error);
        });

        SabrHarness.sec("B11", () -> {
            SabrDownloader d = SabrHarness.newDownloader(SabrHarness.tok("A"), null);
            final int[] seq = {0};
            OkHttpClient.responder = (req, call) -> Response.ok(SabrTestServer.concat(
                    call == 0 ? SabrHarness.inits() : new byte[0],
                    SabrTestServer.streamProtection(2),
                    seq[0] * SabrHarness.SEG < SabrHarness.DUR ? SabrHarness.segsAt(seq[0]++) : new byte[0]));
            d.download();
            check("B11 a token the server keeps pending is never reported accepted",
                    d.isAttestationPending() && !d.isTokenAccepted(), "");
            d.setPoToken(SabrHarness.tok("B"));
            check("B11b ...and a new token starts with neither flag",
                    !d.isAttestationPending() && !d.isTokenAccepted(), "");

            SabrDownloader bare = SabrHarness.newDownloader(null, null);
            SabrHarness.serve(null);
            bare.download();
            check("B11c media with no token at all is not an accepted token",
                    !bare.isTokenAccepted(), "");
        });
    }
}
