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
 * translation onto the FAB 1:1 while the bar is visible, and parks the FAB
 * at a deliberate PEEK once the bar is essentially collapsed — a tiny
 * sliver of the hero button stays visible at the bottom edge as a handle
 * (maintainer's call: "not hidden, not visible, tiny visible"; it remains
 * tappable, opening the Captured sheet without re-summoning the bars). The
 * FAB's REST position is owned by LAYOUT, not by this behavior — bottom
 * gravity plus a code-set bottomMargin of (nav inset + app_bar_fab_margin),
 * the same Home/Tabs dock recipe, wired in BrowserFragment. History: this
 * behavior used to add a {@code restOffset} lift on top of an anchored
 * position, but anchoring broke when the edge-to-edge bar's bottom edge
 * moved to the true window bottom (the FAB sank under the system nav), and
 * an event-driven lift gives a non-deterministic first frame — layout-owned
 * rest has neither problem.
 *
 * <p>The peek position is HELD CONSTANT while hidden, never re-derived from
 * the bar's translation: the hidden bar can still wiggle a few px (the
 * inset dead-zone of the self-padded toolbar's extra travel), and 1:1
 * tracking in the hidden state turned that wiggle into a bobbing sliver
 * (observed on a Pixel-class device). Fullscreen video and find-in-page
 * fully hide the FAB via {@code mDownloadButton.hide()} regardless, so the
 * peek never shows there.
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

    /** Height of the FAB sliver left visible in the hidden (peek) state. */
    private final int peek;

    @Nullable private Animator runningAnim;
    private boolean hidden = false;

    public BottomNavigationFABBehavior(@Nullable Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        peek = context != null
                ? context.getResources().getDimensionPixelOffset(R.dimen.app_bar_fab_peek)
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
            snapTo(child, peekTranslation(parent, child));
            return true;
        }
        if (hidden && collapse <= SHOW_AT) {
            hidden = false;
            // Back to the layout-owned rest position (translation 0).
            snapTo(child, 0f);
            return true;
        }
        if (runningAnim == null) {
            if (hidden) {
                // Hold the peek steady — re-deriving from the bar's
                // translation here turned the dead-zone wiggle into a
                // bobbing sliver (see class javadoc).
                child.setTranslationY(peekTranslation(parent, child));
            } else {
                // Pure 1:1 tracking — the rest position lives in the layout
                // (gravity + bottomMargin), so the follower adds NO offset
                // of its own; the snap animations cover the peek delta.
                child.setTranslationY(barTrans);
            }
            return true;
        }
        return false;
    }

    /**
     * Translation that leaves exactly {@code peek} px of the FAB visible
     * above the window's bottom edge. Derived from the LAYOUT geometry
     * (gravity-bottom + bottomMargin → the gap below the child is
     * {@code parentHeight − child.bottom}), so it is correct for any nav
     * inset, including zero (gesture-nav devices with no bar), where the
     * old fixed-distance snap mis-aimed.
     */
    private float peekTranslation(@NonNull CoordinatorLayout parent,
                                  @NonNull FloatingActionButton child) {
        int marginBelow = parent.getHeight() - child.getBottom();
        return marginBelow + child.getHeight() - peek;
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
