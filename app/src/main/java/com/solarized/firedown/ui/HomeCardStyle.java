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
 * Safe Folder, Trackers blocked. Five variants give the
 * user a single 'home cards' choice rather than per-card colour knobs.
 *
 * <p>Colours are raw hex (not theme attrs) because most variants are
 * deliberately off-palette (pale washes, full-saturation brand
 * surfaces, dark-in-both-modes). Encoding them as tokens would mean
 * adding light + dark colour resources for every variant and still
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

    /** 0 — Neutral. True greyscale shelves — neutral surface, neutral
     *  icons tinted with onSurfaceVariant so no brand colour leaks
     *  through. The 'minimal' option for users who don't want coral /
     *  raspberry / peach on the home page at all.
     *
     *  <p>Neutral-surface card tone (here and in TONAL below) is the M3
     *  FILLED-card role — surfaceContainerHighest — because the cards
     *  are flat (no stroke, no shadow). The grey chip steps DOWN to
     *  surfaceContainerHigh: a recessed well, and the exact tone of the
     *  Home search pill. History: the card used to sit at
     *  surfaceContainer, which rendered DARKER than the search pill and
     *  was reported as a tone mismatch; per Material the cards sit one
     *  step LIGHTER than the pill (docked search = High). Hexes mirror
     *  values(-night)/colors.xml Highest/High — keep them in sync. */
    public static final HomeCardStyle NEUTRAL = new HomeCardStyle("neutral",
            R.string.home_card_style_neutral,
            new CardLook(0xFFDAD8DD, 0xFF1B1B1E, 0xFFE9E7EA, 0xFF44474C),
            new CardLook(0xFF343537, 0xFFE4E2E5, 0xFF292A2C, 0xFFC5C6CD),
            new CardLook(0xFFDAD8DD, 0xFF1B1B1E, 0xFFE9E7EA, 0xFF44474C),
            new CardLook(0xFF343537, 0xFFE4E2E5, 0xFF292A2C, 0xFFC5C6CD),
            new CardLook(0xFFDAD8DD, 0xFF1B1B1E, 0xFFE9E7EA, 0xFF44474C),
            new CardLook(0xFF343537, 0xFFE4E2E5, 0xFF292A2C, 0xFFC5C6CD));

    /** 1 — Tonal. Neutral surface + tonal chips (coral Downloads,
     *  raspberry Safe Folder, peach Trackers). What 'Neutral' used
     *  to mean before the true-greyscale Neutral landed — kept under
     *  a new name for users who liked the per-card colour identity.
     *  Card surface = surfaceContainerHighest, same filled-card role
     *  as NEUTRAL (see its doc); brand chips unchanged. */
    public static final HomeCardStyle TONAL = new HomeCardStyle("tonal",
            R.string.home_card_style_tonal,
            new CardLook(0xFFDAD8DD, 0xFF1B1B1E, 0xFFFF857F, 0xFF460005),
            new CardLook(0xFF343537, 0xFFE4E2E5, 0xFFF66A66, 0xFF0F0000),
            new CardLook(0xFFDAD8DD, 0xFF1B1B1E, 0xFFC8417B, 0xFFFFFFFF),
            new CardLook(0xFF343537, 0xFFE4E2E5, 0xFFC8417B, 0xFFFFFFFF),
            new CardLook(0xFFDAD8DD, 0xFF1B1B1E, 0xFFFFBF9B, 0xFF5D2E0D),
            new CardLook(0xFF343537, 0xFFE4E2E5, 0xFFFAB186, 0xFF532606));

    /** 1 — Blush. Pale coral surface (light) / deep warm surface
     *  (dark), no chip. The 'soft tinted' option. */
    public static final HomeCardStyle BLUSH = new HomeCardStyle("blush",
            R.string.home_card_style_blush,
            new CardLook(0xFFFFE6E0, 0xFF5C1313, null, 0xFFB11030),
            new CardLook(0xFF3A1F1C, 0xFFF4DDDB, null, 0xFFF66A66),
            new CardLook(0xFFFBE2EC, 0xFF5C1B3F, null, 0xFF8C1F49),
            new CardLook(0xFF3A1A28, 0xFFF4DDDB, null, 0xFFFFB0C9),
            new CardLook(0xFFFFEEDC, 0xFF5D2E0D, null, 0xFFA85918),
            new CardLook(0xFF332218, 0xFFF4DDDB, null, 0xFFFAB186));

    /** 2 — Bloom. Same tinted surface as Blush, with the brand chips
     *  kept on top — two layers of brand colour. */
    public static final HomeCardStyle BLOOM = new HomeCardStyle("bloom",
            R.string.home_card_style_bloom,
            new CardLook(0xFFFFE6E0, 0xFF5C1313, 0xFFFF857F, 0xFF460005),
            new CardLook(0xFF3A1F1C, 0xFFF4DDDB, 0xFFF66A66, 0xFF0F0000),
            new CardLook(0xFFFBE2EC, 0xFF5C1B3F, 0xFFC8417B, 0xFFFFFFFF),
            new CardLook(0xFF3A1A28, 0xFFF4DDDB, 0xFFC8417B, 0xFFFFFFFF),
            new CardLook(0xFFFFEEDC, 0xFF5D2E0D, 0xFFFFBF9B, 0xFF5D2E0D),
            new CardLook(0xFF332218, 0xFFF4DDDB, 0xFFFAB186, 0xFF532606));

    /** 3 — Coral. Full-saturation brand surface across the three
     *  shelves — Downloads coral, Safe Folder raspberry, Trackers
     *  peach (each owns its M3 tonal container). */
    public static final HomeCardStyle CORAL = new HomeCardStyle("coral",
            R.string.home_card_style_coral,
            new CardLook(0xFFFF857F, 0xFF460005, null, 0xFF460005),
            new CardLook(0xFFF66A66, 0xFF0F0000, null, 0xFF0F0000),
            new CardLook(0xFFC8417B, 0xFFFFFFFF, null, 0xFFFFFFFF),
            new CardLook(0xFFC8417B, 0xFFFFFFFF, null, 0xFFFFFFFF),
            new CardLook(0xFFFFBF9B, 0xFF5D2E0D, null, 0xFF5D2E0D),
            new CardLook(0xFFFAB186, 0xFF532606, null, 0xFF532606));

    public static final List<HomeCardStyle> ALL =
            Arrays.asList(NEUTRAL, TONAL, BLUSH, BLOOM, CORAL);

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
     * icon / subtitle just skips that branch, so the same method
     * paints every shelf card.</p>
     */
    public static void applyToCard(@NonNull MaterialCardView card,
                                   @Nullable View chip,
                                   @Nullable ImageView icon,
                                   @Nullable TextView title,
                                   @Nullable TextView subtitle,
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
    }
}
