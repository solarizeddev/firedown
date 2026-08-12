package com.solarized.firedown.geckoview;

import android.util.Log;
import org.json.JSONObject;
import org.mozilla.geckoview.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;

/** Drives the REAL PoTokenGenerator against stubs. Every case is a path
 *  through the class, not a re-implementation of it. */
public class Harness {
    static int pass=0, fail=0;
    static void check(String name, boolean ok, String detail){
        if(ok) { pass++; System.out.println("PASS  "+name+(detail.isEmpty()?"":"  ["+detail+"]")); }
        else   { fail++; System.out.println("FAIL  "+name+"  ["+detail+"]"); }
    }

    static PoTokenGenerator gen;
    static volatile WebExtension.Port livePort;
    static final AtomicIntegerHolder portSeq = new AtomicIntegerHolder();
    static class AtomicIntegerHolder { int v=0; synchronized int next(){ return ++v; } }

    /** Fresh generator whose sessions auto-connect a port + signal ready. */
    static void newGen(boolean autoConnect){
        GeckoSession.reset(); Log.clear(); android.os.Handler.postDelayMs = 0;
        gen = new PoTokenGenerator(new GeckoRuntime(), s -> {});
        livePort = null;
        GeckoSession.onLoad = s -> {
            if (!autoConnect) return;
            WebExtension.Port p = new WebExtension.Port("port#"+portSeq.next());
            livePort = p;
            gen.onPortConnected(p);
            p.ready();
        };
    }

    static Object f(String name) throws Exception {
        Field fl = PoTokenGenerator.class.getDeclaredField(name); fl.setAccessible(true); return fl.get(gen);
    }
    static void setF(String name, Object v) throws Exception {
        Field fl = PoTokenGenerator.class.getDeclaredField(name); fl.setAccessible(true); fl.set(gen, v);
    }
    @SuppressWarnings("unchecked")
    static Map<String,Object> cache() throws Exception { return (Map<String,Object>) f("tokenCache"); }

    /** Rewind a cached token's mintedAt to simulate elapsed time. */
    static void ageCachedToken(String videoId, long byMs) throws Exception {
        Object ct = cache().get(videoId);
        Field mf = ct.getClass().getDeclaredField("mintedAt"); mf.setAccessible(true);
        mf.setLong(ct, mf.getLong(ct) - byMs);
    }
    static void ageSession(long byMs) throws Exception {
        Field sf = PoTokenGenerator.class.getDeclaredField("sessionCreatedAt"); sf.setAccessible(true);
        sf.setLong(gen, sf.getLong(gen) - byMs);
    }

    public static void main(String[] a) throws Exception {
        // ── 1. cold start ────────────────────────────────────────────────
        newGen(true);
        String t1 = gen.generate("vidA","VD");
        check("1  cold start mints a token", t1!=null && t1.startsWith("TOK"), String.valueOf(t1));
        check("1b cold start created exactly one session", GeckoSession.created.get()==1, "created="+GeckoSession.created.get());

        // ── 2. cache hit ─────────────────────────────────────────────────
        int before = livePort.sent.size();
        String t2 = gen.generate("vidA","VD");
        check("2  second call for same video is served from cache",
                t2.equals(t1) && livePort.sent.size()==before, "sent="+(livePort.sent.size()-before));

        // ── 3. cache TTL expiry ──────────────────────────────────────────
        ageCachedToken("vidA", 11*60*1000);
        String t3 = gen.generate("vidA","VD");
        check("3  cached token past TOKEN_CACHE_TTL_MS is re-minted",
                t3!=null && !t3.equals(t1) && Log.saw("expired"), String.valueOf(t3));

        // ── 4. different video is NOT served another video's token ───────
        String t4 = gen.generate("vidB","VD");
        check("4  a different videoId never gets vidA's token", !t4.equals(t3), t4);

        // ── 5. forceFresh bypasses a VALID cache + flags the page ────────
        before = livePort.sent.size();
        String t5 = gen.generateFresh("vidB","VD");
        JSONObject last = livePort.sent.get(livePort.sent.size()-1);
        check("5  generateFresh mints despite a valid cache entry",
                !t5.equals(t4) && livePort.sent.size()==before+1, t5);
        check("5b generateFresh sets forceFresh on the wire",
                last.optBoolean("forceFresh",false), last.toString());
        check("5c a plain generate does NOT set forceFresh",
                !livePort.sent.get(0).optBoolean("forceFresh",false), livePort.sent.get(0).toString());
        check("5d the fresh token replaces the cache entry (sibling sees the good one)",
                gen.generate("vidB","VD").equals(t5), "");

        // ── 6. forceFresh whose mint FAILS must not leave the old token ──
        livePort.replyError = "att/get 429";
        String t6 = gen.generateFresh("vidB","VD");
        check("6  a failed forceFresh returns null", t6==null, String.valueOf(t6));
        check("6b ...and evicts, so the refused token is not served next",
                !cache().containsKey("vidB"), "cache="+cache().keySet());
        livePort.replyError = null;

        // ── 7. an error REPLY keeps the session (page is alive) ──────────
        check("7  an error reply does NOT recycle the session",
                GeckoSession.closed.get()==0 && f("session")!=null, "closed="+GeckoSession.closed.get());

        // ── 8. invalidate() ──────────────────────────────────────────────
        gen.generate("vidC","VD");
        gen.invalidate("vidC");
        check("8  invalidate drops just that video", !cache().containsKey("vidC"), "cache="+cache().keySet());

        // ── 9. concurrent callers share ONE session creation ─────────────
        newGen(true);
        ExecutorService ex = Executors.newFixedThreadPool(4);
        List<Future<String>> fs = new ArrayList<>();
        for (int i=0;i<4;i++){ final int n=i; fs.add(ex.submit(() -> gen.generate("v"+n,"VD"))); }
        int ok=0; for (Future<String> ff:fs) if (ff.get()!=null) ok++;
        check("9  4 concurrent cold callers all get tokens", ok==4, "ok="+ok);
        check("9b ...from exactly ONE session (piggy-back works)",
                GeckoSession.created.get()==1, "created="+GeckoSession.created.get());

        // ── 10. out-of-order / multiplexed replies ───────────────────────
        newGen(true);
        gen.generate("warm","VD");
        livePort.autoReply = false;                       // we answer manually
        Future<String> fA = ex.submit(() -> gen.generate("mA","VD"));
        Future<String> fB = ex.submit(() -> gen.generate("mB","VD"));
        Thread.sleep(300);
        List<JSONObject> reqs = new ArrayList<>(livePort.sent.subList(1, livePort.sent.size()));
        Collections.reverse(reqs);                        // reply in REVERSE order
        for (JSONObject r : reqs) {
            JSONObject rep = new JSONObject();
            rep.put("type","mintResult"); rep.put("requestId", r.optString("requestId",""));
            rep.put("token","TOK-"+r.optString("videoId",""));
            livePort.deliver(rep);
        }
        check("10 replies arriving out of order reach the right callers",
                "TOK-mA".equals(fA.get()) && "TOK-mB".equals(fB.get()), fA.get()+" / "+fB.get());
        livePort.autoReply = true;

        // ── 11. STALE port disconnect must NOT kill the live session ─────
        newGen(true);
        gen.generate("x","VD");
        WebExtension.Port stale = livePort;
        WebExtension.Port fresh = new WebExtension.Port("port#new");
        gen.onPortConnected(fresh); fresh.ready();        // reconnect (page reload)
        int closedBefore = GeckoSession.closed.get();
        stale.disconnect();                               // old port's late disconnect
        check("11 a stale port's disconnect leaves the live session alone",
                f("session")!=null && f("port")==fresh && GeckoSession.closed.get()==closedBefore,
                "session="+(f("session")!=null)+" portIsFresh="+(f("port")==fresh));
        check("11b ...and a mint still works right after it",
                gen.generate("y","VD")!=null, "");

        // ── 12. the CURRENT port disconnecting DOES tear down ────────────
        fresh.disconnect();
        check("12 the live port disconnecting clears session+port+cache",
                f("session")==null && f("port")==null && cache().isEmpty(), "");

        // ── 13. session TTL expiry recycles ──────────────────────────────
        newGen(true);
        gen.generate("ttl","VD");
        ageSession(6L*60*60*1000);                        // > SESSION_TTL_MS (5h)
        gen.generate("ttl2","VD");
        check("13 a session past SESSION_TTL_MS is recycled",
                GeckoSession.created.get()==2 && Log.saw("stale"), "created="+GeckoSession.created.get());

        // ── 14. wedged page: mint timeout recycles the session ───────────
        newGen(true);
        gen.generate("w0","VD");
        livePort.autoReply = false;                       // page never answers
        long t0 = System.currentTimeMillis();
        String tw = gen.generate("w1","VD");
        long dt = System.currentTimeMillis()-t0;
        check("14 a mint the page never answers returns null", tw==null, "took="+dt+"ms");
        check("14b ...uses the 15s cached-minter ceiling", dt>=15000 && dt<20000, dt+"ms");
        check("14c ...and RECYCLES the wedged session (else it stays wedged 5h)",
                f("session")==null && Log.saw("recycling session"), "");

        // ── 15. forceFresh gets the WIDER 30s ceiling ────────────────────
        newGen(true);
        gen.generate("f0","VD");
        livePort.autoReply = false;
        t0 = System.currentTimeMillis();
        gen.generateFresh("f1","VD");
        dt = System.currentTimeMillis()-t0;
        check("15 a forceFresh mint waits MINT_FRESH_TIMEOUT_MS, not 15s", dt>=30000 && dt<35000, dt+"ms");

        // ── 16. init timeout: young session is LEFT LOADING ──────────────
        newGen(false);                                    // port never connects
        t0 = System.currentTimeMillis();
        String ti = gen.generate("slow","VD");
        dt = System.currentTimeMillis()-t0;
        check("16 no ready signal -> null after INIT_TIMEOUT_MS", ti==null && dt>=10000 && dt<13000, dt+"ms");
        check("16b a still-young session is left loading, not destroyed",
                f("session")!=null && Log.saw("leaving the session loading"), "");

        // ── 17. ...and a late-arriving page is collected by the next call ─
        final WebExtension.Port late = new WebExtension.Port("port#late");
        new Thread(() -> { try { Thread.sleep(200); } catch(Exception e){} gen.onPortConnected(late); late.ready(); }).start();
        String tl = gen.generate("slow","VD");
        check("17 the next call collects the page that arrived late",
                tl!=null, String.valueOf(tl));
        check("17b ...without building a second session",
                GeckoSession.created.get()==1, "created="+GeckoSession.created.get());

        // ── 18. init timeout past the grace window DOES recycle ──────────
        newGen(false);
        gen.generate("g1","VD");                          // 10s, leaves it loading
        ageSession(40*1000);                              // > SESSION_INIT_GRACE_MS
        Log.clear();
        gen.generate("g2","VD");
        check("18 past SESSION_INIT_GRACE_MS a stuck session is recycled",
                Log.saw("recycling"), "");

        // ── 19. session create failure ───────────────────────────────────
        newGen(true); GeckoSession.failOpen = true;
        String tf = gen.generate("boom","VD");
        check("19 a session that fails to open returns null, no crash", tf==null, String.valueOf(tf));
        GeckoSession.failOpen = false;
        check("19b ...and the next call can still recover",
                gen.generate("after","VD")!=null, "");

        // ── 21. a mint in flight when the port drops fails FAST ──────────
        newGen(true);
        gen.generate("p0","VD");
        livePort.autoReply = false;
        final WebExtension.Port dying = livePort;
        Future<String> inflight = ex.submit(() -> gen.generate("p1","VD"));
        Thread.sleep(300);
        t0 = System.currentTimeMillis();
        dying.disconnect();                               // sweep must fail it now
        String r21 = inflight.get(5, TimeUnit.SECONDS);   // NOT the 15s timeout
        dt = System.currentTimeMillis()-t0;
        check("21 a mint in flight when the port drops fails fast, not on timeout",
                r21==null && dt<3000, "took="+dt+"ms");

        // ── 22. junk from the page is survivable ─────────────────────────
        newGen(true);
        gen.generate("j0","VD");
        JSONObject junk = new JSONObject();
        junk.put("type","mintResult"); junk.put("requestId","never-seen"); junk.put("token","X");
        livePort.deliver(junk);                           // unknown id
        JSONObject noId = new JSONObject();
        noId.put("type","mintResult"); noId.put("token","X");
        livePort.deliver(noId);                           // missing id
        JSONObject weird = new JSONObject();
        weird.put("type","who-knows");
        livePort.deliver(weird);                          // unknown type
        check("22 late/unknown/malformed page messages are ignored, not fatal",
                gen.generate("j1","VD")!=null, "");

        // ── 20. shutdown ─────────────────────────────────────────────────
        gen.shutdown();
        check("20 shutdown clears session, port and cache",
                f("session")==null && f("port")==null && cache().isEmpty(), "");

        ex.shutdownNow();
        System.out.println("\n"+pass+" passed, "+fail+" failed");
        System.exit(fail==0?0:1);
    }
}
