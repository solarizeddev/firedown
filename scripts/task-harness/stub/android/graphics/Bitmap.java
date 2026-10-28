package android.graphics;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
public class Bitmap {
    public enum Config { ARGB_8888, RGB_565 }
    public enum CompressFormat { JPEG, PNG, WEBP }
    public static final List<Bitmap> ALL = Collections.synchronizedList(new ArrayList<>());
    public static volatile boolean compressFails;
    private final int width, height;
    private volatile boolean recycled;
    public volatile int useAfterRecycle;
    private Bitmap(int w, int h) { width = w; height = h; }
    public static Bitmap createBitmap(int w, int h, Config c) { Bitmap b = new Bitmap(w, h); ALL.add(b); return b; }
    public int getWidth() { if (recycled) useAfterRecycle++; return width; }
    public int getHeight() { if (recycled) useAfterRecycle++; return height; }
    /** Writes a JPEG-shaped stream (SOI ... EOI). */
    public boolean compress(CompressFormat format, int quality, OutputStream stream) {
        if (recycled) throw new IllegalStateException("Can't compress a recycled bitmap");
        if (compressFails) return false;
        try {
            stream.write(new byte[]{(byte) 0xFF, (byte) 0xD8});
            stream.write(new byte[2048]);
            stream.write(new byte[]{(byte) 0xFF, (byte) 0xD9});
        } catch (IOException e) {
            return false;
        }
        return true;
    }
    public void recycle() { recycled = true; }
    public boolean isRecycled() { return recycled; }
}
