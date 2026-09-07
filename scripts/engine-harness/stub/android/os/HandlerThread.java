package android.os;
import java.util.concurrent.CountDownLatch;
public class HandlerThread extends Thread {
    private volatile Looper looper;
    private final CountDownLatch ready = new CountDownLatch(1);
    public HandlerThread(String name, int priority) { super(name); setDaemon(true); }
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
}
