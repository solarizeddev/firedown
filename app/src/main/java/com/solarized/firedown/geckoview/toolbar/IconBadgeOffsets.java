package com.solarized.firedown.geckoview.toolbar;

/**
 * The offsets that centre a bottom-bar dot on its icon's top-end corner,
 * half outside the icon: a mark ON the icon, not a part of it. Inside the
 * corner, the ⋮ dot sat 4dp from the glyph's top dot and read as a fourth
 * dot of the glyph (canvas board 12). Pure Java, so
 * {@code scripts/badge-harness} runs it against Material's and Android's own
 * rounding at every density.
 *
 * <p>Material 3's badge (edge alignment, top-end gravity) measures its
 * offsets from the BUTTON's top-end corner: the dot's centre lands
 * {@code horizontal - dotWidth / 2} in from the button's end and
 * {@code vertical - dotHeight / 2} down from the button's top. The dot's
 * size as Material drew it is always an even number of pixels (it rounds the
 * half size), so the halves are exact.
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
        return fromEnd + dotWidth / 2;
    }

    /**
     * @param iconTop   the icon's top edge, in the button's pixels
     * @param dotHeight the dot's height in pixels, as the badge drew it
     */
    static int vertical(int iconTop, int dotHeight) {
        return iconTop + dotHeight / 2;
    }
}
