package com.solarized.firedown.utils;

import android.graphics.Bitmap;
import android.graphics.Color;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;

/**
 * The app's ONE QR encoder. Three surfaces render a QR — the P2P share offer/
 * reply codes ({@code P2pShareBaseFragment}), the Lightning invoice on the
 * buy-credit screen ({@code BuyCreditFragment}), and the recovery code on the
 * Cloud screen ({@code SyncSettingsFragment}) — and the first two carried
 * byte-identical private copies of this method before it was extracted. Keep it
 * shared: the same "a private copy in one adapter is exactly how they drifted
 * apart" rule that put {@code compactDuration} in {@link DateUtils}.
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
        try {
            BitMatrix matrix = new QRCodeWriter()
                    .encode(content, BarcodeFormat.QR_CODE, SIZE_PX, SIZE_PX);
            int width = matrix.getWidth();
            int height = matrix.getHeight();
            int[] pixels = new int[width * height];
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    pixels[y * width + x] = matrix.get(x, y) ? Color.BLACK : Color.WHITE;
                }
            }
            // RGB_565 is enough for two colours and halves the allocation.
            Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565);
            bitmap.setPixels(pixels, 0, width, 0, 0, width, height);
            return bitmap;
        } catch (Exception e) {
            return null;
        }
    }
}
