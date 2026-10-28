package android.os;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.io.StringWriter;
import java.io.PrintWriter;
/**
 * A REAL single-thread message loop. An exception escaping a dispatch kills
 * the process on Android; here it is recorded in UNCAUGHT (with the thread
 * name) and the loop goes on, so the harness can report it as a failure.
 */
public class Looper {
    private static final ThreadLocal<Looper> CURRENT = new ThreadLocal<>();
    private static final Message QUIT = new Message();
    public static final List<String> UNCAUGHT = Collections.synchronizedList(new ArrayList<>());
    private final LinkedBlockingQueue<Message> queue = new LinkedBlockingQueue<>();
    private volatile boolean dispatching;
    private volatile Thread thread;
    private static volatile Looper sMain;
    public static Looper myLooper() { return CURRENT.get(); }
    /** The app's main looper: Sys's "main" thread. */
    public static Looper getMainLooper() { return sMain; }
    public static void setMainLooper(Looper l) { sMain = l; }
    static void bind(Looper l) { CURRENT.set(l); l.thread = Thread.currentThread(); }
    boolean enqueue(Message m) { return queue.offer(m); }
    public void quit() { queue.offer(QUIT); }
    public void quitSafely() { queue.offer(QUIT); }
    public Thread getThread() { return thread; }
    /** Nothing queued and nothing being dispatched. */
    public boolean isIdle() { return queue.isEmpty() && !dispatching; }
    public void loop() {
        while (true) {
            Message m;
            try { m = queue.take(); } catch (InterruptedException e) { return; }
            if (m == QUIT) return;
            dispatching = true;
            try { m.target.dispatchMessage(m); }
            catch (Throwable t) {
                StringWriter sw = new StringWriter();
                t.printStackTrace(new PrintWriter(sw));
                UNCAUGHT.add(Thread.currentThread().getName() + ": " + sw);
            }
            finally { dispatching = false; }
        }
    }
}
