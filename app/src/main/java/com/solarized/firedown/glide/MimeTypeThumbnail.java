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

import com.solarized.firedown.R;
import com.solarized.firedown.utils.FileUriHelper;

public class MimeTypeThumbnail {

    /**
     * The brand fill for the LETTERBOX (media viewer) fallback: its glyph tint
     * plus the ~12% wash behind it. The fill path (list rows + grid tiles)
     * uses the per-theme {@code mime_fallback_ground} / {@code mime_fallback_glyph}
     * resources instead — see {@link #groundColor}.
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
     * (list rows + grid tiles) paints the {@code mime_fallback_ground}
     * resource instead — see {@link #groundColor} for why a theme-composited
     * wash had to go.
     */
    private static final int WASH_ALPHA = 30;

    /**
     * The fallback ground on its own, with no mime glyph — the
     * {@code mime_fallback_ground} resource for the current theme. For a slot
     * that has no artwork and no room for a glyph either: the Downloads grid
     * tile during a DOWNLOAD, where the progress ring is the focal element and
     * a glyph behind it would compete. Same colour the full fallback paints, so
     * a downloading tile and the art-less finished tile beside it match.
     *
     * <p>The ground is a per-theme RESOURCE, one literal per theme, never a
     * formula over the theme background. Light is {@code #FDDECE} (peach at
     * 40% over the page — Google's tinted-container principle with our hue)
     * under DARK caption ink; dark is {@code #342C2B} (one step off the page,
     * hue 30° at chroma 4 — the most warmth a dark ground takes before the
     * brown trap, and before the status ink drops under 4.5:1) under the
     * layouts' white ink. Which ink a grid caption takes is the adapters' job
     * ({@code FallbackInks}); the resources' comments carry every measured
     * figure. History: the ground was ONE literal in both themes for a while
     * ({@code #2E2F31}), which was forced, not chosen — a white caption pinned
     * on the tile allows only a dark ground, and a dark warm ground is brown
     * (the {@code #4A2120} episode), so neutral was the only survivor, and on
     * a light page five dark slabs above the fold were the loudest thing on
     * screen. Letting the caption follow the tile is what made a light
     * placeholder possible; it also fixed the neutral's BLUE lean (hue 272°),
     * which read cool beside the coral glyph.
     */
    public static int groundColor(@NonNull Context context) {
        return ContextCompat.getColor(context, R.color.mime_fallback_ground);
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
     *   as the OPAQUE per-theme {@code mime_fallback_ground} (see
     *   {@link #groundColor}); when {@code false} it
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
        // The glyph is the brand: coral as-is on the dark ground (4.73:1),
        // and the deeper progress_indicator tone on the light cream (3.39:1 —
        // the brand coral is 2.1:1 there, under the 3:1 glyph floor; the same
        // rule the progress bars follow). Per-theme resource; see groundColor.
        int color = ContextCompat.getColor(context, R.color.mime_fallback_glyph);
        // One opaque literal per theme — never composited over the theme
        // background (that form resolved to a pastel no caption ink fit).
        // Opaque, so nothing behind it (card colour, ripple, a previous frame)
        // shows through as a veil.
        int ground = groundColor(context);
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
