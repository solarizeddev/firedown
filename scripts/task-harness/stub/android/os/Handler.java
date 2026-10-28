package android.os;
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
    public void handleMessage(Message m) {}
    void dispatchMessage(Message m) {
        if (m.callback != null) { m.callback.run(); return; }
        if (callback != null && callback.handleMessage(m)) return;
        handleMessage(m);
    }
}
