package com.solarized.firedown.phone;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Double-tap seek on a web video in page fullscreen: a tap on a side is
 * held until no second tap can follow, a double tap never reaches the
 * page, and every held event is handed over or dropped exactly once.
 */
public class WebVideoSeekGestureTest {

    private static final int W = 900;
    private static final int H = 600;
    private static final float BAND = 90f;
    private static final float LEFT = 100f;
    private static final float MIDDLE = 450f;
    private static final float RIGHT = 800f;
    private static final float Y = 300f;
    private static final long DOUBLE_TAP = 300L;
    private static final long TAP = 400L;

    /** Records what the gesture asks of the view. */
    private static final class Rec implements WebVideoSeekGesture.Host<String> {
        final List<String> log = new ArrayList<>();
        final List<String> delivered = new ArrayList<>();
        final List<Integer> seeks = new ArrayList<>();
        final Map<String, Integer> held = new HashMap<>();
        long scheduled = -1;

        @Override
        public String copy(String event) {
            held.merge(event, 1, Integer::sum);
            return event;
        }

        @Override
        public void deliver(String event) {
            release(event);
            delivered.add(event);
            log.add("deliver:" + event);
        }

        @Override
        public void discard(String event) {
            release(event);
            log.add("discard:" + event);
        }

        private void release(String event) {
            Integer n = held.get(event);
            assertTrue("released a copy never taken: " + event, n != null && n > 0);
            if (n == 1) {
                held.remove(event);
            } else {
                held.put(event, n - 1);
            }
        }

        @Override
        public void seek(int direction, int taps) {
            seeks.add(direction);
            log.add("seek:" + direction + "/" + taps);
        }

        @Override
        public void schedule(long delayMs) {
            scheduled = delayMs;
        }

        @Override
        public void cancelSchedule() {
            scheduled = -1;
        }

        void assertNothingHeld() {
            assertTrue("copies never released: " + held, held.isEmpty());
        }
    }

    private Rec rec;
    private int seq;

    private WebVideoSeekGesture<String> gesture() {
        rec = new Rec();
        seq = 0;
        return new WebVideoSeekGesture<>(rec, DOUBLE_TAP, 100f, 10f, TAP);
    }

    private boolean down(WebVideoSeekGesture<String> g, float x, float y, long t, boolean enabled) {
        return g.onDown("D" + (++seq), x, y, t, W, H, BAND, enabled);
    }

    private boolean up(WebVideoSeekGesture<String> g, float x, float y, long t) {
        return g.onUp("U" + seq, x, y, t);
    }

    /** One tap; returns {down kept, up kept}. */
    private boolean[] tap(WebVideoSeekGesture<String> g, float x, long t) {
        boolean d = down(g, x, Y, t, true);
        boolean u = up(g, x, Y, t + 50);
        return new boolean[] {d, u};
    }

    // ── zones ────────────────────────────────────────────────────────────

    @Test
    public void zones() {
        assertEquals(-1, WebVideoSeekGesture.zoneOf(LEFT, Y, W, H, BAND));
        assertEquals(0, WebVideoSeekGesture.zoneOf(MIDDLE, Y, W, H, BAND));
        assertEquals(1, WebVideoSeekGesture.zoneOf(RIGHT, Y, W, H, BAND));
        assertEquals(0, WebVideoSeekGesture.zoneOf(LEFT, Y, 0, H, BAND));
        assertEquals(0, WebVideoSeekGesture.zoneOf(LEFT, Y, W, 0, BAND));
    }

    @Test
    public void topAndBottomBandsAreThePages() {
        assertEquals(0, WebVideoSeekGesture.zoneOf(RIGHT, 20f, W, H, BAND));
        assertEquals(0, WebVideoSeekGesture.zoneOf(RIGHT, H - 20f, W, H, BAND));
        assertEquals(1, WebVideoSeekGesture.zoneOf(RIGHT, BAND + 1, W, H, BAND));
        assertEquals(1, WebVideoSeekGesture.zoneOf(RIGHT, H - BAND - 1, W, H, BAND));
    }

    @Test
    public void bandIsAShareOfTheHeightNeverUnderTheMinimum() {
        assertEquals(150f, WebVideoSeekGesture.bandFor(1000, 100f), 1e-3);
        assertEquals(100f, WebVideoSeekGesture.bandFor(400, 100f), 1e-3);
    }

    // ── single taps ──────────────────────────────────────────────────────

    @Test
    public void tapOnASideIsHeldThenHandedOverInOrder() {
        WebVideoSeekGesture<String> g = gesture();
        boolean[] r = tap(g, RIGHT, 0);
        assertTrue(r[0]);
        assertTrue(r[1]);
        assertTrue(rec.delivered.isEmpty());
        assertEquals(DOUBLE_TAP, rec.scheduled);
        g.onTimeout();
        assertEquals(List.of("D1", "U1"), rec.delivered);
        assertTrue(rec.seeks.isEmpty());
        assertFalse(g.isHolding());
        rec.assertNothingHeld();
    }

    @Test
    public void middleIsThePagesAndNeverHeld() {
        WebVideoSeekGesture<String> g = gesture();
        assertFalse(tap(g, MIDDLE, 0)[0]);
        boolean[] second = tap(g, MIDDLE, 200);
        assertFalse(second[0]);
        assertFalse(second[1]);
        assertTrue(rec.seeks.isEmpty());
        assertFalse(g.isHolding());
    }

    @Test
    public void controlBarTapsAreNeverHeld() {
        WebVideoSeekGesture<String> g = gesture();
        assertFalse(down(g, RIGHT, H - 30f, 0, true));
        assertFalse(up(g, RIGHT, H - 30f, 50));
        assertFalse(down(g, RIGHT, H - 30f, 200, true));
        assertFalse(up(g, RIGHT, H - 30f, 250));
        assertTrue(rec.seeks.isEmpty());
    }

    // ── double taps ──────────────────────────────────────────────────────

    @Test
    public void doubleTapRightSeeksForwardAndThePageSeesNeitherTap() {
        WebVideoSeekGesture<String> g = gesture();
        tap(g, RIGHT, 0);
        boolean[] second = tap(g, RIGHT, 200);
        assertTrue(second[0]);
        assertTrue(second[1]);
        assertEquals(List.of(1), rec.seeks);
        assertTrue(rec.delivered.isEmpty());
        assertTrue(rec.log.contains("discard:D1"));
        assertTrue(rec.log.contains("discard:U1"));
        assertEquals(1, g.getStreakTaps());
        rec.assertNothingHeld();
    }

    @Test
    public void doubleTapLeftSeeksBack() {
        WebVideoSeekGesture<String> g = gesture();
        tap(g, LEFT, 0);
        tap(g, LEFT, 200);
        assertEquals(List.of(-1), rec.seeks);
    }

    @Test
    public void lateSecondTapIsAnotherFirstTap() {
        WebVideoSeekGesture<String> g = gesture();
        tap(g, RIGHT, 0);
        g.onTimeout();
        assertEquals(List.of("D1", "U1"), rec.delivered);
        assertTrue(tap(g, RIGHT, 400)[0]);
        assertTrue(g.isHolding());
        tap(g, RIGHT, 600);
        assertEquals(List.of(1), rec.seeks);
    }

    @Test
    public void secondTapPastTheTimeoutHandsOverTheFirstBeforeHoldingItself() {
        WebVideoSeekGesture<String> g = gesture();
        tap(g, RIGHT, 0); // up at 50
        // The timer has not run yet, but the clock says it is too late.
        assertTrue(down(g, RIGHT, Y, 50 + DOUBLE_TAP + 1, true));
        assertEquals(List.of("D1", "U1"), rec.delivered);
        assertTrue(g.isHolding());
    }

    @Test
    public void tapInTheOtherThirdHandsOverTheFirst() {
        WebVideoSeekGesture<String> g = gesture();
        tap(g, LEFT, 0);
        assertTrue(down(g, RIGHT, Y, 200, true));
        assertEquals(List.of("D1", "U1"), rec.delivered);
        assertTrue(rec.seeks.isEmpty());
    }

    @Test
    public void tapInTheMiddleHandsOverTheFirstAndPasses() {
        WebVideoSeekGesture<String> g = gesture();
        tap(g, LEFT, 0);
        assertFalse(down(g, MIDDLE, Y, 200, true));
        assertEquals(List.of("D1", "U1"), rec.delivered);
    }

    @Test
    public void farApartTapsInOneThirdDoNotPair() {
        WebVideoSeekGesture<String> g = gesture();
        down(g, RIGHT, 150f, 0, true);
        up(g, RIGHT, 150f, 50);
        assertTrue(down(g, RIGHT, 450f, 200, true));
        assertEquals(List.of("D1", "U1"), rec.delivered);
        assertTrue(rec.seeks.isEmpty());
    }

    // ── a held touch that is not a tap ───────────────────────────────────

    @Test
    public void dragFromASideIsHandedOverAndPassesLive() {
        WebVideoSeekGesture<String> g = gesture();
        assertTrue(down(g, RIGHT, Y, 0, true));
        assertTrue(g.onMove(RIGHT + 5, Y)); // within the slop
        assertTrue(rec.delivered.isEmpty());
        assertFalse(g.onMove(RIGHT - 80, Y));
        assertEquals(List.of("D1"), rec.delivered);
        assertFalse(g.onMove(RIGHT - 120, Y));
        assertFalse(up(g, RIGHT - 120, Y, 100));
        assertFalse(g.isHolding());
        rec.assertNothingHeld();
    }

    @Test
    public void upFarFromItsDownIsNotATap() {
        WebVideoSeekGesture<String> g = gesture();
        down(g, RIGHT, Y, 0, true);
        assertFalse(up(g, RIGHT - 80, Y, 60));
        assertEquals(List.of("D1"), rec.delivered);
        assertFalse(g.isHolding());
    }

    @Test
    public void pressHeldPastTheTapTimeoutIsHandedOver() {
        WebVideoSeekGesture<String> g = gesture();
        down(g, RIGHT, Y, 0, true);
        assertEquals(TAP, rec.scheduled);
        g.onTimeout();
        assertEquals(List.of("D1"), rec.delivered);
        assertFalse(g.onMove(RIGHT + 2, Y));
        assertFalse(up(g, RIGHT, Y, 900));
        rec.assertNothingHeld();
    }

    @Test
    public void secondFingerHandsOverTheHeldDown() {
        WebVideoSeekGesture<String> g = gesture();
        down(g, RIGHT, Y, 0, true);
        assertFalse(g.onOtherPointer());
        assertEquals(List.of("D1"), rec.delivered);
        assertFalse(g.onOtherPointer());
        assertFalse(up(g, RIGHT, Y, 100));
    }

    @Test
    public void cancelledHeldTouchIsDroppedNotHandedOver() {
        WebVideoSeekGesture<String> g = gesture();
        down(g, RIGHT, Y, 0, true);
        assertTrue(g.onCancel());
        assertTrue(rec.delivered.isEmpty());
        assertTrue(rec.log.contains("discard:D1"));
        assertFalse(down(g, MIDDLE, Y, 100, true));
        rec.assertNothingHeld();
    }

    // ── streak ───────────────────────────────────────────────────────────

    @Test
    public void streakSeeksOncePerTapWithoutHolding() {
        WebVideoSeekGesture<String> g = gesture();
        tap(g, RIGHT, 0);
        long t = 200;
        for (int i = 0; i < 5; i++) {
            boolean[] r = tap(g, RIGHT, t);
            assertTrue(r[0]);
            assertTrue(r[1]);
            assertFalse(g.isHolding());
            t += 300;
        }
        assertEquals(5, rec.seeks.size());
        assertEquals(5, g.getStreakTaps());
        assertTrue(rec.log.contains("seek:1/5"));
        assertTrue(rec.delivered.isEmpty());
    }

    @Test
    public void streakEndsAfterItsWindow() {
        WebVideoSeekGesture<String> g = gesture();
        tap(g, RIGHT, 0);
        tap(g, RIGHT, 200); // up at 250, streak until 1050
        assertTrue(down(g, RIGHT, Y, 1100, true));
        assertTrue(g.isHolding());
    }

    @Test
    public void tapInTheOtherThirdEndsTheStreak() {
        WebVideoSeekGesture<String> g = gesture();
        tap(g, RIGHT, 0);
        tap(g, RIGHT, 200);
        tap(g, LEFT, 400); // held
        assertTrue(g.isHolding());
        tap(g, RIGHT, 600); // hands the left tap over, held itself
        assertEquals(1, rec.seeks.size());
        assertTrue(rec.delivered.contains("D3"));
    }

    @Test
    public void newDoubleTapRestartsTheCount() {
        WebVideoSeekGesture<String> g = gesture();
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
        WebVideoSeekGesture<String> g = gesture();
        tap(g, RIGHT, 0);
        assertTrue(down(g, RIGHT, Y, 200, true));
        assertTrue(g.onMove(RIGHT - 80, Y));
        assertTrue(up(g, RIGHT - 80, Y, 260));
        assertTrue(rec.seeks.isEmpty());
    }

    @Test
    public void keptLongPressDoesNotSeek() {
        WebVideoSeekGesture<String> g = gesture();
        tap(g, RIGHT, 0);
        assertTrue(down(g, RIGHT, Y, 200, true));
        assertTrue(up(g, RIGHT, Y, 900));
        assertTrue(rec.seeks.isEmpty());
    }

    @Test
    public void keptSecondFingerIsNotASeek() {
        WebVideoSeekGesture<String> g = gesture();
        tap(g, RIGHT, 0);
        assertTrue(down(g, RIGHT, Y, 200, true));
        assertTrue(g.onOtherPointer());
        assertTrue(up(g, RIGHT, Y, 250));
        assertTrue(rec.seeks.isEmpty());
    }

    // ── disabled, flush, reset ───────────────────────────────────────────

    @Test
    public void disabledPassesEverythingAndHandsOverWhatIsHeld() {
        WebVideoSeekGesture<String> g = gesture();
        tap(g, RIGHT, 0);
        assertFalse(down(g, RIGHT, Y, 200, false));
        assertEquals(List.of("D1", "U1"), rec.delivered);
        assertFalse(up(g, RIGHT, Y, 250));
        assertTrue(rec.seeks.isEmpty());
        // The disabled tap pairs with nothing.
        assertTrue(down(g, RIGHT, Y, 400, true));
        assertTrue(g.isHolding());
    }

    @Test
    public void disabledEndsTheStreak() {
        WebVideoSeekGesture<String> g = gesture();
        tap(g, RIGHT, 0);
        tap(g, RIGHT, 200);
        assertFalse(down(g, RIGHT, Y, 400, false));
        up(g, RIGHT, Y, 450);
        assertTrue(down(g, RIGHT, Y, 500, true));
        assertTrue(g.isHolding());
        assertEquals(1, rec.seeks.size());
    }

    @Test
    public void flushHandsOverAHeldTap() {
        WebVideoSeekGesture<String> g = gesture();
        tap(g, RIGHT, 0);
        g.flush();
        assertEquals(List.of("D1", "U1"), rec.delivered);
        assertEquals(-1, rec.scheduled);
        rec.assertNothingHeld();
    }

    @Test
    public void flushWithTheFingerDownHandsOverTheDownAndPassesTheRest() {
        WebVideoSeekGesture<String> g = gesture();
        down(g, RIGHT, Y, 0, true);
        g.flush();
        assertEquals(List.of("D1"), rec.delivered);
        assertFalse(up(g, RIGHT, Y, 50));
    }

    @Test
    public void resetDropsWhatIsHeld() {
        WebVideoSeekGesture<String> g = gesture();
        tap(g, RIGHT, 0);
        g.reset();
        assertTrue(rec.delivered.isEmpty());
        rec.assertNothingHeld();
        assertFalse(g.isHolding());
    }

    @Test
    public void timeoutWithNothingHeldDoesNothing() {
        WebVideoSeekGesture<String> g = gesture();
        g.onTimeout();
        tap(g, MIDDLE, 0);
        g.onTimeout();
        assertTrue(rec.log.isEmpty());
    }

    // ── random sequences ─────────────────────────────────────────────────

    /**
     * Random touches, timers and gate changes: every copy is released
     * exactly once, a held UP is never handed over before its DOWN, a
     * gesture's DOWN reaches the page at most once, and nothing is left
     * held once the gesture is flushed.
     */
    @Test
    public void randomSequencesBalanceEveryCopy() {
        Random random = new Random(7);
        float[] xs = {LEFT, MIDDLE, RIGHT, LEFT + 20, RIGHT - 20};
        float[] ys = {Y, 20f, H - 20f, Y + 40};
        for (int run = 0; run < 2000; run++) {
            WebVideoSeekGesture<String> g = gesture();
            long t = 0;
            for (int step = 0; step < 40; step++) {
                t += 20 + random.nextInt(500);
                boolean enabled = random.nextInt(8) != 0;
                float x = xs[random.nextInt(xs.length)];
                float y = ys[random.nextInt(ys.length)];
                if (random.nextInt(5) == 0) {
                    g.onTimeout();
                }
                down(g, x, y, t, enabled);
                int kind = random.nextInt(6);
                if (kind == 0) {
                    g.onMove(x + 60, y);
                } else if (kind == 1) {
                    g.onOtherPointer();
                } else if (kind == 2) {
                    g.onCancel();
                    continue;
                } else if (kind == 3 && random.nextBoolean()) {
                    g.onTimeout();
                }
                t += 10 + random.nextInt(kind == 4 ? 700 : 120);
                up(g, x, y, t);
            }
            g.flush();
            rec.assertNothingHeld();
            List<String> d = rec.delivered;
            for (int i = 0; i < d.size(); i++) {
                String e = d.get(i);
                assertEquals("delivered twice: " + e, i, d.indexOf(e));
                if (e.startsWith("U")) {
                    int down = d.indexOf("D" + e.substring(1));
                    assertTrue("UP before its DOWN: " + e, down >= 0 && down < i);
                }
            }
        }
    }

    // ── seek target ──────────────────────────────────────────────────────

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
