package android.widget;

import android.content.Context;
import android.os.SystemClock;

/** A fling with constant deceleration, clamped to its bounds. */
public class OverScroller {
    private static final float DECELERATION = 6000f; /* px/s² */

    private boolean mFinished = true;
    private int mStartX;
    private int mMinX;
    private int mMaxX;
    private float mVelocity;
    private long mStartTime;
    private long mDuration;
    private int mCurrX;

    public OverScroller(Context context) {
    }

    public void fling(int startX, int startY, int velocityX, int velocityY,
                      int minX, int maxX, int minY, int maxY) {
        mStartX = startX;
        mCurrX = startX;
        mMinX = minX;
        mMaxX = maxX;
        mVelocity = velocityX;
        mStartTime = SystemClock.uptimeMillis();
        mDuration = (long) (Math.abs(velocityX) / DECELERATION * 1000f);
        mFinished = mDuration <= 0;
    }

    public boolean computeScrollOffset() {
        if (mFinished) {
            return false;
        }
        long t = SystemClock.uptimeMillis() - mStartTime;
        if (t >= mDuration) {
            t = mDuration;
            mFinished = true;
        }
        float s = t / 1000f;
        float sign = Math.signum(mVelocity);
        float x = mStartX + mVelocity * s - sign * DECELERATION * s * s / 2f;
        mCurrX = (int) Math.max(mMinX, Math.min(mMaxX, x));
        return true;
    }

    public int getCurrX() {
        return mCurrX;
    }

    public void forceFinished(boolean finished) {
        mFinished = finished;
    }

    public boolean isFinished() {
        return mFinished;
    }
}
