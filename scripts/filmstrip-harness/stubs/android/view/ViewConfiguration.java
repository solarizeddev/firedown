package android.view;

import android.content.Context;

/** The platform's values at density 2.75. */
public class ViewConfiguration {
    private static final ViewConfiguration INSTANCE = new ViewConfiguration();

    public static ViewConfiguration get(Context context) {
        return INSTANCE;
    }

    public int getScaledTouchSlop() {
        return 22;
    }

    public int getScaledMinimumFlingVelocity() {
        return 137;
    }

    public int getScaledMaximumFlingVelocity() {
        return 22000;
    }
}
