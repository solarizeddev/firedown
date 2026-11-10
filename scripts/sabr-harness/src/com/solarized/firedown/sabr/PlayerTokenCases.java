package com.solarized.firedown.sabr;

import okhttp3.OkHttpClient;
import okhttp3.Response;

import java.util.ArrayList;
import java.util.List;

/**
 * The REAL SabrDownloader and the REAL PlayerTokenSource against a server
 * that behaves as the device logs show: a token the page's own player made
 * is served with no status; a token minted in the hidden page is kept
 * "pending" for two responses of media, then refused with status 3.
 *
 * The token choice below is SabrStrategy.mintPoToken's: the page player's
 * token when the source offers one, else a minted one.
 */
final class PlayerTokenCases {

    static final class Run {
        SabrDownloader d;
        final PlayerTokenSource source = new PlayerTokenSource();
        /** What the page's player has sent so far; null = nothing yet. */
        String playerHas;
        /** Player tokens the server no longer takes. */
        final List<String> expired = new ArrayList<>();
        final List<String> used = new ArrayList<>();
        String onWire;
        boolean finished;
        long reachedMs;
        String error;
        int pendings;
    }

    interface Script { void at(Run run, int call); }

    static String pick(Run run) {
        String p = run.source.next(run.playerHas);
        String t = p != null ? p : "minted-" + run.used.size();
        run.used.add(t);
        run.onWire = t;
        return SabrHarness.tok(t);
    }

    /**
     * @param demandsAt calls where the server asks again of a player token
     *                  it had accepted (the token is expired from then on)
     * @param script    what happens outside the download before each call
     */
    static Run play(String playerAtStart, List<Integer> demandsAt, Script script) {
        Run run = new Run();
        run.playerHas = playerAtStart;
        final List<Integer> demands = new ArrayList<>(demandsAt);
        String first = pick(run);
        run.d = SabrHarness.newDownloader(first, () -> pick(run));
        final int[] seq = {0};
        final int[] servedMinted = {0};
        OkHttpClient.responder = (req, call) -> {
            if (script != null) script.at(run, call);
            List<byte[]> parts = new ArrayList<>();
            if (call == 0) parts.add(SabrHarness.inits());
            boolean player = run.onWire.startsWith("player") && !run.expired.contains(run.onWire);
            boolean media;
            if (player) {
                if (!demands.isEmpty() && demands.get(0) == call) {
                    demands.remove(0);
                    run.expired.add(run.onWire);
                    parts.add(SabrTestServer.streamProtection(3));
                    media = false;
                } else {
                    media = true;
                }
            } else if (run.onWire.startsWith("player")) {
                parts.add(SabrTestServer.streamProtection(3));
                media = false;
            } else if (servedMinted[0] < 2) {
                parts.add(SabrTestServer.streamProtection(2));
                run.pendings++;
                servedMinted[0]++;
                media = true;
            } else {
                parts.add(SabrTestServer.streamProtection(2));
                parts.add(SabrTestServer.streamProtection(3));
                media = false;
            }
            if (media && seq[0] * SabrHarness.SEG < SabrHarness.DUR) {
                parts.add(SabrHarness.segsAt(seq[0]++));
            }
            return Response.ok(SabrTestServer.concat(parts.toArray(new byte[0][])));
        };
        try {
            SabrDownloader.Result r = run.d.download();
            run.finished = r != null && seq[0] * SabrHarness.SEG >= SabrHarness.DUR;
        } catch (Exception e) {
            run.error = String.valueOf(e.getMessage());
        }
        run.reachedMs = seq[0] * SabrHarness.SEG;
        return run;
    }

    static List<Integer> none() { return new ArrayList<>(); }
    static List<Integer> at(int... calls) {
        List<Integer> l = new ArrayList<>();
        for (int c : calls) l.add(c);
        return l;
    }

    static void check(String n, boolean ok, String d) { SabrHarness.check(n, ok, d); }

    static void run() {
        System.out.println("\n-- the page player's token (real downloader + real PlayerTokenSource) --");

        SabrHarness.sec("P0", () -> {
            PlayerTokenSource s = new PlayerTokenSource();
            check("P0  nothing from the player offers nothing", s.next(null) == null && s.next("") == null, "");
            check("P0b a token is offered once", "a".equals(s.next("a")) && s.next("a") == null, "");
            check("P0c a newer one is offered", "b".equals(s.next("b")), "");
            check("P0d ...and the older one again only as a different value", "a".equals(s.next("a")), "");
        });

        SabrHarness.sec("P1", () -> {
            Run r = play("player-1", none(), null);
            check("P1  the player's token downloads the whole video", r.finished && r.error == null,
                    "reached=" + r.reachedMs + " err=" + r.error);
            check("P1b ...with that one token, nothing minted, never pending",
                    r.used.size() == 1 && r.used.get(0).equals("player-1") && r.pendings == 0,
                    r.used.toString());
        });

        SabrHarness.sec("P2", () -> {
            Run r = play(null, none(), null);
            check("P2  with no player token the minted one is refused and the download errors",
                    !r.finished && r.error != null && r.error.contains("rejected"), String.valueOf(r.error));
            check("P2b ...never reported as complete", r.reachedMs < SabrHarness.DUR, "reached=" + r.reachedMs);
        });

        SabrHarness.sec("P3", () -> {
            // The player token arrives after the download began with a
            // minted one: taken at the server's demand.
            Run r = play(null, none(), (run, call) -> { if (call == 1) run.playerHas = "player-1"; });
            check("P3  a player token that arrives mid-download rescues it", r.finished && r.error == null,
                    "used=" + r.used + " err=" + r.error);
            check("P3b ...as the first thing tried at the demand",
                    r.used.size() == 2 && r.used.get(1).equals("player-1"), r.used.toString());
        });

        SabrHarness.sec("P4", () -> {
            // The accepted token stops being accepted; the player has made
            // a newer one meanwhile.
            Run r = play("player-1", at(4), (run, call) -> { if (call == 3) run.playerHas = "player-2"; });
            check("P4  an expired player token is replaced by the player's newer one",
                    r.finished && r.error == null, "used=" + r.used + " err=" + r.error);
            check("P4b ...with nothing minted", r.used.toString().equals("[player-1, player-2]"), r.used.toString());
        });

        SabrHarness.sec("P5", () -> {
            // Expired, and the player has nothing newer: minting is all
            // that is left, and the server refuses minted tokens.
            Run r = play("player-1", at(4), null);
            check("P5  an expired player token with nothing newer ends in an error, not a short file",
                    !r.finished && r.error != null, "used=" + r.used + " err=" + r.error);
            check("P5b ...the expired token is not sent again",
                    r.used.indexOf("player-1") == r.used.lastIndexOf("player-1"), r.used.toString());
            check("P5c ...and what was downloaded before the demand is kept as progress",
                    r.reachedMs > 0 && r.reachedMs < SabrHarness.DUR, "reached=" + r.reachedMs);
        });

        SabrHarness.sec("P6", () -> {
            // Several expiries over one download, a newer token each time.
            Run r = play("player-1", at(3, 7, 11), (run, call) -> {
                if (call == 2) run.playerHas = "player-2";
                if (call == 6) run.playerHas = "player-3";
                if (call == 10) run.playerHas = "player-4";
            });
            check("P6  three expiries, three newer tokens: the download finishes",
                    r.finished && r.used.size() == 4, "used=" + r.used + " err=" + r.error);
        });

        SabrHarness.sec("P7", () -> {
            // A player token the server refuses from the start (bound to
            // another video): one try, then the minted path.
            Run r = new Run();
            r.expired.add("player-x");
            r.playerHas = "player-x";
            String first = pick(r);
            final Run run = r;
            run.d = SabrHarness.newDownloader(first, () -> pick(run));
            OkHttpClient.responder = (req, call) -> {
                List<byte[]> parts = new ArrayList<>();
                if (call == 0) parts.add(SabrHarness.inits());
                parts.add(SabrTestServer.streamProtection(3));
                return Response.ok(SabrTestServer.concat(parts.toArray(new byte[0][])));
            };
            try { run.d.download(); } catch (Exception e) { run.error = String.valueOf(e.getMessage()); }
            check("P7  a refused player token is tried once and the download ends bounded",
                    run.error != null && run.used.indexOf("player-x") == run.used.lastIndexOf("player-x")
                            && run.used.size() <= 5, "used=" + run.used);
        });
    }
}
