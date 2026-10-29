package android.graphics;

import java.util.ArrayList;
import java.util.List;

/** Records what is drawn, clipped to the last clipRect. */
public class Canvas {
    public static final class Op {
        public final boolean bitmap;
        public final int color;
        public final float left;
        public final float top;
        public final float right;
        public final float bottom;

        Op(boolean bitmap, int color, float left, float top, float right, float bottom) {
            this.bitmap = bitmap;
            this.color = color;
            this.left = left;
            this.top = top;
            this.right = right;
            this.bottom = bottom;
        }
    }

    public final List<Op> ops = new ArrayList<>();
    private float mClipLeft = -1e9f;
    private float mClipRight = 1e9f;
    private int mSaves;

    public int save() {
        return ++mSaves;
    }

    public void restore() {
        mSaves--;
        mClipLeft = -1e9f;
        mClipRight = 1e9f;
    }

    public boolean clipRect(int left, int top, int right, int bottom) {
        mClipLeft = left;
        mClipRight = right;
        return true;
    }

    private void add(boolean bitmap, int color, float l, float t, float r, float b) {
        float cl = Math.max(l, mClipLeft);
        float cr = Math.min(r, mClipRight);
        if (cr > cl) {
            ops.add(new Op(bitmap, color, cl, t, cr, b));
        }
    }

    public void drawRect(float left, float top, float right, float bottom, Paint paint) {
        add(false, paint.getColor(), left, top, right, bottom);
    }

    public void drawRect(Rect r, Paint paint) {
        add(false, paint.getColor(), r.left, r.top, r.right, r.bottom);
    }

    public void drawBitmap(Bitmap b, Rect src, Rect dst, Paint paint) {
        if (src.right <= src.left || src.bottom <= src.top
                || src.left < 0 || src.top < 0
                || src.right > b.getWidth() || src.bottom > b.getHeight()) {
            throw new IllegalStateException("bad src rect");
        }
        add(true, 0, dst.left, dst.top, dst.right, dst.bottom);
    }
}
