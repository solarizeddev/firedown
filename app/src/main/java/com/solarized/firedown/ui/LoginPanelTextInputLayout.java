package com.solarized.firedown.ui;

import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.TypedArray;
import android.util.AttributeSet;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.google.android.material.textfield.TextInputLayout;
import com.solarized.firedown.R;
import com.solarized.firedown.utils.Utils;


public class LoginPanelTextInputLayout extends TextInputLayout {


    public LoginPanelTextInputLayout(@NonNull Context context) {
        super(context);
    }

    public LoginPanelTextInputLayout(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
    }

    public LoginPanelTextInputLayout(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init(context, attrs, defStyleAttr);
    }

    private void init(Context context, AttributeSet attrs, int defStyleAttr) {

        TypedArray array = context.obtainStyledAttributes(attrs, R.styleable.LoginPanelTextInputLayout, defStyleAttr, 0);

        int primary = Utils.themeColor(context, com.google.android.material.R.attr.colorPrimary);

        setDefaultHintTextColor(ColorStateList.valueOf(primary));

        int errorTextColor = array.getColor(R.styleable.LoginPanelTextInputLayout_InputLayoutErrorTextColor, 0);

        int errorIconColor = array.getColor(R.styleable.LoginPanelTextInputLayout_InputLayoutErrorIconColor, 0);

        // The styleable values are resolved COLOURS (array.getColor), not resource
        // ids — they used to be passed back through ContextCompat.getColor as if
        // they were ids.
        setErrorTextColor(ColorStateList.valueOf(errorTextColor != 0 ? errorTextColor : primary));

        setErrorIconTintList(ColorStateList.valueOf(errorIconColor != 0 ? errorIconColor : primary));

        array.recycle();

    }

}

