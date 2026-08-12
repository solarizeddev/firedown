package android.os;
import java.util.concurrent.*;
public class Handler {
    public static final ExecutorService MAIN =
            Executors.newSingleThreadExecutor(r -> { Thread t=new Thread(r,"fake-main"); t.setDaemon(true); return t; });
    /** Set >0 to delay the "main thread" runnable, simulating a busy UI thread. */
    public static volatile long postDelayMs = 0;
    public Handler(Looper l) {}
    public boolean post(Runnable r) {
        long d = postDelayMs;
        MAIN.execute(() -> { if (d>0) { try { Thread.sleep(d); } catch (InterruptedException e){ Thread.currentThread().interrupt(); } } r.run(); });
        return true;
    }
}
