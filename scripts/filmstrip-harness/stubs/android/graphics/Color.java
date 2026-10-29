package android.graphics;

public final class Color {
    public static final int WHITE = 0xFFFFFFFF;
    public static final int MAGENTA = 0xFFFF00FF;
    public static final int DKGRAY = 0xFF444444;

    public static int argb(int a, int r, int g, int b) {
        return (a << 24) | (r << 16) | (g << 8) | b;
    }
}
