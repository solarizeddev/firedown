package com.solarized.firedown.geckoview.toolbar;


import android.animation.Animator;
import android.animation.ObjectAnimator;
import android.content.Context;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.coordinatorlayout.widget.CoordinatorLayout;

import com.google.android.material.floatingactionbutton.FloatingActionButton;


import com.solarized.firedown.R;


public final class BottomNavigationFABBehavior extends CoordinatorLayout.Behavior<FloatingActionButton> {

    private enum State { COLLAPSED, ANIMATING_COLLAPSE, EXPANDED, ANIMATING_EXPAND}

    private static final String TAG = BottomNavigationFABBehavior.class.getName();

    private static final int DURATION = 150;

    private static final float BASE_LIMIT = -0.0f;

    private static final float UPPER_LIMIT = 1.0f;

    private static final float DOWN_LIMIT = 0.95f;

    private final int mOffsetOriginal;

    private final int mActionBarSize;

    private final float mSlideDownThreshold;

    private State mState;

    private Animator mCurrentAnimator;

    private int mOffset;

    public BottomNavigationFABBehavior(@Nullable Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        assert context != null;
        mActionBarSize = context.getResources().getDimensionPixelSize(R.dimen.app_bar_size);
        mSlideDownThreshold = mActionBarSize * DOWN_LIMIT;
        mOffsetOriginal = context.getResources().getDimensionPixelOffset(R.dimen.app_bar_fab_margin);
        mOffset = mOffsetOriginal;
        mState = State.EXPANDED;
    }

    @Override
    public boolean layoutDependsOn(@Nullable CoordinatorLayout parent, @NonNull FloatingActionButton child, @NonNull View dependency) {
        return dependency instanceof BottomNavigationBar;
    }


    @Override
    public void onDependentViewRemoved(@NonNull CoordinatorLayout parent, @NonNull FloatingActionButton child, @NonNull View dependency) {
        child.setTranslationY(0.0f);
    }

    @Override
    public boolean onDependentViewChanged(@NonNull CoordinatorLayout parent, @NonNull FloatingActionButton child, @NonNull View dependency) {
        return updateButton(child, dependency);
    }


    private boolean updateButton(FloatingActionButton child, View dependency) {
        if (dependency instanceof BottomNavigationBar) {
            float oldTranslation = child.getTranslationY();
            float newTranslation = dependency.getTranslationY() - mOffset;
            if (shouldSlideDown(dependency)) {
                slideDown(child);
            } else if (shouldSlideUp(dependency)) {
                slideUp(child);
            }
            if (mState != State.ANIMATING_COLLAPSE && mState != State.ANIMATING_EXPAND) {
                child.setTranslationY(newTranslation);
            }
            return oldTranslation != newTranslation;
        } else {
            return false;
        }
    }

    private boolean shouldSlideDown(View dependency){
        float translationY = dependency.getTranslationY();
        return translationY <= mActionBarSize && translationY > mSlideDownThreshold && mState == State.EXPANDED;
    }

    private boolean shouldSlideUp(View dependency){
        float translationY = dependency.getTranslationY();
        return translationY >= BASE_LIMIT && translationY < UPPER_LIMIT && mState == State.COLLAPSED;
    }

    private void slideUp(FloatingActionButton child){


        if(mCurrentAnimator != null) {
            mCurrentAnimator.cancel();
            child.clearAnimation();
        }

        mCurrentAnimator = ObjectAnimator.ofFloat(child, "translationY", -mOffsetOriginal);
        mCurrentAnimator.addListener(mAnimatorListener);
        mCurrentAnimator.setDuration(DURATION);//set duration
        mCurrentAnimator.start();//start animation
        mState = State.ANIMATING_EXPAND;
    }

    private void slideDown(FloatingActionButton child) {


        if(mCurrentAnimator != null) {
            mCurrentAnimator.cancel();
            child.clearAnimation();
        }

        mCurrentAnimator = ObjectAnimator.ofFloat(child, "translationY", mActionBarSize);
        mCurrentAnimator.addListener(mAnimatorListener);
        mCurrentAnimator.setDuration(DURATION);//set duration
        mCurrentAnimator.start();//start animation
        mState = State.ANIMATING_COLLAPSE;


    }

    private final Animator.AnimatorListener mAnimatorListener = new Animator.AnimatorListener() {
        @Override
        public void onAnimationStart(@NonNull Animator animator) {
        }

        @Override
        public void onAnimationEnd(@NonNull Animator animator) {
            mCurrentAnimator = null;
            if(mState == State.ANIMATING_EXPAND) {
                mState = State.EXPANDED;
                mOffset = mOffsetOriginal;
            }
            else if(mState == State.ANIMATING_COLLAPSE) {
                mState = State.COLLAPSED;
                mOffset = 0;
            }
        }

        @Override
        public void onAnimationCancel(@NonNull Animator animator) {

        }

        @Override
        public void onAnimationRepeat(@NonNull Animator animator) {

        }
    };
}
