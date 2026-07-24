package com.solarized.firedown.glide;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import android.graphics.drawable.Drawable;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.ColorUtils;

import com.google.android.material.color.MaterialColors;

import com.solarized.firedown.utils.FileUriHelper;

public class MimeTypeThumbnail {

    /**
     * The ONE brand fill for every generated mime fallback — both the glyph tint
     * and the ~12% wash behind it. Audio USED to get a lighter peach (ffa386),
     * but the type is already carried by the glyph SHAPE (note / film / doc) and
     * the mime chip, so a second per-type hue was redundant decoration rather
     * than information — and it broke the "one ground everywhere" rule (every
     * fallback tile now shares the same pastel). Unified to the brand coral.
     */
    private static final int COLOR_BRAND = 0xFFf0716c;

    /**
     * Strength of the brand wash behind the mime glyph (out of 255, ≈12%).
     * ONE ground for every thumbnail slot — list rows AND grid tiles, both
     * themes (maintainer's call; the earlier dark-duotone grid ground and its
     * theme/surface routing were removed). On the fill path the wash is
     * composited into an OPAQUE pastel over the theme background — a
     * translucent paint let the card/pressed state bleed through and read as
     * a dim overlay rather than a designed ground. The letterbox (media
     * viewer) keeps the translucent form so it sits on the player background.
     */
    private static final int WASH_ALPHA = 30;

    /**
     * Upper bound (dp) on the mime icon for the {@code fillBounds} (list /
     * grid) path. The icon is normally half the cell's shorter side, which on
     * a ~124dp-tall grid tile is a ~62dp glyph that dominates the tile and
     * crowds the title. Capping it keeps the glyph reading as a tasteful
     * placeholder on the larger grid/Captured tiles while leaving the small
     * list slot (~64dp → ~32dp icon, already under the cap) and the
     * letterboxed player fallback (which never sets this cap) unchanged.
     */
    private static final int MAX_FILL_ICON_DP = 50;

    /**
     * Letterboxed fallback — paints a centred 16:10 card with the mime
     * icon, the same aspect real artwork takes under PlayerView's
     * {@code resize_mode="fit"}. Used by the media viewer; kept as the
     * default so existing callers are unchanged. Translucent wash on
     * purpose: the card floats on the player's own background.
     */
    @NonNull
    public static Drawable generateDrawable(@NonNull Context context, @NonNull String mimeType) {
        int color = COLOR_BRAND;
        int ground = ColorUtils.setAlphaComponent(color, WASH_ALPHA);
        return new MimeTypeFallbackDrawable(ground, tintedIcon(context, mimeType, color),
                /* fillBounds= */ false, Integer.MAX_VALUE);
    }

    /**
     * Returns a resolution-independent Drawable that paints a tinted
     * card with the mime icon centred inside, sized from the host's
     * current bounds. No intermediate raster — the icon stays crisp at
     * any view size (grid / list / sw600 / sw720 / player full screen).
     *
     * @param fillBounds when {@code true} the ground fills the whole view
     *   (so it reaches every corner of the rounded-clipped thumbnail
     *   slot the same way centerCrop artwork does — the list/grid rows)
     *   as an OPAQUE pastel (the brand wash pre-composited over the theme
     *   background — see {@link #WASH_ALPHA}); when {@code false} it
     *   letterboxes to a centred 16:10 card (the media viewer, matching
     *   {@code resize_mode="fit"}). A square-ish list slot (78×64) would
     *   otherwise leave the 16:10 card floating with transparent bands
     *   top/bottom, never reaching the corners.
     */
    @NonNull
    public static Drawable generateDrawable(@NonNull Context context, @NonNull String mimeType,
                                            boolean fillBounds) {
        if (!fillBounds) {
            return generateDrawable(context, mimeType);
        }
        int color = COLOR_BRAND;
        // Solid pastel: the wash flattened onto the theme background, so the
        // tile is opaque — nothing behind it (card color, ripple, a previous
        // frame) shows through as a dimming veil. Follows the theme: over a
        // dark background the same 12% tint lands dark, over light it lands
        // light.
        int surface = MaterialColors.getColor(context,
                android.R.attr.colorBackground, Color.WHITE);
        int ground = ColorUtils.compositeColors(
                ColorUtils.setAlphaComponent(color, WASH_ALPHA), surface);
        int maxIconPx = Math.round(MAX_FILL_ICON_DP
                * context.getResources().getDisplayMetrics().density);
        return new MimeTypeFallbackDrawable(ground, tintedIcon(context, mimeType, color),
                /* fillBounds= */ true, maxIconPx);
    }

    @Nullable
    private static Drawable tintedIcon(@NonNull Context context, @NonNull String mimeType,
                                       int color) {
        Drawable icon = ContextCompat.getDrawable(context, FileUriHelper.getMimeTypeIcon(mimeType));
        if (icon != null) {
            icon = icon.mutate();
            icon.setTint(color);
        }
        return icon;
    }

    private static final class MimeTypeFallbackDrawable extends Drawable {

        private final Paint mBgPaint;
        private final Drawable mIcon;
        private final boolean mFillBounds;
        private final int mMaxIconPx;

        MimeTypeFallbackDrawable(int groundColor, @Nullable Drawable icon, boolean fillBounds,
                                 int maxIconPx) {
            mBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            mBgPaint.setColor(groundColor);
            mIcon = icon;
            mFillBounds = fillBounds;
            mMaxIconPx = maxIconPx;
        }

        /** 16:10, matching DownloadFragment's grid cell card. */
        private static final float CARD_ASPECT = 16f / 10f;

        @Override
        public void draw(@NonNull Canvas canvas) {
            Rect b = getBounds();
            if (b.isEmpty()) return;
            int cardWidth, cardHeight, cardLeft, cardTop;
            if (mFillBounds) {
                // List / grid thumbnail slot: fill the whole view so the
                // ground reaches every (rounded-clipped) corner, the same way
                // centerCrop artwork fills it. No letterbox.
                cardWidth = b.width();
                cardHeight = b.height();
                cardLeft = b.left;
                cardTop = b.top;
            } else {
                // Media viewer: paint a centred 16:10 card, not the full
                // viewport, so the fallback letterboxes the same way real
                // artwork does under PlayerView's resize_mode="fit".
                if (b.width() / (float) b.height() > CARD_ASPECT) {
                    cardHeight = b.height();
                    cardWidth = Math.round(cardHeight * CARD_ASPECT);
                } else {
                    cardWidth = b.width();
                    cardHeight = Math.round(cardWidth / CARD_ASPECT);
                }
                cardLeft = b.left + (b.width() - cardWidth) / 2;
                cardTop = b.top + (b.height() - cardHeight) / 2;
            }
            canvas.drawRect(cardLeft, cardTop, cardLeft + cardWidth, cardTop + cardHeight, mBgPaint);
            if (mIcon == null) return;
            // Half the card's shorter side, capped (fill-bounds path only) so a
            // large grid tile doesn't get an oversized glyph — see MAX_FILL_ICON_DP.
            int iconSize = Math.min((int) (Math.min(cardWidth, cardHeight) * 0.5f), mMaxIconPx);
            int iconLeft = cardLeft + (cardWidth - iconSize) / 2;
            int iconTop = cardTop + (cardHeight - iconSize) / 2;
            mIcon.setBounds(iconLeft, iconTop, iconLeft + iconSize, iconTop + iconSize);
            mIcon.draw(canvas);
        }

        @Override
        public void setAlpha(int alpha) {
            mBgPaint.setAlpha(alpha);
            if (mIcon != null) mIcon.setAlpha(alpha);
            invalidateSelf();
        }

        @Override
        public void setColorFilter(@Nullable ColorFilter colorFilter) {
            mBgPaint.setColorFilter(colorFilter);
            if (mIcon != null) mIcon.setColorFilter(colorFilter);
            invalidateSelf();
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }
}
