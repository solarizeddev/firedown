package com.solarized.firedown.geckoview.toolbar;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.coordinatorlayout.widget.CoordinatorLayout;

import com.solarized.firedown.R;

public class AnchorBehavior extends CoordinatorLayout.Behavior<View> {

    private static final String TAG = AnchorBehavior.class.getSimpleName();

    /**
     * Gap between the anchor and the bottom bar's top edge. The bar's own
     * height is read LIVE from the dependency, not from app_bar_size: with
     * the edge-to-edge browser the bar self-pads by the nav inset, so its
     * real height is app_bar_size + inset — a hardcoded dimen would park
     * the anchor (and every snackbar on it) inside the bar.
     */
    private final int mMargin;

    public AnchorBehavior(@Nullable Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        mMargin = context.getResources().getDimensionPixelOffset(R.dimen.snack_bar_anchor_margin);
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
            child.setTranslationY(newTranslation - dependency.getHeight() - mMargin);
            return oldTranslation != newTranslation;
        } else {
            return false;
        }
    }

}
