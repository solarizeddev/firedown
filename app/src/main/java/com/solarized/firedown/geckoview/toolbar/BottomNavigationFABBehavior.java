package com.solarized.firedown.geckoview.toolbar;


import android.content.Context;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.coordinatorlayout.widget.CoordinatorLayout;

import com.google.android.material.floatingactionbutton.FloatingActionButton;


/**
 * PROPORTIONAL FOLLOWER: maps the {@link BottomNavigationBar}'s hidden
 * FRACTION ({@code bar.translationY / bar.height}) onto the FAB's own
 * full-hide distance, so the FAB and the bar hide and show in lockstep and
 * the FAB ends FULLY off-screen when the bar does. The mapping must be
 * fractional, not a 1:1 translation copy: on a device with no nav inset
 * (gesture nav, e.g. Pixel-class) the bar's maximum travel is its own 64dp
 * height, but the FAB needs bottomMargin + its own height (~72dp) to clear
 * the window edge — copying the bar's translation verbatim left a sliver of
 * the button visible in the hidden-bars state (the original on-device bug).
 *
 * <p>History: a deliberate "peek" sliver (FAB parked 10dp visible as a
 * tappable handle) was tried and rejected — on gesture-nav devices it sat on
 * the system gesture pill and read as a clipped button, not an affordance;
 * the bottom bar's download badge is the real captured-content signal. A
 * threshold/snap state machine was also tried and rejected: its collapse
 * thresholds were fractions of the bar's FULL travel, which the dead-zone
 * (the self-padded toolbar travels height+statusInset while the viewport
 * reclaims only height) often leaves uncompleted, so the snaps fired late or
 * never. The stateless fraction map has neither failure mode: at every bar
 * position the FAB has exactly one correct place.
 *
 * <p>The FAB's REST position is owned by LAYOUT, not by this behavior —
 * bottom gravity plus a code-set bottomMargin of (nav inset +
 * app_bar_fab_margin), the same Home/Tabs dock recipe, wired in
 * BrowserFragment; at fraction 0 this follower sets translation 0 and adds
 * no offset of its own. Fullscreen video and find-in-page hide the FAB via
 * {@code mDownloadButton.hide()} regardless of scroll state.
 */
public final class BottomNavigationFABBehavior extends CoordinatorLayout.Behavior<FloatingActionButton> {

    public BottomNavigationFABBehavior(@Nullable Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
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
        child.setTranslationY(0f);
    }

    @Override
    public boolean onDependentViewChanged(@NonNull CoordinatorLayout parent,
                                          @NonNull FloatingActionButton child,
                                          @NonNull View dependency) {
        int barHeight = dependency.getHeight();
        if (barHeight <= 0 || child.getHeight() <= 0) {
            return false;
        }
        float fraction = dependency.getTranslationY() / barHeight;
        if (fraction < 0f) {
            fraction = 0f;
        } else if (fraction > 1f) {
            fraction = 1f;
        }
        child.setTranslationY(fraction * fullHideTranslation(parent, child));
        return true;
    }

    /**
     * Translation that puts the FAB fully below the window's bottom edge.
     * Derived from the LAYOUT geometry (gravity-bottom + bottomMargin → the
     * gap below the child is {@code parentHeight − child.bottom}), so it is
     * correct for any nav inset, including zero (gesture-nav devices with no
     * bar), where the bar's own travel alone is too short to cover it.
     */
    private float fullHideTranslation(@NonNull CoordinatorLayout parent,
                                      @NonNull FloatingActionButton child) {
        int marginBelow = parent.getHeight() - child.getBottom();
        return marginBelow + child.getHeight();
    }
}
