package com.solarized.firedown.ui.browser;

import android.content.Context;
import android.util.AttributeSet;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.solarized.firedown.R;


public class ReloadBrowserButton extends BasicBrowserButton {

    public ReloadBrowserButton(@NonNull Context context) {
        super(context);
    }

    public ReloadBrowserButton(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);

    }

    public ReloadBrowserButton(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public void setLoading(boolean value){
        setIconResource(value ? R.drawable.ic_clear_24 : R.drawable.ic_refresh_24);
        setId(value ? R.id.popup_stop : R.id.popup_reload);
        setText(value ? R.string.browser_menu_stop : R.string.browser_menu_refresh) ;
    }
}
