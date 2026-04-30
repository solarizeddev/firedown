package com.solarized.firedown.glide;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import androidx.annotation.NonNull;
import android.graphics.drawable.Drawable;
import androidx.core.content.ContextCompat;

import com.solarized.firedown.utils.FileUriHelper;

public class MimeTypeThumbnail {

    private static final int DEFAULT_SIZE = 256;

    // Color palette
    private static final int COLOR_BRAND_YELLOW    = 0xFFffa386;
    private static final int COLOR_BRAND_ORANGE    = 0xFFf0716c;


    @NonNull
    public static Bitmap generate(
            @NonNull Context context, @NonNull String mimeType, int width, int height
    ) {
        if (width <= 0) width = DEFAULT_SIZE;
        if (height <= 0) height = DEFAULT_SIZE;

        int iconRes = FileUriHelper.getMimeTypeIcon(mimeType);
        int color = getColorForMimeType(mimeType);

        int density = context.getResources().getDisplayMetrics().densityDpi;

        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        bitmap.setDensity(density);
        Canvas canvas = new Canvas(bitmap);

        // Tinted background
        Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        bgPaint.setColor(color);
        bgPaint.setAlpha(30);
        canvas.drawRect(0, 0, width, height, bgPaint);

        // Draw the icon centered at 50% of the smallest dimension
        Drawable icon = ContextCompat.getDrawable(context, iconRes);
        if (icon != null) {
            int iconSize = (int) (Math.min(width, height) * 0.5f);
            int left = (width - iconSize) / 2;
            int top = (height - iconSize) / 2;

            icon.setBounds(left, top, left + iconSize, top + iconSize);
            icon.setTint(color);
            icon.draw(canvas);
        }

        return bitmap;
    }

    private static int getColorForMimeType(@NonNull String mimeType) {
        if (FileUriHelper.isVideo(mimeType))                return COLOR_BRAND_ORANGE;
        if (FileUriHelper.isAudio(mimeType))                return COLOR_BRAND_YELLOW;
        return COLOR_BRAND_ORANGE;
    }
}