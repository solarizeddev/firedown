package com.solarized.firedown.geckoview.toolbar;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.coordinatorlayout.widget.CoordinatorLayout;

import com.solarized.firedown.geckoview.GeckoToolbar;

import java.util.List;


/**
 * {@link CoordinatorLayout.Behavior} for the bottom navigation bar ({@link BottomNavigationBar}):
 * a passive FOLLOWER of the top toolbar, not an independent scroll listener.
 *
 * <p>Matches Fenix's {@code NavbarToolbarSyncBehavior} (the top-toolbar + bottom-navbar
 * configuration of the 2024+ toolbar redesign): only the top toolbar owns scroll detection
 * ({@code GeckoToolbarBehavior} — gesture detector, nested-scroll handshake, snap logic), and
 * the bottom bar mirrors its translation through a CoordinatorLayout dependency.
 *
 * <p><b>Why this replaced the previous gesture-driven implementation.</b> The old
 * {@code BottomNavigationBehavior} was a full copy of {@code EngineViewScrollingGesturesBehavior}
 * with its own {@code BrowserGestureDetector} and {@code YTranslator}. Two independent
 * behaviors see the same gesture but snap on their <em>own</em> half-height: with unequal bar
 * heights (the bottom bar self-pads with the navigation-bar inset, so its runtime height can
 * exceed {@code app_bar_size}) a drag can end past the toolbar's halfway point but short of the
 * bottom bar's — one bar snaps open while the other snaps closed, a state Fenix's master/slave
 * design makes impossible. It also doubled every force-show call site (fullscreen,
 * {@code onShowDynamicToolbar}, …). Don't reintroduce a second gesture listener here.
 *
 * <p><b>Proportional, not a raw mirror.</b> Fenix slaves with
 * {@code child.translationY = -toolbar.translationY}, which assumes equal heights (its navbar
 * over-translates harmlessly when the toolbar is taller, but would never fully hide if the
 * toolbar were the shorter bar). We sync on the <em>hidden fraction</em> instead:
 * {@code child.translationY = (-toolbar.translationY / toolbarHeight) * childHeight}, so both
 * bars reach fully-hidden/fully-visible together regardless of heights, and
 * {@code NestedGeckoViewBehavior}'s clipping ({@code topTranslation - bottomTranslation})
 * lands exactly on {@code -dynamicToolbarMaxHeight} when hidden.
 *
 * <p>{@link #onLayoutChild} re-syncs from the toolbar's current state on every layout pass —
 * a bar coming back from GONE (fullscreen exit, find-in-page exit) would otherwise keep a
 * stale translation until the toolbar next moves, because {@code onDependentViewChanged}
 * only fires on dependency <em>changes</em>.
 */
public final class BottomNavigationBehavior
        extends CoordinatorLayout.Behavior<BottomNavigationBar> {

    @SuppressWarnings("unused")
    private static final String TAG = BottomNavigationBehavior.class.getName();


    public BottomNavigationBehavior(@Nullable Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
    }

    // ── CoordinatorLayout.Behavior ────────────────────────────────────────────────────────────────

    @Override
    public boolean layoutDependsOn(@NonNull CoordinatorLayout parent,
                                   @NonNull BottomNavigationBar child,
                                   @NonNull View dependency) {
        return dependency instanceof GeckoToolbar;
    }

    @Override
    public boolean onDependentViewChanged(@NonNull CoordinatorLayout parent,
                                          @NonNull BottomNavigationBar child,
                                          @NonNull View dependency) {
        return syncWithToolbar(child, dependency);
    }

    @Override
    public boolean onLayoutChild(@NonNull CoordinatorLayout parent,
                                 @NonNull BottomNavigationBar child,
                                 int layoutDirection) {
        parent.onLayoutChild(child, layoutDirection);
        List<View> dependencies = parent.getDependencies(child);
        for (int i = 0; i < dependencies.size(); i++) {
            View dependency = dependencies.get(i);
            if (dependency instanceof GeckoToolbar) {
                syncWithToolbar(child, dependency);
                break;
            }
        }
        return true;
    }

    // ── Internal ──────────────────────────────────────────────────────────────────────────────────

    /**
     * Mirrors the toolbar's hidden fraction onto the bottom bar.
     * Toolbar translationY ∈ [-toolbarHeight, 0] → bar translationY ∈ [0, barHeight].
     */
    private boolean syncWithToolbar(@NonNull BottomNavigationBar child, @NonNull View toolbar) {
        if (child.getVisibility() != View.VISIBLE) {
            return false;
        }
        int toolbarHeight = toolbar.getHeight();
        int childHeight   = child.getHeight();
        if (toolbarHeight <= 0 || childHeight <= 0) {
            return false;
        }
        float toolbarTranslationY = toolbar.getTranslationY();
        if (Float.isNaN(toolbarTranslationY)) {
            return false;
        }
        float hiddenFraction = Math.max(0f, Math.min(1f, -toolbarTranslationY / toolbarHeight));
        float target = hiddenFraction * childHeight;
        if (child.getTranslationY() == target) {
            return false;
        }
        child.setTranslationY(target);
        return true;
    }
}
