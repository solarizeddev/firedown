package com.solarized.firedown.utils;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;

import androidx.annotation.ColorInt;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;

import java.util.EnumMap;
import java.util.Map;

/**
 * The app's ONE QR encoder. Four surfaces render a QR — the P2P share offer/
 * reply codes ({@code P2pShareBaseFragment}), the Lightning invoice on the
 * buy-credit screen ({@code BuyCreditFragment}), the recovery code on the
 * Cloud screen ({@code SyncSettingsFragment}) and the download link on the
 * share screen ({@code ShareAppFragment}) — and the first two carried
 * byte-identical private copies of this method before it was extracted. Keep it
 * shared: the same "a private copy in one adapter is exactly how they drifted
 * apart" rule that put {@code compactDuration} in {@link DateUtils}.
 *
 * <p>Only the share screen wants a logo in the middle, and it gets its own
 * entry point ({@link #encodeWithLogo}) rather than a flag on the common one,
 * because a centre hole is readable ONLY at the higher error correction that
 * overload sets — see {@link #LOGO_FRACTION}.
 *
 * <p>Deliberately monochrome black-on-WHITE regardless of theme, and never
 * theme-tinted: a scanner needs the quiet-zone contrast, and a dark-theme
 * inversion is exactly what makes some readers fail. The caller supplies the
 * white ground by putting the bitmap on a light surface.
 *
 * <p>Encoding is CPU-only (no IO), a few ms for the short payloads here, so
 * callers render it inline. zxing's {@code encode} throws on content it cannot
 * fit at the requested size, so failure is normal-ish and reported as
 * {@code null} for the caller to hide its view.
 */
public final class QrCodes {

    /** Square edge in px. 512 is plenty for a ~1.2 KB P2P code and is what all
     *  three call sites used before the extraction; the ImageView scales it. */
    private static final int SIZE_PX = 512;

    /**
     * Edge of the centre logo tile, as a fraction of the QR edge. 0.22 (7.3% of
     * the symbol's area once the white ring around it is counted) is inside what
     * {@link ErrorCorrectionLevel#H} recovers, which is the ONLY reason punching
     * a hole in a QR is safe at all.
     *
     * <p>Measured, not guessed: this geometry rendered for the share URL and
     * decoded with OpenCV's detector reads back at 0.22 and 0.26 and FAILS from
     * 0.30 — and the identical hole at zxing's default level L fails already at
     * 0.22, which is why the plain {@link #encode} must never grow a logo. A
     * real camera at an angle has less margin than a synthetic image, so treat
     * 0.26 as the hard ceiling rather than a target.
     */
    private static final float LOGO_FRACTION = 0.22f;

    /** White gap between the QR modules and the logo tile, as a fraction of
     *  the QR edge. Separates the tile from the pattern for the decoder and
     *  for the eye. */
    private static final float LOGO_RING_FRACTION = 0.025f;

    /** Corner rounding of the tile / its ring, as a fraction of their own edge
     *  — the launcher-icon squircle look rather than a hard square. */
    private static final float LOGO_CORNER_FRACTION = 0.26f;

    private QrCodes() {
    }

    /**
     * Encodes {@code content} as a monochrome QR bitmap, or returns null when
     * zxing cannot represent it (over-long payload, unsupported characters).
     * Callers must handle null by hiding the image rather than showing an empty
     * frame that looks like a broken scan target.
     */
    @Nullable
    public static Bitmap encode(@NonNull String content) {
        // Default error correction, and RGB_565 because the result really is
        // two colours. Both change in the logo overload below.
        return render(content, null, Bitmap.Config.RGB_565);
    }

    /**
     * Encodes {@code content} with {@code logo} sitting on a rounded tile in the
     * middle — the app-icon-in-the-QR look.
     *
     * <p>The hole is only readable because this overload encodes at
     * {@link ErrorCorrectionLevel#H}; see {@link #LOGO_FRACTION}. Do NOT add a
     * logo to {@link #encode} instead — its callers (the P2P offer/reply codes,
     * the Lightning invoice, the recovery code) carry payloads orders of
     * magnitude longer than a URL, and H costs symbol capacity they need.
     *
     * <p>{@code tileColor} must be a FIXED colour, never a theme attribute: the
     * QR ground is deliberately white in both themes, so a theme-derived tile
     * turns into a white square on white in one of them.
     */
    @Nullable
    public static Bitmap encodeWithLogo(@NonNull String content, @NonNull Drawable logo,
                                        @ColorInt int tileColor) {
        Bitmap bitmap = render(content, ErrorCorrectionLevel.H, Bitmap.Config.ARGB_8888);
        if (bitmap == null) {
            return null;
        }
        int size = bitmap.getWidth();
        float centre = size / 2f;
        float tile = size * LOGO_FRACTION;
        float ring = tile + size * LOGO_RING_FRACTION * 2f;

        Canvas canvas = new Canvas(bitmap);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        RectF ringRect = new RectF(centre - ring / 2f, centre - ring / 2f,
                centre + ring / 2f, centre + ring / 2f);
        paint.setColor(Color.WHITE);
        float ringCorner = ring * LOGO_CORNER_FRACTION;
        canvas.drawRoundRect(ringRect, ringCorner, ringCorner, paint);

        RectF tileRect = new RectF(centre - tile / 2f, centre - tile / 2f,
                centre + tile / 2f, centre + tile / 2f);
        paint.setColor(tileColor);
        float tileCorner = tile * LOGO_CORNER_FRACTION;
        canvas.drawRoundRect(tileRect, tileCorner, tileCorner, paint);

        // The launcher foreground already carries its own inner padding, so it
        // is drawn to the full tile rather than inset again.
        logo.setBounds(Math.round(tileRect.left), Math.round(tileRect.top),
                Math.round(tileRect.right), Math.round(tileRect.bottom));
        logo.draw(canvas);
        return bitmap;
    }

    /**
     * The one encode. {@code level} null = zxing's default.
     */
    @Nullable
    private static Bitmap render(@NonNull String content, @Nullable ErrorCorrectionLevel level,
                                 @NonNull Bitmap.Config config) {
        try {
            Map<EncodeHintType, Object> hints = null;
            if (level != null) {
                hints = new EnumMap<>(EncodeHintType.class);
                hints.put(EncodeHintType.ERROR_CORRECTION, level);
            }
            BitMatrix matrix = new QRCodeWriter()
                    .encode(content, BarcodeFormat.QR_CODE, SIZE_PX, SIZE_PX, hints);
            int width = matrix.getWidth();
            int height = matrix.getHeight();
            int[] pixels = new int[width * height];
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    pixels[y * width + x] = matrix.get(x, y) ? Color.BLACK : Color.WHITE;
                }
            }
            Bitmap bitmap = Bitmap.createBitmap(width, height, config);
            bitmap.setPixels(pixels, 0, width, 0, 0, width, height);
            return bitmap;
        } catch (Exception e) {
            return null;
        }
    }
}
