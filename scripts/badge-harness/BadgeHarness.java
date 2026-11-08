package com.solarized.firedown.geckoview.toolbar;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Drives the REAL {@link IconBadgeOffsets} (copied from app/src by run.sh)
 * through the arithmetic the bottom bar meets on a device, at every
 * density from 120 to 640 dpi and every bar width from 280dp to 960dp,
 * left to right and right to left:
 *
 * <ul>
 *   <li>Android 14's own rounding, copied from AOSP: TypedValue's
 *       getDimension / getDimensionPixelOffset / getDimensionPixelSize,
 *       LinearLayout's weighted widths (the bar's five buttons),
 *       ImageView's CENTER_INSIDE placement of the 24dp icon, the vector's
 *       intrinsic size;</li>
 *   <li>Material 1.14.0's badge, copied from BadgeDrawable / BadgeState /
 *       BadgeUtils with the values of Widget.Material3.Badge (the app
 *       themes' badgeStyle): edge alignment, a 6dp dot, top-end gravity,
 *       its rounding of the dot's half size and its nudge back inside the
 *       parent.</li>
 * </ul>
 *
 * The check: after the pin, the dot's pixel rectangle is centred exactly on
 * the icon's top-end corner. It also reports what the first code did (fixed
 * 20dp offsets from the button) and what a fixed 3dp half-dot does (off by a
 * pixel where Material rounds the dot up). Two button positions are swept
 * (the fourth and the last, which takes LinearLayout's rounding remainder).
 */
public final class BadgeHarness {

    // ---- bottom_bar.xml / dimens.xml / the drawables ------------------
    private static final float BAR_HEIGHT_DP = 64f;   // app_bar_size, the row's minHeight
    private static final float BUTTON_PADDING_DP = 6f;
    private static final float ICON_DP = 24f;
    private static final int BUTTONS = 5;
    private static final int DOWNLOADS = 3;
    private static final int MORE = 4;

    // ---- Widget.Material3.Badge ----------------------------------------
    private static final float DOT_DP = 6f;            // m3_badge_size = m3_comp_badge_size
    private static final float STYLE_OFFSET_DP = 6f;   // m3_badge_offset

    // ---- AOSP TypedValue ----------------------------------------------
    static float dimension(float dp, float density) {
        return dp * density;
    }

    static int dimensionPixelOffset(float dp, float density) {
        return (int) (dp * density);
    }

    static int dimensionPixelSize(float dp, float density) {
        float f = dp * density;
        int res = (int) ((f >= 0) ? (f + 0.5f) : (f - 0.5f));
        if (res != 0) {
            return res;
        }
        return dp == 0 ? 0 : (dp > 0 ? 1 : -1);
    }

    // ---- AOSP LinearLayout.measureHorizontal, weighted 0-width children
    static int[] weightedWidths(int widthPx) {
        int[] widths = new int[BUTTONS];
        int remainingExcess = widthPx;
        float remainingWeightSum = BUTTONS;
        for (int i = 0; i < BUTTONS; i++) {
            float childWeight = 1f;
            int share = (int) (childWeight * remainingExcess / remainingWeightSum);
            remainingExcess -= share;
            remainingWeightSum -= childWeight;
            widths[i] = share;
        }
        return widths;
    }

    /** The button's x in the row: LinearLayout.layoutHorizontal places RTL children from the last. */
    static int buttonX(int[] widths, int index, boolean rtl) {
        int x = 0;
        for (int i = 0; i < BUTTONS; i++) {
            if (rtl ? i > index : i < index) {
                x += widths[i];
            }
        }
        return x;
    }

    // ---- AOSP ImageView.configureBounds, CENTER_INSIDE ------------------
    /** {left, top, right, bottom} of the icon in the button's pixels. */
    static int[] iconRect(int width, int height, float density) {
        int pad = dimensionPixelSize(BUTTON_PADDING_DP, density);
        int dwidth = dimensionPixelSize(ICON_DP, density);   // VectorDrawable mBaseWidth
        int dheight = dwidth;
        int vwidth = width - pad - pad;
        int vheight = height - pad - pad;
        float scale;
        if (dwidth <= vwidth && dheight <= vheight) {
            scale = 1.0f;
        } else {
            scale = Math.min((float) vwidth / (float) dwidth, (float) vheight / (float) dheight);
        }
        float dx = Math.round((vwidth - dwidth * scale) * 0.5f);
        float dy = Math.round((vheight - dheight * scale) * 0.5f);
        // RectF(0, 0, dwidth, dheight) through setScale + postTranslate, then the padding,
        // rounded as BottomNavigationBar rounds it.
        return new int[] {
                Math.round(pad + dx),
                Math.round(pad + dy),
                Math.round(pad + dx + dwidth * scale),
                Math.round(pad + dy + dheight * scale)};
    }

    // ---- Material 1.14.0 BadgeDrawable, Widget.Material3.Badge ----------
    static final class Badge {
        final float badgeWidth;
        final float badgeHeight;
        int horizontalOffset;
        int verticalOffset;
        float halfBadgeWidth;
        float halfBadgeHeight;
        float badgeCenterX;
        float badgeCenterY;
        final int[] bounds = new int[4];

        Badge(float density) {
            badgeWidth = dimension(DOT_DP, density);
            badgeHeight = dimension(DOT_DP, density);
            horizontalOffset = dimensionPixelOffset(STYLE_OFFSET_DP, density);
            verticalOffset = dimensionPixelOffset(STYLE_OFFSET_DP, density);
        }

        /** updateCenterAndBounds -> calculateCenterAndBounds -> autoAdjustWithinViewBounds. */
        void update(int anchorWidth, int anchorX, int parentWidth, int parentHeight, boolean rtl) {
            halfBadgeWidth = Math.round(badgeWidth / 2);
            halfBadgeHeight = Math.round(badgeHeight / 2);

            int vOffset = verticalOffset;
            vOffset -= Math.round(halfBadgeHeight);          // OFFSET_ALIGNMENT_MODE_EDGE
            int totalVerticalOffset = vOffset;
            badgeCenterY = 0 + totalVerticalOffset;          // TOP_END: anchorRect.top + offset

            int totalHorizontalOffset = horizontalOffset;    // edge mode adds no legacy inset
            badgeCenterX = rtl                               // TOP_END, BADGE_FIXED_EDGE_START
                    ? 0 - halfBadgeWidth + totalHorizontalOffset
                    : anchorWidth + halfBadgeWidth - totalHorizontalOffset;

            // The anchor's parent (the button row) no longer clips; its parent does.
            float totalAnchorYOffset = 0;
            float totalAnchorXOffset = anchorX;
            float topCutOff = badgeCenterY - halfBadgeHeight + totalAnchorYOffset;
            float leftCutOff = badgeCenterX - halfBadgeWidth + totalAnchorXOffset;
            float bottomCutOff = badgeCenterY + halfBadgeHeight - parentHeight + totalAnchorYOffset;
            float rightCutOff = badgeCenterX + halfBadgeWidth - parentWidth + totalAnchorXOffset;
            if (topCutOff < 0) {
                badgeCenterY += Math.abs(topCutOff);
            }
            if (leftCutOff < 0) {
                badgeCenterX += Math.abs(leftCutOff);
            }
            if (bottomCutOff > 0) {
                badgeCenterY -= Math.abs(bottomCutOff);
            }
            if (rightCutOff > 0) {
                badgeCenterX -= Math.abs(rightCutOff);
            }

            // BadgeUtils.updateBadgeBounds
            bounds[0] = (int) (badgeCenterX - halfBadgeWidth);
            bounds[1] = (int) (badgeCenterY - halfBadgeHeight);
            bounds[2] = (int) (badgeCenterX + halfBadgeWidth);
            bounds[3] = (int) (badgeCenterY + halfBadgeHeight);
        }

        int intrinsicWidth() {
            return bounds[2] - bounds[0];
        }

        int intrinsicHeight() {
            return bounds[3] - bounds[1];
        }
    }

    // ---- the run -----------------------------------------------------
    private static int sChecks;
    private static int sFailures;
    private static final List<String> sFirstFailures = new ArrayList<>();

    private static void fail(String what) {
        sFailures++;
        if (sFirstFailures.size() < 12) {
            sFirstFailures.add(what);
        }
    }

    /** One button laid out at a width: the pin as BottomNavigationBar runs it. */
    static int[] pin(float density, int barWidthPx, int index, boolean rtl, Badge badge) {
        int height = dimensionPixelSize(BAR_HEIGHT_DP, density);
        int[] widths = weightedWidths(barWidthPx);
        int width = widths[index];
        int x = buttonX(widths, index, rtl);
        badge.update(width, x, barWidthPx, height, rtl);                 // attach / new bounds
        int[] icon = iconRect(width, height, density);
        badge.horizontalOffset = IconBadgeOffsets.horizontal(width, icon[0], icon[2], rtl,
                badge.intrinsicWidth());
        badge.update(width, x, barWidthPx, height, rtl);
        badge.verticalOffset = IconBadgeOffsets.vertical(icon[1], badge.intrinsicHeight());
        badge.update(width, x, barWidthPx, height, rtl);
        return icon;
    }

    public static void main(String[] args) {
        boolean quiet = args.length > 0 && "quiet".equals(args[0]);
        int densities = 0;
        int fixedInsetMisses = 0;
        List<String> fixedInsetDensities = new ArrayList<>();
        float oldPortraitMax = 0;
        float oldLandscapeMax = 0;

        for (int dpi = 120; dpi <= 640; dpi++) {
            float density = dpi / 160f;
            densities++;
            int fromPx = Math.round(280 * density);
            int toPx = Math.round(960 * density);
            boolean fixedMissedHere = false;
            for (int barPx = fromPx; barPx <= toPx; barPx++) {
                for (int dir = 0; dir < 2; dir++) {
                    boolean rtl = dir == 1;
                    for (int index : new int[] {DOWNLOADS, MORE}) {
                        Badge badge = new Badge(density);
                        int[] icon = pin(density, barPx, index, rtl, badge);
                        int dot = badge.intrinsicWidth();
                        int half = dot / 2;
                        int[] want = rtl
                                ? new int[] {icon[0] - half, icon[1] - half, icon[0] + half, icon[1] + half}
                                : new int[] {icon[2] - half, icon[1] - half, icon[2] + half, icon[1] + half};
                        sChecks++;
                        boolean ok = badge.intrinsicHeight() == dot
                                && dot >= dimensionPixelOffset(DOT_DP, density)
                                && dot <= dimensionPixelOffset(DOT_DP, density) + 2
                                && java.util.Arrays.equals(badge.bounds, want);
                        if (!ok) {
                            fail(String.format(Locale.ROOT,
                                    "%ddpi bar=%dpx %s %s: dot %s, icon corner %s",
                                    dpi, barPx, rtl ? "RTL" : "LTR", index == MORE ? "more" : "downloads",
                                    java.util.Arrays.toString(badge.bounds), java.util.Arrays.toString(want)));
                        }

                        // A fixed 3dp half-dot in place of the measured dot.
                        if (!rtl) {
                            Badge fixed = new Badge(density);
                            int height = dimensionPixelSize(BAR_HEIGHT_DP, density);
                            int[] widths = weightedWidths(barPx);
                            int w = widths[index];
                            int x = buttonX(widths, index, false);
                            int inset = dimensionPixelOffset(DOT_DP / 2f, density);
                            fixed.horizontalOffset = w - icon[2] + inset;
                            fixed.verticalOffset = icon[1] + inset;
                            fixed.update(w, x, barPx, height, false);
                            if (fixed.bounds[0] + fixed.bounds[2] != 2 * icon[2]
                                    || fixed.bounds[1] + fixed.bounds[3] != 2 * icon[1]) {
                                fixedMissedHere = true;
                            }

                            // The code before: 20dp/20dp from the button's corner.
                            Badge old = new Badge(density);
                            old.horizontalOffset = dimensionPixelOffset(20, density);
                            old.verticalOffset = dimensionPixelOffset(20, density);
                            old.update(w, x, barPx, height, false);
                            float gapDp = (old.bounds[0] - icon[2]) / density;
                            float barDp = barPx / density;
                            if (barDp <= 480) {
                                oldPortraitMax = Math.max(oldPortraitMax, gapDp);
                            } else {
                                oldLandscapeMax = Math.max(oldLandscapeMax, gapDp);
                            }
                        }
                    }
                }
            }
            if (fixedMissedHere) {
                fixedInsetMisses++;
                if (fixedInsetDensities.size() < 8) {
                    fixedInsetDensities.add(dpi + "dpi");
                }
            }
        }

        // A rotation without a new pin: offsets worked out for portrait, kept in landscape.
        boolean staleMisses = false;
        {
            float density = 420 / 160f;
            Badge badge = new Badge(density);
            int portrait = Math.round(393 * density);
            int landscape = Math.round(852 * density);
            pin(density, portrait, DOWNLOADS, false, badge);
            int height = dimensionPixelSize(BAR_HEIGHT_DP, density);
            int[] widths = weightedWidths(landscape);
            badge.update(widths[DOWNLOADS], buttonX(widths, DOWNLOADS, false), landscape, height, false);
            int[] icon = iconRect(widths[DOWNLOADS], height, density);
            staleMisses = badge.bounds[0] + badge.bounds[2] != 2 * icon[2];
            Badge freshBadge = new Badge(density);
            int[] fresh = pin(density, landscape, DOWNLOADS, false, freshBadge);
            sChecks++;
            if (freshBadge.bounds[0] + freshBadge.bounds[2] != 2 * fresh[2]) {
                fail("landscape pin does not land on the icon");
            }
        }

        // Teeth: the sweep must be able to see a one-pixel slip and a stale layout.
        sChecks++;
        if (fixedInsetMisses == 0) {
            fail("a fixed 3dp half-dot never missed: the sweep would not catch a pixel slip");
        }
        sChecks++;
        if (!staleMisses) {
            fail("offsets kept from portrait landed on the icon: the rotation case proves nothing");
        }

        if (!quiet) {
            System.out.println(String.format(Locale.ROOT, "badge-harness: %d densities (120-640 dpi), "
                    + "bars 280-960dp, LTR and RTL, the fourth and the last button", densities));
            System.out.println("  common densities (dot px / icon px):");
            for (int dpi : new int[] {120, 160, 213, 240, 320, 360, 400, 420, 440, 450, 480, 560, 640}) {
                float density = dpi / 160f;
                Badge badge = new Badge(density);
                int[] icon = pin(density, Math.round(390 * density), MORE, false, badge);
                System.out.println(String.format(Locale.ROOT, "    %3ddpi  dot %2dpx  icon %3dpx  dot %s  icon corner [%d, %d]",
                        dpi, badge.intrinsicWidth(), icon[2] - icon[0],
                        java.util.Arrays.toString(badge.bounds), icon[2], icon[1]));
            }
            System.out.println(String.format(Locale.ROOT,
                    "  before (20dp from the button): up to %.1fdp right of the icon on a phone in portrait, %.1fdp in landscape",
                    oldPortraitMax, oldLandscapeMax));
            System.out.println("  a fixed 3dp half-dot slips a pixel at " + fixedInsetMisses + " densities, e.g. "
                    + String.join(", ", fixedInsetDensities));
            System.out.println("  offsets kept from portrait after a rotation: " + (staleMisses ? "miss the icon" : "land"));
        }
        System.out.println(String.format(Locale.ROOT, "%d checks, %d failures", sChecks, sFailures));
        for (String f : sFirstFailures) {
            System.out.println("  FAIL " + f);
        }
        System.exit(sFailures == 0 ? 0 : 1);
    }
}
