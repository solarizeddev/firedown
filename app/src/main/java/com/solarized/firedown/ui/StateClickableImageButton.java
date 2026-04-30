package com.solarized.firedown.ui;

import android.content.Context;
import android.util.AttributeSet;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.material.button.MaterialButton;
import com.solarized.firedown.R;

public class StateClickableImageButton extends MaterialButton {

    public StateClickableImageButton(@NonNull Context context) {
        super(context);
    }

    public StateClickableImageButton(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
    }

    public StateClickableImageButton(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    @Override
    public int[] onCreateDrawableState(int extraSpace) {
        int[] drawableStates =  super.onCreateDrawableState(extraSpace + 1);
        if (isClickable()) {
            int[] state = {R.attr.stateClickable};
            mergeDrawableStates(drawableStates, state);
        }
        return drawableStates;
    }

    @Override
    public void setClickable(boolean clickable) {
        super.setClickable(clickable);
        refreshDrawableState();
    }
}
