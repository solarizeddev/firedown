package com.solarized.firedown;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.solarized.firedown.UpdatePromptPolicy.Busy;
import com.solarized.firedown.UpdatePromptPolicy.Place;
import com.solarized.firedown.UpdatePromptPolicy.Snooze;

import org.junit.Test;

/** When the "Update ready" sheet opens by itself (UpdatePromptPolicy). */
public class UpdatePromptPolicyTest {

    private static final long NOW = 1_800_000_000_000L;

    @Test
    public void opensOnHomeAndTheAppsOwnScreens() {
        assertTrue(UpdatePromptPolicy.autoShow(true, false, Place.HOME, false, Busy.NONE));
        assertTrue(UpdatePromptPolicy.autoShow(true, false, Place.SCREEN, false, Busy.NONE));
    }

    @Test
    public void neverOverAPageBeingRead() {
        assertFalse(UpdatePromptPolicy.autoShow(true, false, Place.PAGE, false, Busy.NONE));
    }

    @Test
    public void onAPageOnlyAsTheFirstScreenAfterStart() {
        assertTrue(UpdatePromptPolicy.autoShow(true, false, Place.PAGE, true, Busy.NONE));
    }

    @Test
    public void neverOnTheLockScreenOrSafeFolder() {
        assertFalse(UpdatePromptPolicy.autoShow(true, false, Place.NEVER, true, Busy.NONE));
        assertFalse(UpdatePromptPolicy.autoShow(true, false, Place.NEVER, false, Busy.NONE));
    }

    @Test
    public void heldWhileAnythingRuns() {
        for (Busy busy : Busy.values()) {
            if (busy == Busy.NONE) {
                continue;
            }
            for (Place place : Place.values()) {
                assertFalse(busy + " on " + place,
                        UpdatePromptPolicy.autoShow(true, false, place, true, busy));
            }
        }
    }

    @Test
    public void notWithoutAReadyUpdateOrWhileSnoozed() {
        assertFalse(UpdatePromptPolicy.autoShow(false, false, Place.HOME, true, Busy.NONE));
        assertFalse(UpdatePromptPolicy.autoShow(true, true, Place.HOME, true, Busy.NONE));
    }

    @Test
    public void busyNamesTheFirstThingThatWouldStop() {
        assertEquals(Busy.NONE, UpdatePromptPolicy.busy(false, false, false, false));
        assertEquals(Busy.DOWNLOAD, UpdatePromptPolicy.busy(true, true, true, true));
        assertEquals(Busy.TASK, UpdatePromptPolicy.busy(false, true, true, true));
        assertEquals(Busy.SHARE, UpdatePromptPolicy.busy(false, false, true, true));
        assertEquals(Busy.PLAYBACK, UpdatePromptPolicy.busy(false, false, false, true));
    }

    @Test
    public void snoozeHoldsOnlyItsOwnVersionUntilItEnds() {
        long until = NOW + 1000;
        assertTrue(UpdatePromptPolicy.snoozed(1207, 1207, until, NOW));
        assertFalse(UpdatePromptPolicy.snoozed(1207, 1207, until, until));
        // A newer update is not held back by the previous one's snooze.
        assertFalse(UpdatePromptPolicy.snoozed(1208, 1207, until, NOW));
    }

    @Test
    public void threeDismissalsThenQuiet() {
        Snooze first = UpdatePromptPolicy.afterDismissal(1207, -1, 0, NOW);
        assertEquals(1, first.count);
        assertEquals(NOW + UpdatePromptPolicy.SNOOZE_INTERVAL_MS, first.until);
        Snooze second = UpdatePromptPolicy.afterDismissal(1207, 1207, first.count, NOW);
        assertEquals(2, second.count);
        assertEquals(NOW + UpdatePromptPolicy.SNOOZE_INTERVAL_MS, second.until);
        Snooze third = UpdatePromptPolicy.afterDismissal(1207, 1207, second.count, NOW);
        assertEquals(UpdatePromptPolicy.MAX_PROMPTS, third.count);
        assertEquals(Long.MAX_VALUE, third.until);
    }

    @Test
    public void aNewVersionStartsItsOwnCount() {
        Snooze s = UpdatePromptPolicy.afterDismissal(1208, 1207, 3, NOW);
        assertEquals(1, s.count);
        assertEquals(NOW + UpdatePromptPolicy.SNOOZE_INTERVAL_MS, s.until);
    }
}
