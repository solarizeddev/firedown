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
     * The brand fill for the LETTERBOX (media viewer) fallback: its glyph tint
     * plus the ~12% wash behind it. The fill path (list rows + grid tiles)
     * uses {@link #COLOR_FALLBACK_GROUND} + {@link #COLOR_FALLBACK_GLYPH}
     * instead — see those for why the grid carries no brand.
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
     * an empty photo slot is dark AND NEUTRAL. {@code #2E2F31} (L* 19, chroma 1 —
     * a hair under dark theme's {@code surfaceContainerHighest}): white clears
     * <b>13.4:1</b> and the glyph ({@link #COLOR_FALLBACK_GLYPH}) <b>7.8:1</b>
     * in both themes, so the caption needs no scrim at all (see
     * {@code DownloadItemAdapter.applyGridTileGround}); it separates from the
     * dark page at 1.38:1 and the light page at 12.8:1. The step below
     * {@code #343537} is for the grid's ERROR/QUEUED status ink,
     * {@code colorPrimaryContainer}: dark theme's {@code #F66A66} measures
     * 4.19:1 on {@code #343537} (under the 4.5 text floor) and <b>4.58:1</b>
     * here — the darkest of the three inks this ground must carry, so it is
     * the binding one. Re-measure it before lightening the ground.
     *
     * <p>History: the first literal was the brand-tinted {@code #4A2120} (L* 19,
     * chroma 22, hue 27°), picked to "read as deliberate brand rather than as a
     * hole". On-device it read as BROWN — the same dark-warm-low-chroma trap
     * this app hit with the buy-credit segments and the checked chip (brown is
     * nothing but dark, low-chroma orange), and on a grid where five of eight
     * tiles are fallbacks it was most of what the screen showed. There is no
     * escape inside that hue: at this lightness a warm colour is muddy however
     * saturated, and lighter breaks the white caption. So the ground carries
     * no brand at all; the brand sits in the GLYPH (see
     * {@link #COLOR_FALLBACK_GLYPH}). Type is carried by the glyph SHAPE and
     * the mime chip, never by a hue.
     *
     * <p>Do NOT re-derive this from the theme background. Doing so is what
     * split the caption ink, and no amount of tuning the ink fixes it.
     */
    private static final int COLOR_FALLBACK_GROUND = 0xFF2E2F31;

    /**
     * The glyph ink on the {@link #COLOR_FALLBACK_GROUND} fill path: the brand
     * CORAL ({@link #COLOR_BRAND}, 5.00:1 on the ground — over the 4.5:1 text
     * floor, with less margin than the peach had, so re-measure it if the
     * ground ever moves), at the reduced size {@link #MAX_FILL_ICON_DP} sets.
     *
     * <p>History, three hues in three commits, maintainer calls each time: a
     * neutral GREY (Firefox for Android / AOSP file picker's shape) was
     * rejected for carrying no brand; the launcher's PEACH ({@code #FFB58A},
     * h 56°) was rejected on-device as "too big and too yellow" — on a
     * neutral ground the warm arm loses the coral neighbour that makes it read
     * as peach in the icon, and lands as orange-yellow; so the glyph is the
     * brand's own coral. The earlier objection to coral — the ACTING hue on
     * the most inert element, five times over on a placeholder-heavy grid —
     * is met by SIZE rather than hue: the glyph is small enough to read as a
     * mark, not a control. Why ONE hue rather than per-type (Drive / Files by
     * Google): type is carried by the glyph SHAPE and the mime chip, and a
     * per-type palette is only worth it as a full SYSTEM (chip glyph + tile
     * glyph + caption glyph sharing the hue) needing a second, deeper set for
     * light-theme surfaces. On this dark ground it is the same in both
     * themes, so one constant is correct everywhere. The letterbox (media
     * viewer) path uses the same {@link #COLOR_BRAND} at its own size.
     */
    private static final int COLOR_FALLBACK_GLYPH = COLOR_BRAND;

    /**
     * The fallback ground on its own, with no mime glyph. For a slot that has
     * no artwork and no room for a glyph either — the Downloads grid tile
     * during a DOWNLOAD, where the progress ring is the focal element and a
     * glyph behind it would compete. Same colour the full fallback paints, so
     * a downloading tile and the art-less finished tile beside it match.
     */
    public static int groundColor() {
        return COLOR_FALLBACK_GROUND;
    }

    /**
     * Upper bound (dp) on the mime icon for the {@code fillBounds} (list /
     * grid) path. The icon is normally half the cell's shorter side, which on
     * a ~124dp-tall grid tile is a ~62dp glyph that dominates the tile and
     * crowds the title. A 50dp cap still read as "too big" on-device once the
     * glyph went coral (a large acting-hue mark on every placeholder), so it
     * is 32dp: the glyph is a MARK in the tile, not its subject, and the list
     * slot's own half-of-64dp is exactly this value, so list and grid glyphs
     * are now the same size. The letterboxed player fallback never sets the
     * cap and keeps its larger glyph (one tile, no grid to multiply it).
     */
    private static final int MAX_FILL_ICON_DP = 32;

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
        int color = COLOR_FALLBACK_GLYPH;
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
