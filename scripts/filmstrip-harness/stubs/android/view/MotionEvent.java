package android.view;

import android.os.SystemClock;

public class MotionEvent {
    public static final int ACTION_DOWN = 0;
    public static final int ACTION_UP = 1;
    public static final int ACTION_MOVE = 2;
    public static final int ACTION_CANCEL = 3;

    private final int mAction;
    private final float mX;
    private final long mTime;

    private MotionEvent(int action, float x) {
        mAction = action;
        mX = x;
        mTime = SystemClock.uptimeMillis();
    }

    public static MotionEvent of(int action, float x) {
        return new MotionEvent(action, x);
    }

    public int getActionMasked() {
        return mAction;
    }

    public float getX() {
        return mX;
    }

    public long getEventTime() {
        return mTime;
    }
}
