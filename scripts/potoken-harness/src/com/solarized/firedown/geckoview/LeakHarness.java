package com.solarized.firedown.geckoview;

import android.util.Log;
import org.mozilla.geckoview.*;
import java.lang.reflect.*;
import java.util.*;

/** Resource-lifetime audit of the REAL PoTokenGenerator: every GeckoSession
 *  opened must end up closed, neither map may grow without bound, and no
 *  path may strand an entry in `pending`. */
public class LeakHarness {
    static int pass=0, fail=0;
    static void check(String n, boolean ok, String d){
        if(ok){pass++;System.out.println("PASS  "+n+(d.isEmpty()?"":"  ["+d+"]"));}
        else  {fail++;System.out.println("FAIL  "+n+"  ["+d+"]");}
    }
    static PoTokenGenerator gen;
    static Object f(String n) throws Exception {
        Field fl=PoTokenGenerator.class.getDeclaredField(n); fl.setAccessible(true); return fl.get(gen); }
    @SuppressWarnings("unchecked")
    static Map<String,Object> cache() throws Exception { return (Map<String,Object>) f("tokenCache"); }
    @SuppressWarnings("unchecked")
    static Map<String,Object> pending() throws Exception { return (Map<String,Object>) f("pending"); }
    static WebExtension.Port live;
    static int seq=0;
    static void newGen(boolean autoConnect){
        GeckoSession.reset(); Log.clear(); android.os.Handler.postDelayMs=0;
        gen = new PoTokenGenerator(new GeckoRuntime(), s -> {});
        live=null;
        GeckoSession.onLoad = s -> { if(!autoConnect) return;
            WebExtension.Port p=new WebExtension.Port("p"+(++seq)); live=p; gen.onPortConnected(p); p.ready(); };
    }
    static int leaked(){ return GeckoSession.created.get()-GeckoSession.closed.get(); }

    public static void main(String[] a) throws Exception {
        // ── L1: churn — recycle many times, everything opened must be closed
        newGen(true);
        for (int i=0;i<5;i++){
            gen.generate("v"+i,"VD");
            live.disconnect();            // forces teardown + rebuild next round
        }
        gen.shutdown();
        Thread.sleep(200);
        check("L1 repeated create/teardown closes every session it opened",
                leaked()==0, "created="+GeckoSession.created+" closed="+GeckoSession.closed);

        // ── L2: the create runnable lands AFTER ensureReady gave up ───────
        newGen(true);
        android.os.Handler.postDelayMs = 12000;      // > INIT_TIMEOUT_MS (10s)
        gen.generate("late","VD");                   // gives up at 10s, session==null
        Thread.sleep(4000);                          // let the runnable land
        boolean orphan = leaked() > 0;
        check("L2 a session created after we gave up is not left open",
                !orphan, "leaked="+leaked()+" sessionField="+(f("session")!=null));
        android.os.Handler.postDelayMs = 0;
        gen.shutdown(); Thread.sleep(200);

        // ── L3: an exception AFTER open() must still close the session ────
        newGen(true);
        GeckoSession.failSetActive = true;
        gen.generate("boom","VD");
        Thread.sleep(500);
        check("L3 a session opened then failing mid-setup is closed",
                leaked()==0, "created="+GeckoSession.created+" closed="+GeckoSession.closed);
        GeckoSession.failSetActive = false;
        gen.shutdown(); Thread.sleep(200);

        // ── L4: postMessage throwing must not strand a pending entry ──────
        newGen(true);
        gen.generate("ok","VD");
        live.throwOnPost = true;
        try { gen.generate("dead","VD"); } catch (RuntimeException e) { /* propagates today */ }
        check("L4 a failed postMessage leaves no orphan in `pending`",
                pending().isEmpty(), "pending="+pending().size());
        live.throwOnPost = false;
        gen.shutdown(); Thread.sleep(200);

        // ── L5: tokenCache must not grow without bound ────────────────────
        newGen(true);
        for (int i=0;i<300;i++) gen.generate("vid"+i,"VD");
        check("L5 tokenCache is bounded across many videos",
                cache().size()<=128, "entries="+cache().size());

        // ── L6: shutdown drops everything ─────────────────────────────────
        gen.shutdown(); Thread.sleep(200);
        check("L6 shutdown leaves no session open and no state held",
                leaked()==0 && cache().isEmpty() && pending().isEmpty(),
                "leaked="+leaked()+" cache="+cache().size()+" pending="+pending().size());

        System.out.println("\n"+pass+" passed, "+fail+" failed");
        System.exit(fail==0?0:1);
    }
}
