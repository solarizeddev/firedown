package android.graphics;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/** A sized, salted stand-in: getByteCount drives the budget, compress writes
 *  a recognisable payload so the harness can tell captures apart on disk. */
public class Bitmap {
    public enum CompressFormat { JPEG, PNG, WEBP, WEBP_LOSSY, WEBP_LOSSLESS }

    private final int width;
    private final int height;
    public final int salt;
    private boolean recycled;

    public Bitmap(int width, int height, int salt) { this.width = width; this.height = height; this.salt = salt; }

    public int getWidth() { return width; }
    public int getHeight() { return height; }
    public int getByteCount() { return width * height * 4; }
    public boolean isRecycled() { return recycled; }
    public void recycle() { recycled = true; }

    public boolean compress(CompressFormat format, int quality, OutputStream out) {
        try {
            out.write(("RIFF----WEBP " + format + " q" + quality + " salt=" + salt).getBytes(StandardCharsets.UTF_8));
            return true;
        } catch (IOException e) {
            return false;
        }
    }
}
