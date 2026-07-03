package com.solarized.firedown.settings;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.graphics.ColorUtils;

import com.google.android.material.color.MaterialColors;

/**
 * The Cloud Backup status hero's "time as space" runway: a gradient line from
 * <b>Today</b> (solid dot, left) to the covered-until date (soft dot, right) —
 * the date is a place on the line, the duration is its length, so neither
 * needs restating. Replaced the month-tick {@code CloudRunwayView}: the ticks
 * double-encoded the "N of M months" text, and with balance-derived coverage
 * that text was degenerate anyway (N and M derive from the same balance, so it
 * could only ever read "N of N"). Ink is coral normally, amber in grace
 * ({@link #setInk}); the dots ring in the window background so they read as
 * sitting ON the line against any theme.
 */
public class CloudTimelineView extends View {

    private final Paint mLine = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mDot = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mRing = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF mRect = new RectF();
    private final float mDensity;

    private int mInk = Color.TRANSPARENT;

    public CloudTimelineView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        mDensity = getResources().getDisplayMetrics().density;
        mRing.setColor(MaterialColors.getColor(context,
                android.R.attr.colorBackground, Color.WHITE));
    }

    /** The data ink (coral normally, amber in grace). Rebuilds the gradient. */
    public void setInk(int ink) {
        if (mInk != ink) {
            mInk = ink;
            mLine.setShader(null); // rebuilt with the new ink on next draw
            invalidate();
        }
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        mLine.setShader(null); // width changed — gradient spans the new width
    }

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        super.onDraw(canvas);
        float w = getWidth();
        float h = getHeight();
        if (w <= 0 || h <= 0) {
            return;
        }
        float cy = h / 2f;
        float startR = 6.5f * mDensity;   // Today — solid, slightly larger
        float endR = 4.5f * mDensity;     // covered-until — soft, smaller
        float half = 3f * mDensity;       // line half-thickness (6dp line)
        int soft = ColorUtils.setAlphaComponent(mInk, 120);

        if (mLine.getShader() == null) {
            mLine.setShader(new LinearGradient(0, 0, w, 0, mInk, soft,
                    Shader.TileMode.CLAMP));
        }
        mRect.set(startR, cy - half, w - endR, cy + half);
        canvas.drawRoundRect(mRect, half, half, mLine);

        // Dots on top, each with a background-colored ring so they read as
        // markers ON the line rather than blobs merged into it.
        float ring = 2f * mDensity;
        canvas.drawCircle(startR, cy, startR, mRing);
        mDot.setColor(mInk);
        canvas.drawCircle(startR, cy, startR - ring, mDot);
        canvas.drawCircle(w - endR, cy, endR, mRing);
        mDot.setColor(soft);
        canvas.drawCircle(w - endR, cy, endR - ring, mDot);
    }
}
