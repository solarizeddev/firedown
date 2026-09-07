package android.os;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
/**
 * A REAL single-thread message loop, not a no-op: the engine's threading
 * contract (one owner thread for the task lists, host callbacks delivered on
 * it) is what the harness exercises, so messages must actually be queued and
 * dispatched on a different thread than the caller's.
 */
public class Looper {
    private static final ThreadLocal<Looper> CURRENT = new ThreadLocal<>();
    private static final Looper MAIN = new Looper();
    private static final Message QUIT = new Message();
    /** Exceptions a handler threw while dispatching — the harness asserts none. */
    public static final List<Throwable> UNCAUGHT = Collections.synchronizedList(new ArrayList<>());
    private final LinkedBlockingQueue<Message> queue = new LinkedBlockingQueue<>();
    public static Looper myLooper() { return CURRENT.get(); }
    public static Looper getMainLooper() { return MAIN; }
    /** Marks the calling thread as "the main thread" for filePathInTasks' guard. */
    public static void bindMainToCurrentThread() { CURRENT.set(MAIN); }
    static void bind(Looper l) { CURRENT.set(l); }
    boolean enqueue(Message m) { return queue.offer(m); }
    public void quit() { queue.offer(QUIT); }
    /** Drains what is already queued, then returns from loop() — the real
     *  contract; the harness's leak sweep checks the engine thread ended. */
    public void quitSafely() { queue.offer(QUIT); }
    public void loop() {
        while (true) {
            Message m;
            try { m = queue.take(); } catch (InterruptedException e) { return; }
            if (m == QUIT) return;
            try { m.target.handleMessage(m); }
            catch (Throwable t) { UNCAUGHT.add(t); }
        }
    }
}
