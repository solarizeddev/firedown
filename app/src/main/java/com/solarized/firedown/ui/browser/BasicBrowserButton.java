package com.solarized.firedown.ui.browser;

import android.content.Context;
import android.util.AttributeSet;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.google.android.material.button.MaterialButton;

public class BasicBrowserButton extends MaterialButton {

    protected OnButtonDismissListener mCallback;


    public BasicBrowserButton(@NonNull Context context) {
        super(context);

    }

    public BasicBrowserButton(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);

    }

    public BasicBrowserButton(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }


    public void setOnDismissListener(OnButtonDismissListener onDismissListener){
        mCallback = onDismissListener;
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        mCallback = null;
    }


}
