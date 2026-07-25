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
import androidx.core.graphics.ColorUtils;


import com.solarized.firedown.utils.FileUriHelper;

public class MimeTypeThumbnail {

    /**
     * The ONE brand fill for every generated mime fallback: the glyph tint on
     * both paths, plus the ~12% wash behind it on the letterbox path (the fill
     * path's ground is {@link #COLOR_FALLBACK_GROUND}).
     * Audio USED to get a lighter peach (ffa386),
     * but the type is already carried by the glyph SHAPE (note / film / doc) and
     * the mime chip, so a second per-type hue was redundant decoration rather
     * than information — and it broke the "one ground everywhere" rule (every
     * fallback tile now shares the same pastel). Unified to the brand coral.
     */
    private static final int COLOR_BRAND = 0xFFf0716c;

    /**
     * Strength of the brand wash behind the mime glyph (out of 255, ≈12%).
     * Used ONLY by the letterbox (media viewer) form now, which stays
     * translucent so it sits on the player's own background. The fill path
     * (list rows + grid tiles) paints {@link #COLOR_FALLBACK_GROUND} instead —
     * see its javadoc for why a theme-composited wash had to go.
     */
    private static final int WASH_ALPHA = 30;

    /**
     * The ONE ground for every filled fallback slot — list rows AND grid tiles,
     * in BOTH themes. A single literal colour, not a formula.
     *
     * <p>This replaced compositing {@link #WASH_ALPHA} of the brand over the
     * theme background, which resolved to two very different colours:
     * {@code #FAE9EA} in light and {@code #2D1E1F} in dark. That is the root of
     * a defect that looked like a text problem: white caption text sits at
     * <b>1.17:1</b> on the light pastel — invisible, well under the 4.5:1 floor
     * — so the grid tile had to fall back to theme ink, and with it lost the
     * scrim, the text shadow and the white ⋮. Four differences, all downstream
     * of one ground being two colours. Uniform ink over a ground that swings
     * 0.83 in luminance is unreachable by construction.
     *
     * <p>The fallback tile is not a card; it is a photo slot with no photo, and
     * an empty photo slot is dark. At {@code #4A2120} white clears
     * <b>13.7:1</b> and the coral glyph <b>4.8:1</b> in both themes, so the
     * caption needs no scrim at all (see {@code DownloadItemAdapter
     * .applyGridTileGround}).
     *
     * <p>Chosen over the dark theme's old {@code #2D1E1F}, which would have
     * made dark theme a literal no-op but kept a latent bug: that value sits at
     * <b>1.16:1</b> against the dark page background, so the tile had no edge
     * and dissolved into the page. {@code #4A2120} separates from both page
     * grounds (1.35:1 dark, 13.1:1 light) and reads as deliberate brand rather
     * than as a hole. Deeper ({@code #552724}) buys a firmer edge at some
     * restraint; lighter ({@code #3A2321}) the reverse. All three clear the
     * contrast floors — the choice inside that range is taste.
     *
     * <p>Do NOT re-derive this from the theme background. Doing so is what
     * split the caption ink, and no amount of tuning the ink fixes it.
     */
    private static final int COLOR_FALLBACK_GROUND = 0xFF4A2120;

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
     *   as the OPAQUE {@link #COLOR_FALLBACK_GROUND}, one colour in both
     *   themes; when {@code false} it
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
        // One opaque ground, both themes — deliberately NOT composited over the
        // theme background any more. See COLOR_FALLBACK_GROUND: the theme-
        // following version resolved to a pale pink in light theme that white
        // caption text cannot sit on (1.17:1), which forced the grid tile into a
        // second, theme-inked treatment. Opaque either way, so nothing behind it
        // (card colour, ripple, a previous frame) shows through as a veil.
        int ground = COLOR_FALLBACK_GROUND;
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
