package com.solarized.firedown.phone;

/**
 * Picture-in-picture decisions shared by the browser and the file player.
 * Pure Java (no Android types) so a JVM test drives the real rules.
 *
 * <p>The browser follows Firefox for Android (android-components'
 * PictureInPictureFeature): leaving the app enters PiP only when the tab
 * on screen shows a video in page fullscreen and that tab's media plays.
 * A video that is not fullscreen keeps playing as audio, as before.
 */
public final class PipPolicy {

    /** AOSP's accepted aspect range is 100/239 .. 239/100. */
    static final double MIN_ASPECT = 100.0 / 239.0;
    static final double MAX_ASPECT = 239.0 / 100.0;

    /**
     * Kept inside the bounds: the framework compares a float made from the
     * Rational, and a ratio exactly on the bound can round outside it.
     */
    private static final double MARGIN = 0.001;

    /** Denominator of a clamped ratio. */
    static final int CLAMP_DENOMINATOR = 10000;

    private PipPolicy() {
    }

    /**
     * Whether leaving the browser should put the page in a PiP window.
     *
     * @param onBrowserScreen    the browser page is the screen shown: not a
     *                           home, the tab grid, a sheet or a dialog
     * @param domFullScreen      the tab on screen is in page (DOM)
     *                           fullscreen; the toolbar-hiding pseudo
     *                           fullscreen does not count
     * @param tabMediaPlaying    THAT tab's media is playing; another tab
     *                           playing in the background does not count
     * @param leavingForOwnStart the app itself is starting another screen
     *                           or app (onUserLeaveHint fires for that too)
     * @param alreadyInPip       the activity is already in PiP
     */
    public static boolean browserShouldEnter(boolean onBrowserScreen,
                                             boolean domFullScreen,
                                             boolean tabMediaPlaying,
                                             boolean leavingForOwnStart,
                                             boolean alreadyInPip) {
        return onBrowserScreen
                && domFullScreen
                && tabMediaPlaying
                && !leavingForOwnStart
                && !alreadyInPip;
    }

    /**
     * Whether an onStop that ends a PiP stay means the user threw the
     * window away (X, or dragged it off). Screen off keeps playing.
     *
     * <p>Three orderings, from the file player's on-device history:
     * the mode left PiP and no resume followed (X on most devices); the
     * activity stops while still in PiP with the screen ON (Samsung's
     * drag-to-dismiss, which never reports the mode change first); the
     * activity stops in PiP with the screen OFF (not a dismissal).
     *
     * @param pipTeardown   the mode left PiP and no onResume came since
     * @param inPip         the activity still reports PiP mode
     * @param interactive   the display is on
     */
    public static boolean dismissedAtStop(boolean pipTeardown, boolean inPip, boolean interactive) {
        if (pipTeardown) {
            return true;
        }
        return inPip && interactive;
    }

    /**
     * Whether leaving PiP mode means the window was thrown away: a mode
     * change that arrives with the activity already stopped. An expand
     * arrives while it is still started and is followed by onResume.
     */
    public static boolean dismissedAtModeExit(boolean activityStopped) {
        return activityStopped;
    }

    /**
     * The PiP window's aspect ratio as {numerator, denominator}, from the
     * video's size. Unknown or empty sizes give 16:9. A ratio outside the
     * framework's range is clamped just inside it, on the same side of 1
     * so the window keeps the video's orientation. Clamping is done on the
     * real ratio over a fixed denominator: rounding one integer side can
     * land just outside the bound again (a 10px-wide source:
     * round(10 * 2.39) = 24, and 10/24 = 0.4167 < 0.41841).
     */
    public static int[] aspect(int width, int height) {
        if (width <= 0 || height <= 0) {
            return new int[]{16, 9};
        }
        double ratio = (double) width / (double) height;
        if (ratio < MIN_ASPECT || ratio > MAX_ASPECT) {
            double clamped = Math.min(Math.max(ratio, MIN_ASPECT + MARGIN), MAX_ASPECT - MARGIN);
            return new int[]{(int) Math.round(clamped * CLAMP_DENOMINATOR), CLAMP_DENOMINATOR};
        }
        return new int[]{width, height};
    }

    /**
     * Smallest-width bucket of the resource qualifiers the app ships
     * (values-sw600dp, values-sw720dp). The browser handles a
     * smallestScreenSize change itself (entering PiP changes it, and a
     * recreate would rebuild the page's chrome inside the PiP window), so
     * it recreates only when the bucket changes outside PiP: an unfolding
     * foldable still gets the tablet resources.
     */
    public static int widthBucket(int smallestScreenWidthDp) {
        if (smallestScreenWidthDp >= 720) {
            return 2;
        }
        if (smallestScreenWidthDp >= 600) {
            return 1;
        }
        return 0;
    }
}
