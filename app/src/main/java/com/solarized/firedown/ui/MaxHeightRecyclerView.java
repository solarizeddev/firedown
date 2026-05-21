package com.solarized.firedown.ui;

import android.content.Context;
import android.content.res.TypedArray;
import android.util.AttributeSet;
import android.view.View;

import androidx.recyclerview.widget.RecyclerView;

import com.solarized.firedown.R;

/**
 * RecyclerView that clamps its measured height to {@code app:maxHeight}.
 *
 * <p>Used inside the TrackersInfoSheet to give the 'Top trackers' list a
 * fixed scroll ceiling without making the entire sheet a scroll surface.
 * Setting {@code layout_height="wrap_content"} on this view yields
 * content-sized height up to {@code maxHeight}, then scroll. A fixed
 * {@code layout_height} bypasses the cap.</p>
 *
 * <p>Shares the {@code app:maxHeight} styleable attribute with other
 * max-bounded views in {@code com.solarized.firedown.ui}.</p>
 */
public class MaxHeightRecyclerView extends RecyclerView {

    private int mMaxHeightPx;

    public MaxHeightRecyclerView(Context context, AttributeSet attrs) {
        super(context, attrs);
        TypedArray a = context.obtainStyledAttributes(
                attrs, R.styleable.MaxHeightView);
        mMaxHeightPx = a.getDimensionPixelSize(
                R.styleable.MaxHeightView_maxHeight, 0);
        a.recycle();
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        if (mMaxHeightPx > 0) {
            heightSpec = View.MeasureSpec.makeMeasureSpec(
                    mMaxHeightPx, View.MeasureSpec.AT_MOST);
        }
        super.onMeasure(widthSpec, heightSpec);
    }
}
