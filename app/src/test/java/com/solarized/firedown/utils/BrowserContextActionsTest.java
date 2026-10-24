package com.solarized.firedown.utils;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * The hand-off bound: a URL copied to the clipboard or shared with another
 * app crosses Binder, and a {@code data:} image's URL is the image.
 */
public class BrowserContextActionsTest {

    private static String ofLength(int n) {
        StringBuilder sb = new StringBuilder(n);
        for (int i = 0; i < n; i++) {
            sb.append('a');
        }
        return sb.toString();
    }

    @Test
    public void textAtTheBoundIsHandedOff() {
        assertFalse(BrowserContextActions.tooLargeToHandOff(ofLength(BrowserContextActions.MAX_HANDOFF_CHARS)));
    }

    @Test
    public void textPastTheBoundIsNot() {
        assertTrue(BrowserContextActions.tooLargeToHandOff(ofLength(BrowserContextActions.MAX_HANDOFF_CHARS + 1)));
    }

    @Test
    public void nullAndEmptyAreHandedOff() {
        assertFalse(BrowserContextActions.tooLargeToHandOff(null));
        assertFalse(BrowserContextActions.tooLargeToHandOff(""));
    }
}
