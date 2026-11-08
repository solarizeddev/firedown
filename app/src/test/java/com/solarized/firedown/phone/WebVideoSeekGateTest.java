package com.solarized.firedown.phone;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Double-tap seek stays on for a fullscreen stint once the video showed itself. */
public class WebVideoSeekGateTest {

    @Test
    public void offUntilTheVideoShowsItself() {
        WebVideoSeekGate gate = new WebVideoSeekGate();
        assertFalse(gate.allow(1, true, false));
        assertTrue(gate.allow(1, true, true));
    }

    @Test
    public void staysOnWhenPausedInTheSameStint() {
        WebVideoSeekGate gate = new WebVideoSeekGate();
        assertTrue(gate.allow(1, true, true));
        assertTrue(gate.allow(1, true, false));
        assertTrue(gate.allow(1, true, false));
    }

    @Test
    public void aFailingBaseForgetsTheStint() {
        WebVideoSeekGate gate = new WebVideoSeekGate();
        gate.allow(1, true, true);
        assertFalse(gate.allow(1, false, true));
        assertFalse(gate.allow(1, true, false));
    }

    @Test
    public void clearStartsANewStint() {
        WebVideoSeekGate gate = new WebVideoSeekGate();
        gate.allow(1, true, true);
        gate.clear();
        assertFalse(gate.allow(1, true, false));
    }

    @Test
    public void anotherTabMustShowItsOwnVideo() {
        WebVideoSeekGate gate = new WebVideoSeekGate();
        gate.allow(1, true, true);
        assertFalse(gate.allow(2, true, false));
        assertTrue(gate.allow(2, true, true));
        assertFalse(gate.allow(1, true, false));
    }

    @Test
    public void tabIdOfAnyValueWorks() {
        WebVideoSeekGate gate = new WebVideoSeekGate();
        assertFalse(gate.allow(Integer.MIN_VALUE, true, false));
        assertTrue(gate.allow(Integer.MIN_VALUE, true, true));
        assertTrue(gate.allow(Integer.MIN_VALUE, true, false));
    }
}
