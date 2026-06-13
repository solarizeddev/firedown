package com.solarized.firedown.ui.browser;

import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.util.TypedValue;

import android.content.res.Resources;
import android.graphics.drawable.Drawable;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * The live tab-count glyph as a {@link Drawable} — the same rounded
 * rectangle + count that {@link TabsBrowserButton} renders as a view,
 * for hosts that can only take a drawable: the tabs screen's segmented
 * toggle (a MaterialButtonToggleGroup requires MaterialButton children,
 * so the count cannot be a custom view there; it rides the button's
 * {@code icon} slot instead).
 *
 * <p>Count formatting is shared with the bottom bar through
 * {@link TabsBrowserButton#formatTabsCount(int)} — including the
 * {@code >99} 🔥 easter egg — so the two renderings can never drift.
 * The flame emoji draws in its own colors regardless of tint (color
 * emoji ignore the paint color), which is the intended brand wink.
 *
 * <p><b>Theming contract:</b> the host's {@code iconTint}
 * ColorStateList is honored via {@link #setTintList}/{@link
 * #onStateChange} — MaterialButton applies its iconTint to the icon
 * drawable and TextView propagates the button's drawable state to
 * stateful compound drawables, so the segmented control's
 * selected/unselected/incognito content colors
 * (updateSegmentedButtons' setIconTint) paint the rect and digits with
 * zero extra wiring here.
 */
public class TabCountDrawable extends Drawable {

    /** Stroke width matches the bottom bar's count-rect restroke. */
    private static final float STROKE_DP = 1.8f;
    private static final float CORNER_DP = 5f;
    private static final float TEXT_DP   = 10f;
    private static final float SIZE_DP   = 20f;

    private final Paint mRectPaint;
    private final Paint mTextPaint;
    private final float mCorner;
    private final float mStrokeHalf;
    private final int   mIntrinsicSize;

    private String mText = "0";
    @Nullable private ColorStateList mTint;
    private int mColor = Color.GRAY;

    public TabCountDrawable(@NonNull Resources resources) {
        float density = resources.getDisplayMetrics().density;
        float stroke = STROKE_DP * density;
        mCorner = CORNER_DP * density;
        mStrokeHalf = stroke / 2f;
        mIntrinsicSize = Math.round(SIZE_DP * density);

        mRectPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        mRectPaint.setStyle(Paint.Style.STROKE);
        mRectPaint.setStrokeWidth(stroke);

        mTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        mTextPaint.setTextAlign(Paint.Align.CENTER);
        mTextPaint.setFakeBoldText(true);
        mTextPaint.setTextSize(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, TEXT_DP, resources.getDisplayMetrics()));
    }

    /** Updates the rendered count (shared formatter, incl. the 🔥 cap). */
    public void setCount(int count) {
        String text = TabsBrowserButton.formatTabsCount(count);
        if (!text.equals(mText)) {
            mText = text;
            invalidateSelf();
        }
    }

    @Override
    public void draw(@NonNull Canvas canvas) {
        Rect bounds = getBounds();
        if (bounds.isEmpty()) {
            return;
        }
        mRectPaint.setColor(mColor);
        mTextPaint.setColor(mColor);

        RectF rect = new RectF(
                bounds.left + mStrokeHalf,
                bounds.top + mStrokeHalf,
                bounds.right - mStrokeHalf,
                bounds.bottom - mStrokeHalf);
        canvas.drawRoundRect(rect, mCorner, mCorner, mRectPaint);

        // Vertical centering from the font metrics — a bare drawText at
        // centerY sits the BASELINE there and the digits ride high.
        Paint.FontMetrics fm = mTextPaint.getFontMetrics();
        float baseline = bounds.exactCenterY() - (fm.ascent + fm.descent) / 2f;
        canvas.drawText(mText, bounds.exactCenterX(), baseline, mTextPaint);
    }

    @Override
    public void setTintList(@Nullable ColorStateList tint) {
        mTint = tint;
        if (resolveColor(getState())) {
            invalidateSelf();
        }
    }

    @Override
    public void setTint(int tintColor) {
        setTintList(ColorStateList.valueOf(tintColor));
    }

    @Override
    public boolean isStateful() {
        return mTint != null && mTint.isStateful();
    }

    @Override
    protected boolean onStateChange(@NonNull int[] state) {
        return resolveColor(state);
    }

    private boolean resolveColor(int[] state) {
        if (mTint == null) {
            return false;
        }
        int resolved = mTint.getColorForState(state, mTint.getDefaultColor());
        if (resolved == mColor) {
            return false;
        }
        mColor = resolved;
        return true;
    }

    @Override
    public int getIntrinsicWidth() {
        return mIntrinsicSize;
    }

    @Override
    public int getIntrinsicHeight() {
        return mIntrinsicSize;
    }

    @Override
    public void setAlpha(int alpha) {
        mRectPaint.setAlpha(alpha);
        mTextPaint.setAlpha(alpha);
        invalidateSelf();
    }

    @Override
    public void setColorFilter(@Nullable ColorFilter colorFilter) {
        mRectPaint.setColorFilter(colorFilter);
        mTextPaint.setColorFilter(colorFilter);
        invalidateSelf();
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
