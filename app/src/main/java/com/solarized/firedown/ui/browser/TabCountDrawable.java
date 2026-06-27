package com.solarized.firedown.ui.browser;

import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
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

    /** A direct port of Chromium/Brave's {@code TabSwitcherDrawable} strategy
     *  (verified against the Chromium source), because a single hardcoded
     *  digit size never matched it across devices. Everything below mirrors
     *  what Chromium does:
     *
     *  <ol>
     *  <li><b>The digit size adapts to the digit COUNT</b>, with Chromium's
     *      EXACT dimens: {@code toolbar_tab_count_text_size_1_digit} = 12dp,
     *      {@code ..._2_digit} = 10dp. A single digit is drawn larger (it fills
     *      the box); two digits shrink to fit. A lone fixed size has to leave
     *      room for two digits, so a single digit never fills the box — exactly
     *      why ours read small next to Brave's "2". Picked per {@link #draw} by
     *      {@code mText}'s code-point count.</li>
     *  <li><b>Condensed bold digits</b> — {@code Typeface.create(
     *      "sans-serif-condensed", BOLD)}, verbatim from Chromium: tall, narrow
     *      glyphs that read big and bold yet still fit two. (Plain
     *      {@link Typeface#DEFAULT_BOLD} is wider/shorter — less bold-looking,
     *      and forces a smaller size to fit two digits.)</li>
     *  <li><b>Glyph-bounds vertical centering</b> — Chromium centers the
     *      measured text BOUNDS (the visible ink), not the font line, so the
     *      digits sit optically centered in the box. See {@link #draw}.</li>
     *  <li><b>The box is an INSET rounded-square</b>, not full-bleed: Chromium
     *      draws the {@code btn_tabswitcher_modern} vector — a rounded square
     *      with an internal margin — inside the 24dp toolbar-icon canvas
     *      ({@code toolbar_icon_height} = 24dp). So we keep the 24dp FOOTPRINT
     *      (level with the sibling 24dp bottom-bar icons / the toggle iconSize)
     *      but inset the stroked rect by {@link #BOX_INSET_DP}, giving the same
     *      ~18dp visible box Chromium/Brave show. (The exact vector pathData
     *      wasn't fetchable; the inset/corner reproduce its proportions.)</li>
     *  </ol>
     *
     *  Hosts reference 24dp so box + digit stay in lockstep across BOTH the
     *  bottom bar and the tabs-header toggle that share this drawable. (dp
     *  already scales per density — the device-to-device drift was the
     *  digit-count adaptivity + condensed font, not the unit.) */
    private static final float STROKE_DP = 2f;
    private static final float CORNER_DP = 3f;
    private static final float BOX_INSET_DP = 3f;
    private static final float TEXT_1_DIGIT_DP = 12f;
    private static final float TEXT_2_DIGIT_DP = 10f;
    private static final float SIZE_DP   = 24f;

    private final Paint mRectPaint;
    private final Paint mTextPaint;
    private final Rect  mTextBounds = new Rect();
    private final float mCorner;
    private final float mStrokeHalf;
    private final float mBoxInset;
    private final int   mIntrinsicSize;
    private final float mText1DigitSize;
    private final float mText2DigitSize;

    private String mText = "0";
    @Nullable private ColorStateList mTint;
    private int mColor = Color.GRAY;

    public TabCountDrawable(@NonNull Resources resources) {
        float density = resources.getDisplayMetrics().density;
        float stroke = STROKE_DP * density;
        mCorner = CORNER_DP * density;
        mStrokeHalf = stroke / 2f;
        mBoxInset = BOX_INSET_DP * density;
        mIntrinsicSize = Math.round(SIZE_DP * density);

        mRectPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        mRectPaint.setStyle(Paint.Style.STROKE);
        mRectPaint.setStrokeWidth(stroke);

        mText1DigitSize = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, TEXT_1_DIGIT_DP, resources.getDisplayMetrics());
        mText2DigitSize = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, TEXT_2_DIGIT_DP, resources.getDisplayMetrics());

        mTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        mTextPaint.setTextAlign(Paint.Align.CENTER);
        // Condensed bold, as Chromium's TabSwitcherDrawable uses: tall, narrow
        // digits that read big and bold yet still fit two of them — no
        // letter-spacing needed. The actual size is chosen per draw() by the
        // digit count (see class doc), so it's set there, not here.
        mTextPaint.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
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

        // Inset rounded-square box (Chromium's btn_tabswitcher_modern has an
        // internal margin inside the 24dp icon canvas — not full-bleed).
        float inset = mStrokeHalf + mBoxInset;
        RectF rect = new RectF(
                bounds.left + inset,
                bounds.top + inset,
                bounds.right - inset,
                bounds.bottom - inset);
        canvas.drawRoundRect(rect, mCorner, mCorner, mRectPaint);

        // Digit size adapts to the digit count (Chromium): a single digit
        // fills the box; two+ digits (incl. the 🔥 glyph, a surrogate pair)
        // shrink to fit. Counted by code points so a surrogate-pair glyph
        // isn't mistaken for two characters.
        int displayLen = mText.codePointCount(0, mText.length());
        mTextPaint.setTextSize(displayLen > 1 ? mText2DigitSize : mText1DigitSize);

        // Glyph-bounds vertical centering, verbatim from Chromium's draw():
        // center the measured text BOUNDS (visible ink), not the font line, so
        // the digits sit optically centered rather than riding high/low.
        mTextPaint.getTextBounds(mText, 0, mText.length(), mTextBounds);
        float baseline = bounds.exactCenterY()
                + (mTextBounds.bottom - mTextBounds.top) / 2f - mTextBounds.bottom;
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
