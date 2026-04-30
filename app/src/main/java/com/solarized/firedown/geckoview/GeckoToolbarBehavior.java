package com.solarized.firedown.geckoview;

import android.content.Context;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.coordinatorlayout.widget.CoordinatorLayout;
import androidx.core.view.ViewCompat;

import com.solarized.firedown.utils.FindViewUtils;


/**
 * {@link CoordinatorLayout.Behavior} for the top toolbar ({@link GeckoToolbar}).
 *
 * <p>Matches upstream {@code EngineViewScrollingGesturesBehavior} from android-components.
 * Key changes vs previous Firedown implementation:
 *
 * <ul>
 *   <li><b>{@code onNestedPreScroll} removed.</b> Upstream drives bar translation exclusively
 *       through {@code BrowserGestureDetector → onVerticalScroll → tryToScrollVertically},
 *       not through the NestedScrolling protocol.  The previous Firedown onNestedPreScroll
 *       override was a workaround for missing MOVE events; the correct fix is that
 *       {@link NestedGeckoView} only calls {@code requestDisallowInterceptTouchEvent(true)} on
 *       {@code ACTION_DOWN}, and the APZ callback may clear it — restoring normal intercept
 *       flow for the behaviors.
 *   <li><b>{@code shouldScroll} no longer accepts UNKNOWN.</b> Upstream requires
 *       {@code canScrollToBottom() || canScrollToTop()} (both need {@code INPUT_HANDLED}).
 *   <li><b>{@code scrollConfirmed} guard removed.</b> Upstream's {@code stopNestedScroll} always
 *       snaps if {@code shouldSnapAfterScroll} or {@code TYPE_NON_TOUCH}, without the extra flag.
 *   <li><b>{@code startNestedScroll} no longer force-expands on {@code isTouchUnhandled}.</b>
 *       The force-expand path was Firedown-specific; upstream simply returns false.
 *   <li><b>{@code onVerticalScroll} simplified.</b> Upstream calls
 *       {@code tryToScrollVertically} (scroll if conditions met) with no force-expand fallback.
 * </ul>
 */
public class GeckoToolbarBehavior extends CoordinatorLayout.Behavior<GeckoToolbar>
        implements BrowserGestureDetector.BrowserGestureListener {

    @SuppressWarnings("unused")
    private final static String TAG = GeckoToolbarBehavior.class.getName();

    private final YTranslator yTranslator;
    private final BrowserGestureDetector mBrowserGestureDetector;

    private NestedGeckoView mNestedGeckoView;
    private GeckoToolbar    mGeckoToolbar;

    private boolean shouldSnapAfterScroll = true;
    private boolean isScrollEnabled       = true;
    private boolean startedScroll         = false;


    public GeckoToolbarBehavior(@Nullable Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        yTranslator          = new YTranslator(ViewPosition.TOP);
        mBrowserGestureDetector = new BrowserGestureDetector(context, this);
    }

    // ── CoordinatorLayout.Behavior ────────────────────────────────────────────────────────────────

    @Override
    public boolean onLayoutChild(@NonNull CoordinatorLayout parent,
                                 @NonNull GeckoToolbar child,
                                 int layoutDirection) {
        mGeckoToolbar    = child;
        mNestedGeckoView = FindViewUtils.recursivelyFindGeckoView(parent);
        return super.onLayoutChild(parent, child, layoutDirection);
    }

    @Override
    public boolean onStartNestedScroll(@NonNull CoordinatorLayout coordinatorLayout,
                                       @NonNull GeckoToolbar child,
                                       @NonNull View directTargetChild,
                                       @NonNull View target,
                                       int axes,
                                       int type) {
        if (mGeckoToolbar != null) {
            return startNestedScroll(axes, type);
        }
        return false;
    }

    @Override
    public void onStopNestedScroll(@NonNull CoordinatorLayout coordinatorLayout,
                                   @NonNull GeckoToolbar child,
                                   @NonNull View target,
                                   int type) {
        if (mGeckoToolbar != null) {
            stopNestedScroll(type, child);
        }
    }

    // NOTE: onNestedPreScroll is intentionally NOT overridden here.
    // Upstream drives translation only via BrowserGestureDetector → onVerticalScroll.

    @Override
    public boolean onInterceptTouchEvent(@NonNull CoordinatorLayout parent,
                                         @NonNull GeckoToolbar child,
                                         @NonNull MotionEvent ev) {
        if (mGeckoToolbar != null) {
            mBrowserGestureDetector.handleTouchEvent(ev);
        }
        return false; // never steal — let events reach NestedGeckoView
    }

    // ── Public control API ────────────────────────────────────────────────────────────────────────

    public void forceExpand(GeckoToolbar toolbar) {
        yTranslator.expandWithAnimation(toolbar);
    }

    public void forceCollapse(GeckoToolbar toolbar) {
        yTranslator.collapseWithAnimation(toolbar);
    }

    public void enableScrolling()  { isScrollEnabled = true; }
    public void disableScrolling() { isScrollEnabled = false; }

    // ── BrowserGestureDetector.BrowserGestureListener ────────────────────────────────────────────

    @Override
    public void onScroll(float distanceX, float distanceY) { /* unused */ }

    @Override
    public void onVerticalScroll(float distance) {
        // Mirrors upstream tryToScrollVertically — translate only when all conditions are met.
        if (mGeckoToolbar != null) {
            tryToScrollVertically(distance);
        }
    }

    @Override
    public void onHorizontalScroll(float distance) { /* unused */ }

    @Override
    public void onScaleBegin(float scaleFactor) {
        // Scale shouldn't animate the toolbar, but a small Y translation from a prior scroll is
        // possible — snap immediately to avoid a jarring animation when the scale gesture starts.
        if (mGeckoToolbar != null) {
            yTranslator.snapImmediately(mGeckoToolbar);
        }
    }

    @Override
    public void onScale(float scaleFactor) { /* unused */ }

    @Override
    public void onScaleEnd(float scaleFactor) { /* unused */ }

    // ── Internal helpers ──────────────────────────────────────────────────────────────────────────

    /**
     * Translate the toolbar by {@code distance} if scrolling conditions are met.
     * Mirrors upstream {@code tryToScrollVertically}.
     */
    private void tryToScrollVertically(float distance) {
        if (shouldScroll() && startedScroll) {
            yTranslator.translate(mGeckoToolbar, distance);
        }
    }

    /**
     * Whether the current gesture should be allowed to scroll the toolbar.
     *
     * <p>Matches upstream exactly: requires {@code INPUT_HANDLED} with scroll directions set.
     * The previous Firedown code also returned {@code true} for {@code UNKNOWN}; that optimism
     * caused the toolbar to move for APZ-owned gestures (e.g. YouTube Shorts) before the real
     * result arrived.
     */
    private boolean shouldScroll() {
        if (mNestedGeckoView != null) {
            return (mNestedGeckoView.canScrollBottom() || mNestedGeckoView.canScrollTop())
                    && isScrollEnabled;
        }
        return false;
    }

    private boolean startNestedScroll(int axes, int type) {
        if (shouldScroll() && axes == ViewCompat.SCROLL_AXIS_VERTICAL) {
            startedScroll         = true;
            shouldSnapAfterScroll = (type == ViewCompat.TYPE_TOUCH);
            yTranslator.cancelInProgressTranslation();
            return true;
        }
        return false;
    }

    private void stopNestedScroll(int type, GeckoToolbar toolbar) {
        startedScroll = false;
        // Snap unconditionally if the gesture was touch-driven or fling-driven.
        // Upstream does not use a scrollConfirmed guard.
        if (shouldSnapAfterScroll || type == ViewCompat.TYPE_NON_TOUCH) {
            yTranslator.snapWithAnimation(toolbar);
        }
    }
}