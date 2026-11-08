package com.solarized.firedown.phone;

/**
 * Double-tap to seek on a web video in page fullscreen, decided on raw
 * touch coordinates and times. The touch events themselves are an opaque
 * type {@code E} handed back to the {@link Host}, so the JVM tests run the
 * real rules with no Android types.
 *
 * <p>The left third seeks back, the right third forward. The middle third
 * and a band along the top and bottom (where a player's own controls sit)
 * are the page's: those touches pass through untouched.
 *
 * <p>A tap on a side is HELD from the page until it is clear it is not
 * the first tap of a double tap (the double-tap timeout), then handed to
 * the page late. So a double tap never reaches the page at all: a player
 * that pauses on a single tap does not pause and then seek, and a site's
 * own double-tap handling (a zoom, its own seek) cannot add to ours. A
 * held touch that turns into anything but a tap (it moves past the touch
 * slop, a second finger lands, it is held longer than the tap timeout) is
 * handed over at once, and the rest of its gesture passes live.
 *
 * <p>After a double tap, each tap in the same third within
 * {@link #STREAK_WINDOW_MS} seeks again at once (the file player's streak),
 * so taps map one to one to seeks after the opening pair.
 */
public final class WebVideoSeekGesture<E> {

    /** Seconds per seek, the file player's step. */
    public static final double SEEK_SECONDS = 10.0;

    /** How long after a seek another tap in the same third continues it. */
    public static final long STREAK_WINDOW_MS = 800L;

    /** Share of the view's height left to the page at the top and bottom. */
    public static final float BAND_FRACTION = 0.15f;

    /** What the gesture asks of the view it watches. */
    public interface Host<E> {
        /** A copy the gesture may keep past the event's dispatch. */
        E copy(E event);

        /** Hands a held copy to the page now, then releases it. */
        void deliver(E event);

        /** Releases a held copy the page will never see. */
        void discard(E event);

        /** Seek by one step; {@code taps} seeks in the streak so far. */
        void seek(int direction, int taps);

        /** Calls {@link #onTimeout} after {@code delayMs}, replacing any earlier call. */
        void schedule(long delayMs);

        void cancelSchedule();
    }

    private enum Mode { IDLE, PASS, HOLD, KEEP }

    private final Host<E> mHost;
    private final long mDoubleTapTimeoutMs;
    private final float mDoubleTapSlopPx;
    private final float mTouchSlopPx;
    private final long mTapTimeoutMs;

    // The gesture in progress (finger down to finger up).
    private Mode mMode = Mode.IDLE;
    private int mDownZone;
    private float mDownX;
    private float mDownY;
    private long mDownTime;
    private boolean mMoved;

    // The held first tap: its DOWN while the finger is down (mode HOLD),
    // then its DOWN and UP while a second tap may still come.
    private E mHeldDown;
    private E mHeldUp;
    private int mHeldZone;
    private float mHeldX;
    private float mHeldY;
    private long mHeldUpTime;

    // The streak.
    private int mStreakZone;
    private long mStreakUntil;
    private int mStreakTaps;

    public WebVideoSeekGesture(Host<E> host, long doubleTapTimeoutMs, float doubleTapSlopPx,
                               float touchSlopPx, long tapTimeoutMs) {
        mHost = host;
        mDoubleTapTimeoutMs = doubleTapTimeoutMs;
        mDoubleTapSlopPx = doubleTapSlopPx;
        mTouchSlopPx = touchSlopPx;
        mTapTimeoutMs = tapTimeoutMs;
    }

    /** The band height for a view of this height: a share of it, never under the minimum. */
    public static float bandFor(int height, float minBandPx) {
        return Math.max(height * BAND_FRACTION, minBandPx);
    }

    /**
     * -1 for the left third, +1 for the right third, 0 for the middle
     * third and for the top and bottom bands.
     */
    public static int zoneOf(float x, float y, int width, int height, float bandPx) {
        if (width <= 0 || height <= 0) {
            return 0;
        }
        if (y < bandPx || y > height - bandPx) {
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
     * The first finger of a gesture. Returns true when the event is kept
     * from the page (held or swallowed).
     *
     * @param enabled whether a seekable video is in page fullscreen now;
     *                false forgets the streak
     */
    public boolean onDown(E event, float x, float y, long time,
                          int width, int height, float bandPx, boolean enabled) {
        int zone = enabled ? zoneOf(x, y, width, height, bandPx) : 0;

        // A tap is held, waiting for this one.
        if (mHeldUp != null) {
            if (zone != 0
                    && zone == mHeldZone
                    && time - mHeldUpTime <= mDoubleTapTimeoutMs
                    && distance(x, y, mHeldX, mHeldY) <= mDoubleTapSlopPx) {
                // A double tap: the page sees neither tap.
                dropHeld();
                startGesture(Mode.KEEP, zone, x, y, time);
                mStreakZone = 0;
                mStreakTaps = 0;
                return true;
            }
            flushHeld();
        } else if (mHeldDown != null) {
            // A DOWN with no UP in between: the platform lost one; hand
            // over what is held rather than drop it.
            flushHeld();
        }

        if (zone == 0) {
            if (!enabled) {
                mStreakTaps = 0;
            }
            mStreakZone = 0;
            startGesture(Mode.PASS, zone, x, y, time);
            return false;
        }
        if (mStreakZone == zone && time < mStreakUntil) {
            startGesture(Mode.KEEP, zone, x, y, time);
            return true;
        }
        mStreakZone = 0;
        startGesture(Mode.HOLD, zone, x, y, time);
        mHeldDown = mHost.copy(event);
        mHeldZone = zone;
        mHeldX = x;
        mHeldY = y;
        mHost.schedule(mTapTimeoutMs);
        return true;
    }

    /** A finger moved. Returns true when the event is kept from the page. */
    public boolean onMove(float x, float y) {
        switch (mMode) {
            case KEEP:
                if (distance(x, y, mDownX, mDownY) > mTouchSlopPx) {
                    mMoved = true;
                }
                return true;
            case HOLD:
                if (distance(x, y, mDownX, mDownY) > mTouchSlopPx) {
                    releaseHeldDown();
                    return false;
                }
                // Within the slop the page loses nothing it would act on.
                return true;
            default:
                return false;
        }
    }

    /**
     * Any other event of the gesture (a second finger down or up). Returns
     * true when the event is kept from the page.
     */
    public boolean onOtherPointer() {
        switch (mMode) {
            case KEEP:
                mMoved = true;
                return true;
            case HOLD:
                releaseHeldDown();
                return false;
            default:
                return false;
        }
    }

    /** The last finger left. Returns true when the event is kept from the page. */
    public boolean onUp(E event, float x, float y, long time) {
        Mode mode = mMode;
        mMode = Mode.IDLE;
        switch (mode) {
            case KEEP: {
                if (distance(x, y, mDownX, mDownY) > mTouchSlopPx) {
                    mMoved = true;
                }
                boolean tap = !mMoved && time - mDownTime <= mTapTimeoutMs;
                if (!tap) {
                    mStreakZone = 0;
                    return true;
                }
                mStreakZone = mDownZone;
                mStreakUntil = time + STREAK_WINDOW_MS;
                mStreakTaps++;
                mHost.seek(mDownZone, mStreakTaps);
                return true;
            }
            case HOLD: {
                if (distance(x, y, mDownX, mDownY) > mTouchSlopPx) {
                    // Not a tap after all: the page gets the DOWN, and this
                    // UP live.
                    releaseHeldDown();
                    mMode = Mode.IDLE;
                    return false;
                }
                mHeldUp = mHost.copy(event);
                mHeldUpTime = time;
                mHost.schedule(mDoubleTapTimeoutMs);
                return true;
            }
            default:
                return false;
        }
    }

    /** The gesture was cancelled. Returns true when the event is kept from the page. */
    public boolean onCancel() {
        Mode mode = mMode;
        mMode = Mode.IDLE;
        switch (mode) {
            case KEEP:
                mStreakZone = 0;
                return true;
            case HOLD:
                // The page never saw this gesture start.
                dropHeld();
                return true;
            default:
                return false;
        }
    }

    /** The time {@link Host#schedule} asked for has come. */
    public void onTimeout() {
        if (mHeldUp != null) {
            // No second tap: the page gets its tap, late.
            flushHeld();
        } else if (mMode == Mode.HOLD) {
            // A press, not a tap: the page gets it, and the rest live.
            releaseHeldDown();
        }
    }

    /** Hands anything held to the page and forgets the streak. */
    public void flush() {
        if (mMode == Mode.HOLD) {
            releaseHeldDown();
        } else {
            flushHeld();
        }
        mStreakZone = 0;
        mStreakTaps = 0;
    }

    /** Drops anything held without handing it over, and forgets everything. */
    public void reset() {
        dropHeld();
        mMode = Mode.IDLE;
        mStreakZone = 0;
        mStreakTaps = 0;
    }

    /** Seeks in the current streak, for the "+30 s" badge. */
    public int getStreakTaps() {
        return mStreakTaps;
    }

    /** Whether a touch is held from the page. */
    public boolean isHolding() {
        return mHeldDown != null;
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

    private void startGesture(Mode mode, int zone, float x, float y, long time) {
        mMode = mode;
        mDownZone = zone;
        mDownX = x;
        mDownY = y;
        mDownTime = time;
        mMoved = false;
    }

    /** The finger is still down: the page gets the DOWN, then the rest live. */
    private void releaseHeldDown() {
        mHost.cancelSchedule();
        E down = mHeldDown;
        mHeldDown = null;
        mHeldUp = null;
        mMode = Mode.PASS;
        if (down != null) {
            mHost.deliver(down);
        }
    }

    /** A held tap goes to the page: its DOWN, then its UP. */
    private void flushHeld() {
        mHost.cancelSchedule();
        E down = mHeldDown;
        E up = mHeldUp;
        mHeldDown = null;
        mHeldUp = null;
        if (down != null) {
            mHost.deliver(down);
        }
        if (up != null) {
            mHost.deliver(up);
        }
    }

    private void dropHeld() {
        mHost.cancelSchedule();
        E down = mHeldDown;
        E up = mHeldUp;
        mHeldDown = null;
        mHeldUp = null;
        if (down != null) {
            mHost.discard(down);
        }
        if (up != null) {
            mHost.discard(up);
        }
    }

    private static float distance(float x1, float y1, float x2, float y2) {
        float dx = x1 - x2;
        float dy = y1 - y2;
        return (float) Math.sqrt(dx * dx + dy * dy);
    }
}
