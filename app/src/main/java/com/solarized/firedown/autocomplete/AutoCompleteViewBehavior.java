package com.solarized.firedown.autocomplete;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.coordinatorlayout.widget.CoordinatorLayout;

import com.solarized.firedown.geckoview.GeckoToolbar;


public class AutoCompleteViewBehavior extends CoordinatorLayout.Behavior<View> {

    private final AutoCompleteView mAutoCompleteView;

    private final int mToolbarHeight;

    public AutoCompleteViewBehavior(@Nullable Context context, @Nullable AttributeSet attrs, @NonNull final View parentView, final int toolbarHeight) {
        super(context, attrs);
        mAutoCompleteView = recursivelyFindAutoCompleteView(parentView);
        mToolbarHeight = toolbarHeight;
    }

    @Override
    public boolean layoutDependsOn(@NonNull CoordinatorLayout parent, @NonNull View child, @NonNull View dependency) {

        if(dependency instanceof GeckoToolbar){
            return true;
        }

        return super.layoutDependsOn(parent, child, dependency);
    }

    @Override
    public boolean onDependentViewChanged(@NonNull CoordinatorLayout parent, @NonNull View child, @NonNull View dependency){
        if(mAutoCompleteView != null){
            float newToolbarTranslationY = dependency.getTranslationY();
            mAutoCompleteView.setTranslationY(newToolbarTranslationY + (float)mToolbarHeight);
        }
        return true;
    }


    @Nullable
    private static AutoCompleteView recursivelyFindAutoCompleteView(View view) {
        if (view instanceof ViewGroup viewGroup) {
            //ViewGroup

            if (!(viewGroup instanceof AutoCompleteView)) {
                for (int i = 0; i < viewGroup.getChildCount(); i++) {
                    AutoCompleteView result = recursivelyFindAutoCompleteView(viewGroup.getChildAt(i));

                    if (result != null) {
                        return result;
                    }
                }
            }else{

                return (AutoCompleteView) viewGroup;
            }
        }
        return null;
    }




}
