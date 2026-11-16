package android.os;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
/** Queue-backed Handler: messages and runnables really are dispatched on the
 *  Looper's thread, in order. */
public class Handler {
    public interface Callback { boolean handleMessage(Message msg); }
    private final Looper looper;
    private final Callback callback;
    public Handler(Looper looper) { this(looper, null); }
    public Handler(Looper looper, Callback callback) { this.looper = looper; this.callback = callback; }
    public Looper getLooper() { return looper; }
    public final Message obtainMessage() { Message m = new Message(); m.target = this; return m; }
    public final Message obtainMessage(int what) { Message m = obtainMessage(); m.what = what; return m; }
    public final Message obtainMessage(int what, Object obj) { Message m = obtainMessage(); m.what = what; m.obj = obj; return m; }
    public final boolean sendMessage(Message m) { m.target = this; return looper.enqueue(m); }
    public final boolean post(Runnable r) { Message m = new Message(); m.target = this; m.callback = r; return looper.enqueue(m); }
    private final Set<Runnable> pending = Collections.newSetFromMap(new IdentityHashMap<>());
    /** A delayed runnable is posted after the delay unless removed meanwhile. */
    public final boolean postDelayed(Runnable r, long delayMs) {
        synchronized (pending) { pending.add(r); }
        Thread t = new Thread(() -> {
            try { Thread.sleep(delayMs); } catch (InterruptedException e) { return; }
            boolean still;
            synchronized (pending) { still = pending.remove(r); }
            if (still) post(r);
        });
        t.setDaemon(true);
        t.start();
        return true;
    }
    public final void removeCallbacks(Runnable r) { synchronized (pending) { pending.remove(r); } }
    public void handleMessage(Message m) {}
    void dispatchMessage(Message m) {
        if (m.callback != null) { m.callback.run(); return; }
        if (callback != null && callback.handleMessage(m)) return;
        handleMessage(m);
    }
}
