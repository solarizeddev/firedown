package android.content.res;

import android.util.DisplayMetrics;

public class Resources {
    public static final Resources INSTANCE = new Resources();
    private final DisplayMetrics mMetrics = new DisplayMetrics();

    public DisplayMetrics getDisplayMetrics() {
        return mMetrics;
    }
}
