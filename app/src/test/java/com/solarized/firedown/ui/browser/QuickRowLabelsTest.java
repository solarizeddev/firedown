package com.solarized.firedown.ui.browser;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * JVM unit tests for the browser popup quick-row label breakpoint
 * ({@link QuickRowLabels#iconOnly}). Pins the label → icon-only decision so a
 * future tweak to the five-up row can't silently start wrapping / scrolling /
 * re-hiding the bookmark star at small widths or large accessibility fonts.
 *
 * <p>The threshold is {@code perColumn < 52·fontScale + 6}, with
 * {@code perColumn = (screenWidthDp - 24) / 5}.</p>
 */
public class QuickRowLabelsTest {

    /** A comfortable phone at the default font scale keeps its labels. */
    @Test
    public void wideScreenDefaultFont_showsLabels() {
        // 384dp: perColumn = 72 ≥ need = 58 → labels.
        assertFalse(QuickRowLabels.iconOnly(384, 1.0f));
        assertFalse(QuickRowLabels.iconOnly(360, 1.0f));
    }

    /** A very narrow screen drops to icon-only even at 1×. */
    @Test
    public void narrowScreenDefaultFont_dropsToIconOnly() {
        // 248dp: perColumn = 44.8 < need = 58 → icon-only.
        assertTrue(QuickRowLabels.iconOnly(248, 1.0f));
        assertTrue(QuickRowLabels.iconOnly(300, 1.0f));
    }

    /** A large accessibility font scale forces icon-only on ordinary phones. */
    @Test
    public void largeFontScale_dropsToIconOnly() {
        // need at 1.6× = 89.2; a 360dp phone has perColumn = 67.2 < 89.2.
        assertTrue(QuickRowLabels.iconOnly(360, 1.6f));
        // Even a wide 384dp screen (perColumn 72) drops at 1.6×.
        assertTrue(QuickRowLabels.iconOnly(384, 1.6f));
    }

    /**
     * The 1.3× "roughly the breakpoint" band: labels survive on a wide
     * screen but a mid-size phone tips over to icon-only.
     */
    @Test
    public void midFontScale_isNearTheBreakpoint() {
        // need at 1.3× = 73.6. 384dp → perColumn 72 < 73.6 → icon-only.
        assertTrue(QuickRowLabels.iconOnly(384, 1.3f));
        // A wider 420dp tablet-ish width (perColumn 79.2) keeps labels.
        assertFalse(QuickRowLabels.iconOnly(420, 1.3f));
    }

    /** Monotonic in width: shrinking the screen can only ever drop labels. */
    @Test
    public void narrowerNeverReAddsLabels() {
        float fontScale = 1.0f;
        boolean sawIconOnly = false;
        for (int w = 480; w >= 240; w -= 4) {
            boolean iconOnly = QuickRowLabels.iconOnly(w, fontScale);
            if (sawIconOnly) {
                assertTrue("labels must not reappear as the screen narrows", iconOnly);
            }
            sawIconOnly = iconOnly;
        }
    }
}
