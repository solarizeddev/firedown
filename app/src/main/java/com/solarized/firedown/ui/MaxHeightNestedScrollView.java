package com.solarized.firedown.ui;

import android.content.Context;
import android.content.res.TypedArray;
import android.util.AttributeSet;
import android.view.View;

import androidx.core.widget.NestedScrollView;

import com.solarized.firedown.R;

/**
 * NestedScrollView that clamps its measured height to {@code app:maxHeight}.
 *
 * <p>Used inside the TrackersInfoSheet to give the 'Top trackers' list a
 * fixed scroll ceiling without making the entire sheet a scroll surface.
 * The sheet's own content is laid out at intrinsic height; only the
 * trackers list scrolls when its row count exceeds {@code maxHeight}.</p>
 *
 * <p>Setting {@code layout_height="wrap_content"} on this view yields
 * content-sized height up to {@code maxHeight}, then scroll. A fixed
 * {@code layout_height} bypasses the cap.</p>
 */
public class MaxHeightNestedScrollView extends NestedScrollView {

    private int mMaxHeightPx;

    public MaxHeightNestedScrollView(Context context, AttributeSet attrs) {
        super(context, attrs);
        TypedArray a = context.obtainStyledAttributes(
                attrs, R.styleable.MaxHeightNestedScrollView);
        mMaxHeightPx = a.getDimensionPixelSize(
                R.styleable.MaxHeightNestedScrollView_maxHeight, 0);
        a.recycle();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        if (mMaxHeightPx > 0) {
            heightMeasureSpec = View.MeasureSpec.makeMeasureSpec(
                    mMaxHeightPx, View.MeasureSpec.AT_MOST);
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
    }
}
