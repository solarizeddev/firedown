package org.mozilla.geckoview;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
public class GeckoSession {
    public static final AtomicInteger created = new AtomicInteger();
    public static final AtomicInteger closed  = new AtomicInteger();
    /** Harness hook: called on loadUri with the session that loaded. */
    public static volatile Consumer<GeckoSession> onLoad = s -> {};
    /** Make open() throw, to exercise the session-create failure path. */
    public static volatile boolean failOpen = false;
    public final int id;
    public volatile boolean isClosed = false;
    public GeckoSession(GeckoSessionSettings s){ id = created.incrementAndGet(); }
    public void open(GeckoRuntime r){ if (failOpen) throw new IllegalStateException("open failed (test)"); }
    public void setActive(boolean a){}
    public void loadUri(String uri){ onLoad.accept(this); }
    public void close(){ isClosed = true; closed.incrementAndGet(); }
    public static void reset(){ created.set(0); closed.set(0); failOpen=false; onLoad = s -> {}; }
}
