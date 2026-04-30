package com.solarized.firedown.geckoview;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.widget.ProgressBar;

import androidx.core.content.res.ResourcesCompat;

import com.solarized.firedown.R;

public class GeckoProgressBar extends ProgressBar {

    private boolean isAutoCompleteVisible;

    public GeckoProgressBar(Context context) {
        super(context);
    }

    public GeckoProgressBar(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public GeckoProgressBar(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public void setAutoCompleteVisible(boolean autoCompleteVisible) {
        isAutoCompleteVisible = autoCompleteVisible;
    }

    public boolean isAutoCompleteVisible() {
        return isAutoCompleteVisible;
    }
}
