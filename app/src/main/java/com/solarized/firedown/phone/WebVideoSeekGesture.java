package com.solarized.firedown.phone;

/**
 * Double-tap to seek on a web video in page fullscreen, decided on raw
 * touch coordinates (no Android types, so the JVM tests run the real
 * rules).
 *
 * <p>The left third seeks back, the right third forward, the middle is
 * the page's. The FIRST tap always goes to the page (a site's player
 * shows its controls on it); the second tap of a double tap is kept from
 * the page, and so is every further tap in the same third while the
 * streak lasts. The page never sees a double tap there, so its own
 * double-tap handling (a zoom, a player that seeks by itself) cannot add
 * to ours. A gesture is kept or passed WHOLE: the decision is made at
 * its first finger down.
 *
 * <p>The streak is the file player's: after a double tap, each tap in
 * the same third within {@link #STREAK_WINDOW_MS} seeks again, so the
 * number of taps maps one to one to seeks after the opening pair.
 */
public final class WebVideoSeekGesture {

    /** Seconds per seek, the file player's step. */
    public static final double SEEK_SECONDS = 10.0;

    /** How long after a seek another tap in the same third continues it. */
    public static final long STREAK_WINDOW_MS = 800L;

    private final long mDoubleTapTimeoutMs;
    private final float mDoubleTapSlopPx;
    private final float mTouchSlopPx;
    private final long mTapTimeoutMs;

    // The gesture in progress.
    private boolean mKept;
    private int mDownZone;
    private float mDownX;
    private float mDownY;
    private long mDownTime;
    private boolean mMoved;

    // The last tap the page received, which a second tap may pair with.
    private boolean mLastTapValid;
    private int mLastTapZone;
    private float mLastTapX;
    private float mLastTapY;
    private long mLastTapUpTime;

    // The streak.
    private int mStreakZone;
    private long mStreakUntil;
    private int mStreakTaps;

    public WebVideoSeekGesture(long doubleTapTimeoutMs, float doubleTapSlopPx,
                               float touchSlopPx, long tapTimeoutMs) {
        mDoubleTapTimeoutMs = doubleTapTimeoutMs;
        mDoubleTapSlopPx = doubleTapSlopPx;
        mTouchSlopPx = touchSlopPx;
        mTapTimeoutMs = tapTimeoutMs;
    }

    /** -1 for the left third, +1 for the right third, 0 for the middle. */
    public static int zoneOf(float x, int width) {
        if (width <= 0) {
            return 0;
        }
        if (x < width / 3f) {
            return -1;
        }
        if (x > width * 2f / 3f) {
            return 1;
        }
        return 0;
    }

    /**
     * The first finger of a gesture. Returns true when the whole gesture
     * is kept from the page.
     *
     * @param enabled whether a seekable video is in page fullscreen now;
     *                false forgets any tap and streak
     */
    public boolean onDown(float x, float y, long time, int width, boolean enabled) {
        mDownX = x;
        mDownY = y;
        mDownTime = time;
        mMoved = false;
        mDownZone = zoneOf(x, width);
        mKept = false;

        if (!enabled) {
            // Not a first tap either: nothing pairs with it later.
            mDownZone = 0;
            reset();
            return false;
        }
        if (mDownZone == 0) {
            mStreakZone = 0;
            return false;
        }
        if (mStreakZone == mDownZone && time < mStreakUntil) {
            mKept = true;
            return true;
        }
        if (mLastTapValid
                && mLastTapZone == mDownZone
                && time - mLastTapUpTime <= mDoubleTapTimeoutMs
                && distance(x, y, mLastTapX, mLastTapY) <= mDoubleTapSlopPx) {
            mKept = true;
            mStreakZone = 0;
            mStreakTaps = 0;
            return true;
        }
        mStreakZone = 0;
        return false;
    }

    /** A finger moved; a gesture that travels is not a tap. */
    public void onMove(float x, float y) {
        if (distance(x, y, mDownX, mDownY) > mTouchSlopPx) {
            mMoved = true;
        }
    }

    /** A second finger: not a tap. */
    public void onPointerDown() {
        mMoved = true;
    }

    /**
     * The last finger left. Returns the direction to seek (-1 / +1), or 0.
     */
    public int onUp(float x, float y, long time) {
        onMove(x, y);
        boolean tap = !mMoved && time - mDownTime <= mTapTimeoutMs;

        if (mKept) {
            mKept = false;
            mLastTapValid = false;
            if (!tap) {
                mStreakZone = 0;
                return 0;
            }
            mStreakZone = mDownZone;
            mStreakUntil = time + STREAK_WINDOW_MS;
            mStreakTaps++;
            return mDownZone;
        }

        if (tap && mDownZone != 0) {
            mLastTapValid = true;
            mLastTapZone = mDownZone;
            mLastTapX = mDownX;
            mLastTapY = mDownY;
            mLastTapUpTime = time;
        } else {
            mLastTapValid = false;
        }
        return 0;
    }

    /** The gesture was cancelled. */
    public void onCancel() {
        if (mKept) {
            mStreakZone = 0;
        }
        mKept = false;
        mLastTapValid = false;
    }

    /** Seeks in the current streak, for the "+30 s" badge. */
    public int getStreakTaps() {
        return mStreakTaps;
    }

    /** Forgets the paired tap and the streak. */
    public void reset() {
        mKept = false;
        mLastTapValid = false;
        mStreakZone = 0;
        mStreakTaps = 0;
    }

    /**
     * Where a seek lands: the last reported position moved on by the time
     * since it was reported while the media plays, plus the step, inside
     * [0, duration]. NaN when the media has no finite duration (a live
     * stream), which is not seekable this way.
     */
    public static double seekTarget(double duration, double position, double rate,
                                    boolean playing, long sinceReportMs, int direction) {
        if (Double.isNaN(duration) || Double.isInfinite(duration) || duration <= 0) {
            return Double.NaN;
        }
        double now = position;
        if (playing && sinceReportMs > 0 && rate > 0 && !Double.isNaN(rate)) {
            now += sinceReportMs / 1000.0 * rate;
        }
        double target = now + direction * SEEK_SECONDS;
        if (target < 0) {
            target = 0;
        }
        if (target > duration) {
            target = duration;
        }
        return target;
    }

    private static float distance(float x1, float y1, float x2, float y2) {
        float dx = x1 - x2;
        float dy = y1 - y2;
        return (float) Math.sqrt(dx * dx + dy * dy);
    }
}
