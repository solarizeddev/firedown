package com.solarized.firedown.geckoview;

import android.content.Context;
import android.util.AttributeSet;
import android.util.Log;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import androidx.coordinatorlayout.widget.CoordinatorLayout;

import com.solarized.firedown.App;
import com.solarized.firedown.BuildConfig;
import com.solarized.firedown.utils.FindViewUtils;



/**
 * A {@link CoordinatorLayout.Behavior} applied to the SwipeRefreshLayout (the container that
 * wraps the {@link NestedGeckoView}) that coordinates toolbar translations with the GeckoView
 * surface and its compositor viewport.
 *
 * <p>Matches upstream {@code EngineViewClippingBehavior} from android-components.
 *
 * <p><b>Upstream alignment:</b>
 * <ul>
 *   <li>Top vs. bottom toolbar is distinguished by <em>position</em>
 *       ({@code dependency.top < parent.height / 2}) rather than by {@code instanceof} checks.
 *       This matches the upstream comment: "This method will be sequentially called with
 *       BrowserToolbar and ToolbarContainerView as dependencies".
 *   <li>All other logic (NaN guard, clipping formula, safety-snap) is unchanged from
 *       the well-tested Firedown implementation which already matched upstream precisely.
 * </ul>
 *
 * <p><b>Surface position</b> – As the top toolbar slides out of view the SwipeRefreshLayout
 * moves upward so the content fills the revealed space:
 * {@code translationY = topTranslation + topToolbarHeight}.
 *
 * <p><b>Compositor clipping</b> – Formula (from upstream):
 * <pre>
 *     clipping = round(topTranslation - bottomTranslation)
 * </pre>
 * Result grows from 0 toward {@code -dynamicToolbarMaxHeight} as bars hide.
 */
public class NestedGeckoViewBehavior extends CoordinatorLayout.Behavior<View> {

    private static final String TAG = NestedGeckoViewBehavior.class.getSimpleName();

    private final NestedGeckoView mNestedGeckoView;
    private final View             mEngineViewParent;

    private final int mTopToolbarHeight;
    private final int mBottomToolbarHeight;

    /** {@code dynamicToolbarMaxHeight = topHeight + bottomHeight} */
    private final int mDynamicToolbarMaxHeight;

    /** Most recent translationY of the top toolbar. Range: [{@code -mTopToolbarHeight}, 0]. */
    @VisibleForTesting
    float mTopBarTranslationY = 0f;

    /** Most recent translationY of the bottom bar. Range: [0, {@code mBottomToolbarHeight}]. */
    @VisibleForTesting
    float mBottomBarTranslationY = 0f;

    public NestedGeckoViewBehavior(
            @NonNull Context context,
            @Nullable AttributeSet attrs,
            @NonNull View engineViewParent,
            int topToolbarHeight,
            int bottomToolbarHeight) {
        super(context, attrs);
        mEngineViewParent      = engineViewParent;
        mNestedGeckoView       = FindViewUtils.recursivelyFindGeckoView(engineViewParent);
        mTopToolbarHeight      = topToolbarHeight;
        mBottomToolbarHeight   = bottomToolbarHeight;
        mDynamicToolbarMaxHeight = topToolbarHeight + bottomToolbarHeight;
    }

    @Override
    public boolean layoutDependsOn(
            @NonNull CoordinatorLayout parent,
            @NonNull View child,
            @NonNull View dependency) {
        if (isToolbar(dependency)) {
            // Upstream calls applyUpdatesDependentViewChanged() here, not just returns true.
            // This is critical: layoutDependsOn fires on every layout pass, including the first
            // pass after the behavior is re-attached (e.g. after exiting fullscreen). Without
            // this call, applyUpdates is never invoked after re-attachment because
            // onDependentViewChanged only fires when a dependency's position *changes* —
            // if the toolbars are already at their final translationY (forceExpand finished),
            // onDependentViewChanged never fires and the SRL translationY and setVerticalClipping
            // are never recomputed, leaving a gap between the toolbar and the webpage.
            applyFromDependency(parent, dependency);
            return true;
        }
        return super.layoutDependsOn(parent, child, dependency);
    }

    /**
     * Syncs toolbar translation state and applies surface/clipping updates from a dependency.
     * Called from both {@link #layoutDependsOn} (initial layout) and
     * {@link #onDependentViewChanged} (subsequent changes) — mirrors upstream
     * {@code applyUpdatesDependentViewChanged}.
     */
    private void applyFromDependency(@NonNull CoordinatorLayout parent, @NonNull View dependency) {
        if (mNestedGeckoView == null) return;

        float translationY = dependency.getTranslationY();
        if (Float.isNaN(translationY)) {
            Log.w(TAG, "Ignoring NaN translationY from " + dependency.getClass().getSimpleName());
            return;
        }

        boolean dependencyAtTop = dependency.getTop() < parent.getHeight() / 2;
        if (dependencyAtTop) {
            mTopBarTranslationY = Math.max(-mTopToolbarHeight, Math.min(0f, translationY));
        } else {
            mBottomBarTranslationY = Math.max(0f, Math.min(mBottomToolbarHeight, translationY));
        }

        applyUpdates(mEngineViewParent);
    }

    @Override
    public boolean onDependentViewChanged(
            @NonNull CoordinatorLayout parent,
            @NonNull View child,
            @NonNull View dependency) {
        // Delegate to the same path used by layoutDependsOn — single source of truth.
        applyFromDependency(parent, dependency);
        return false;
    }

    /**
     * Recomputes and applies surface position and compositor clipping.
     * Extracted for testability — matches upstream {@code applyUpdatesDependentViewChanged}.
     */
    @VisibleForTesting
    void applyUpdates(@NonNull View engineViewParent) {

        // ── 1. Surface position ───────────────────────────────────────────────────────────────────
        //
        //   • Fully visible (topTranslation = 0):          surface at y = +topHeight (below bar)
        //   • Fully hidden  (topTranslation = -topHeight): surface at y = 0 (fills screen)
        engineViewParent.setTranslationY(mTopBarTranslationY + mTopToolbarHeight);

        // ── 2. Compositor clipping ────────────────────────────────────────────────────────────────
        //
        // Formula from upstream EngineViewClippingBehavior:
        //   clipping = topTranslation - bottomTranslation
        //
        //   topTranslation    ∈ [-topH, 0]      → negative contribution as top bar hides
        //   bottomTranslation ∈ [0, +bottomH]   → negative contribution as bottom bar hides
        //   → result grows from 0 toward -dynamicToolbarMaxHeight
        float rawClipping = mTopBarTranslationY - mBottomBarTranslationY;

        // Safety snap: if within 2px of fully hidden, snap to exact value.
        // Prevents tiny floating-point residuals leaving a 1-2px Gecko rendering gap.
        // See upstream bug 2005988.
        int safeClipping;
        if (Math.abs(mDynamicToolbarMaxHeight + rawClipping) <= 2f) {
            safeClipping = -mDynamicToolbarMaxHeight;
        } else {
            safeClipping = Math.round(rawClipping);
        }

        if(BuildConfig.DEBUG)
            Log.d(TAG, "applyUpdates:"
                    + " topTY="     + mTopBarTranslationY
                    + " bottomTY="  + mBottomBarTranslationY
                    + " surfaceY="  + (mTopBarTranslationY + mTopToolbarHeight)
                    + " clipping="  + safeClipping);

        mNestedGeckoView.setVerticalClipping(safeClipping);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────────────────────────

    /**
     * Returns {@code true} for views that act as scrollable toolbars in our layout.
     * Mirrors upstream's {@code ScrollableToolbar} interface check.
     */
    private boolean isToolbar(View v) {
        return v instanceof GeckoToolbar
                || v instanceof com.solarized.firedown.geckoview.toolbar.BottomNavigationBar;
    }
}