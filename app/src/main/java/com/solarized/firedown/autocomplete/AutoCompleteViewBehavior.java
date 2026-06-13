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

    public AutoCompleteViewBehavior(@Nullable Context context, @Nullable AttributeSet attrs, @NonNull final View parentView) {
        super(context, attrs);
        mAutoCompleteView = recursivelyFindAutoCompleteView(parentView);
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
            // The toolbar's height is read LIVE, not frozen at construction:
            // with the edge-to-edge browser the toolbar self-pads by the
            // status inset AFTER this behavior is built, so a constructor
            // snapshot would park the suggestions panel under the toolbar.
            float newToolbarTranslationY = dependency.getTranslationY();
            mAutoCompleteView.setTranslationY(newToolbarTranslationY + (float) dependency.getHeight());
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
