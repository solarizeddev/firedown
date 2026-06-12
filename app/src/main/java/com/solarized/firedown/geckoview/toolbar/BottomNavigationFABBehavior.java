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
 * PURE FOLLOWER: mirrors the {@link BottomNavigationBar}'s scroll-hide
 * translation onto the FAB 1:1 and snaps it fully clear once the bar is
 * essentially collapsed. The FAB's REST position is owned by LAYOUT, not by
 * this behavior — bottom gravity plus a code-set bottomMargin of
 * (nav inset + app_bar_fab_margin), the same Home/Tabs dock recipe, wired in
 * BrowserFragment. History: this behavior used to add a {@code restOffset}
 * lift on top of an anchored position, but anchoring broke when the
 * edge-to-edge bar's bottom edge moved to the true window bottom (the FAB
 * sank under the system nav), and an event-driven lift gives a
 * non-deterministic first frame — layout-owned rest position has neither
 * problem. {@code clearance} survives only in the snap-OUT target: the docked
 * FAB pokes above the bar's top edge, so translating by barHeight alone would
 * leave that overhang visible; barHeight + clearance clears it fully.
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

    /** Overhang clearance for the snap-out target (see class javadoc). */
    private final int clearance;

    @Nullable private Animator runningAnim;
    private boolean hidden = false;

    public BottomNavigationFABBehavior(@Nullable Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        clearance = context != null
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
            // barHeight alone leaves the docked FAB's above-the-bar overhang
            // visible; + clearance pushes it fully off-screen.
            snapTo(child, barHeight + clearance);
            return true;
        }
        if (hidden && collapse <= SHOW_AT) {
            hidden = false;
            // Back to the layout-owned rest position (translation 0).
            snapTo(child, 0f);
            return true;
        }
        if (runningAnim == null) {
            // Pure 1:1 tracking — the rest position lives in the layout
            // (gravity + bottomMargin), so the follower adds NO offset of
            // its own; the snap animations cover the clearance delta.
            child.setTranslationY(barTrans);
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
