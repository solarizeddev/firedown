package android.media;
import android.graphics.Bitmap;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
/** A frame grab takes delayMs and cannot be interrupted, like the real one
 *  (a synchronous binder/codec call). */
public class MediaMetadataRetriever {
    public static final int OPTION_PREVIOUS_SYNC = 0, OPTION_NEXT_SYNC = 1, OPTION_CLOSEST_SYNC = 2, OPTION_CLOSEST = 3;
    public static volatile long delayMs = 5;
    public static volatile boolean returnNull = false;
    public static volatile int width = 1280, height = 720;
    /** Harness hook: runs on the grabbing thread at the start of a grab. */
    public static volatile Runnable onGetFrame;
    public static final List<Long> REQUESTS_US = Collections.synchronizedList(new ArrayList<>());
    public static final AtomicInteger CREATED = new AtomicInteger(), RELEASED = new AtomicInteger();
    private String source;
    public MediaMetadataRetriever() { CREATED.incrementAndGet(); }
    public void setDataSource(String path) {
        if (path == null || !new File(path).isFile()) throw new IllegalArgumentException("setDataSource failed: status = 0x80000000");
        source = path;
    }
    public Bitmap getFrameAtTime(long timeUs, int option) {
        if (source == null) throw new IllegalStateException("no data source");
        REQUESTS_US.add(timeUs);
        Runnable h = onGetFrame;
        if (h != null) h.run();
        try { Thread.sleep(delayMs); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        return returnNull ? null : Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
    }
    public void release() throws IOException { RELEASED.incrementAndGet(); }
}
