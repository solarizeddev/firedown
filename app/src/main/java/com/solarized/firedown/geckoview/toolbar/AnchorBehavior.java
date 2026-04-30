package com.solarized.firedown.geckoview.toolbar;

import android.content.Context;
import android.util.AttributeSet;
import android.util.Log;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.coordinatorlayout.widget.CoordinatorLayout;

import com.solarized.firedown.R;

public class AnchorBehavior extends CoordinatorLayout.Behavior<View> {

    private static final String TAG = AnchorBehavior.class.getSimpleName();

    private final int mOffset;

    public AnchorBehavior(@Nullable Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        mOffset = context.getResources().getDimensionPixelOffset(R.dimen.snack_bar_anchor_margin) + context.getResources().getDimensionPixelOffset(R.dimen.app_bar_size);
    }

    @Override
    public boolean onLayoutChild(@NonNull CoordinatorLayout parent, @NonNull View child, int layoutDirection){
        return super.onLayoutChild(parent, child, layoutDirection);
    }

    @Override
    public boolean layoutDependsOn(@Nullable CoordinatorLayout parent, @NonNull View child, @NonNull View dependency) {
        return dependency instanceof BottomNavigationBar;
    }


    @Override
    public boolean onDependentViewChanged(@NonNull CoordinatorLayout parent, @NonNull View child, @NonNull View dependency) {
        return updateLayout(child, dependency);
    }

    private boolean updateLayout(View child, View dependency) {
        if (dependency instanceof BottomNavigationBar) {
            float oldTranslation = child.getTranslationY();
            float newTranslation = dependency.getTranslationY();
            Log.d(TAG, "newTranslation: " + newTranslation);
            child.setTranslationY(newTranslation - mOffset);
            return oldTranslation != newTranslation;
        } else {
            return false;
        }
    }

}
