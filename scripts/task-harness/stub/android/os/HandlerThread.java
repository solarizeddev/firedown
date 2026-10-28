package android.os;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
/** Daemon here (not on Android) only so a failing harness can never keep the
 *  JVM alive; ALL lets the harness account for every thread ever started. */
public class HandlerThread extends Thread {
    public static final List<HandlerThread> ALL = Collections.synchronizedList(new ArrayList<>());
    private volatile Looper looper;
    private final CountDownLatch ready = new CountDownLatch(1);
    public final int priority;
    public HandlerThread(String name) { this(name, 0); }
    public HandlerThread(String name, int priority) { super(name); this.priority = priority; setDaemon(true); ALL.add(this); }
    @Override public void run() {
        looper = new Looper();
        Looper.bind(looper);
        ready.countDown();
        looper.loop();
    }
    public Looper getLooper() {
        try { ready.await(); } catch (InterruptedException e) { throw new RuntimeException(e); }
        return looper;
    }
    public boolean quitSafely() { getLooper().quitSafely(); return true; }
}
