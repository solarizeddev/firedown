package com.solarized.firedown.geckoview.toolbar;

/**
 * The offsets that put a bottom-bar dot in its icon's top-end corner.
 * Pure Java, so {@code scripts/badge-harness} runs it against Material's
 * and Android's own rounding at every density.
 *
 * <p>Material 3's badge (edge alignment, top-end gravity) measures its
 * offsets from the BUTTON's top-end corner: the dot's end edge lands
 * {@code horizontal - dotWidth} in from the button's end and its top edge
 * {@code vertical - dotHeight} down from the button's top. Passing the
 * dot's size as Material drew it (a whole number of pixels it rounds per
 * density) makes the two edges meet the icon's exactly.
 */
final class IconBadgeOffsets {

    private IconBadgeOffsets() {
    }

    /**
     * @param buttonWidth the button's width in pixels
     * @param iconLeft    the icon's left edge, in the button's pixels
     * @param iconRight   the icon's right edge, in the button's pixels
     * @param rtl         whether the button lays out right to left (the
     *                    dot then goes on the icon's left corner)
     * @param dotWidth    the dot's width in pixels, as the badge drew it
     */
    static int horizontal(int buttonWidth, int iconLeft, int iconRight, boolean rtl, int dotWidth) {
        int fromEnd = rtl ? iconLeft : buttonWidth - iconRight;
        return fromEnd + dotWidth;
    }

    /**
     * @param iconTop   the icon's top edge, in the button's pixels
     * @param dotHeight the dot's height in pixels, as the badge drew it
     */
    static int vertical(int iconTop, int dotHeight) {
        return iconTop + dotHeight;
    }
}
