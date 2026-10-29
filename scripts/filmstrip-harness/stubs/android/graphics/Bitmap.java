package android.graphics;

public class Bitmap {
    private final int mWidth;
    private final int mHeight;

    private Bitmap(int width, int height) {
        mWidth = width;
        mHeight = height;
    }

    public static Bitmap of(int width, int height) {
        return new Bitmap(width, height);
    }

    public int getWidth() {
        return mWidth;
    }

    public int getHeight() {
        return mHeight;
    }

    public boolean isRecycled() {
        return false;
    }
}
