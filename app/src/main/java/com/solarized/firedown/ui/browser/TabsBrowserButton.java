package com.solarized.firedown.ui.browser;

import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.AppCompatButton;
import androidx.core.content.ContextCompat;

import com.solarized.firedown.R;

public class TabsBrowserButton extends FrameLayout {

    /**
     * Tab counts up to this value render verbatim ("1"–"99"). Anything
     * beyond renders the {@link #LOTS_OF_TABS_GLYPH} easter egg —
     * matching Chrome / Fenix's "stop trying to fit more digits, you
     * have too many tabs" UX while keeping the 22dp×20dp button shape
     * intact. The fire glyph doubles as a brand wink (Firedown).
     */
    private static final int MAX_DISPLAYED_COUNT = 99;
    private static final String LOTS_OF_TABS_GLYPH = "🔥"; // 🔥

    private AppCompatButton mButton;


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

        final TypedArray array = context.obtainStyledAttributes(attrs, R.styleable.TabsBrowserButton, defStyleAttr, 0);

        LayoutInflater inflater = (LayoutInflater) context.getSystemService(Context.LAYOUT_INFLATER_SERVICE);

        View v = inflater.inflate(R.layout.browser_tab_button, this, true);

        mButton = v.findViewById(R.id.button);

        try {

            int textColor = array.getColor(R.styleable.TabsBrowserButton_tabTextColor, 0);

            int backgroundResource = array.getResourceId(R.styleable.TabsBrowserButton_tabBackgroundDrawable, 0);

            if(backgroundResource > 0){
                Drawable backgroundDrawable = ContextCompat.getDrawable(context, backgroundResource);
                setTabsBackground(backgroundDrawable);
            }

           if(textColor > 0){
               setTabsTextColor(textColor);
           }


        } finally {
            array.recycle();
        }


    }

    public void setTabsTextColor(int color){
        mButton.setTextColor(color);
    }

    public void setTabsBackground(Drawable drawable){
        mButton.setBackground(drawable);
    }

    public void setTabsCount(int count){
        mButton.setText(formatTabsCount(count));
    }

    /**
     * The one place the count-to-glyph rule lives: verbatim digits up to
     * {@link #MAX_DISPLAYED_COUNT}, the 🔥 easter egg beyond. Shared with
     * {@link TabCountDrawable} (the tabs screen's segmented-toggle
     * rendering of the same count) so the two can never drift.
     */
    public static String formatTabsCount(int count) {
        return count > MAX_DISPLAYED_COUNT
                ? LOTS_OF_TABS_GLYPH
                : String.valueOf(count);
    }

    public Drawable getTabsBackground() {
        return mButton.getBackground();
    }
}
