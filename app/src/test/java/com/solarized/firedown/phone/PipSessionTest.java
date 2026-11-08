package com.solarized.firedown.phone;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Random;

/**
 * Every ordering of lifecycle callbacks the devices deliver around a PiP
 * window, replayed through the real {@link PipSession} by a small model of
 * {@link BrowserMainActivity}'s wiring (the settle runnable posted on a mode
 * exit while stopped, cancelled by onStart, onResume and a new entry).
 *
 * <p>What the user sees in each flow is asserted as "closes": how many times
 * the video was ended (paused, out of fullscreen). A close must happen
 * exactly when the user threw the window away, once, and never for screen
 * off, an expand or a bring-back.
 */
public class PipSessionTest {

    /** A model of the activity: its state, its PiP mode, a clock, one posted runnable. */
    private static final class Device {
        /** Replaced by a recreate, as the activity is. */
        PipSession pip = new PipSession();
        PipSession.State state = PipSession.State.RESUMED;
        boolean inPip;
        boolean screenOn = true;
        long now = 1_000L;
        long settleAt = -1;
        int closes;
        boolean destroyed;
        /** Another Firedown screen is started over the browser (a notification opened it). */
        boolean otherScreen;
        /** What ApplicationLifeCycleHandler decided at a finishing destroy. */
        Boolean privateTabsKept;

        // ── the activity's callbacks, as BrowserMainActivity wires them ──

        void modeChanged(boolean pipNow) {
            inPip = pipNow;
            if (pipNow) {
                settleAt = -1;
                pip.enteredPip();
                return;
            }
            if (pip.leftPip(state)) {
                settleAt = now + PipSession.STOPPED_EXIT_SETTLE_MS;
            }
        }

        void start() {
            state = PipSession.State.STARTED;
            settleAt = -1;
            pip.onStart();
        }

        void resume() {
            state = PipSession.State.RESUMED;
            settleAt = -1;
            pip.onResume();
        }

        void pause() {
            state = PipSession.State.STARTED;
            pip.onPause();
        }

        void stop() {
            state = PipSession.State.STOPPED;
            if (pip.onStop(inPip, screenOn, otherScreen, false, now)) {
                closes++;
            }
        }

        void destroy(boolean finishing) {
            settleAt = -1;
            if (pip.onDestroy(finishing, otherScreen, false, now)) {
                closes++;
            }
            if (finishing) {
                privateTabsKept = pip.wasClosedFromPip(now);
            }
            destroyed = true;
        }

        /**
         * A configuration the activity does not handle (dark mode, locale):
         * the old instance stops and is destroyed changing configurations,
         * the new one is seeded from the saved stay, then passes through
         * onStart, onResume and onPause back to where the old one was.
         */
        void relaunch() {
            PipSession.State was = state;
            if (state == PipSession.State.RESUMED) {
                pause();
            }
            if (state == PipSession.State.STARTED) {
                state = PipSession.State.STOPPED;
                if (pip.onStop(inPip, screenOn, otherScreen, true, now)) {
                    closes++;
                }
            }
            settleAt = -1;
            if (pip.onDestroy(false, otherScreen, true, now)) {
                closes++;
            }
            // The activity saves whether it is in the window.
            pip = new PipSession();
            if (inPip) {
                pip.recreatedInPip();
            }
            if (was == PipSession.State.STOPPED) {
                return;
            }
            start();
            resume();
            if (was == PipSession.State.STARTED) {
                pause();
            }
        }

        /** Moves the clock; the posted settle runs at its own due time. */
        void advance(long ms) {
            long target = now + ms;
            if (settleAt >= 0 && target >= settleAt) {
                now = settleAt;
                settleAt = -1;
                if (!destroyed && pip.settleStoppedExit(otherScreen, now)) {
                    closes++;
                }
            }
            now = target;
        }

        // ── what the user does, in the order devices report it ──

        /** Home from a fullscreen playing video: leave hint enters PiP, then pause. */
        void homeToPip() {
            assertTrue("Home enters PiP from the resumed browser",
                    PipPolicy.browserShouldEnter(true, true, true, pip.leavingForOwnStart(), inPip));
            modeChanged(true);
            pause();
        }

        /** Tapping expand: AOSP reports the mode change while paused, then resumes. */
        void expand() {
            modeChanged(false);
            resume();
        }

        /** A ROM that resumes before reporting the mode change. */
        void expandResumeFirst() {
            resume();
            modeChanged(false);
        }

        /** X on AOSP: mode change while paused, stop, the task removed. */
        void closeAosp() {
            modeChanged(false);
            stop();
            destroy(true);
        }

        /** Samsung's drag-to-dismiss: stop still in PiP with the screen on, destroy not finishing. */
        void dragDismissSamsung() {
            stop();
            destroy(false);
        }

        void screenOff() {
            screenOn = false;
            stop();
        }

        void screenOnUnlock() {
            screenOn = true;
            start();
        }
    }

    // ── single flows ─────────────────────────────────────────────────────────

    @Test
    public void homeThenExpandKeepsPlaying() {
        Device d = new Device();
        d.homeToPip();
        d.expand();
        d.advance(10_000);
        assertEquals(0, d.closes);
        assertFalse(d.pip.inPipStay());
    }

    @Test
    public void expandOnARomThatResumesFirstKeepsPlaying() {
        Device d = new Device();
        d.homeToPip();
        d.expandResumeFirst();
        d.stop();      // later Home without PiP (paused video, say)
        d.advance(10_000);
        assertEquals(0, d.closes);
    }

    @Test
    public void closeOnAospEndsTheVideoOnceAndKeepsPrivateTabs() {
        Device d = new Device();
        d.homeToPip();
        d.closeAosp();
        d.advance(10_000);
        assertEquals(1, d.closes);
        assertEquals(Boolean.TRUE, d.privateTabsKept);
    }

    @Test
    public void samsungDragDismissEndsTheVideo() {
        Device d = new Device();
        d.homeToPip();
        d.dragDismissSamsung();
        assertEquals(1, d.closes);
    }

    @Test
    public void romThatStopsBeforeReportingTheClose() {
        // onStop still reports PiP (the flag flips only inside the mode
        // callback): closed at the stop; the late mode change and the
        // destroy add nothing.
        Device d = new Device();
        d.homeToPip();
        d.stop();
        assertEquals(1, d.closes);
        d.modeChanged(false);
        d.advance(10_000);
        d.destroy(true);
        assertEquals(1, d.closes);
    }

    @Test
    public void windowRemovedWhileTheScreenWasOffThenTaskDestroyed() {
        // The system removes the window and the task while stopped: decided
        // at the destroy, before the lifecycle handler reads the close.
        Device d = new Device();
        d.homeToPip();
        d.screenOff();
        d.modeChanged(false);
        d.destroy(true);
        assertEquals(1, d.closes);
        assertEquals(Boolean.TRUE, d.privateTabsKept);
    }

    @Test
    public void samsungDragDismissFollowedByAModeChangeClosesOnce() {
        Device d = new Device();
        d.homeToPip();
        d.stop();
        d.modeChanged(false);
        d.destroy(false);
        assertEquals(1, d.closes);
    }

    @Test
    public void systemRemovedTheWindowWhileTheScreenWasOff() {
        // Screen off in PiP (not a close), then the window goes while still
        // stopped and nothing brings the app back: closed once settled.
        Device d = new Device();
        d.homeToPip();
        d.screenOff();
        d.modeChanged(false);
        d.advance(PipSession.STOPPED_EXIT_SETTLE_MS - 1);
        assertEquals("still settling", 0, d.closes);
        d.advance(1);
        assertEquals("closed once settled", 1, d.closes);
        d.advance(60_000);
        assertEquals(1, d.closes);
    }

    @Test
    public void screenOffInPipKeepsPlayingThenExpand() {
        Device d = new Device();
        d.homeToPip();
        d.screenOff();
        d.advance(600_000);
        d.screenOnUnlock();
        assertEquals(0, d.closes);
        d.expand();
        assertEquals(0, d.closes);
    }

    @Test
    public void screenOffOnThenCloseEndsTheVideo() {
        Device d = new Device();
        d.homeToPip();
        d.screenOff();
        d.screenOnUnlock();
        d.closeAosp();
        assertEquals(1, d.closes);
        assertEquals(Boolean.TRUE, d.privateTabsKept);
    }

    @Test
    public void screenOffOnThenSamsungDragEndsTheVideo() {
        Device d = new Device();
        d.homeToPip();
        d.screenOff();
        d.screenOnUnlock();
        d.dragDismissSamsung();
        assertEquals(1, d.closes);
    }

    @Test
    public void broughtBackWhileStoppedIsAnExpand() {
        // Screen off in PiP, then the app opened from the lock screen or a
        // notification: the mode leaves PiP while still stopped.
        Device d = new Device();
        d.homeToPip();
        d.screenOff();
        d.screenOn = true;
        d.modeChanged(false);
        d.start();
        d.resume();
        d.advance(10_000);
        assertEquals(0, d.closes);
    }

    @Test
    public void broughtBackWhileStoppedWithStartInALaterTransaction() {
        Device d = new Device();
        d.homeToPip();
        d.screenOff();
        d.screenOn = true;
        d.modeChanged(false);
        d.advance(PipSession.STOPPED_EXIT_SETTLE_MS / 2);
        d.start();
        d.resume();
        d.advance(10_000);
        assertEquals(0, d.closes);
    }

    @Test
    public void broughtBackWithAStartLaterThanHalfASecondIsNotAClose() {
        Device d = new Device();
        d.homeToPip();
        d.screenOff();
        d.screenOn = true;
        d.modeChanged(false);
        d.advance(1_500);
        d.start();
        d.resume();
        d.advance(10_000);
        assertEquals(0, d.closes);
    }

    @Test
    public void notificationOpeningAnotherScreenIsNotAClose() {
        // A download notification opens Downloads into the task: the window
        // expands, Downloads starts, the browser stops behind it unresumed.
        Device d = new Device();
        d.homeToPip();
        d.modeChanged(false);
        d.otherScreen = true;
        d.stop();
        d.advance(10_000);
        assertEquals(0, d.closes);
        assertFalse(d.pip.inPipStay());
        // Back from Downloads to the browser, then Home again: PiP again.
        d.otherScreen = false;
        d.start();
        d.resume();
        d.homeToPip();
        d.closeAosp();
        assertEquals(1, d.closes);
    }

    @Test
    public void closedWhileAFiredownScreenShowsInAnotherTask() {
        // Downloads opened in a task of its own; the window floats over it
        // and the user closes it: the browser's task is removed at once.
        Device d = new Device();
        d.homeToPip();
        d.otherScreen = true;
        d.closeAosp();
        assertEquals(1, d.closes);
        assertEquals(Boolean.TRUE, d.privateTabsKept);
    }

    @Test
    public void expandedUnderAnotherScreenThenFinishedMuchLaterIsNotAClose() {
        Device d = new Device();
        d.homeToPip();
        d.modeChanged(false);
        d.otherScreen = true;
        d.stop();
        d.advance(60_000);
        d.destroy(true);
        assertEquals(0, d.closes);
        assertEquals(Boolean.FALSE, d.privateTabsKept);
    }

    @Test
    public void notificationWhileTheScreenWasOffIsNotAClose() {
        Device d = new Device();
        d.homeToPip();
        d.screenOff();
        d.screenOn = true;
        d.modeChanged(false);
        d.otherScreen = true;
        d.advance(10_000);
        assertEquals(0, d.closes);
        d.destroy(true);
        assertEquals(0, d.closes);
        assertEquals(Boolean.FALSE, d.privateTabsKept);
    }

    @Test
    public void lockScreenStartedInsideOnResumeKeepsThePauseOutOfPip() {
        // The app lock starts LockActivity from onActivityResumed, i.e. inside
        // super.onResume(); the browser's own onResume runs after it.
        PipSession pip = new PipSession();
        pip.ownStartBegan();
        pip.onResume();
        assertFalse(PipPolicy.browserShouldEnter(true, true, true, pip.leavingForOwnStart(), false));
        pip.onPause();
        assertTrue(PipPolicy.browserShouldEnter(true, true, true, pip.leavingForOwnStart(), false));
    }

    @Test
    public void recreatedInsidePipKeepsTheStay() {
        // Dark mode or locale changes while in PiP: a new activity, a new
        // PipSession, which onCreate seeds from isInPictureInPictureMode().
        Device d = new Device();
        d.homeToPip();
        Device again = new Device();
        again.state = PipSession.State.STARTED;
        again.inPip = true;
        again.pip.enteredPip();
        again.closeAosp();
        assertEquals(1, again.closes);
        assertEquals(Boolean.TRUE, again.privateTabsKept);
    }

    @Test
    public void broughtBackThenStoppedBeforeResumingIsNotAClose() {
        // Started again (visible) but stopped before any resume, then
        // destroyed: the bring-back already happened at the start.
        Device d = new Device();
        d.homeToPip();
        d.screenOff();
        d.screenOn = true;
        d.modeChanged(false);
        d.start();
        d.stop();
        d.destroy(true);
        assertEquals(0, d.closes);
        assertEquals(Boolean.FALSE, d.privateTabsKept);
    }

    @Test
    public void repeatedScreenCyclesThenExpandThenPipAgainThenClose() {
        Device d = new Device();
        d.homeToPip();
        for (int i = 0; i < 5; i++) {
            d.screenOff();
            d.advance(30_000);
            d.screenOnUnlock();
        }
        d.expand();
        d.homeToPip();
        d.expand();
        d.homeToPip();
        assertEquals(0, d.closes);
        d.closeAosp();
        assertEquals(1, d.closes);
    }

    @Test
    public void closeThenReopenedThenPipAgainThenCloseAgain() {
        // A ROM that keeps the activity after the close: reopened from the icon.
        Device d = new Device();
        d.homeToPip();
        d.modeChanged(false);
        d.stop();
        assertEquals(1, d.closes);
        d.advance(2_000);
        d.start();
        d.resume();
        assertFalse(d.pip.wasClosedFromPip(d.now));
        d.homeToPip();
        d.modeChanged(false);
        d.stop();
        assertEquals(2, d.closes);
    }

    @Test
    public void swipedFromRecentsLongAfterACloseDeletesPrivateTabs() {
        // A ROM that keeps the stopped activity after the close; the user
        // swipes the app away much later.
        Device d = new Device();
        d.homeToPip();
        d.modeChanged(false);
        d.stop();
        d.advance(60_000);
        d.destroy(true);
        assertEquals(Boolean.FALSE, d.privateTabsKept);
    }

    @Test
    public void leavingWithoutPipNeverCloses() {
        Device d = new Device();
        d.pause();
        d.stop();
        d.modeChanged(false); // a stray report
        d.advance(10_000);
        d.start();
        d.resume();
        d.pause();
        d.screenOff();
        d.destroy(true);
        assertEquals(0, d.closes);
        assertEquals(Boolean.FALSE, d.privateTabsKept);
    }

    @Test
    public void anotherAppsPipReplacingOursEndsTheVideo() {
        Device d = new Device();
        d.homeToPip();
        d.modeChanged(false);
        d.stop();
        assertEquals(1, d.closes);
    }

    @Test
    public void duplicateEntryReportsAreOneStay() {
        Device d = new Device();
        d.homeToPip();
        d.modeChanged(true);
        d.closeAosp();
        assertEquals(1, d.closes);
    }

    @Test
    public void newEntryCancelsAPendingStoppedExit() {
        Device d = new Device();
        d.homeToPip();
        d.screenOff();
        d.modeChanged(false);
        d.modeChanged(true);
        d.advance(10_000);
        assertEquals(0, d.closes);
    }

    // ── the app's own starts ─────────────────────────────────────────────────

    @Test
    public void ownStartBlocksPipUntilItsPause() {
        PipSession pip = new PipSession();
        pip.ownStartBegan();
        assertFalse(PipPolicy.browserShouldEnter(true, true, true, pip.leavingForOwnStart(), false));
        pip.onPause();
        pip.onResume();
        assertTrue(PipPolicy.browserShouldEnter(true, true, true, pip.leavingForOwnStart(), false));
    }

    @Test
    public void ownStartThatThrowsLeavesHomeToTheUser() {
        PipSession pip = new PipSession();
        pip.ownStartBegan();
        pip.ownStartFailed();
        assertTrue(PipPolicy.browserShouldEnter(true, true, true, pip.leavingForOwnStart(), false));
    }

    // ── resource recreate ────────────────────────────────────────────────────

    @Test
    public void noRecreateDuringAnyPartOfAStay() {
        Device d = new Device();
        assertFalse(d.pip.holdsRecreate(false));
        d.modeChanged(true);
        assertTrue(d.pip.holdsRecreate(true));
        d.pause();
        d.modeChanged(false);
        assertTrue("on the way out", d.pip.holdsRecreate(false));
        d.resume();
        assertFalse(d.pip.holdsRecreate(false));

        d.homeToPip();
        d.screenOff();
        d.modeChanged(false);
        assertTrue("a stopped exit settling", d.pip.holdsRecreate(false));
    }

    // ── recreated in the window (dark mode, locale) ─────────────────────────

    @Test
    public void recreateInTheWindowIsNotACloseAndACloseAfterItStillEndsTheVideo() {
        Device d = new Device();
        d.homeToPip();
        d.relaunch();
        assertEquals("a recreate in the window is not a close", 0, d.closes);
        assertTrue("the stay goes on after a recreate", d.pip.inPipStay());
        d.advance(10_000);
        d.dragDismissSamsung();
        assertEquals("a close after the recreate ends the video", 1, d.closes);
    }

    @Test
    public void recreateInTheWindowThenAospClose() {
        Device d = new Device();
        d.homeToPip();
        d.relaunch();
        d.closeAosp();
        assertEquals(1, d.closes);
        assertEquals(Boolean.TRUE, d.privateTabsKept);
    }

    @Test
    public void recreateInTheWindowThenExpandThenHomeWithoutPip() {
        Device d = new Device();
        d.homeToPip();
        d.relaunch();
        d.expand();
        assertFalse("expanded: the stay is over", d.pip.inPipStay());
        d.pause();
        d.stop();      // Home later with the video paused: no PiP
        d.advance(10_000);
        assertEquals(0, d.closes);
    }

    @Test
    public void recreateInTheWindowThenExpandOnAResumeFirstRom() {
        Device d = new Device();
        d.homeToPip();
        d.relaunch();
        d.expandResumeFirst();
        assertFalse(d.pip.inPipStay());
        d.pause();
        d.stop();
        assertEquals(0, d.closes);
    }

    @Test
    public void recreateWithTheScreenOffInTheWindow() {
        Device d = new Device();
        d.homeToPip();
        d.screenOff();
        d.relaunch();
        d.advance(10_000);
        assertEquals(0, d.closes);
        d.screenOnUnlock();
        d.pause();   // back to paused in the window
        assertTrue(d.pip.inPipStay());
        d.dragDismissSamsung();
        assertEquals(1, d.closes);
    }

    @Test
    public void broughtBackWhileStoppedThenRecreatedBeforeStartIsNotAClose() {
        // Screen off in the window, the app brought back from the lock
        // screen, and a recreate (dark mode at unlock) before onStart.
        Device d = new Device();
        d.homeToPip();
        d.screenOff();
        d.modeChanged(false);
        d.relaunch();
        d.screenOn = true;
        d.start();
        d.resume();
        d.advance(10_000);
        assertEquals(0, d.closes);
        assertFalse(d.pip.inPipStay());
    }

    @Test
    public void recreatedThenExpandedBeforeItsTransitPause() {
        // The window is expanded while the new instance passes through
        // onResume: the mode change arrives resumed and ends the stay.
        Device d = new Device();
        d.homeToPip();
        d.state = PipSession.State.STOPPED;
        assertFalse(d.pip.onStop(true, true, false, true, d.now));
        assertFalse(d.pip.onDestroy(false, false, true, d.now));
        d.pip = new PipSession();
        d.pip.recreatedInPip();
        d.start();
        d.resume();
        d.modeChanged(false);
        assertFalse("expanded: the stay is over", d.pip.inPipStay());
        d.pause();
        d.stop();
        d.advance(10_000);
        assertEquals(0, d.closes);
    }

    @Test
    public void aRecreateOutsideAWindowLeavesNoStayBehind() {
        Device d = new Device();
        d.relaunch();
        assertFalse(d.pip.inPipStay());
        d.pause();
        d.stop();
        d.advance(10_000);
        assertEquals(0, d.closes);
    }

    // ── random sequences ─────────────────────────────────────────────────────

    /**
     * Random sequences of device-legal events. Invariants: a stay closes at
     * most once; a screen-off stop never closes; nothing closes without a
     * stay; private tabs are kept at a finishing destroy only right after a
     * close; an onStart cancels a stopped exit.
     */
    @Test
    public void randomSequencesKeepTheInvariants() {
        Random random = new Random(311);
        for (int run = 0; run < 20_000; run++) {
            Device d = new Device();
            int staysClosed = 0;
            boolean stayOpen = false;
            long lastCloseAt = Long.MIN_VALUE / 2;
            for (int step = 0; step < 40 && !d.destroyed; step++) {
                int before = d.closes;
                int pick = random.nextInt(13);
                switch (pick) {
                    case 0:
                        if (d.state == PipSession.State.RESUMED && !d.inPip) {
                            d.homeToPip();
                            stayOpen = true;
                        }
                        break;
                    case 1:
                        if (d.inPip && d.state == PipSession.State.STARTED) {
                            d.expand();
                            stayOpen = false;
                        }
                        break;
                    case 2:
                        if (d.inPip && d.state == PipSession.State.STARTED) {
                            d.modeChanged(false);
                            d.stop();
                        }
                        break;
                    case 3:
                        if (d.state != PipSession.State.STOPPED && d.screenOn) {
                            if (d.state == PipSession.State.RESUMED) {
                                d.pause();
                            }
                            d.screenOff();
                            assertEquals("screen off never closes", before, d.closes);
                        }
                        break;
                    case 4:
                        if (d.state == PipSession.State.STOPPED && !d.screenOn) {
                            d.screenOnUnlock();
                        }
                        break;
                    case 5:
                        if (d.state == PipSession.State.STOPPED && d.inPip) {
                            // brought back while stopped
                            d.screenOn = true;
                            d.modeChanged(false);
                            d.start();
                            d.resume();
                            stayOpen = false;
                            assertEquals("bring-back never closes", before, d.closes);
                        }
                        break;
                    case 6:
                        if (d.inPip && d.state == PipSession.State.STARTED && d.screenOn) {
                            d.dragDismissSamsung();
                        }
                        break;
                    case 7:
                        if (d.state == PipSession.State.STOPPED && !d.inPip) {
                            d.start();
                            d.resume();
                            stayOpen = false;
                            assertFalse(d.pip.wasClosedFromPip(d.now));
                        }
                        break;
                    case 8:
                        d.advance(random.nextInt(8_000));
                        break;
                    case 9:
                        if (d.state == PipSession.State.RESUMED && !d.inPip) {
                            d.pause();
                            d.stop();
                        }
                        break;
                    case 10:
                        if (d.inPip && d.state == PipSession.State.STARTED && d.screenOn) {
                            // a notification opens another Firedown screen
                            d.modeChanged(false);
                            d.otherScreen = true;
                            d.stop();
                            stayOpen = false;
                            assertEquals("another screen never closes", before, d.closes);
                            d.advance(5_000);
                            d.otherScreen = false;
                        }
                        break;
                    case 11:
                        if (d.inPip) {
                            d.relaunch();
                            assertEquals("a recreate never closes", before, d.closes);
                        }
                        break;
                    default:
                        if (d.state == PipSession.State.STOPPED) {
                            d.destroy(random.nextBoolean());
                        }
                        break;
                }
                if (d.closes > before) {
                    assertEquals("one close per event", before + 1, d.closes);
                    assertTrue("a close needs a stay (run " + run + ")", stayOpen);
                    stayOpen = false;
                    staysClosed++;
                    lastCloseAt = d.now;
                }
                if (d.privateTabsKept != null && d.privateTabsKept) {
                    assertTrue("kept only right after a close",
                            d.now - lastCloseAt < PipSession.CLOSED_WINDOW_MS);
                }
            }
            assertTrue(staysClosed >= 0);
        }
    }
}
