package com.solarized.firedown.phone;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Issue #311: a fullscreen web video goes to a PiP window when the user
 * leaves the app; any other page keeps only its audio.
 */
public class PipPolicyTest {

    // ── entry ───────────────────────────────────────────────────────────────

    @Test
    public void fullscreenPlayingVideoEntersPip() {
        assertTrue(PipPolicy.browserShouldEnter(true, true, true, false, false));
    }

    @Test
    public void everyMissingConditionKeepsAudioOnly() {
        // Exhaustive: the one true row is all five conditions met.
        for (int bits = 0; bits < 32; bits++) {
            boolean onBrowser = (bits & 1) != 0;
            boolean fullScreen = (bits & 2) != 0;
            boolean playing = (bits & 4) != 0;
            boolean ownStart = (bits & 8) != 0;
            boolean inPip = (bits & 16) != 0;
            boolean expected = onBrowser && fullScreen && playing && !ownStart && !inPip;
            assertEquals("row " + bits, expected,
                    PipPolicy.browserShouldEnter(onBrowser, fullScreen, playing, ownStart, inPip));
        }
    }

    @Test
    public void notFullscreenStaysAudioOnly() {
        // The reporter's second case: a video playing in the page.
        assertFalse(PipPolicy.browserShouldEnter(true, false, true, false, false));
    }

    @Test
    public void pausedFullscreenVideoDoesNotEnter() {
        assertFalse(PipPolicy.browserShouldEnter(true, true, false, false, false));
    }

    @Test
    public void appStartingAnotherScreenDoesNotEnter() {
        // "Open in app" or the file picker from a fullscreen page also fire
        // onUserLeaveHint.
        assertFalse(PipPolicy.browserShouldEnter(true, true, true, true, false));
    }

    @Test
    public void sheetOrOtherDestinationDoesNotEnter() {
        assertFalse(PipPolicy.browserShouldEnter(false, true, true, false, false));
    }

    // ── leaving PiP ─────────────────────────────────────────────────────────

    @Test
    public void closeDeliveredAsModeExitThenStopIsADismissal() {
        assertTrue(PipPolicy.dismissedAtStop(true, false, true));
        assertTrue(PipPolicy.dismissedAtStop(true, false, false));
    }

    @Test
    public void samsungDragDismissStopInPipWithScreenOnIsADismissal() {
        assertTrue(PipPolicy.dismissedAtStop(false, true, true));
    }

    @Test
    public void screenOffInPipIsNotADismissal() {
        assertFalse(PipPolicy.dismissedAtStop(false, true, false));
    }

    @Test
    public void stopOutsidePipWithNoTeardownIsNotADismissal() {
        assertFalse(PipPolicy.dismissedAtStop(false, false, true));
        assertFalse(PipPolicy.dismissedAtStop(false, false, false));
    }

    @Test
    public void modeExitWhileStoppedIsADismissalWhileStartedIsNot() {
        assertTrue(PipPolicy.dismissedAtModeExit(true));
        // Started: an expand (onResume follows) or a close still on its way
        // to onStop, which decides.
        assertFalse(PipPolicy.dismissedAtModeExit(false));
    }

    // ── aspect ──────────────────────────────────────────────────────────────

    @Test
    public void unknownSizeIsSixteenByNine() {
        assertArrayEquals(new int[]{16, 9}, PipPolicy.aspect(0, 0));
        assertArrayEquals(new int[]{16, 9}, PipPolicy.aspect(1920, 0));
        assertArrayEquals(new int[]{16, 9}, PipPolicy.aspect(-1, 1080));
    }

    @Test
    public void ordinaryVideoKeepsItsOwnRatio() {
        assertArrayEquals(new int[]{1920, 1080}, PipPolicy.aspect(1920, 1080));
        assertArrayEquals(new int[]{1080, 1920}, PipPolicy.aspect(1080, 1920));
        assertArrayEquals(new int[]{3840, 2160}, PipPolicy.aspect(3840, 2160));
    }

    @Test
    public void extremeRatiosAreClampedInsideTheFrameworkRange() {
        int[][] sizes = {{10, 1000}, {10, 24}, {1000, 10}, {24, 10}, {1, 100000}, {100000, 1}};
        for (int[] size : sizes) {
            int[] a = PipPolicy.aspect(size[0], size[1]);
            double ratio = (double) a[0] / a[1];
            float asFloat = (float) a[0] / (float) a[1];
            String label = size[0] + "x" + size[1] + " -> " + a[0] + "/" + a[1];
            assertTrue(label, ratio > PipPolicy.MIN_ASPECT && ratio < PipPolicy.MAX_ASPECT);
            // The framework checks a float made from the Rational.
            assertTrue(label, asFloat > (float) PipPolicy.MIN_ASPECT
                    && asFloat < (float) PipPolicy.MAX_ASPECT);
            // Same side of 1: the window keeps the video's orientation.
            assertEquals(label, size[0] > size[1], a[0] > a[1]);
        }
    }

    @Test
    public void ratiosOnTheBoundAreAccepted() {
        assertArrayEquals(new int[]{239, 100}, PipPolicy.aspect(239, 100));
        assertArrayEquals(new int[]{100, 239}, PipPolicy.aspect(100, 239));
    }

    // ── resource bucket ─────────────────────────────────────────────────────

    @Test
    public void widthBucketsFollowTheShippedQualifiers() {
        assertEquals(0, PipPolicy.widthBucket(0));      // undefined
        assertEquals(0, PipPolicy.widthBucket(180));    // a PiP window
        assertEquals(0, PipPolicy.widthBucket(411));    // a phone
        assertEquals(0, PipPolicy.widthBucket(599));
        assertEquals(1, PipPolicy.widthBucket(600));
        assertEquals(1, PipPolicy.widthBucket(719));
        assertEquals(2, PipPolicy.widthBucket(720));
        assertEquals(2, PipPolicy.widthBucket(1280));
    }
}
