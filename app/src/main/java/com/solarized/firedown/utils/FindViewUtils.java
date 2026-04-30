package com.solarized.firedown.utils;

import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;

import androidx.annotation.Nullable;

import com.solarized.firedown.geckoview.GeckoToolbar;
import com.solarized.firedown.geckoview.NestedGeckoView;
import com.solarized.firedown.geckoview.toolbar.BottomNavigationBar;

public class FindViewUtils {

    public static GeckoToolbar recursivelyFindGeckoToolbar(View view) {
        if (view instanceof ViewGroup viewGroup) {
            //ViewGroup

            if (!(viewGroup instanceof GeckoToolbar)) {
                for (int i = 0; i < viewGroup.getChildCount(); i++) {
                    GeckoToolbar result = recursivelyFindGeckoToolbar(viewGroup.getChildAt(i));

                    if (result != null) {
                        return result;
                    }
                }
            } else {

                return (GeckoToolbar) viewGroup;
            }
        }
        return null;
    }

    @Nullable
    public static NestedGeckoView recursivelyFindGeckoView(View view) {
        if (view instanceof ViewGroup viewGroup) {
            //ViewGroup

            if (!(viewGroup instanceof NestedGeckoView)) {
                for (int i = 0; i < viewGroup.getChildCount(); i++) {
                    NestedGeckoView result = recursivelyFindGeckoView(viewGroup.getChildAt(i));

                    if (result != null) {
                        return result;
                    }
                }
            }else{

                return (NestedGeckoView) viewGroup;
            }
        }
        return null;
    }


    @Nullable
    public static ImageButton recursivelyFindImageButton(View view) {
        if (view instanceof ViewGroup viewGroup) {
            //ViewGroup

            for (int i = 0; i < viewGroup.getChildCount(); i++) {
                View result = viewGroup.getChildAt(i);

                if (result instanceof ImageButton)
                    return (ImageButton) result;

            }

        }
        return null;
    }

    @Nullable
    public static BottomNavigationBar recursivelyFindBottomNavigation(View view) {
        if (view instanceof ViewGroup viewGroup) {
            //ViewGroup

            if (!(viewGroup instanceof BottomNavigationBar)) {
                for (int i = 0; i < viewGroup.getChildCount(); i++) {
                    BottomNavigationBar result = recursivelyFindBottomNavigation(viewGroup.getChildAt(i));

                    if (result != null) {
                        return result;
                    }
                }
            }else{

                return (BottomNavigationBar) viewGroup;
            }
        }
        return null;
    }

}
