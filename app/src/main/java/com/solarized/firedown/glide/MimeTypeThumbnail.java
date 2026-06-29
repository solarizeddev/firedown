package com.solarized.firedown.glide;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import android.graphics.drawable.Drawable;
import androidx.core.content.ContextCompat;

import com.solarized.firedown.utils.FileUriHelper;

public class MimeTypeThumbnail {

    // Color palette
    private static final int COLOR_BRAND_YELLOW    = 0xFFffa386;
    private static final int COLOR_BRAND_ORANGE    = 0xFFf0716c;

    /**
     * Dark duotone ground for the list/grid fallback tiles (the {@code
     * fillBounds} path). The brand-tinted glyph pops on it, and an overlaid
     * white title + the ⋮ button read on their own — so the grid tile no longer
     * needs heavy gradient scrims fighting the old light brand wash. The
     * media-viewer letterbox ({@code fillBounds=false}) keeps that light wash.
     */
    private static final int COLOR_FALLBACK_BG_DARK = 0xFF3E3733;

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
     * default so existing callers are unchanged.
     */
    @NonNull
    public static Drawable generateDrawable(@NonNull Context context, @NonNull String mimeType) {
        return generateDrawable(context, mimeType, false);
    }

    /**
     * Returns a resolution-independent Drawable that paints a tinted
     * card with the mime icon centred inside, sized from the host's
     * current bounds. No intermediate raster — the icon stays crisp at
     * any view size (grid / list / sw600 / sw720 / player full screen).
     *
     * @param fillBounds when {@code true} the tint fills the whole view
     *   (so it reaches every corner of the rounded-clipped thumbnail
     *   slot the same way centerCrop artwork does — the list/grid rows);
     *   when {@code false} it letterboxes to a centred 16:10 card (the
     *   media viewer, matching {@code resize_mode="fit"}). A square-ish
     *   list slot (78×64) would otherwise leave the 16:10 card floating
     *   with transparent bands top/bottom, never reaching the corners.
     */
    @NonNull
    public static Drawable generateDrawable(@NonNull Context context, @NonNull String mimeType,
                                            boolean fillBounds) {
        int color = getColorForMimeType(mimeType);
        Drawable icon = ContextCompat.getDrawable(context, FileUriHelper.getMimeTypeIcon(mimeType));
        if (icon != null) {
            icon = icon.mutate();
            icon.setTint(color);
        }
        // Cap the icon only on the fill-bounds (list/grid) path; the player's
        // letterbox path (fillBounds=false) keeps the uncapped half-side icon
        // so the full-screen fallback isn't shrunk.
        int maxIconPx = fillBounds
                ? Math.round(MAX_FILL_ICON_DP * context.getResources().getDisplayMetrics().density)
                : Integer.MAX_VALUE;
        return new MimeTypeFallbackDrawable(color, icon, fillBounds, maxIconPx);
    }

    private static int getColorForMimeType(@NonNull String mimeType) {
        if (FileUriHelper.isVideo(mimeType))                return COLOR_BRAND_ORANGE;
        if (FileUriHelper.isAudio(mimeType))                return COLOR_BRAND_YELLOW;
        return COLOR_BRAND_ORANGE;
    }

    private static final class MimeTypeFallbackDrawable extends Drawable {

        private final Paint mBgPaint;
        private final Drawable mIcon;
        private final boolean mFillBounds;
        private final int mMaxIconPx;

        MimeTypeFallbackDrawable(int color, @Nullable Drawable icon, boolean fillBounds, int maxIconPx) {
            mBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            if (fillBounds) {
                // List / grid thumbnail slot: opaque dark duotone ground so
                // overlaid white text + the ⋮ button read without the scrims.
                mBgPaint.setColor(COLOR_FALLBACK_BG_DARK);
            } else {
                // Media-viewer letterbox: keep the light brand wash.
                mBgPaint.setColor(color);
                mBgPaint.setAlpha(30);
            }
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
                // tint reaches every (rounded-clipped) corner, the same way
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