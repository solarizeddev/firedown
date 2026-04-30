package com.solarized.firedown.geckoview;

import android.content.Context;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.solarized.firedown.App;

public class GeckoSwipeRefreshLayout extends SwipeRefreshLayout {

    private boolean isQuickScaleInProgress = false;

    private final QuickScaleEvents quickScaleEvents = new QuickScaleEvents();

    private final int doubleTapTimeout = ViewConfiguration.getDoubleTapTimeout();

    private final int doubleTapSlop = ViewConfiguration.get(App.getAppContext()).getScaledDoubleTapSlop();

    private final int doubleTapSlopSquare = doubleTapSlop * doubleTapSlop;

    private float previousX = 0f;

    private float previousY = 0f;

    // Set to true as soon as multiple pointers are detected, and kept true for the rest of
    // the gesture even after fingers lift back to one. This ensures a pinch/zoom sequence
    // (e.g. on YouTube Shorts) never triggers pull-to-refresh mid-gesture.
    private boolean hadMultiTouch;

    private boolean disallowInterceptTouchEvent;


    public GeckoSwipeRefreshLayout(@NonNull Context context) {
        super(context);
    }

    public GeckoSwipeRefreshLayout(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
    }


    @Override
    public boolean onInterceptTouchEvent(MotionEvent event) {
        // Setting "isEnabled = false" is recommended for users of this ViewGroup
        // who are not interested in the pull to refresh functionality.
        // Setting this easily avoids executing code unnecessarily before the check for "canChildScrollUp".
        if (!isEnabled() || disallowInterceptTouchEvent) {
            return false;
        }

        if (MotionEvent.ACTION_DOWN == event.getAction()) {
            // Reset multi-touch tracking at the start of each new gesture.
            hadMultiTouch = false;
        }

        // Layman's scale gesture (with two fingers) detector.
        // Allows for quick, serial inference as opposed to using ScaleGestureDetector
        // which uses callbacks and would be hard to synchronize in the little time we have.
        //
        // Checking hadMultiTouch in the same condition ensures the remainder of this gesture
        // sequence (all subsequent MOVE/UP events) continues to be suppressed even after the
        // second finger lifts and pointerCount drops back to 1.
        if (event.getPointerCount() > 1 || hadMultiTouch) {
            hadMultiTouch = true;
            return false;
        }

        int eventAction = event.getAction();

        // Cleanup if the gesture has been aborted or quick scale just ended.
        if (MotionEvent.ACTION_CANCEL == eventAction ||
                (MotionEvent.ACTION_UP == eventAction && isQuickScaleInProgress)
        ) {
            forgetQuickScaleEvents();
            return super.onInterceptTouchEvent(event);
        }

        // Disable pull to refresh if quick scale is in progress.
        maybeAddDoubleTapEvent(event);

        if (isQuickScaleInProgress(quickScaleEvents)) {
            isQuickScaleInProgress = true;
            return false;
        }

        // Disable pull to refresh if the move was more on the X axis.
        if (MotionEvent.ACTION_DOWN == eventAction) {
            previousX = event.getX();
            previousY = event.getY();
        } else if (MotionEvent.ACTION_MOVE == eventAction) {
            float xDistance = Math.abs(event.getX() - previousX);
            float yDistance = Math.abs(event.getY() - previousY);
            previousX = event.getX();
            previousY = event.getY();
            if (xDistance > yDistance) {
                return false;
            }
        }

        return super.onInterceptTouchEvent(event);
    }


    // Intentionally mirrors upstream VerticalSwipeRefreshLayout.onStartNestedScroll:
    //
    // When pull-to-refresh IS enabled: SwipeRefreshLayout drives P2R through raw touch
    // interception (its own internal mechanism). If we also accepted nested scrolls,
    // descendants scrolling vertically would double-trigger the P2R throbber and compete
    // with the overscroll shadow. Return false to opt out of nested scroll entirely.
    //
    // When pull-to-refresh is NOT enabled (isEnabled=false): we are acting as a plain
    // container. Delegate to super so the NestedScrollingParent chain works correctly
    // for GeckoToolbarBehavior and BottomNavigationBehavior which depend on it.
    //
    // The previous code had this inverted (returning false when !isEnabled), which blocked
    // nested scrolls exactly when P2R was disabled — the opposite of correct behaviour.
    @Override
    public boolean onStartNestedScroll(View child, View target, int nestedScrollAxes) {
        if (isEnabled()) {
            return false;
        }
        return super.onStartNestedScroll(child, target, nestedScrollAxes);
    }

    private void maybeAddDoubleTapEvent(MotionEvent event) {
        int currentEventAction = event.getAction();

        // A double tap event must follow the order:
        // ACTION_DOWN - ACTION_UP - ACTION_DOWN
        // all these events happening in an interval defined by a system constant - DOUBLE_TAP_TIMEOUT

        if (MotionEvent.ACTION_DOWN == currentEventAction) {
            if (quickScaleEvents.upEvent != null) {
                if (event.getEventTime() - quickScaleEvents.upEvent.getEventTime() > doubleTapTimeout) {
                    // Too much time passed for the MotionEvents sequence to be considered
                    // a quick scale gesture. Restart counting.
                    forgetQuickScaleEvents();
                    quickScaleEvents.firstDownEvent = MotionEvent.obtain(event);
                } else {
                    quickScaleEvents.secondDownEvent = MotionEvent.obtain(event);
                }
            } else {
                // This may be the first time the user touches the screen or
                // the gesture was not finished with ACTION_UP.
                forgetQuickScaleEvents();
                quickScaleEvents.firstDownEvent = MotionEvent.obtain(event);
            }
        }
        // For the double tap events series we need ACTION_DOWN first
        // and then ACTION_UP second.
        else if (MotionEvent.ACTION_UP == currentEventAction && quickScaleEvents.firstDownEvent != null) {
            quickScaleEvents.upEvent = MotionEvent.obtain(event);
        }
    }

    // Intentionally does NOT call super(), matching upstream VerticalSwipeRefreshLayout.
    //
    // We only need to stop *this* view from intercepting when NestedGeckoView signals that
    // the gesture belongs to GeckoView/APZ (e.g. YouTube Shorts navigation). Propagating
    // the disallow request further up to our parent CoordinatorLayout would unnecessarily
    // suppress its behaviors' onInterceptTouchEvent path. While bar translation now runs
    // through onNestedPreScroll (immune to the interception flag), other CoordinatorLayout
    // behaviors (e.g. scale-gesture detection via BrowserGestureDetector) still use the
    // intercept path from the parent chain.
    //
    // Upstream rationale (android-components VerticalSwipeRefreshLayout):
    //   "We don't want to propagate the request to the parent, because they may use the
    //    gesture for other purpose, like propagating it to ToolbarBehavior."
    @Override
    public void requestDisallowInterceptTouchEvent(boolean b) {
        disallowInterceptTouchEvent = b;
        // super() deliberately NOT called — see comment above.
    }


    private void forgetQuickScaleEvents() {
        if (quickScaleEvents.firstDownEvent != null)
            quickScaleEvents.firstDownEvent.recycle();
        if (quickScaleEvents.upEvent != null)
            quickScaleEvents.upEvent.recycle();
        if (quickScaleEvents.secondDownEvent != null) {
            quickScaleEvents.secondDownEvent.recycle();
        }
        quickScaleEvents.firstDownEvent = null;
        quickScaleEvents.upEvent = null;
        quickScaleEvents.secondDownEvent = null;
        isQuickScaleInProgress = false;
    }

    private boolean isQuickScaleInProgress(QuickScaleEvents events) {
        if (events.isNotNull()) {
            return isQuickScaleInProgress(events.firstDownEvent, events.upEvent, events.secondDownEvent);
        } else {
            return false;
        }
    }

    // Method closely following GestureDetectorCompat#isConsideredDoubleTap.
    // Allows for serial inference of double taps as opposed to using callbacks.
    private boolean isQuickScaleInProgress(MotionEvent firstDown, MotionEvent firstUp, MotionEvent secondDown) {

        if (secondDown.getEventTime() - firstUp.getEventTime() > doubleTapTimeout) {
            return false;
        }

        int deltaX = (int) (firstDown.getX() - secondDown.getX());
        int deltaY = (int) (firstDown.getY() - secondDown.getY());

        return deltaX * deltaX + deltaY * deltaY < doubleTapSlopSquare;
    }

    private static class QuickScaleEvents {

        MotionEvent firstDownEvent;
        MotionEvent upEvent;
        MotionEvent secondDownEvent;

        private boolean isNotNull() {
            return firstDownEvent != null && upEvent != null && secondDownEvent != null;
        }

    }

    public void setProgressRefreshing(int progress) {
        if (progress == 100) setRefreshing(false);
    }

}