package com.solarized.firedown.geckoview.toolbar;


import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ObjectAnimator;
import android.content.Context;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.coordinatorlayout.widget.CoordinatorLayout;

import com.google.android.material.floatingactionbutton.FloatingActionButton;

import com.solarized.firedown.R;


/**
 * Couples the floating action button to the {@link BottomNavigationBar} so the
 * FAB tracks the bar's vertical translation (smoothly follows the bar as the
 * user scrolls the page) and snaps fully off-screen once the bar is essentially
 * collapsed. Snap-out is needed because the FAB sits {@code restOffset} above
 * the bar's top edge — pure tracking would leave that strip of FAB visible
 * when the bar is fully hidden.
 *
 * <p>Thresholds are expressed as a collapse fraction
 * ({@code bar.translationY / bar.height}) with hysteresis so the FAB doesn't
 * stutter when a drag wobbles across the boundary.
 */
public final class BottomNavigationFABBehavior extends CoordinatorLayout.Behavior<FloatingActionButton> {

    /** Snap the FAB out once the bar is this fraction collapsed. */
    private static final float HIDE_AT = 0.95f;
    /** Snap the FAB back in once the bar is this fraction expanded (≤). */
    private static final float SHOW_AT = 0.05f;
    private static final int   ANIM_DURATION_MS = 150;

    /** Distance the FAB sits above the bar's top edge when fully expanded. */
    private final int restOffset;

    @Nullable private Animator runningAnim;
    private boolean hidden = false;

    public BottomNavigationFABBehavior(@Nullable Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        restOffset = context != null
                ? context.getResources().getDimensionPixelOffset(R.dimen.app_bar_fab_margin)
                : 0;
    }

    @Override
    public boolean layoutDependsOn(@Nullable CoordinatorLayout parent,
                                   @NonNull FloatingActionButton child,
                                   @NonNull View dependency) {
        return dependency instanceof BottomNavigationBar;
    }

    @Override
    public void onDependentViewRemoved(@NonNull CoordinatorLayout parent,
                                       @NonNull FloatingActionButton child,
                                       @NonNull View dependency) {
        cancelAnim();
        child.setTranslationY(0f);
        hidden = false;
    }

    @Override
    public boolean onDependentViewChanged(@NonNull CoordinatorLayout parent,
                                          @NonNull FloatingActionButton child,
                                          @NonNull View dependency) {
        final int barHeight = dependency.getHeight();
        if (barHeight <= 0) return false;

        final float barTrans = dependency.getTranslationY();
        final float collapse = barTrans / barHeight;

        if (!hidden && collapse >= HIDE_AT) {
            hidden = true;
            // FAB bottom edge at the bar's bottom edge → fully off-screen.
            snapTo(child, barHeight);
            return true;
        }
        if (hidden && collapse <= SHOW_AT) {
            hidden = false;
            snapTo(child, -restOffset);
            return true;
        }
        if (runningAnim == null) {
            // Track the bar. The tracking offset depends on state: visible
            // keeps the FAB restOffset above the bar's top edge; hidden glues
            // the FAB to the bar (offset 0) so when the bar slides back up from
            // off-screen, the FAB rides along smoothly. The snap-in animation
            // covers the final restOffset lift once the bar is fully expanded.
            final float offset = hidden ? 0f : restOffset;
            child.setTranslationY(barTrans - offset);
            return true;
        }
        return false;
    }

    private void snapTo(@NonNull FloatingActionButton child, float target) {
        cancelAnim();
        final ObjectAnimator anim = ObjectAnimator.ofFloat(child, View.TRANSLATION_Y, target);
        anim.setDuration(ANIM_DURATION_MS);
        anim.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(@NonNull Animator a) {
                if (runningAnim == a) runningAnim = null;
            }
        });
        runningAnim = anim;
        anim.start();
    }

    private void cancelAnim() {
        if (runningAnim != null) {
            runningAnim.cancel();
            runningAnim = null;
        }
    }
}
