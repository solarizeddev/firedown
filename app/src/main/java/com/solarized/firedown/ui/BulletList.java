package com.solarized.firedown.ui;

import android.content.Context;
import android.graphics.Paint;
import android.os.Build;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.BulletSpan;
import android.text.style.LineHeightSpan;

import androidx.annotation.ColorInt;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.List;

/**
 * A bulleted list in one TextView: each item its own paragraph with a
 * {@link BulletSpan}, so a wrapped line hangs under the item's text rather
 * than under the bullet (a typed "•  " prefix put it under the bullet), and
 * {@value #ITEM_GAP_DP}dp between items. A closing line, when given, follows
 * without a bullet. Used by an add-on's permission list (its page and the
 * install question).
 */
public final class BulletList {

    private static final int ITEM_GAP_DP = 6;
    private static final int BULLET_GAP_DP = 12;
    private static final float BULLET_RADIUS_DP = 2.5f;

    private BulletList() {}

    @NonNull
    public static CharSequence build(@NonNull Context context, @NonNull List<String> items,
                                     @Nullable String closing, @ColorInt int bulletColor) {
        float density = context.getResources().getDisplayMetrics().density;
        int gap = Math.round(BULLET_GAP_DP * density);
        int itemGap = Math.round(ITEM_GAP_DP * density);
        SpannableStringBuilder out = new SpannableStringBuilder();
        int count = items.size() + (closing == null ? 0 : 1);
        for (int i = 0; i < count; i++) {
            boolean bulleted = i < items.size();
            String text = bulleted ? items.get(i) : closing;
            int start = out.length();
            out.append(text);
            boolean last = i == count - 1;
            if (!last) {
                out.append('\n');
            }
            int end = out.length();
            if (bulleted) {
                out.setSpan(bullet(gap, bulletColor, density), start, end,
                        Spanned.SPAN_INCLUSIVE_EXCLUSIVE);
            }
            if (!last) {
                out.setSpan(new ParagraphGap(itemGap), start, end, Spanned.SPAN_INCLUSIVE_EXCLUSIVE);
            }
        }
        return out;
    }

    @NonNull
    private static BulletSpan bullet(int gap, @ColorInt int color, float density) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            return new BulletSpan(gap, color, Math.round(BULLET_RADIUS_DP * density));
        }
        return new BulletSpan(gap, color);
    }

    /** Extra space under the last line of the paragraph it covers. */
    private static final class ParagraphGap implements LineHeightSpan {

        private final int mGap;

        ParagraphGap(int gap) {
            mGap = gap;
        }

        @Override
        public void chooseHeight(CharSequence text, int start, int end, int spanstartv,
                                 int lineHeight, Paint.FontMetricsInt fm) {
            if (text instanceof Spanned spanned && end == spanned.getSpanEnd(this)) {
                fm.descent += mGap;
                fm.bottom += mGap;
            }
        }
    }
}
