package com.solarized.firedown.ui;

import android.content.Context;
import android.content.res.ColorStateList;
import android.util.TypedValue;

import com.solarized.firedown.R;

/**
 * Incognito color resolver.
 *
 * <p>When incognito, colors are read from {@code res/values/colors_incognito.xml}.
 * When not incognito, colors are resolved from the current theme (respecting light/dark mode).</p>
 *
 * <p>XML mirror: res/values/colors_incognito.xml — keep both in sync.</p>
 */
public final class IncognitoColors {

    private IncognitoColors() {}

    // ── Theme attribute resolver ────────────────────────────────────

    public static int resolve(Context context, int materialAttr) {
        TypedValue tv = new TypedValue();
        context.getTheme().resolveAttribute(materialAttr, tv, true);
        return tv.data;
    }

    // ── Primary ─────────────────────────────────────────────────────

    public static int getPrimary(Context context, boolean incognito) {
        if (incognito) return context.getColor(R.color.incognito_primary);
        return resolve(context, androidx.appcompat.R.attr.colorPrimary);
    }

    public static int getPrimaryContainer(Context context, boolean incognito) {
        if (incognito) return context.getColor(R.color.incognito_primary_container);
        return resolve(context, com.google.android.material.R.attr.colorPrimaryContainer);
    }

    public static int getOnPrimaryContainer(Context context, boolean incognito) {
        if (incognito) return context.getColor(R.color.incognito_on_primary_container);
        return resolve(context, com.google.android.material.R.attr.colorOnPrimaryContainer);
    }

    public static int getPrimaryInverse(Context context, boolean incognito) {
        if (incognito) return context.getColor(R.color.incognito_primary_inverse);
        return resolve(context, com.google.android.material.R.attr.colorPrimaryInverse);
    }

    // ── Secondary ───────────────────────────────────────────────────

    public static int getSecondary(Context context, boolean incognito) {
        if (incognito) return context.getColor(R.color.incognito_secondary);
        return resolve(context, com.google.android.material.R.attr.colorSecondary);
    }

    public static int getOnSecondary(Context context, boolean incognito) {
        if (incognito) return context.getColor(R.color.incognito_on_secondary);
        return resolve(context, com.google.android.material.R.attr.colorOnSecondary);
    }

    public static int getSecondaryContainer(Context context, boolean incognito) {
        if (incognito) return context.getColor(R.color.incognito_secondary_container);
        return resolve(context, com.google.android.material.R.attr.colorSecondaryContainer);
    }

    public static int getOnSecondaryContainer(Context context, boolean incognito) {
        if (incognito) return context.getColor(R.color.incognito_on_secondary_container);
        return resolve(context, com.google.android.material.R.attr.colorOnSecondaryContainer);
    }

    // ── Tertiary ────────────────────────────────────────────────────

    public static int getTertiary(Context context, boolean incognito) {
        if (incognito) return context.getColor(R.color.incognito_tertiary);
        return resolve(context, com.google.android.material.R.attr.colorTertiary);
    }

    public static int getOnTertiary(Context context, boolean incognito) {
        if (incognito) return context.getColor(R.color.incognito_on_tertiary);
        return resolve(context, com.google.android.material.R.attr.colorOnTertiary);
    }

    public static int getTertiaryContainer(Context context, boolean incognito) {
        if (incognito) return context.getColor(R.color.incognito_tertiary_container);
        return resolve(context, com.google.android.material.R.attr.colorTertiaryContainer);
    }

    public static int getOnTertiaryContainer(Context context, boolean incognito) {
        if (incognito) return context.getColor(R.color.incognito_on_tertiary_container);
        return resolve(context, com.google.android.material.R.attr.colorOnTertiaryContainer);
    }

    // ── Error ───────────────────────────────────────────────────────

    public static int getError(Context context, boolean incognito) {
        if (incognito) return context.getColor(R.color.incognito_error);
        return resolve(context, androidx.appcompat.R.attr.colorError);
    }

    public static int getOnError(Context context, boolean incognito) {
        if (incognito) return context.getColor(R.color.incognito_on_error);
        return resolve(context, com.google.android.material.R.attr.colorOnError);
    }

    public static int getErrorContainer(Context context, boolean incognito) {
        if (incognito) return context.getColor(R.color.incognito_error_container);
        return resolve(context, com.google.android.material.R.attr.colorErrorContainer);
    }

    public static int getOnErrorContainer(Context context, boolean incognito) {
        if (incognito) return context.getColor(R.color.incognito_on_error_container);
        return resolve(context, com.google.android.material.R.attr.colorOnErrorContainer);
    }

    // ── Surface ─────────────────────────────────────────────────────

    public static int getSurface(Context context, boolean incognito) {
        if (incognito) return context.getColor(R.color.incognito_surface);
        return resolve(context, com.google.android.material.R.attr.colorSurface);
    }

    public static int getOnSurface(Context context, boolean incognito) {
        if (incognito) return context.getColor(R.color.incognito_on_surface);
        return resolve(context, com.google.android.material.R.attr.colorOnSurface);
    }

    public static int getSurfaceVariant(Context context, boolean incognito) {
        if (incognito) return context.getColor(R.color.incognito_surface_variant);
        return resolve(context, com.google.android.material.R.attr.colorSurfaceVariant);
    }

    public static int getOnSurfaceVariant(Context context, boolean incognito) {
        if (incognito) return context.getColor(R.color.incognito_on_surface_variant);
        return resolve(context, com.google.android.material.R.attr.colorOnSurfaceVariant);
    }

    public static int getSurfaceInverse(Context context, boolean incognito) {
        if (incognito) return context.getColor(R.color.incognito_surface_inverse);
        return resolve(context, com.google.android.material.R.attr.colorSurfaceInverse);
    }

    public static int getOnSurfaceInverse(Context context, boolean incognito) {
        if (incognito) return context.getColor(R.color.incognito_on_surface_inverse);
        return resolve(context, com.google.android.material.R.attr.colorOnSurfaceInverse);
    }

    public static int getSurfaceDim(Context context, boolean incognito) {
        if (incognito) return context.getColor(R.color.incognito_surface_dim);
        return resolve(context, com.google.android.material.R.attr.colorSurfaceDim);
    }

    public static int getSurfaceBright(Context context, boolean incognito) {
        if (incognito) return context.getColor(R.color.incognito_surface_bright);
        return resolve(context, com.google.android.material.R.attr.colorSurfaceBright);
    }

    public static int getSurfaceContainer(Context context, boolean incognito) {
        if (incognito) return context.getColor(R.color.incognito_surface_container);
        return resolve(context, com.google.android.material.R.attr.colorSurfaceContainer);
    }

    public static int getSurfaceContainerLow(Context context, boolean incognito) {
        if (incognito) return context.getColor(R.color.incognito_surface_container_low);
        return resolve(context, com.google.android.material.R.attr.colorSurfaceContainerLow);
    }

    public static int getSurfaceContainerHigh(Context context, boolean incognito) {
        if (incognito) return context.getColor(R.color.incognito_surface_container_high);
        return resolve(context, com.google.android.material.R.attr.colorSurfaceContainerHigh);
    }

    public static int getSurfaceContainerHighest(Context context, boolean incognito) {
        if (incognito) return context.getColor(R.color.incognito_surface_container_highest);
        return resolve(context, com.google.android.material.R.attr.colorSurfaceContainerHighest);
    }

    public static int getSurfaceContainerLowest(Context context, boolean incognito) {
        if (incognito) return context.getColor(R.color.incognito_surface_container_lowest);
        return resolve(context, com.google.android.material.R.attr.colorSurfaceContainerLowest);
    }

    // ── Outline ─────────────────────────────────────────────────────

    public static int getOutline(Context context, boolean incognito) {
        if (incognito) return context.getColor(R.color.incognito_outline);
        return resolve(context, com.google.android.material.R.attr.colorOutline);
    }

    public static int getOutlineVariant(Context context, boolean incognito) {
        if (incognito) return context.getColor(R.color.incognito_outline_variant);
        return resolve(context, com.google.android.material.R.attr.colorOutlineVariant);
    }

    // ── Background ──────────────────────────────────────────────────

    public static int getBackground(Context context, boolean incognito) {
        if (incognito) return context.getColor(R.color.incognito_background);
        return resolve(context, android.R.attr.colorBackground);
    }

    // ── ColorStateList builders ─────────────────────────────────────

    public static ColorStateList segmentedButtonBackground(Context context, boolean incognito) {
        return new ColorStateList(
                new int[][]{
                        new int[]{android.R.attr.state_checked},
                        new int[]{}
                },
                new int[]{
                        getSecondaryContainer(context, incognito),
                        android.graphics.Color.TRANSPARENT
                }
        );
    }

    public static ColorStateList segmentedButtonContent(Context context, boolean incognito) {
        return new ColorStateList(
                new int[][]{
                        new int[]{android.R.attr.state_checked},
                        new int[]{}
                },
                new int[]{
                        getOnSecondaryContainer(context, incognito),
                        getOnSurfaceVariant(context, incognito)
                }
        );
    }

    public static ColorStateList segmentedButtonStroke(Context context, boolean incognito) {
        return ColorStateList.valueOf(getOutline(context, incognito));
    }
}