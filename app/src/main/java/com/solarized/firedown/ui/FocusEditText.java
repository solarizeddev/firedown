package com.solarized.firedown.ui;

import static android.content.Context.INPUT_METHOD_SERVICE;

import android.content.Context;
import android.util.AttributeSet;
import android.util.Log;
import android.os.Build;
import android.view.ViewTreeObserver;
import android.view.WindowInsetsController;
import android.view.inputmethod.InputMethodManager;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.AppCompatEditText;

import com.solarized.firedown.utils.BuildUtils;

public class FocusEditText extends AppCompatEditText {

    private static final String TAG = FocusEditText.class.getSimpleName();

    protected InputMethodManager mInputMethodManager;

    public FocusEditText(@NonNull Context context) {
        super(context);
        init(context);
    }

    public FocusEditText(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    public FocusEditText(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init(context);
    }

    public void init(Context context){

        mInputMethodManager = (InputMethodManager)context.getSystemService(INPUT_METHOD_SERVICE);
    }

    public void restartInput() {
        if(mInputMethodManager != null)
            mInputMethodManager.restartInput(this);
    }

    public void showKeyboardNow(){
        if (hasFocus()) {
            post(() -> {
                Log.d(TAG, "showKeyboardNow");
                try {
                    if (!hasFocus()) return;
                    restartInput();
                    // Use WindowInsetsController on API 30+ for reliable keyboard show
                    if (BuildUtils.hasAndroidR()) {
                        WindowInsetsController wic = getWindowInsetsController();
                        if (wic != null) {
                            wic.show(android.view.WindowInsets.Type.ime());
                            return;
                        }
                    }
                    // Fallback for older APIs
                    if (mInputMethodManager != null)
                        mInputMethodManager.showSoftInput(FocusEditText.this, InputMethodManager.SHOW_FORCED);
                } catch (NullPointerException e) {
                    // See bug 782096 for details
                }
            });
        }
    }

    public void focusAndShowKeyboard(){

        // Use requestFocusFromTouch so Android treats this as a user-initiated
        // focus event, making the IME more willing to show the keyboard.
        requestFocusFromTouch();

        if(hasWindowFocus()){
            showKeyboardNow();
        }else{
            getViewTreeObserver().addOnWindowFocusChangeListener(new ViewTreeObserver.OnWindowFocusChangeListener() {
                @Override
                public void onWindowFocusChanged(boolean hasFocus) {
                    if (hasFocus) {
                        Log.d(TAG, "onWindowsFocusChange");
                        showKeyboardNow();
                        // It’s very important to remove this listener once we are done.
                        getViewTreeObserver().removeOnWindowFocusChangeListener(this);
                    }
                }
            });
        }

    }
}
