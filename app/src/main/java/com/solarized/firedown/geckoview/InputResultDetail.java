package com.solarized.firedown.geckoview;

/**
 * All data about how a touch will be handled by the browser.
 *
 * <p>Matches upstream mozilla.components.concept.engine.InputResultDetail:
 * immutable value object with a {@link #copy} method and a {@link #newInstance} factory.
 * The old mutable {@code reset()} / {@code copy(int,int,int)} API has been removed.
 *
 * <ul>
 *   <li>whether the event is used for panning/zooming by the browser / website or ignored.
 *   <li>whether the event can scroll the page and in what direction.
 *   <li>whether the event can overscroll the page and in what direction.
 * </ul>
 */
public final class InputResultDetail {

    // ── Input result constants ────────────────────────────────────────────────────────────────────

    /** No response yet from the browser about how the touch was handled. */
    public static final int INPUT_HANDLING_UNKNOWN   = -1;
    /** The content has no scrollable element. */
    public static final int INPUT_UNHANDLED          =  0;
    /** The touch event is consumed by the EngineView (browser / APZ). */
    public static final int INPUT_HANDLED            =  1;
    /** The touch event is consumed by the website through its own touch listeners. */
    public static final int INPUT_HANDLED_CONTENT    =  2;

    // ── Scroll direction flags (bitwise OR'd) ─────────────────────────────────────────────────────

    public static final int SCROLL_DIRECTIONS_NONE   = 0;
    public static final int SCROLL_DIRECTIONS_TOP    = 1 << 0;
    public static final int SCROLL_DIRECTIONS_RIGHT  = 1 << 1;
    public static final int SCROLL_DIRECTIONS_BOTTOM = 1 << 2;
    public static final int SCROLL_DIRECTIONS_LEFT   = 1 << 3;

    // ── Overscroll direction flags (bitwise OR'd) ─────────────────────────────────────────────────

    public static final int OVERSCROLL_DIRECTIONS_NONE       = 0;
    public static final int OVERSCROLL_DIRECTIONS_HORIZONTAL = 1 << 0;
    public static final int OVERSCROLL_DIRECTIONS_VERTICAL   = 1 << 1;

    // ── Immutable fields ──────────────────────────────────────────────────────────────────────────

    public final int inputResult;
    public final int scrollDirections;
    public final int overscrollDirections;

    // ── Constructor (private – use newInstance / copy) ────────────────────────────────────────────

    private InputResultDetail(int inputResult, int scrollDirections, int overscrollDirections) {
        this.inputResult          = inputResult;
        this.scrollDirections     = scrollDirections;
        this.overscrollDirections = overscrollDirections;
    }

    // ── Factory ───────────────────────────────────────────────────────────────────────────────────

    /**
     * Creates a new instance with default (UNKNOWN / no scroll / no overscroll) values.
     *
     * @param verticalOverscrollInitiallyEnabled {@code true} to pre-enable vertical overscroll so
     *        pull-to-refresh can fire before the async APZ result arrives.  Mirrors upstream's
     *        {@code InputResultDetail.newInstance(verticalOverscrollInitiallyEnabled = true)} which
     *        is used in {@link NestedGeckoView} to allow the very first gesture to trigger P2R.
     */
    public static InputResultDetail newInstance(boolean verticalOverscrollInitiallyEnabled) {
        return new InputResultDetail(
                INPUT_HANDLING_UNKNOWN,
                SCROLL_DIRECTIONS_NONE,
                verticalOverscrollInitiallyEnabled
                        ? OVERSCROLL_DIRECTIONS_VERTICAL
                        : OVERSCROLL_DIRECTIONS_NONE);
    }

    /** Convenience overload — vertical overscroll initially disabled. */
    public static InputResultDetail newInstance() {
        return newInstance(false);
    }

    // ── Immutable update ──────────────────────────────────────────────────────────────────────────

    /**
     * Returns a <em>new</em> {@link InputResultDetail} with the supplied values, silently ignoring
     * any value that is out-of-range (the current field value is kept instead).
     * Pass {@code null} for any argument to keep the existing value unchanged.
     *
     * <p>Mirrors upstream's Kotlin {@code fun copy(inputResult, scrollDirections,
     * overscrollDirections)} with the same validation logic.
     */
    public InputResultDetail copy(Integer newInputResult,
                                  Integer newScrollDirections,
                                  Integer newOverscrollDirections) {

        // inputResult: must be in [INPUT_UNHANDLED, INPUT_HANDLED_CONTENT]
        int validInputResult;
        if (newInputResult != null
                && newInputResult >= INPUT_UNHANDLED
                && newInputResult <= INPUT_HANDLED_CONTENT) {
            validInputResult = newInputResult;
        } else {
            validInputResult = this.inputResult;
        }

        // scrollDirections: must be in [0, max bitmask]
        int maxScrollDirs = SCROLL_DIRECTIONS_LEFT | (SCROLL_DIRECTIONS_LEFT - 1);
        int validScrollDirs;
        if (newScrollDirections != null
                && newScrollDirections >= SCROLL_DIRECTIONS_NONE
                && newScrollDirections <= maxScrollDirs) {
            validScrollDirs = newScrollDirections;
        } else {
            validScrollDirs = this.scrollDirections;
        }

        // overscrollDirections: must be in [0, max bitmask]
        int maxOverscrollDirs = OVERSCROLL_DIRECTIONS_VERTICAL | (OVERSCROLL_DIRECTIONS_VERTICAL - 1);
        int validOverscrollDirs;
        if (newOverscrollDirections != null
                && newOverscrollDirections >= OVERSCROLL_DIRECTIONS_NONE
                && newOverscrollDirections <= maxOverscrollDirs) {
            validOverscrollDirs = newOverscrollDirections;
        } else {
            validOverscrollDirs = this.overscrollDirections;
        }

        return new InputResultDetail(validInputResult, validScrollDirs, validOverscrollDirs);
    }

    // ── Input result queries ──────────────────────────────────────────────────────────────────────

    /** The EngineView has not yet responded on how it handled the MotionEvent. */
    public boolean isTouchHandlingUnknown()  { return inputResult == INPUT_HANDLING_UNKNOWN; }

    /** The EngineView handled the last MotionEvent to pan or zoom the content. */
    public boolean isTouchHandledByBrowser() { return inputResult == INPUT_HANDLED; }

    /** The website handled the last MotionEvent through its own touch listeners. */
    public boolean isTouchHandledByWebsite() { return inputResult == INPUT_HANDLED_CONTENT; }

    /** Neither the EngineView nor the website will handle this MotionEvent. */
    public boolean isTouchUnhandled()        { return inputResult == INPUT_UNHANDLED; }

    // ── Scroll direction queries ──────────────────────────────────────────────────────────────────

    public boolean canScrollToLeft() {
        return inputResult == INPUT_HANDLED
                && (scrollDirections & SCROLL_DIRECTIONS_LEFT) != 0;
    }

    public boolean canScrollToTop() {
        return inputResult == INPUT_HANDLED
                && (scrollDirections & SCROLL_DIRECTIONS_TOP) != 0;
    }

    public boolean canScrollToRight() {
        return inputResult == INPUT_HANDLED
                && (scrollDirections & SCROLL_DIRECTIONS_RIGHT) != 0;
    }

    public boolean canScrollToBottom() {
        return inputResult == INPUT_HANDLED
                && (scrollDirections & SCROLL_DIRECTIONS_BOTTOM) != 0;
    }

    // ── Overscroll direction queries ──────────────────────────────────────────────────────────────

    public boolean canOverscrollLeft() {
        return inputResult != INPUT_HANDLED_CONTENT
                && (scrollDirections & SCROLL_DIRECTIONS_LEFT) == 0
                && (overscrollDirections & OVERSCROLL_DIRECTIONS_HORIZONTAL) != 0;
    }

    public boolean canOverscrollTop() {
        return inputResult != INPUT_HANDLED_CONTENT
                && (scrollDirections & SCROLL_DIRECTIONS_TOP) == 0
                && (overscrollDirections & OVERSCROLL_DIRECTIONS_VERTICAL) != 0;
    }

    public boolean canOverscrollRight() {
        return inputResult != INPUT_HANDLED_CONTENT
                && (scrollDirections & SCROLL_DIRECTIONS_RIGHT) == 0
                && (overscrollDirections & OVERSCROLL_DIRECTIONS_HORIZONTAL) != 0;
    }

    public boolean canOverscrollBottom() {
        return inputResult != INPUT_HANDLED_CONTENT
                && (scrollDirections & SCROLL_DIRECTIONS_BOTTOM) == 0
                && (overscrollDirections & OVERSCROLL_DIRECTIONS_VERTICAL) != 0;
    }

    // ── Object overrides ──────────────────────────────────────────────────────────────────────────

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof InputResultDetail)) return false;
        InputResultDetail o = (InputResultDetail) other;
        return inputResult == o.inputResult
                && scrollDirections == o.scrollDirections
                && overscrollDirections == o.overscrollDirections;
    }

    @Override
    public int hashCode() {
        int hash = Integer.hashCode(inputResult);
        hash += Integer.hashCode(scrollDirections)     * 10;
        hash += Integer.hashCode(overscrollDirections) * 100;
        return hash;
    }

    @Override
    public String toString() {
        return "InputResultDetail("
                + "inputResult=" + inputResult
                + ", scrollDirections=" + scrollDirections
                + ", overscrollDirections=" + overscrollDirections + ')';
    }
}
