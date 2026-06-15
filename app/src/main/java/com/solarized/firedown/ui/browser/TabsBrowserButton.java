package com.solarized.firedown.ui.browser;

import android.content.Context;
import android.content.res.TypedArray;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.AppCompatImageView;

import com.solarized.firedown.R;

/**
 * The bottom bar's live tab-count button. Renders via {@link TabCountDrawable}
 * - the SAME drawable the tabs screen's segmented toggle uses - so the bottom
 * bar and the tabs header show an identical rounded-rect + count (same stroke,
 * corner, bold digits, and the &gt;99 fire easter egg). Previously this drew the
 * count as button text over a separately-stroked GradientDrawable, which read
 * heavier/different from the tabs header; sharing TabCountDrawable keeps the two
 * renderings from drifting.
 */
public class TabsBrowserButton extends FrameLayout {

    /**
     * Tab counts up to this value render verbatim ("1"–"99"). Anything
     * beyond renders the {@link #LOTS_OF_TABS_GLYPH} easter egg —
     * matching Chrome / Fenix's "stop trying to fit more digits, you
     * have too many tabs" UX. The fire glyph doubles as a brand wink.
     */
    private static final int MAX_DISPLAYED_COUNT = 99;
    private static final String LOTS_OF_TABS_GLYPH = "🔥"; // 🔥

    private TabCountDrawable mCountDrawable;


    public TabsBrowserButton(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init(context, attrs,  0);
    }

    public TabsBrowserButton(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init(context, attrs, defStyleAttr);
    }

    public TabsBrowserButton(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
        init(context, attrs, defStyleAttr);
    }

    private void init(Context context, AttributeSet attrs, int defStyleAttr){

        LayoutInflater inflater = (LayoutInflater) context.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
        View v = inflater.inflate(R.layout.browser_tab_button, this, true);

        AppCompatImageView icon = v.findViewById(R.id.button);
        mCountDrawable = new TabCountDrawable(getResources());
        icon.setImageDrawable(mCountDrawable);

        final TypedArray array = context.obtainStyledAttributes(attrs, R.styleable.TabsBrowserButton, defStyleAttr, 0);
        try {
            int textColor = array.getColor(R.styleable.TabsBrowserButton_tabTextColor, 0);
            if (textColor != 0) {
                setTabsTextColor(textColor);
            }
        } finally {
            array.recycle();
        }
    }

    /** Tints the count rect + digits (TabCountDrawable colours both). */
    public void setTabsTextColor(int color){
        mCountDrawable.setTint(color);
    }

    public void setTabsCount(int count){
        mCountDrawable.setCount(count);
    }

    /**
     * The one place the count-to-glyph rule lives: verbatim digits up to
     * {@link #MAX_DISPLAYED_COUNT}, the 🔥 easter egg beyond. Shared with
     * {@link TabCountDrawable} so the two renderings can never drift.
     */
    public static String formatTabsCount(int count) {
        return count > MAX_DISPLAYED_COUNT
                ? LOTS_OF_TABS_GLYPH
                : String.valueOf(count);
    }
}
