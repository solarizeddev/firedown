package com.solarized.firedown.ui;

import android.view.View;

import androidx.annotation.NonNull;

import com.solarized.firedown.R;

/**
 * A list's padding, kept as two parts that two different owners write: the
 * BASE (what an inset listener reserves for the system bars and the cutout)
 * and the EXTRA (the edge a spacing decoration puts around the items, the
 * part of the gutter its items don't carry themselves). The view's padding
 * is always their sum, so neither owner can erase the other's part.
 *
 * <p>Why it exists: the spacing decorations ({@link
 * EqualSpacingItemDecoration#attachUniform}, {@link CaptureSpacingDecoration})
 * moved the list's outer gutter onto the RecyclerView's padding, and every
 * inset listener on these screens set that padding OUTRIGHT
 * ({@code setPadding(insets.left, 0, insets.right, insets.bottom)}). Insets
 * are dispatched after the view attaches, which is after the decoration,
 * so the listener ran last and the gutter was gone: tiles 4dp from the
 * screen edge instead of 8dp. And a decoration re-attached later (the
 * Downloads grid/list switch) set the padding back to its own edge and
 * dropped the nav-bar inset, putting the last row under the gesture bar.
 * Through here, the order no longer matters.
 *
 * <p>A third, optional part is the COVER: what a view laid over the list's
 * bottom edge hides while it shows (the Downloads task bar), so the last
 * row can still scroll clear of it.
 *
 * <p>The parts live on the view (a tag), so a fragment can re-run either
 * side as often as it likes. Physical left/right: the extra is the same on
 * both sides, and the insets arrive as left/right.
 */
public final class RecyclerPadding {

    private static final int LEFT = 0;
    private static final int TOP = 1;
    private static final int RIGHT = 2;
    private static final int BOTTOM = 3;
    private static final int EXTRA_HORIZONTAL = 4;
    private static final int EXTRA_VERTICAL = 5;
    private static final int COVER_BOTTOM = 6;

    private RecyclerPadding() {
    }

    /** The base: what an inset listener reserves. Keeps the extra. */
    public static void setBase(@NonNull View view, int left, int top, int right, int bottom) {
        int[] parts = partsOf(view);
        parts[LEFT] = left;
        parts[TOP] = top;
        parts[RIGHT] = right;
        parts[BOTTOM] = bottom;
        apply(view, parts);
    }

    /** The base's bottom alone, for a listener that only reserves the
     *  navigation bar: the rest of the base and the extra are kept. */
    public static void setBaseBottom(@NonNull View view, int bottom) {
        int[] parts = partsOf(view);
        parts[BOTTOM] = bottom;
        apply(view, parts);
    }

    /** The extra: the same on both sides, and on top and bottom. Keeps the
     *  base. */
    public static void setExtra(@NonNull View view, int horizontal, int vertical) {
        int[] parts = partsOf(view);
        parts[EXTRA_HORIZONTAL] = horizontal;
        parts[EXTRA_VERTICAL] = vertical;
        apply(view, parts);
    }

    /** The cover: the height a view over the list's bottom edge hides, 0
     *  when it is gone. Keeps the base and the extra. */
    public static void setCoverBottom(@NonNull View view, int bottom) {
        int[] parts = partsOf(view);
        if (parts[COVER_BOTTOM] == bottom) {
            return;
        }
        parts[COVER_BOTTOM] = bottom;
        apply(view, parts);
    }

    /** The parts, created on first use with the view's current padding as
     *  the base: whatever the layout gave it, or what an inset listener set
     *  before any of this ran. */
    @NonNull
    private static int[] partsOf(@NonNull View view) {
        Object tag = view.getTag(R.id.recycler_padding_parts);
        if (tag instanceof int[] stored) {
            return stored;
        }
        int[] parts = new int[] {
                view.getPaddingLeft(), view.getPaddingTop(),
                view.getPaddingRight(), view.getPaddingBottom(), 0, 0, 0
        };
        view.setTag(R.id.recycler_padding_parts, parts);
        return parts;
    }

    private static void apply(@NonNull View view, @NonNull int[] parts) {
        view.setPadding(parts[LEFT] + parts[EXTRA_HORIZONTAL],
                parts[TOP] + parts[EXTRA_VERTICAL],
                parts[RIGHT] + parts[EXTRA_HORIZONTAL],
                parts[BOTTOM] + parts[EXTRA_VERTICAL] + parts[COVER_BOTTOM]);
    }
}
