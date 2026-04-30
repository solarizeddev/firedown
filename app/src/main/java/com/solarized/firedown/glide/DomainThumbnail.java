package com.solarized.firedown.glide;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.text.TextUtils;

import androidx.annotation.NonNull;

import com.solarized.firedown.utils.WebUtils;

public class DomainThumbnail {

    private static final int DEFAULT_SIZE = 128;

    // Material palette for deterministic color assignment
    private static final int[] COLORS = {
            0xFFd74d86, // pink
            0xFFc84d97, // pink alt
            0xFFffa386, // yellow
            0xFFffa386, // yellow alt
            0xFFf0716c, // orange
            0xFFed6279, // orange alt
    };

    @NonNull
    public static Bitmap generate(@NonNull Context context, @NonNull String domain, int size) {
        if (size <= 0) size = DEFAULT_SIZE;

        String label = extractLabel(domain);
        int color = COLORS[Math.abs(domain.hashCode()) % COLORS.length];
        int density = context.getResources().getDisplayMetrics().densityDpi;

        Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        bitmap.setDensity(density);
        Canvas canvas = new Canvas(bitmap);

        // Rounded background
        Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        bgPaint.setColor(color);
        float radius = size * 0.2f;
        canvas.drawRoundRect(new RectF(0, 0, size, size), radius, radius, bgPaint);

        // Letter
        Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        textPaint.setColor(Color.WHITE);
        textPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        textPaint.setTextSize(size * 0.45f);
        textPaint.setTextAlign(Paint.Align.CENTER);

        float x = size / 2f;
        float y = (size / 2f) - ((textPaint.descent() + textPaint.ascent()) / 2f);
        canvas.drawText(label, x, y, textPaint);

        return bitmap;
    }

    @NonNull
    private static String extractLabel(@NonNull String url) {

        String domain = WebUtils.getDomainName(url);

        if(TextUtils.isEmpty(domain))
            return "#";

        // Strip www. and common prefixes
        String clean = domain.toLowerCase()
                .replaceFirst("^https?://", "")
                .replaceFirst("^www\\.", "")
                .replaceFirst("^m\\.", "");

        if (clean.isEmpty()) return "#";

        // Use first letter, uppercased
        return String.valueOf(Character.toUpperCase(clean.charAt(0)));
    }
}