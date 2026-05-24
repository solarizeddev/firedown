package com.solarized.firedown.utils;

import android.content.Context;
import android.graphics.Color;

import androidx.annotation.AttrRes;
import androidx.annotation.ColorInt;
import androidx.annotation.NonNull;

import com.google.android.material.color.MaterialColors;

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
        int primaryContainer = MaterialColors.getColor(
                context,
                com.google.android.material.R.attr.colorPrimaryContainer,
                Color.TRANSPARENT);
        return MaterialColors.layer(surface, primaryContainer, WASH_ALPHA);
    }
}
