package com.solarized.firedown.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.material.color.MaterialColors;

/**
 * Stacked "storage per type" bar (the Storage screen's hero, after Signal's
 * Manage storage bar). One pill, one segment per type in the order given,
 * each segment's width proportional to its bytes.
 *
 * <p>Mark rules: the whole bar is clipped to ONE rounded pill (so only the
 * outer ends round, never each segment), adjacent segments are separated by a
 * 2dp gap that shows the surface, and a non-zero segment never renders
 * narrower than {@link #MIN_SEGMENT_DP} — a 3 MB subtitle folder beside
 * 40 GB of video would otherwise be a sub-pixel sliver the legend claims
 * exists. An all-zero bar draws the empty track only.
 *
 * <p>Identity is never colour-alone: the Storage screen always renders a
 * labelled legend (name + size) under this view, so the view itself is
 * decorative for accessibility and carries a summary contentDescription set
 * by the caller.
 */
public class StorageBarView extends View {

    private static final float GAP_DP = 2f;
    private static final float MIN_SEGMENT_DP = 4f;

    private final Paint mTrackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mSegmentPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path mClip = new Path();
    private final RectF mBounds = new RectF();
    private final float mGap;
    private final float mMinSegment;

    private long[] mValues = new long[0];
    private int[] mColors = new int[0];

    public StorageBarView(Context context) {
        this(context, null);
    }

    public StorageBarView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        float density = getResources().getDisplayMetrics().density;
        mGap = GAP_DP * density;
        mMinSegment = MIN_SEGMENT_DP * density;
        mTrackPaint.setColor(MaterialColors.getColor(this,
                com.google.android.material.R.attr.colorSurfaceContainerHighest));
    }

    /** Segment bytes + colours, same length, in draw order. */
    public void setSegments(@NonNull long[] values, @NonNull int[] colors) {
        if (values.length != colors.length) {
            throw new IllegalArgumentException("values/colors length mismatch");
        }
        mValues = values.clone();
        mColors = colors.clone();
        invalidate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        mBounds.set(getPaddingLeft(), getPaddingTop(),
                w - getPaddingRight(), h - getPaddingBottom());
        float radius = mBounds.height() / 2f;
        mClip.reset();
        mClip.addRoundRect(mBounds, radius, radius, Path.Direction.CW);
    }

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        super.onDraw(canvas);
        float width = mBounds.width();
        if (width <= 0) {
            return;
        }

        canvas.save();
        canvas.clipPath(mClip);

        long total = 0;
        int nonZero = 0;
        for (long v : mValues) {
            if (v > 0) {
                total += v;
                nonZero++;
            }
        }
        if (total <= 0) {
            canvas.drawRect(mBounds, mTrackPaint);
            canvas.restore();
            return;
        }

        // Width left for data once the gaps and the minimum-width floors are
        // paid; each segment gets its floor plus a proportional share of it.
        float gaps = mGap * (nonZero - 1);
        float floors = mMinSegment * nonZero;
        float free = Math.max(0f, width - gaps - floors);

        boolean rtl = getLayoutDirection() == LAYOUT_DIRECTION_RTL;
        float x = rtl ? mBounds.right : mBounds.left;
        int drawn = 0;
        for (int i = 0; i < mValues.length; i++) {
            long v = mValues[i];
            if (v <= 0) {
                continue;
            }
            drawn++;
            float segment = mMinSegment + free * ((float) v / total);
            // The last segment absorbs rounding so the bar always ends flush.
            if (drawn == nonZero) {
                segment = rtl ? x - mBounds.left : mBounds.right - x;
            }
            mSegmentPaint.setColor(mColors[i]);
            if (rtl) {
                canvas.drawRect(x - segment, mBounds.top, x, mBounds.bottom, mSegmentPaint);
                x -= segment + mGap;
            } else {
                canvas.drawRect(x, mBounds.top, x + segment, mBounds.bottom, mSegmentPaint);
                x += segment + mGap;
            }
        }
        canvas.restore();
    }
}
