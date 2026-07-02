package com.solarized.firedown.settings;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.material.color.MaterialColors;

/**
 * The Cloud Backup status hero's month-tick "time runway": one rounded segment
 * per month of the purchased plan, filled = months of coverage left (drawn
 * left-to-right, so the runway drains from the right as time passes). A tiny
 * custom view instead of N child views — it rebinds on every preference
 * notifyChanged and a 24-tick 2-year plan would churn view inflation for a
 * 6dp-tall row.
 */
public class CloudRunwayView extends View {

    /** Sanity ceiling — a plan is 1–24 months today; anything absurd from a
     *  future keyset collapses to a coarse bar instead of hairline ticks. */
    private static final int MAX_TICKS = 36;

    private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF mRect = new RectF();
    private final float mGap;
    private final float mRadius;
    private final int mOffColor;

    private int mTotal = 12;
    private int mFilled;
    private int mOnColor = Color.TRANSPARENT;

    public CloudRunwayView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        float density = getResources().getDisplayMetrics().density;
        mGap = 3f * density;
        mRadius = 2f * density;
        mOffColor = MaterialColors.getColor(context,
                com.google.android.material.R.attr.colorSurfaceVariant, Color.GRAY);
    }

    /** @param total  ticks (plan months), clamped to [1, {@link #MAX_TICKS}]
     *  @param filled lit ticks (months left), clamped to [0, total]
     *  @param onColor the data-ink colour (coral normally, amber in grace) */
    public void setTicks(int total, int filled, int onColor) {
        mTotal = Math.max(1, Math.min(total, MAX_TICKS));
        mFilled = Math.max(0, Math.min(filled, mTotal));
        mOnColor = onColor;
        invalidate();
    }

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        super.onDraw(canvas);
        float width = getWidth();
        float height = getHeight();
        if (width <= 0 || height <= 0) {
            return;
        }
        float tick = (width - mGap * (mTotal - 1)) / mTotal;
        if (tick <= 0) {
            // Degenerate width — draw one plain bar rather than nothing.
            mPaint.setColor(mFilled > 0 ? mOnColor : mOffColor);
            mRect.set(0, 0, width, height);
            canvas.drawRoundRect(mRect, mRadius, mRadius, mPaint);
            return;
        }
        for (int i = 0; i < mTotal; i++) {
            float left = i * (tick + mGap);
            mRect.set(left, 0, left + tick, height);
            mPaint.setColor(i < mFilled ? mOnColor : mOffColor);
            canvas.drawRoundRect(mRect, mRadius, mRadius, mPaint);
        }
    }
}
