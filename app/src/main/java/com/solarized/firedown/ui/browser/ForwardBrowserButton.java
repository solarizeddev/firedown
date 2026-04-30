package com.solarized.firedown.ui.browser;

import android.content.Context;
import android.util.AttributeSet;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;


public class ForwardBrowserButton extends BasicBrowserButton{


    public ForwardBrowserButton(@NonNull Context context) {
        super(context);
    }

    public ForwardBrowserButton(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);

    }

    public ForwardBrowserButton(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
    }

}
