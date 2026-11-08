package com.solarized.firedown.phone;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Double-tap seek on a web video in page fullscreen: the first tap goes to
 * the page, the second (and each streak tap) is kept and seeks.
 */
public class WebVideoSeekGestureTest {

    private static final int W = 900;
    private static final float LEFT = 100f;
    private static final float MIDDLE = 450f;
    private static final float RIGHT = 800f;
    private static final float Y = 500f;

    private static WebVideoSeekGesture gesture() {
        return new WebVideoSeekGesture(300L, 100f, 10f, 400L);
    }

    /** One tap: returns {kept ? 1 : 0, direction}. */
    private static int[] tap(WebVideoSeekGesture g, float x, long t) {
        boolean kept = g.onDown(x, Y, t, W, true);
        int dir = g.onUp(x, Y, t + 50);
        return new int[] {kept ? 1 : 0, dir};
    }

    @Test
    public void zones() {
        assertEquals(-1, WebVideoSeekGesture.zoneOf(LEFT, W));
        assertEquals(0, WebVideoSeekGesture.zoneOf(MIDDLE, W));
        assertEquals(1, WebVideoSeekGesture.zoneOf(RIGHT, W));
        assertEquals(0, WebVideoSeekGesture.zoneOf(LEFT, 0));
    }

    @Test
    public void singleTapGoesToThePage() {
        WebVideoSeekGesture g = gesture();
        int[] r = tap(g, RIGHT, 0);
        assertEquals(0, r[0]);
        assertEquals(0, r[1]);
    }

    @Test
    public void doubleTapRightSeeksForwardAndKeepsTheSecondTap() {
        WebVideoSeekGesture g = gesture();
        assertEquals(0, tap(g, RIGHT, 0)[0]);
        int[] second = tap(g, RIGHT, 200);
        assertEquals(1, second[0]);
        assertEquals(1, second[1]);
        assertEquals(1, g.getStreakTaps());
    }

    @Test
    public void doubleTapLeftSeeksBack() {
        WebVideoSeekGesture g = gesture();
        tap(g, LEFT, 0);
        assertEquals(-1, tap(g, LEFT, 200)[1]);
    }

    @Test
    public void middleIsThePages() {
        WebVideoSeekGesture g = gesture();
        tap(g, MIDDLE, 0);
        int[] second = tap(g, MIDDLE, 200);
        assertEquals(0, second[0]);
        assertEquals(0, second[1]);
    }

    @Test
    public void slowSecondTapIsAnotherFirstTap() {
        WebVideoSeekGesture g = gesture();
        tap(g, RIGHT, 0);
        int[] late = tap(g, RIGHT, 50 + 301 + 1);
        assertEquals(0, late[0]);
        // ...and pairs with the one after it.
        assertEquals(1, tap(g, RIGHT, 50 + 302 + 100)[1]);
    }

    @Test
    public void tapsInDifferentThirdsDoNotPair() {
        WebVideoSeekGesture g = gesture();
        tap(g, LEFT, 0);
        assertEquals(0, tap(g, RIGHT, 200)[0]);
    }

    @Test
    public void farApartTapsInOneThirdDoNotPair() {
        WebVideoSeekGesture g = gesture();
        assertFalse(g.onDown(800f, 100f, 0, W, true));
        g.onUp(800f, 100f, 50);
        assertFalse(g.onDown(800f, 400f, 200, W, true));
    }

    @Test
    public void streakSeeksOncePerTap() {
        WebVideoSeekGesture g = gesture();
        tap(g, RIGHT, 0);
        int seeks = 0;
        long t = 200;
        for (int i = 0; i < 5; i++) {
            int[] r = tap(g, RIGHT, t);
            assertEquals(1, r[0]);
            seeks += r[1];
            t += 300;
        }
        assertEquals(5, seeks);
        assertEquals(5, g.getStreakTaps());
    }

    @Test
    public void streakEndsAfterItsWindow() {
        WebVideoSeekGesture g = gesture();
        tap(g, RIGHT, 0);
        tap(g, RIGHT, 200); // up at 250, streak until 1050
        assertEquals(0, tap(g, RIGHT, 1100)[0]);
    }

    @Test
    public void tapInTheOtherThirdEndsTheStreak() {
        WebVideoSeekGesture g = gesture();
        tap(g, RIGHT, 0);
        tap(g, RIGHT, 200);
        assertEquals(0, tap(g, LEFT, 400)[0]);
        assertEquals(0, tap(g, RIGHT, 600)[0]);
    }

    @Test
    public void newDoubleTapRestartsTheCount() {
        WebVideoSeekGesture g = gesture();
        tap(g, RIGHT, 0);
        tap(g, RIGHT, 200);
        tap(g, RIGHT, 400);
        assertEquals(2, g.getStreakTaps());
        tap(g, LEFT, 2000);
        tap(g, LEFT, 2200);
        assertEquals(1, g.getStreakTaps());
    }

    @Test
    public void keptGestureThatDragsDoesNotSeek() {
        WebVideoSeekGesture g = gesture();
        tap(g, RIGHT, 0);
        assertTrue(g.onDown(RIGHT, Y, 200, W, true));
        g.onMove(RIGHT - 80, Y);
        assertEquals(0, g.onUp(RIGHT - 80, Y, 260));
    }

    @Test
    public void keptLongPressDoesNotSeek() {
        WebVideoSeekGesture g = gesture();
        tap(g, RIGHT, 0);
        assertTrue(g.onDown(RIGHT, Y, 200, W, true));
        assertEquals(0, g.onUp(RIGHT, Y, 900));
    }

    @Test
    public void secondFingerIsNotATap() {
        WebVideoSeekGesture g = gesture();
        tap(g, RIGHT, 0);
        assertTrue(g.onDown(RIGHT, Y, 200, W, true));
        g.onPointerDown();
        assertEquals(0, g.onUp(RIGHT, Y, 250));
    }

    @Test
    public void draggedFirstTapDoesNotPair() {
        WebVideoSeekGesture g = gesture();
        g.onDown(RIGHT, Y, 0, W, true);
        g.onMove(RIGHT, Y + 50);
        g.onUp(RIGHT, Y + 50, 60);
        assertFalse(g.onDown(RIGHT, Y, 200, W, true));
    }

    @Test
    public void cancelledFirstTapDoesNotPair() {
        WebVideoSeekGesture g = gesture();
        g.onDown(RIGHT, Y, 0, W, true);
        g.onCancel();
        assertFalse(g.onDown(RIGHT, Y, 100, W, true));
    }

    @Test
    public void disabledKeepsNothingAndForgets() {
        WebVideoSeekGesture g = gesture();
        tap(g, RIGHT, 0);
        assertFalse(g.onDown(RIGHT, Y, 200, W, false));
        g.onUp(RIGHT, Y, 250);
        // The tap while disabled forgot the first; it is a first tap itself
        // only once seeking is possible again.
        assertFalse(g.onDown(RIGHT, Y, 400, W, true));
    }

    // ── seek target ─────────────────────────────────────────────────────────

    @Test
    public void targetMovesOnWhilePlaying() {
        assertEquals(42.0, WebVideoSeekGesture.seekTarget(100, 30, 1, true, 2000, 1), 1e-9);
        assertEquals(26.0, WebVideoSeekGesture.seekTarget(100, 30, 2, true, 3000, -1), 1e-9);
    }

    @Test
    public void pausedTargetIgnoresTheClock() {
        assertEquals(40.0, WebVideoSeekGesture.seekTarget(100, 30, 1, false, 5000, 1), 1e-9);
    }

    @Test
    public void targetClampsToTheVideo() {
        assertEquals(0.0, WebVideoSeekGesture.seekTarget(100, 4, 1, false, 0, -1), 1e-9);
        assertEquals(100.0, WebVideoSeekGesture.seekTarget(100, 95, 1, false, 0, 1), 1e-9);
    }

    @Test
    public void liveOrUnknownLengthIsNotSeekable() {
        assertTrue(Double.isNaN(WebVideoSeekGesture.seekTarget(
                Double.POSITIVE_INFINITY, 30, 1, true, 0, 1)));
        assertTrue(Double.isNaN(WebVideoSeekGesture.seekTarget(Double.NaN, 30, 1, true, 0, 1)));
        assertTrue(Double.isNaN(WebVideoSeekGesture.seekTarget(0, 0, 1, true, 0, 1)));
    }
}
