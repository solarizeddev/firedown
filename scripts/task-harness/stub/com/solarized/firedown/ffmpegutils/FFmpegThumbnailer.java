package com.solarized.firedown.ffmpegutils;
import android.graphics.Bitmap;
import java.io.File;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
/** SaveFrameTask's position-0 fallback (the real class is all native). */
public class FFmpegThumbnailer {
    public static volatile boolean returnBitmap = true;
    public static final AtomicInteger CREATED = new AtomicInteger(), RELEASED = new AtomicInteger();
    public FFmpegThumbnailer() { CREATED.incrementAndGet(); }
    public int setDataSource(String filePath, Map<String, String> dictionary) {
        return filePath != null && new File(filePath).isFile() ? 0 : -1;
    }
    public Bitmap getBitmap(long streamPos) {
        return returnBitmap ? Bitmap.createBitmap(320, 180, Bitmap.Config.ARGB_8888) : null;
    }
    public void release() { RELEASED.incrementAndGet(); }
}
