package com.solarized.firedown.phone;

/**
 * The browser's picture-in-picture bookkeeping across the activity's
 * lifecycle, as a pure state machine (no Android types) so a JVM test can
 * replay every ordering of callbacks the devices deliver.
 *
 * <p>{@link BrowserMainActivity} forwards its callbacks here and acts on the
 * answers: a {@code true} from {@link #onStop}, {@link #settleStoppedExit}
 * or {@link #onDestroy} means the user threw the PiP window away, and the
 * video ends (paused, out of fullscreen). Everything else keeps playing.
 *
 * <p>Orderings, from devices (the file player's history and AOSP):
 * <ul>
 *   <li>Expand: the mode leaves PiP while paused, then onResume.</li>
 *   <li>Close (X) on AOSP: the mode leaves PiP while paused, then onStop
 *       with no onResume, then a finishing onDestroy.</li>
 *   <li>Close on ROMs that stop first: onStop in PiP, then the mode leaves
 *       PiP while stopped, then onDestroy.</li>
 *   <li>Drag-to-dismiss on Samsung: onStop still in PiP with the screen
 *       on, no mode change, then onDestroy without the finishing flag.</li>
 *   <li>Screen off in PiP: onStop in PiP with the screen off (not a
 *       close); screen on: onStart, still in PiP.</li>
 *   <li>Brought back while stopped (screen off, then the app opened from a
 *       notification or the launcher): the mode leaves PiP while stopped,
 *       then onStart and onResume. Indistinguishable from the stop-first
 *       close at the mode change, so that one is decided a moment later:
 *       an onStart in between makes it an expand.</li>
 * </ul>
 */
public final class PipSession {

    /** The activity's lifecycle state when a callback arrives. */
    public enum State { STOPPED, STARTED, RESUMED }

    /**
     * How long a close keeps the private tabs from being deleted by the
     * finishing destroy that follows it (the system removes the task right
     * after the close). A later finish is the user leaving the browser.
     */
    static final long CLOSED_WINDOW_MS = 5_000L;

    /**
     * How long a mode exit while stopped waits for an onStart before it is
     * read as a close. The bring-back delivers onStart in the same or the
     * next transaction; the close never does.
     */
    public static final long STOPPED_EXIT_SETTLE_MS = 500L;

    private boolean mLeavingForOwnStart;
    private boolean mInPipStay;
    private boolean mTeardown;
    private boolean mStoppedExitPending;
    private boolean mClosedFromPip;
    private long mClosedAtMs;

    // ── the app's own starts ────────────────────────────────────────────────

    /** The activity is about to start another screen or app itself. */
    public void ownStartBegan() {
        mLeavingForOwnStart = true;
    }

    /** The start threw: nothing left, the next leave is the user's. */
    public void ownStartFailed() {
        mLeavingForOwnStart = false;
    }

    public boolean leavingForOwnStart() {
        return mLeavingForOwnStart;
    }

    // ── mode changes ────────────────────────────────────────────────────────

    /** The activity entered PiP. */
    public void enteredPip() {
        mInPipStay = true;
        mTeardown = false;
        mStoppedExitPending = false;
    }

    /**
     * The activity left PiP. Never a close by itself: while stopped the
     * caller must post {@link #settleStoppedExit} (returns true then).
     *
     * @return true when the caller must schedule {@link #settleStoppedExit}
     */
    public boolean leftPip(State state) {
        if (!mInPipStay) {
            return false;
        }
        if (state == State.STOPPED) {
            mStoppedExitPending = true;
            return true;
        }
        if (state == State.STARTED) {
            // Expand (onResume follows) or a close on its way to onStop.
            mTeardown = true;
        }
        // RESUMED: a ROM that resumed before reporting the change expanded.
        return false;
    }

    /**
     * The settle delay after a mode exit while stopped has passed.
     *
     * @return true when it was a close
     */
    public boolean settleStoppedExit(long nowMs) {
        if (!mStoppedExitPending) {
            return false;
        }
        return dismiss(nowMs);
    }

    // ── lifecycle ───────────────────────────────────────────────────────────

    public void onStart() {
        // Visible again: a pending mode exit was a bring-back, and a later
        // finish is the user leaving the browser.
        mStoppedExitPending = false;
        mClosedFromPip = false;
    }

    public void onResume() {
        mLeavingForOwnStart = false;
        mInPipStay = false;
        mTeardown = false;
        mStoppedExitPending = false;
        mClosedFromPip = false;
    }

    public void onPause() {
        // onUserLeaveHint runs before onPause: an own start is one leave.
        mLeavingForOwnStart = false;
    }

    /**
     * @param inPip        the activity still reports PiP mode
     * @param interactive  the display is on
     * @return true when this stop is a close
     */
    public boolean onStop(boolean inPip, boolean interactive, long nowMs) {
        if (!mInPipStay) {
            return false;
        }
        if (PipPolicy.dismissedAtStop(mTeardown, inPip, interactive)) {
            return dismiss(nowMs);
        }
        return false;
    }

    /**
     * The activity is being destroyed. A mode exit still waiting to settle
     * is a close (the stop-first ROMs destroy right after it).
     *
     * @return true when it was a close
     */
    public boolean onDestroy(long nowMs) {
        return settleStoppedExit(nowMs);
    }

    // ── queries ─────────────────────────────────────────────────────────────

    /** In a PiP window, or on the way out of one: no resource recreate. */
    public boolean holdsRecreate(boolean inPip) {
        return inPip || mInPipStay || mTeardown || mStoppedExitPending;
    }

    /**
     * The PiP window was just closed: the finishing destroy that follows is
     * the system removing the task, not the user leaving private browsing.
     */
    public boolean wasClosedFromPip(long nowMs) {
        return mClosedFromPip && nowMs - mClosedAtMs >= 0 && nowMs - mClosedAtMs < CLOSED_WINDOW_MS;
    }

    boolean inPipStay() {
        return mInPipStay;
    }

    private boolean dismiss(long nowMs) {
        if (!mInPipStay) {
            mStoppedExitPending = false;
            return false;
        }
        mInPipStay = false;
        mTeardown = false;
        mStoppedExitPending = false;
        mClosedFromPip = true;
        mClosedAtMs = nowMs;
        return true;
    }
}
