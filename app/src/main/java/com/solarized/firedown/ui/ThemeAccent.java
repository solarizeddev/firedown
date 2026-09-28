package com.solarized.firedown.ui;

import android.content.Context;
import android.content.res.Resources;
import android.os.Build;

import androidx.annotation.ColorInt;
import androidx.annotation.NonNull;
import androidx.annotation.StyleRes;
import androidx.core.content.ContextCompat;
import androidx.preference.PreferenceManager;

import com.solarized.firedown.Preferences;
import com.solarized.firedown.R;

/**
 * The user's colour theme ("accent"), layered over {@code Theme.FireDown} as a
 * {@code ThemeOverlay.FireDown.Accent.*} — the same mechanism as the OLED
 * overlay, and applied just before it (OLED only touches surfaces, accents
 * only touch the primary/secondary/tertiary families and the fd* semantic
 * attrs, so the two compose in either order).
 *
 * <p>{@link #FIREDOWN} is the hand-tuned coral palette and applies NO overlay:
 * the default theme is exactly the one that shipped before accents existed.
 * The presets are GENERATED and contrast-checked by
 * {@code scripts/theme-palettes/palettes.mjs}; {@link #SYSTEM} is Material You,
 * an overlay of {@code @android:color/system_accent*} references (API 31+).
 *
 * <p>Incognito/vault never take an accent — BaseActivity skips the overlay for
 * {@code isIncognitoTheme}, and ThemeOverlay.FireDown.Incognito pins its own
 * values. Launcher icon, splash and logo are the brand and stay coral.
 *
 * <p>Every surface that paints a colour must read it from the THEME
 * ({@code ?attr/}, {@link com.solarized.firedown.utils.Utils#themeColor}) for
 * this to reach it — a {@code @color/md_theme_*} lookup is the static coral
 * base and silently ignores the overlay.
 */
public final class ThemeAccent {

    public static final String FIREDOWN = "firedown";
    public static final String OCEAN = "ocean";
    public static final String TEAL = "teal";
    public static final String FOREST = "forest";
    public static final String VIOLET = "violet";
    public static final String SYSTEM = "system";

    /** Display order in Settings → Theme. */
    public static final String[] ALL = {FIREDOWN, SYSTEM, OCEAN, TEAL, FOREST, VIOLET};

    private ThemeAccent() {}

    /** The stored accent, falling back to {@link #FIREDOWN} for an unknown or unavailable one. */
    @NonNull
    public static String current(@NonNull Context context) {
        String id = PreferenceManager.getDefaultSharedPreferences(context)
                .getString(Preferences.SETTINGS_ACCENT, FIREDOWN);
        if (id == null || (overlayFor(id) == 0 && !FIREDOWN.equals(id))) {
            return FIREDOWN;
        }
        return id;
    }

    /** Material You needs the system tonal palettes, added in Android 12. */
    public static boolean isAvailable(@NonNull String id) {
        return !SYSTEM.equals(id) || Build.VERSION.SDK_INT >= Build.VERSION_CODES.S;
    }

    /** The overlay for {@code id}, or 0 for the default palette / an unavailable accent. */
    @StyleRes
    public static int overlayFor(@NonNull String id) {
        if (!isAvailable(id)) {
            return 0;
        }
        return switch (id) {
            case OCEAN -> R.style.ThemeOverlay_FireDown_Accent_Ocean;
            case TEAL -> R.style.ThemeOverlay_FireDown_Accent_Teal;
            case FOREST -> R.style.ThemeOverlay_FireDown_Accent_Forest;
            case VIOLET -> R.style.ThemeOverlay_FireDown_Accent_Violet;
            case SYSTEM -> R.style.ThemeOverlay_FireDown_Accent_System;
            default -> 0;
        };
    }

    /**
     * Layers the stored accent onto {@code theme}. Must run BEFORE the
     * activity's {@code super.onCreate()} (like the OLED overlay) so the
     * window background and the first inflation already see it.
     */
    public static void apply(@NonNull Context context, @NonNull Resources.Theme theme) {
        int overlay = overlayFor(current(context));
        if (overlay != 0) {
            theme.applyStyle(overlay, true);
        }
    }

    /** The swatch shown beside {@code id} in Settings — the accent's primary. */
    @ColorInt
    public static int swatchColor(@NonNull Context context, @NonNull String id) {
        int res = switch (id) {
            case OCEAN -> R.color.accent_swatch_ocean;
            case TEAL -> R.color.accent_swatch_teal;
            case FOREST -> R.color.accent_swatch_forest;
            case VIOLET -> R.color.accent_swatch_violet;
            case SYSTEM -> Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                    ? android.R.color.system_accent1_300
                    : R.color.md_theme_primary;
            default -> R.color.md_theme_primary;
        };
        return ContextCompat.getColor(context, res);
    }

    /** The settings row key for {@code id}. */
    @NonNull
    public static String preferenceKey(@NonNull String id) {
        return Preferences.SETTINGS_ACCENT + "." + id;
    }

    /** The settings row title for {@code id}. */
    public static int titleRes(@NonNull String id) {
        return switch (id) {
            case OCEAN -> R.string.settings_accent_ocean;
            case TEAL -> R.string.settings_accent_teal;
            case FOREST -> R.string.settings_accent_forest;
            case VIOLET -> R.string.settings_accent_violet;
            case SYSTEM -> R.string.settings_accent_system;
            default -> R.string.settings_accent_firedown;
        };
    }
}
