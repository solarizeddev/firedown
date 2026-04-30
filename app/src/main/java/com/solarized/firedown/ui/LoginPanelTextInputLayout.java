package com.solarized.firedown.ui;

import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.TypedArray;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.google.android.material.textfield.TextInputLayout;
import com.solarized.firedown.R;

import java.util.ArrayList;

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

        setDefaultHintTextColor(ColorStateList.valueOf(ContextCompat.getColor(context, R.color.md_theme_primaryContainer)));

        int errorTextColor = array.getColor(R.styleable.LoginPanelTextInputLayout_InputLayoutErrorTextColor, 0);

        int errorIconColor = array.getColor(R.styleable.LoginPanelTextInputLayout_InputLayoutErrorIconColor, 0);

        setErrorTextColor(ColorStateList.valueOf(ContextCompat.getColor(context, errorTextColor != 0 ? errorTextColor : R.color.md_theme_primaryContainer)));

        setErrorIconTintList(ColorStateList.valueOf(ContextCompat.getColor(context, errorTextColor != 0 ? errorIconColor : R.color.md_theme_primaryContainer)));

        array.recycle();

    }

}

