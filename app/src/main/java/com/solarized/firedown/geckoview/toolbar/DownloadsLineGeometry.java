package com.solarized.firedown.geckoview.toolbar;

/**
 * Where the Downloads button's tray and progress line go, in the 24-unit grid
 * of a 24dp icon (one unit = 1dp). Pure, so the bounds can be checked on a JVM
 * ({@code scripts/downloads-line-harness}).
 *
 * <p>At rest the icon is Material's "download": an arrow over a tray whose
 * base runs x 4..20, y 18..20, with 3-unit sides at each end. While anything
 * downloads, the sides retract and the base becomes the progress line, 2 units
 * lower and 2 wider (x 3..21, y 20..22). At the base's own size the arrow and
 * the line read as a small icon beside the bar's others (on-device review of
 * the sketch); the gap under the arrow tip (y 16) grows from 2 to 4.
 */
final class DownloadsLineGeometry {

    static final float REST_LEFT = 4f;
    static final float REST_RIGHT = 20f;
    static final float REST_TOP = 18f;
    static final float SIDE_TOP = 15f;
    static final float SIDE_WIDTH = 2f;

    static final float LINE_LEFT = 3f;
    static final float LINE_RIGHT = 21f;
    static final float LINE_TOP = 20f;
    static final float THICKNESS = 2f;

    /** The fill never shows less than this while a percentage is known. */
    static final float MIN_FILL = 2f;
    /** The piece that slides along the line while no total is known. */
    static final float SEGMENT = 6f;

    private DownloadsLineGeometry() {
    }

    /** The base line at morph fraction {@code a} (0 = tray at rest, 1 = progress line): left, top, right, bottom. */
    static float[] base(float a) {
        float t = clamp01(a);
        float top = lerp(REST_TOP, LINE_TOP, t);
        return new float[]{lerp(REST_LEFT, LINE_LEFT, t), top, lerp(REST_RIGHT, LINE_RIGHT, t), top + THICKNESS};
    }

    /**
     * The tray's two sides at morph fraction {@code a}: they shrink toward the
     * base's top until gone. Eight values, left side then right side, or an
     * empty array once retracted.
     */
    static float[] sides(float a) {
        float t = clamp01(a);
        float[] base = base(t);
        float height = (REST_TOP - SIDE_TOP) * (1f - t);
        if (height <= 0f) {
            return new float[0];
        }
        float top = base[1] - height;
        return new float[]{
                base[0], top, base[0] + SIDE_WIDTH, base[1],
                base[2] - SIDE_WIDTH, top, base[2], base[1]};
    }

    /**
     * The coral fill of a known percentage: left, right, filling from the
     * start edge (the right one in RTL). Never under {@link #MIN_FILL}.
     */
    static float[] fill(float fraction, boolean rtl) {
        float width = LINE_RIGHT - LINE_LEFT;
        float filled = Math.max(MIN_FILL, width * clamp01(fraction));
        if (rtl) {
            return new float[]{LINE_RIGHT - filled, LINE_RIGHT};
        }
        return new float[]{LINE_LEFT, LINE_LEFT + filled};
    }

    /**
     * The sliding piece at sweep phase {@code phase} (0..1): it enters at the
     * start edge and leaves at the end edge, clipped to the line. Returns
     * left, right, or an empty array while it is wholly outside.
     */
    static float[] segment(float phase, boolean rtl) {
        float width = LINE_RIGHT - LINE_LEFT;
        float travel = width + SEGMENT;
        float lead = LINE_LEFT + travel * clamp01(phase);
        float left = lead - SEGMENT;
        float right = lead;
        if (rtl) {
            float mirroredLeft = LINE_LEFT + LINE_RIGHT - right;
            right = LINE_LEFT + LINE_RIGHT - left;
            left = mirroredLeft;
        }
        left = Math.max(left, LINE_LEFT);
        right = Math.min(right, LINE_RIGHT);
        if (right <= left) {
            return new float[0];
        }
        return new float[]{left, right};
    }

    /** The still piece shown instead of the sweep when animations are off: the line's middle. */
    static float[] stillSegment() {
        float middle = (LINE_LEFT + LINE_RIGHT) / 2f;
        return new float[]{middle - SEGMENT / 2f, middle + SEGMENT / 2f};
    }

    static float lerp(float from, float to, float t) {
        return from + (to - from) * t;
    }

    static float clamp01(float value) {
        return Math.max(0f, Math.min(1f, value));
    }
}
