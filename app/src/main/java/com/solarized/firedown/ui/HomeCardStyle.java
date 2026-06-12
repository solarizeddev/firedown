package com.solarized.firedown.ui;

import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.ColorInt;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.core.graphics.ColorUtils;
import androidx.core.widget.ImageViewCompat;

import com.google.android.material.card.MaterialCardView;
import com.solarized.firedown.R;

import java.util.Arrays;
import java.util.List;

/**
 * Packaged style for the home-page shelf cards — Downloads,
 * Safe Folder, Trackers blocked. Three variants give the user a single
 * 'home cards' choice rather than per-card colour knobs: Neutral (calm
 * greyscale), Tonal (the DEFAULT — grey card + brand chip, color as a
 * landmark), Coral (full brand surface, expressive).
 *
 * <p>Colours are raw hex (not theme attrs) because Coral is deliberately
 * off-palette (full-saturation brand surfaces); Neutral/Tonal mirror the
 * surface tokens (see NEUTRAL's doc). Encoding them as tokens would still
 * end up with these constants, so we keep them inline.</p>
 */
public final class HomeCardStyle {

    /** Resolved colour set for one card under one theme mode. */
    public static final class CardLook {
        @ColorInt public final int bg;
        @ColorInt public final int fg;
        /** null = no chip background (icon sits directly on the card surface). */
        @Nullable @ColorInt public final Integer chipBg;
        @ColorInt public final int iconColor;

        public CardLook(@ColorInt int bg, @ColorInt int fg,
                        @Nullable @ColorInt Integer chipBg,
                        @ColorInt int iconColor) {
            this.bg = bg;
            this.fg = fg;
            this.chipBg = chipBg;
            this.iconColor = iconColor;
        }
    }

    @NonNull public final String key;
    @StringRes public final int nameRes;
    @NonNull private final CardLook downloadsLight, downloadsDark;
    @NonNull private final CardLook vaultLight, vaultDark;
    @NonNull private final CardLook trackersLight, trackersDark;

    private HomeCardStyle(@NonNull String key, @StringRes int nameRes,
                          @NonNull CardLook dl, @NonNull CardLook dd,
                          @NonNull CardLook vl, @NonNull CardLook vd,
                          @NonNull CardLook tl, @NonNull CardLook td) {
        this.key = key;
        this.nameRes = nameRes;
        this.downloadsLight = dl;  this.downloadsDark = dd;
        this.vaultLight = vl;      this.vaultDark = vd;
        this.trackersLight = tl;   this.trackersDark = td;
    }

    @NonNull public CardLook downloads(boolean night) { return night ? downloadsDark : downloadsLight; }
    @NonNull public CardLook vault(boolean night)     { return night ? vaultDark     : vaultLight;     }
    @NonNull public CardLook trackers(boolean night)  { return night ? trackersDark  : trackersLight;  }

    /** 0 — Neutral. Greyscale rows on a SUBTLE tile — card surface =
     *  surfaceContainer (light #EFEDF0, dark #1F1F22): one gentle step off
     *  the canvas, far quieter than the old home_surface_container fill,
     *  so the tile reads as a tappable affordance without the battleship
     *  weight. Grey chip, icons onSurfaceVariant so no brand colour leaks.
     *  The 'minimal / calmest' option.
     *
     *  <p>Subtle-tile surface (here and in TONAL); incognito's INFO rows
     *  stay flat (they inform; these navigate — tile + chevron is the
     *  honest "tap me"). Both homes still share canvas / chip / FAB /
     *  spacing rhythm. Grey chip well one step up from the tile: dark
     *  #292A2C, light #DAD8DD. Coral keeps the full-brand surface. */
    public static final HomeCardStyle NEUTRAL = new HomeCardStyle("neutral",
            R.string.home_card_style_neutral,
            new CardLook(0xFFEFEDF0, 0xFF1B1B1E, 0xFFDAD8DD, 0xFF44474C),
            new CardLook(0xFF1F1F22, 0xFFE4E2E5, 0xFF292A2C, 0xFFC5C6CD),
            new CardLook(0xFFEFEDF0, 0xFF1B1B1E, 0xFFDAD8DD, 0xFF44474C),
            new CardLook(0xFF1F1F22, 0xFFE4E2E5, 0xFF292A2C, 0xFFC5C6CD),
            new CardLook(0xFFEFEDF0, 0xFF1B1B1E, 0xFFDAD8DD, 0xFF44474C),
            new CardLook(0xFF1F1F22, 0xFFE4E2E5, 0xFF292A2C, 0xFFC5C6CD));

    /** 1 — Tonal (the DEFAULT). Subtle-tile rows like Neutral
     *  (surfaceContainer surface), but the chips carry their brand
     *  IDENTITY at DIMMED (tonal-container) chroma — coral Downloads,
     *  raspberry Safe Folder, peach Trackers — so colour is a small
     *  landmark, not an attention block, and the dimmed coral chip no
     *  longer twins the full-coral hero FAB. Card surface is
     *  surfaceContainer (see NEUTRAL's doc); the brand chips were the
     *  full-saturation containers before the redesign, kept here as
     *  history: dl #FF857F/#F66A66, vault #C8417B, trk #FFBF9B/#FAB186. */
    public static final HomeCardStyle TONAL = new HomeCardStyle("tonal",
            R.string.home_card_style_tonal,
            new CardLook(0xFFEFEDF0, 0xFF1B1B1E, 0xFFFAD9D6, 0xFFA23A36),
            new CardLook(0xFF1F1F22, 0xFFE4E2E5, 0xFF5A2E2C, 0xFFF6B6B3),
            new CardLook(0xFFEFEDF0, 0xFF1B1B1E, 0xFFFAD7E4, 0xFF8C2F5A),
            new CardLook(0xFF1F1F22, 0xFFE4E2E5, 0xFF552538, 0xFFF4A9C6),
            new CardLook(0xFFEFEDF0, 0xFF1B1B1E, 0xFFFAE0CC, 0xFF8A5A2A),
            new CardLook(0xFF1F1F22, 0xFFE4E2E5, 0xFF4A3A28, 0xFFF3CBA3));

    /** 1 — Blush/Bloom REMOVED (maintainer's call): the tinted-surface
     *  pair was the muddy middle — a brand chip on a same-hue tinted card
     *  loses the landmark value Tonal's chips carry, and the dark tints
     *  read dirty rather than intentional (on-device review). Three styles
     *  remain — Neutral (calm) / Tonal (default, meaningful accent) /
     *  Coral (full expressive). Do NOT reintroduce them without a fresh
     *  contrast pass. */

    /** 2 — Coral. The one FILLED style (Neutral/Tonal are flat rows now):
     *  full-saturation brand surface across the three shelves — Downloads
     *  coral, Safe Folder raspberry, Trackers peach. The loud, expressive
     *  opt-in; the trailing chevron tints to each card's on-colour. */
    public static final HomeCardStyle CORAL = new HomeCardStyle("coral",
            R.string.home_card_style_coral,
            new CardLook(0xFFFF857F, 0xFF460005, null, 0xFF460005),
            new CardLook(0xFFF66A66, 0xFF0F0000, null, 0xFF0F0000),
            new CardLook(0xFFC8417B, 0xFFFFFFFF, null, 0xFFFFFFFF),
            new CardLook(0xFFC8417B, 0xFFFFFFFF, null, 0xFFFFFFFF),
            new CardLook(0xFFFFBF9B, 0xFF5D2E0D, null, 0xFF5D2E0D),
            new CardLook(0xFFFAB186, 0xFF532606, null, 0xFF532606));

    public static final List<HomeCardStyle> ALL =
            Arrays.asList(NEUTRAL, TONAL, CORAL);

    @NonNull
    public static HomeCardStyle fromKey(@Nullable String key, @NonNull HomeCardStyle fallback) {
        if (key == null) return fallback;
        for (HomeCardStyle s : ALL) if (s.key.equals(key)) return s;
        return fallback;
    }

    public static boolean isNightMode(@NonNull Resources resources) {
        int mode = resources.getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        return mode == Configuration.UI_MODE_NIGHT_YES;
    }

    /**
     * Paints one card with the given look — card surface, chip
     * background (or transparent), icon tint, title /
     * subtitle colours all flip together. {@code mutate()} on the
     * chip drawable scopes the colour change to this view instance
     * so cards sharing a drawable resource don't smear into each
     * other.
     *
     * <p>Any view argument may be null — a card without a chip /
     * icon / subtitle / chevron just skips that branch, so the same
     * method paints every shelf card.</p>
     *
     * <p>The {@code chevron} is the navigation affordance for the FLAT
     * styles (Neutral/Tonal have a transparent card surface, so without
     * a trailing › a tappable row would read like incognito's
     * NON-tappable info rows). It is tinted with {@code look.fg} (the
     * subtitle alpha) so it stays legible on every surface: onSurface on
     * the flat canvas, the brand on-colour on a Coral card.</p>
     */
    public static void applyToCard(@NonNull MaterialCardView card,
                                   @Nullable View chip,
                                   @Nullable ImageView icon,
                                   @Nullable TextView title,
                                   @Nullable TextView subtitle,
                                   @Nullable ImageView chevron,
                                   @NonNull CardLook look) {
        card.setCardBackgroundColor(look.bg);

        if (chip != null) {
            Drawable bg = chip.getBackground();
            if (bg != null) {
                bg = bg.mutate();
                int chipColor = look.chipBg == null ? Color.TRANSPARENT : look.chipBg;
                if (bg instanceof GradientDrawable) {
                    ((GradientDrawable) bg).setColor(chipColor);
                } else {
                    bg.setTint(chipColor);
                }
                chip.setBackground(bg);
            }
        }
        if (icon != null) {
            ImageViewCompat.setImageTintList(icon, ColorStateList.valueOf(look.iconColor));
        }
        if (title != null)    title.setTextColor(look.fg);
        if (subtitle != null) subtitle.setTextColor(ColorUtils.setAlphaComponent(look.fg, 0xB3));
        if (chevron != null) {
            ImageViewCompat.setImageTintList(chevron,
                    ColorStateList.valueOf(ColorUtils.setAlphaComponent(look.fg, 0xB3)));
        }
    }
}
