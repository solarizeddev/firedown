package com.solarized.firedown.utils;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.Drawable;

import androidx.annotation.AttrRes;
import androidx.annotation.ColorInt;
import androidx.annotation.NonNull;

import com.google.android.material.color.MaterialColors;
import com.solarized.firedown.R;

/**
 * Shared selection-chrome helpers for adapters that put a card-style
 * row into action mode (downloads, bookmarks, web history).
 *
 * <p>Centralises the formula for the "selected" tonal background so
 * the three adapters can't drift apart on opacity or source token.
 * Each adapter still owns its own bind path — this is intentionally
 * just a colour-resolver, not a full styler — because the surrounding
 * chrome (check icon, stroke, action-icon swap) differs enough per
 * adapter that a one-shot helper would be either overfit or hollow.
 *
 * <p>The wash itself is {@code colorPrimaryContainer} layered at 20%
 * over whatever surface the row normally sits on. That's loud enough
 * to register at scroll speed (the original 2dp stroke alone wasn't —
 * users had to look carefully to confirm "did I really select these
 * 12?") but quiet enough that select-all on 50 rows doesn't turn the
 * whole screen into a brand wall.
 */
public final class SelectionStyling {

    /** 20% feels right — visible at a glance, not loud. */
    private static final float WASH_ALPHA = 0.20f;

    private SelectionStyling() {}

    /**
     * Compose the selected-state card background for a row that
     * normally renders against {@code surfaceAttr}. Resolves both
     * attributes off the supplied context, layers primaryContainer
     * over the surface at {@link #WASH_ALPHA}, and returns the
     * resulting opaque colour ready for
     * {@link com.google.android.material.card.MaterialCardView#setCardBackgroundColor(int)}.
     */
    @ColorInt
    public static int selectedCardWashOver(@NonNull Context context, @AttrRes int surfaceAttr) {
        int surface = MaterialColors.getColor(context, surfaceAttr, Color.TRANSPARENT);
        int accent = MaterialColors.getColor(
                context,
                android.R.attr.colorPrimary,
                Color.TRANSPARENT);
        return MaterialColors.layer(surface, accent, WASH_ALPHA);
    }

    /**
     * Same wash, but over explicit colours — for surfaces that don't
     * resolve from the theme (the incognito palette lives in colour
     * resources, not attrs; BrowserTabsAdapter's active-tab chrome).
     * Keeps {@link #WASH_ALPHA} the single source of the opacity.
     */
    @ColorInt
    public static int washOver(@ColorInt int surface, @ColorInt int accent) {
        return MaterialColors.layer(surface, accent, WASH_ALPHA);
    }

    /**
     * The ink every piece of selection chrome is drawn in — the check, the
     * empty radio, the grid tile's stroke, the active tab's border — resolved
     * from the theme's {@code ?attr/brandInk}: the brand at a contrast that
     * reads as a LINE on a surface (the deeper coral in light theme, the brand
     * itself in dark; the incognito overlay maps it to its own primary). Never
     * {@code colorPrimary} here — that is a FILL token and measures 2.56:1 on
     * the light page and 2.3:1 on the selection wash, under the 3:1 glyph
     * floor, which is what made the tick read as a different red from the
     * mime placeholder's glyph beside it. Resolved off the context, so a view
     * inflated under the incognito overlay or the Vault theme gets theirs.
     */
    @ColorInt
    public static int selectionInk(@NonNull Context context) {
        return MaterialColors.getColor(context, R.attr.brandInk, Color.MAGENTA);
    }

    /** The checked glyph in {@link #selectionInk}, freshly wrapped + mutated
     *  (a tinted drawable is per-holder state; never share one instance across
     *  adapters). */
    @NonNull
    public static Drawable checkedDrawable(@NonNull Context context) {
        return Utils.tintDrawableColor(context, R.drawable.ic_baseline_check_circle_24,
                selectionInk(context));
    }

    /** The unchecked radio ring in {@link #selectionInk} — the same ink as the
     *  check on every list, so "selectable" and "selected" read as one control
     *  everywhere (the tab archive once tinted its ring onSurfaceVariant and
     *  read as a different control from the bookmark/history rows). */
    @NonNull
    public static Drawable uncheckedDrawable(@NonNull Context context) {
        return Utils.tintDrawableColor(context, R.drawable.radio_button_unchecked_24,
                selectionInk(context));
    }
}
