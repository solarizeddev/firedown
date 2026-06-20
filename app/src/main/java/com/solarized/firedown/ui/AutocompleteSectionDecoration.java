package com.solarized.firedown.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.util.TypedValue;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

/**
 * Draws a single inset hairline at the boundaries between sections of the
 * autocomplete list (search block | history | open tabs) — not between every
 * row. The card already groups the whole list; these dividers only separate
 * the distinct kinds of suggestion.
 *
 * <p>Section membership comes from the adapter via {@link Sectioned}; a
 * divider is reserved + drawn above any row whose section differs from the
 * row before it (never above position 0). Start-inset aligns the line with
 * the row text (past the leading icon), matching the M3 inset-divider style.</p>
 */
public class AutocompleteSectionDecoration extends RecyclerView.ItemDecoration {

    /** Implemented by the adapter so the decoration can group rows. */
    public interface Sectioned {
        /** Stable section key for the row at {@code position}. */
        int sectionAt(int position);
    }

    private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final int mThickness;
    private final int mInsetStart;
    private final int mInsetEnd;

    public AutocompleteSectionDecoration(@NonNull Context context) {
        float d = context.getResources().getDisplayMetrics().density;
        mThickness = Math.max(1, Math.round(d));           // 1dp hairline
        mInsetStart = Math.round(48 * d);                  // align with row text (12 + 24 + 12)
        mInsetEnd = Math.round(12 * d);
        mPaint.setStyle(Paint.Style.FILL);
        // Sensible default until the host calls setColor() from its theme pass.
        TypedValue tv = new TypedValue();
        int color = 0x33888888;
        if (context.getTheme().resolveAttribute(
                com.google.android.material.R.attr.colorOutlineVariant, tv, true)) {
            color = tv.data;
        }
        mPaint.setColor(color);
    }

    /** Themed divider colour (regular / incognito), set by AutoCompleteView. */
    public void setColor(int color) {
        mPaint.setColor(color);
    }

    private boolean startsNewSection(@NonNull RecyclerView parent, int position) {
        if (position <= 0) return false;
        RecyclerView.Adapter<?> adapter = parent.getAdapter();
        if (!(adapter instanceof Sectioned s)) return false;
        return s.sectionAt(position) != s.sectionAt(position - 1);
    }

    @Override
    public void getItemOffsets(@NonNull Rect outRect, @NonNull View view,
                               @NonNull RecyclerView parent, @NonNull RecyclerView.State state) {
        int position = parent.getChildAdapterPosition(view);
        if (position != RecyclerView.NO_POSITION && startsNewSection(parent, position)) {
            outRect.top = mThickness;
        }
    }

    @Override
    public void onDraw(@NonNull Canvas canvas, @NonNull RecyclerView parent,
                       @NonNull RecyclerView.State state) {
        boolean rtl = parent.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
        int left = rtl ? mInsetEnd : mInsetStart;
        int right = parent.getWidth() - (rtl ? mInsetStart : mInsetEnd);
        for (int i = 0; i < parent.getChildCount(); i++) {
            View child = parent.getChildAt(i);
            int position = parent.getChildAdapterPosition(child);
            if (position == RecyclerView.NO_POSITION) continue;
            if (!startsNewSection(parent, position)) continue;
            int top = child.getTop() - mThickness + (int) child.getTranslationY();
            canvas.drawRect(left, top, right, top + mThickness, mPaint);
        }
    }
}
