package android.os;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;
/** Records every wake lock; held state follows Android's rules: a
 *  non-reference-counted lock is held after any acquire until one release or
 *  its timeout; a reference-counted one counts, and an extra release throws
 *  "WakeLock under-locked" like the real one. */
public class PowerManager {
    public static final int PARTIAL_WAKE_LOCK = 1;
    public static final List<WakeLock> ALL = Collections.synchronizedList(new ArrayList<>());
    /** Harness hook: runs on the acquiring thread inside acquire(), before it returns. */
    public static volatile Consumer<WakeLock> onAcquire;
    public WakeLock newWakeLock(int levelAndFlags, String tag) {
        WakeLock w = new WakeLock(levelAndFlags, tag);
        ALL.add(w);
        return w;
    }
    public static boolean anyHeld() {
        synchronized (ALL) { for (WakeLock w : ALL) if (w.isHeld()) return true; }
        return false;
    }
    public static final class WakeLock {
        public final int flags; public final String tag;
        private boolean refCounted = true;
        private int count;
        private long deadline = Long.MAX_VALUE;
        public volatile int acquires, releases;
        public volatile long lastTimeoutMs = -1;
        public final List<String> threads = Collections.synchronizedList(new ArrayList<>());
        WakeLock(int f, String t) { flags = f; tag = t; }
        public synchronized void setReferenceCounted(boolean v) { refCounted = v; }
        public void acquire() { acquireInternal(-1); }
        public void acquire(long timeoutMs) { acquireInternal(timeoutMs); }
        private void acquireInternal(long timeoutMs) {
            synchronized (this) {
                acquires++;
                threads.add(Thread.currentThread().getName());
                lastTimeoutMs = timeoutMs;
                if (refCounted) count++; else count = 1;
                deadline = timeoutMs > 0 ? System.currentTimeMillis() + timeoutMs : Long.MAX_VALUE;
            }
            Consumer<WakeLock> h = onAcquire;
            if (h != null) h.accept(this);
        }
        public synchronized void release() {
            releases++;
            if (refCounted) {
                if (count <= 0) throw new RuntimeException("WakeLock under-locked " + tag);
                count--;
            } else {
                count = 0;
            }
        }
        public synchronized boolean isHeld() {
            if (count > 0 && System.currentTimeMillis() > deadline) count = 0;
            return count > 0;
        }
    }
}
