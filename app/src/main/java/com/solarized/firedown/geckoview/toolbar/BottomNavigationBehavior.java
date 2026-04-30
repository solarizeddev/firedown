package com.solarized.firedown.geckoview.toolbar;

import android.content.Context;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.coordinatorlayout.widget.CoordinatorLayout;
import androidx.core.view.ViewCompat;

import com.solarized.firedown.geckoview.BrowserGestureDetector;
import com.solarized.firedown.geckoview.NestedGeckoView;
import com.solarized.firedown.geckoview.ViewPosition;
import com.solarized.firedown.geckoview.YTranslator;
import com.solarized.firedown.utils.FindViewUtils;


/**
 * {@link CoordinatorLayout.Behavior} for the bottom navigation bar ({@link BottomNavigationBar}).
 *
 * <p>Matches upstream {@code EngineViewScrollingGesturesBehavior} from android-components.
 * See {@code GeckoToolbarBehavior} for a full description of the changes vs the previous
 * Firedown implementation — both behaviors are kept in sync with each other.
 *
 * <p>Summary of upstream alignment:
 * <ul>
 *   <li>{@code onNestedPreScroll} removed — translation driven by BrowserGestureDetector only.
 *   <li>{@code shouldScroll} requires {@code INPUT_HANDLED} (no UNKNOWN optimism).
 *   <li>{@code scrollConfirmed} guard removed — snap unconditionally on gesture end.
 *   <li>{@code startNestedScroll} no longer force-expands on {@code isTouchUnhandled}.
 *   <li>{@code onVerticalScroll} simplified — no force-expand fallback.
 * </ul>
 */
public final class BottomNavigationBehavior
        extends CoordinatorLayout.Behavior<BottomNavigationBar>
        implements BrowserGestureDetector.BrowserGestureListener {

    @SuppressWarnings("unused")
    private static final String TAG = BottomNavigationBehavior.class.getName();

    private final YTranslator           yTranslator;
    private final BrowserGestureDetector mBrowserGestureDetector;

    private NestedGeckoView    mNestedGeckoView;
    private BottomNavigationBar mBottomNavigationBar;

    private boolean shouldSnapAfterScroll = true;
    private boolean isScrollEnabled       = true;
    private boolean startedScroll         = false;


    public BottomNavigationBehavior(@Nullable Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        yTranslator             = new YTranslator(ViewPosition.BOTTOM);
        mBrowserGestureDetector = new BrowserGestureDetector(context, this);
    }

    // ── CoordinatorLayout.Behavior ────────────────────────────────────────────────────────────────

    @Override
    public boolean onLayoutChild(@NonNull CoordinatorLayout parent,
                                 @NonNull BottomNavigationBar child,
                                 int layoutDirection) {
        mBottomNavigationBar = child;
        mNestedGeckoView     = FindViewUtils.recursivelyFindGeckoView(parent);
        return super.onLayoutChild(parent, child, layoutDirection);
    }

    @Override
    public boolean onStartNestedScroll(@NonNull CoordinatorLayout coordinatorLayout,
                                       @NonNull BottomNavigationBar child,
                                       @NonNull View directTargetChild,
                                       @NonNull View target,
                                       int axes,
                                       int type) {
        if (mBottomNavigationBar != null) {
            return startNestedScroll(axes, type);
        }
        return false;
    }

    @Override
    public void onStopNestedScroll(@NonNull CoordinatorLayout coordinatorLayout,
                                   @NonNull BottomNavigationBar child,
                                   @NonNull View target,
                                   int type) {
        if (mBottomNavigationBar != null) {
            stopNestedScroll(type, child);
        }
    }

    // NOTE: onNestedPreScroll is intentionally NOT overridden here.

    @Override
    public boolean onInterceptTouchEvent(@NonNull CoordinatorLayout parent,
                                         @NonNull BottomNavigationBar child,
                                         @NonNull MotionEvent ev) {
        if (mBottomNavigationBar != null) {
            mBrowserGestureDetector.handleTouchEvent(ev);
        }
        return false;
    }

    // ── Public control API ────────────────────────────────────────────────────────────────────────

    public void forceExpand(View view)   { yTranslator.expandWithAnimation(view); }
    public void forceCollapse(View view) { yTranslator.collapseWithAnimation(view); }

    public void enableScrolling()  { isScrollEnabled = true; }
    public void disableScrolling() { isScrollEnabled = false; }

    // ── BrowserGestureDetector.BrowserGestureListener ────────────────────────────────────────────

    @Override public void onScroll(float distanceX, float distanceY) { /* unused */ }

    @Override
    public void onVerticalScroll(float distance) {
        if (mBottomNavigationBar != null) {
            tryToScrollVertically(distance);
        }
    }

    @Override public void onHorizontalScroll(float distance) { /* unused */ }

    @Override
    public void onScaleBegin(float scaleFactor) {
        if (mBottomNavigationBar != null) {
            yTranslator.snapImmediately(mBottomNavigationBar);
        }
    }

    @Override public void onScale(float scaleFactor)    { /* unused */ }
    @Override public void onScaleEnd(float scaleFactor) { /* unused */ }

    // ── Internal helpers ──────────────────────────────────────────────────────────────────────────

    private void tryToScrollVertically(float distance) {
        if (shouldScroll() && startedScroll) {
            yTranslator.translate(mBottomNavigationBar, distance);
        }
    }

    /**
     * Requires {@code INPUT_HANDLED} — matches upstream exactly.
     * No UNKNOWN optimism (removed from Firedown's earlier override).
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

    private void stopNestedScroll(int type, View view) {
        startedScroll = false;
        if (shouldSnapAfterScroll || type == ViewCompat.TYPE_NON_TOUCH) {
            yTranslator.snapWithAnimation(view);
        }
    }
}