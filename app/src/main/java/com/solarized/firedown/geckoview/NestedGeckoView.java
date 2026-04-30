package com.solarized.firedown.geckoview;

import android.annotation.SuppressLint;
import android.content.Context;
import android.util.AttributeSet;
import android.util.Log;
import android.view.MotionEvent;
import android.view.ViewParent;

import androidx.core.view.NestedScrollingChild;
import androidx.core.view.NestedScrollingChildHelper;
import androidx.core.view.ViewCompat;

import org.mozilla.geckoview.GeckoResult;
import org.mozilla.geckoview.GeckoView;
import org.mozilla.geckoview.PanZoomController;


/**
 * GeckoView that supports nested scrolls (for use inside a CoordinatorLayout).
 *
 * <p>Matches upstream {@code mozilla.components.browser.engine.gecko.NestedGeckoView} as closely
 * as possible, translated to Java for the Firedown codebase.
 *
 * <p>Key upstream behaviours preserved:
 * <ul>
 *   <li>{@link #inputResultDetail} is initialised with
 *       {@code InputResultDetail.newInstance(true)} (vertical overscroll pre-enabled) and reset in
 *       {@code ACTION_UP} / {@code ACTION_CANCEL} — <em>not</em> deferred to the next
 *       {@code ACTION_DOWN}. This lets pull-to-refresh fire on the very first gesture.
 *   <li>{@code dispatchNestedPreScroll} is called <em>only</em> when
 *       {@code isTouchHandledByBrowser()} — not when {@code UNKNOWN}. Upstream's
 *       {@code EngineViewScrollingGesturesBehavior} gates on {@code shouldScroll} which requires
 *       {@code INPUT_HANDLED}; dispatching during UNKNOWN is therefore not needed here.
 *   <li>{@link #updateInputResult} is called <em>only</em> from {@code ACTION_DOWN}. There is no
 *       "gestureCanReachParent + hasDragGestureStarted" re-call on {@code ACTION_MOVE} as there was
 *       in earlier Firedown code. The async callback from that single {@code ACTION_DOWN} call is
 *       sufficient to gate subsequent {@code ACTION_MOVE} dispatching.
 *   <li>The {@code InitialScrollDirection} enum drives
 *       {@code requestDisallowInterceptTouchEvent} decisions inside the async callback.
 * </ul>
 */
public class NestedGeckoView extends GeckoView implements NestedScrollingChild {

    private final static String TAG = NestedGeckoView.class.getSimpleName();

    // ── Scroll-tracking state ─────────────────────────────────────────────────────────────────────

    private int mLastY;
    private final int[] mScrollOffset   = new int[2];
    private final int[] mScrollConsumed = new int[2];
    private int mNestedOffsetY;

    /** Whether the current gesture is allowed to reach the parent (e.g. trigger P2R). */
    private boolean gestureCanReachParent = true;

    private float initialDownY = 0f;

    /**
     * Direction of the finger's first movement in the current gesture, set synchronously in
     * {@code ACTION_MOVE} before the async APZ callback fires.
     *
     * <p>Mirrors upstream's {@code InitialScrollDirection} enum.
     */
    private enum InitialScrollDirection { NOT_YET, DOWN, UP }
    private InitialScrollDirection initialScrollDirection = InitialScrollDirection.NOT_YET;

    // ── Nested-scroll helper ──────────────────────────────────────────────────────────────────────

    private final NestedScrollingChildHelper mChildHelper;

    // ── Input result ──────────────────────────────────────────────────────────────────────────────

    /**
     * How the user's {@link MotionEvent} was (or will be) handled.
     *
     * <p>Initialised with {@code newInstance(true)} — vertical overscroll pre-enabled — so
     * pull-to-refresh works before the first async APZ response arrives, matching upstream.
     */
    InputResultDetail inputResultDetail;

    // ── Constructors ──────────────────────────────────────────────────────────────────────────────

    public NestedGeckoView(final Context context) {
        this(context, null);
    }

    public NestedGeckoView(final Context context, final AttributeSet attrs) {
        super(context, attrs);
        mChildHelper      = new NestedScrollingChildHelper(this);
        inputResultDetail = InputResultDetail.newInstance(true);
        setNestedScrollingEnabled(true);
    }

    // ── Touch handling ────────────────────────────────────────────────────────────────────────────

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        MotionEvent event = MotionEvent.obtain(ev);
        final int action = event.getActionMasked();
        int eventY = (int) event.getY();

        ViewParent viewParent = getParent();

        switch (action) {

            case MotionEvent.ACTION_MOVE: {
                // Only dispatch nested scroll when the browser/APZ confirmed it handles the event.
                // Upstream does NOT dispatch during INPUT_HANDLING_UNKNOWN — unlike earlier Firedown
                // code. EngineViewScrollingGesturesBehavior's shouldScroll requires INPUT_HANDLED.
                final boolean allowScroll =
                        !shouldPinOnScreen() && inputResultDetail.isTouchHandledByBrowser();

                int deltaY = mLastY - eventY;

                if (allowScroll
                        && dispatchNestedPreScroll(0, deltaY, mScrollConsumed, mScrollOffset)) {
                    deltaY -= mScrollConsumed[1];
                    event.offsetLocation(0f, (float) -mScrollOffset[1]);
                    mNestedOffsetY += mScrollOffset[1];
                }

                mLastY = eventY - mScrollOffset[1];

                if (allowScroll
                        && dispatchNestedScroll(0, mScrollOffset[1], 0, deltaY, mScrollOffset)) {
                    mLastY -= mScrollOffset[1];
                    event.offsetLocation(0f, (float) mScrollOffset[1]);
                    mNestedOffsetY += mScrollOffset[1];
                }

                // Track scroll direction synchronously on first meaningful MOVE.
                if (initialScrollDirection == InitialScrollDirection.NOT_YET) {
                    if (event.getY() > initialDownY) {
                        // Finger moved down → content could scroll up or P2R
                        initialScrollDirection = InitialScrollDirection.UP;
                    } else if (event.getY() < initialDownY) {
                        // Finger moved up → content scrolls down
                        initialScrollDirection = InitialScrollDirection.DOWN;
                    }
                    // If equal: no movement yet, leave as NOT_YET
                }
                break;
            }

            case MotionEvent.ACTION_DOWN: {
                // A new gesture started. Ask GV if it can handle this.
                if (viewParent != null) viewParent.requestDisallowInterceptTouchEvent(true);
                updateInputResult(event);

                initialScrollDirection = InitialScrollDirection.NOT_YET;
                mNestedOffsetY  = 0;
                mLastY          = eventY;
                initialDownY    = event.getY();

                // The event is handled by onTouchEventForDetailResult, not by super.onTouchEvent.
                event.recycle();
                return true;
            }

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                // Reset inputResultDetail here (not deferred to next ACTION_DOWN) so other
                // features that poll getInputResultDetail() see a clean state immediately.
                // Matches upstream comment: "inputResultDetail needs to be reset here and not in
                // the next ACTION_DOWN".
                inputResultDetail = InputResultDetail.newInstance(true);
                stopNestedScroll();

                // Allow touch event interception for the next ACTION_DOWN.
                if (viewParent != null) viewParent.requestDisallowInterceptTouchEvent(false);
                gestureCanReachParent   = true;
                initialScrollDirection  = InitialScrollDirection.NOT_YET;
                break;
            }
        }

        final boolean eventHandled = callSuperOnTouchEvent(event);
        event.recycle();
        return eventHandled;
    }

    private boolean callSuperOnTouchEvent(MotionEvent event) {
        return super.onTouchEvent(event);
    }

    // ── APZ result callback ───────────────────────────────────────────────────────────────────────

    @SuppressLint("WrongThread")
    private void updateInputResult(MotionEvent event) {
        final int eventAction = event.getActionMasked();

        GeckoResult<PanZoomController.InputResultDetail> geckoResult =
                super.onTouchEventForDetailResult(event);

        geckoResult.accept(detail -> {
            ViewParent parent = getParent();

            // Since the response from APZ is async, we could theoretically get a response
            // that is out of time when ACTION_MOVE events have already been processed.
            // We do not want to forward this to the parent pre-emptively.
            if (!gestureCanReachParent) {
                return;
            }

            if (detail != null) {
                inputResultDetail = inputResultDetail.copy(
                        detail.handledResult(),
                        detail.scrollableDirections(),
                        detail.overscrollDirections());
            }

            if (eventAction == MotionEvent.ACTION_DOWN) {
                // Gesture can reach the parent only if the content is already at the top.
                gestureCanReachParent = inputResultDetail.canOverscrollTop();

                switch (initialScrollDirection) {
                    case NOT_YET:
                    case UP:
                        // Allow pull-to-refresh interception unless the website consumed the event.
                        if (gestureCanReachParent && !inputResultDetail.isTouchHandledByWebsite()) {
                            if (parent != null) parent.requestDisallowInterceptTouchEvent(false);
                        }
                        break;

                    case DOWN:
                        // Finger already moved down before callback arrived — block interception.
                        // Prevents SwipeRefreshLayout from stealing downward Shorts swipes.
                        if (parent != null) parent.requestDisallowInterceptTouchEvent(true);
                        break;
                }
            }

            startNestedScroll(ViewCompat.SCROLL_AXIS_VERTICAL);
        });
    }

    // ── Public accessors ──────────────────────────────────────────────────────────────────────────

    public InputResultDetail getInputResultDetail() { return inputResultDetail; }

    public int  getNestedOffsetY()  { return mNestedOffsetY; }
    public int[] getScrollOffset()  { return mScrollOffset; }

    public boolean canScrollBottom() { return inputResultDetail.canScrollToBottom(); }
    public boolean canScrollTop()    { return inputResultDetail.canScrollToTop(); }
    public boolean isTouchUnhandled(){ return inputResultDetail.isTouchUnhandled(); }

    // ── Lifecycle ─────────────────────────────────────────────────────────────────────────────────

    @Override
    public void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        Log.d(TAG, "onDetachedFromWindow");
        releaseSession();
    }

    // ── NestedScrollingChild delegation ──────────────────────────────────────────────────────────

    @Override public void    setNestedScrollingEnabled(boolean enabled) { mChildHelper.setNestedScrollingEnabled(enabled); }
    @Override public boolean isNestedScrollingEnabled()                 { return mChildHelper.isNestedScrollingEnabled(); }
    @Override public boolean startNestedScroll(int axes)                { return mChildHelper.startNestedScroll(axes); }
    @Override public void    stopNestedScroll()                         { mChildHelper.stopNestedScroll(); }
    @Override public boolean hasNestedScrollingParent()                 { return mChildHelper.hasNestedScrollingParent(); }

    @Override
    public boolean dispatchNestedScroll(int dxConsumed, int dyConsumed,
                                        int dxUnconsumed, int dyUnconsumed,
                                        int[] offsetInWindow) {
        return mChildHelper.dispatchNestedScroll(
                dxConsumed, dyConsumed, dxUnconsumed, dyUnconsumed, offsetInWindow);
    }

    @Override
    public boolean dispatchNestedPreScroll(int dx, int dy,
                                           int[] consumed, int[] offsetInWindow) {
        return mChildHelper.dispatchNestedPreScroll(dx, dy, consumed, offsetInWindow);
    }

    @Override
    public boolean dispatchNestedFling(float velocityX, float velocityY, boolean consumed) {
        return mChildHelper.dispatchNestedFling(velocityX, velocityY, consumed);
    }

    @Override
    public boolean dispatchNestedPreFling(float velocityX, float velocityY) {
        return mChildHelper.dispatchNestedPreFling(velocityX, velocityY);
    }
}