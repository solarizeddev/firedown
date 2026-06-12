package com.solarized.firedown.geckoview.toolbar;


import android.content.Context;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.coordinatorlayout.widget.CoordinatorLayout;

import com.google.android.material.floatingactionbutton.FloatingActionButton;

import com.solarized.firedown.R;


/**
 * STATELESS CLAMPED FOLLOWER: the FAB mirrors the {@link BottomNavigationBar}'s
 * scroll-hide translation 1:1, floored at the PEEK position — the translation
 * that leaves a tiny sliver of the hero button visible at the bottom edge
 * (maintainer's call: "not hidden, not visible, tiny visible"; the sliver
 * remains tappable, opening the Captured sheet without re-summoning the bars).
 * Hiding: the FAB rides down with the bar and simply stops at the sliver.
 * Showing: it rises with the bar immediately. Wiggle: it moves WITH the bar,
 * coherently, never against it.
 *
 * <p>History — why no thresholds, no held state, no animator. A previous
 * version snapped between rest and peek on collapse-fraction thresholds
 * (0.95 / 0.05 of {@code bar.translationY / bar.height}) and HELD the peek
 * constant while "hidden". Both ideas failed on-device: the self-padded
 * toolbar travels {@code height + statusInset} while the viewport reclaims
 * only {@code height}, so a scroll that fully consumes the page parks the
 * bar mid-track (~73% collapsed) — the 0.95 snap-out never fired and the FAB
 * froze at an arbitrary height; symmetrically the 0.05 snap-in made the
 * sliver lag until the bar was almost fully re-shown. The clamp has neither
 * failure mode and needs no hysteresis: at every bar position the FAB has
 * exactly one correct place, {@code min(barTranslation, peekTranslation)}.
 *
 * <p>The FAB's REST position is owned by LAYOUT, not by this behavior —
 * bottom gravity plus a code-set bottomMargin of (nav inset +
 * app_bar_fab_margin), the same Home/Tabs dock recipe, wired in
 * BrowserFragment; the follower adds NO offset of its own. Fullscreen video
 * and find-in-page fully hide the FAB via {@code mDownloadButton.hide()}
 * regardless, so the peek never shows there.
 */
public final class BottomNavigationFABBehavior extends CoordinatorLayout.Behavior<FloatingActionButton> {

    /** Height of the FAB sliver left visible when the bars scroll away. */
    private final int peek;

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
        child.setTranslationY(0f);
    }

    @Override
    public boolean onDependentViewChanged(@NonNull CoordinatorLayout parent,
                                          @NonNull FloatingActionButton child,
                                          @NonNull View dependency) {
        if (dependency.getHeight() <= 0 || child.getHeight() <= 0) {
            return false;
        }
        float barTranslation = dependency.getTranslationY();
        child.setTranslationY(Math.min(barTranslation, peekTranslation(parent, child)));
        return true;
    }

    /**
     * Translation that leaves exactly {@code peek} px of the FAB visible
     * above the window's bottom edge. Derived from the LAYOUT geometry
     * (gravity-bottom + bottomMargin → the gap below the child is
     * {@code parentHeight − child.bottom}), so it is correct for any nav
     * inset, including zero (gesture-nav devices with no bar).
     */
    private float peekTranslation(@NonNull CoordinatorLayout parent,
                                  @NonNull FloatingActionButton child) {
        int marginBelow = parent.getHeight() - child.getBottom();
        return marginBelow + child.getHeight() - peek;
    }
}
