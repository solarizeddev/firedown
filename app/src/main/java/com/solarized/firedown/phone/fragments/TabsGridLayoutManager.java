package com.solarized.firedown.phone.fragments;

import android.content.Context;

import androidx.annotation.Nullable;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

/**
 * GridLayoutManager that exposes a "scroll to this position on first
 * paint, and tell me when it has actually stuck" hook, and disables
 * predictive item animations.
 *
 * <p><b>Why disable predictive animations:</b> The tabs grid doesn't
 * visually animate insertions or removals — cards just appear or
 * disappear. With predictive animations on,
 * {@code notifyItemRangeChanged} with a payload (e.g. an active tab's
 * thumbnail loading after the page is already on screen) triggers a
 * pre-layout pass followed by a real layout pass. Between the two
 * passes the LM resets {@code mAnchorInfo.mValid} to false, so the
 * post-layout pass re-runs {@code updateAnchorFromChildren} — which
 * iterates {@code mChildHelper} indices, not adapter positions. The
 * scrap/reattach of the changed view holder shuffles those indices,
 * so the "first reference child" picked during step 2 isn't the same
 * child step 1 anchored on. Result: the viewport drifts by one row.
 * Returning {@code false} from {@code supportsPredictiveItemAnimations}
 * tells RecyclerView to skip step 1 entirely — single layout pass,
 * no anchor drift, no need to fight the LM with a custom override.</p>
 *
 * <p><b>Sticky initial-position hook:</b> a plain
 * {@code scrollToPositionWithOffset} sets {@code mPendingScrollPosition}
 * and requests a layout, but during the tabs page's first paint a
 * layout pass triggered by inset dispatch or the postpone release can
 * discard that pending scroll before the row becomes visible. The
 * sticky version re-issues the scroll on each {@code onLayoutCompleted}
 * until {@code findFirstVisibleItemPosition} matches; once it does,
 * the {@code onReached} callback fires (the cue to release the
 * postponed enter transition).</p>
 *
 * <p>Self-clears on item-count mismatch and skips pre-layout passes.</p>
 */
public class TabsGridLayoutManager extends GridLayoutManager {

    /**
     * Number of consecutive {@code onLayoutCompleted} passes with an
     * unchanged {@code findFirstVisibleItemPosition()} after which we
     * accept that the LM physically cannot reach {@link #mScrollTarget}
     * and fire the callback anyway. Catches the case where
     * {@code scrollTarget} sits past the LM's natural scroll clamp
     * (e.g. active tab is near the end of a short list, so there
     * aren't enough rows below it to fill the viewport when the LM
     * tries to place the target row at the top).
     */
    private static final int STABILITY_GIVE_UP_PASSES = 3;

    /**
     * Hard ceiling on the total number of {@code onLayoutCompleted}
     * passes we'll wait for any convergence condition to be met
     * (exact hit, anchor visible, or stability). At ~60 fps this is
     * roughly 500 ms wall-clock — well past any sane layout-settling
     * window. Pure belt-and-suspenders against a pathological
     * scenario where {@code firstVisible} jitters every frame without
     * ever matching {@code scrollTarget} or {@code visibleAnchor},
     * which would defeat the stability counter.
     */
    private static final int TOTAL_GIVE_UP_PASSES = 30;

    private int mScrollTarget = RecyclerView.NO_POSITION;
    private int mVisibleAnchor = RecyclerView.NO_POSITION;
    @Nullable private Runnable mOnReached;

    private int mLastFirstVisible = RecyclerView.NO_POSITION;
    private int mStableCount = 0;
    private int mTotalLayoutCount = 0;

    public TabsGridLayoutManager(Context context, int spanCount) {
        super(context, spanCount);
    }

    @Override
    public boolean supportsPredictiveItemAnimations() {
        return false;
    }

    /**
     * Request a one-shot scroll to {@code scrollTarget}, with
     * {@code visibleAnchor} as a fallback success condition: if the
     * LM clamps the scroll short of {@code scrollTarget} but
     * {@code visibleAnchor} ends up inside the visible range, that
     * counts as "reached" too — the user can see the row we actually
     * cared about (typically the active tab) even if the exact
     * "scroll to row above" placement is impossible for this dataset.
     *
     * <p>Self-clears on item-count mismatch ({@code state.getItemCount() <=
     * scrollTarget}) and gives up after a few stable layouts where
     * {@code findFirstVisibleItemPosition()} doesn't advance — so the
     * caller's {@code onReached} callback always fires eventually,
     * even when the LM can't honor the requested target.</p>
     *
     * <p>Pass {@code scrollTarget == NO_POSITION} to cancel a pending
     * request without firing the callback.</p>
     */
    public void setInitialPosition(int scrollTarget, int visibleAnchor,
                                   @Nullable Runnable onReached) {
        mScrollTarget = scrollTarget;
        mVisibleAnchor = visibleAnchor;
        mOnReached = onReached;
        mLastFirstVisible = RecyclerView.NO_POSITION;
        mStableCount = 0;
        mTotalLayoutCount = 0;
        if (scrollTarget != RecyclerView.NO_POSITION) {
            scrollToPositionWithOffset(scrollTarget, 0);
        }
    }

    /** Cancel any pending request without firing the callback. */
    public void cancelInitialPosition() {
        mScrollTarget = RecyclerView.NO_POSITION;
        mVisibleAnchor = RecyclerView.NO_POSITION;
        mOnReached = null;
        mLastFirstVisible = RecyclerView.NO_POSITION;
        mStableCount = 0;
        mTotalLayoutCount = 0;
    }

    @Override
    public void onLayoutCompleted(RecyclerView.State state) {
        super.onLayoutCompleted(state);
        if (mScrollTarget == RecyclerView.NO_POSITION) return;
        if (state.isPreLayout()) return;

        if (state.getItemCount() <= mScrollTarget) {
            fireReached();
            return;
        }

        int firstVis = findFirstVisibleItemPosition();
        int lastVis = findLastVisibleItemPosition();

        boolean exactHit = firstVis == mScrollTarget;
        boolean anchorVisible = mVisibleAnchor != RecyclerView.NO_POSITION
                && firstVis != RecyclerView.NO_POSITION
                && lastVis != RecyclerView.NO_POSITION
                && firstVis <= mVisibleAnchor && mVisibleAnchor <= lastVis;

        if (exactHit || anchorVisible) {
            fireReached();
            return;
        }

        // Defensive #1: if firstVisible hasn't moved across several
        // consecutive layouts, the LM has clamped and won't reach the
        // target. Give up and fire the callback anyway.
        if (firstVis == mLastFirstVisible) {
            mStableCount++;
            if (mStableCount >= STABILITY_GIVE_UP_PASSES) {
                fireReached();
                return;
            }
        } else {
            mStableCount = 0;
            mLastFirstVisible = firstVis;
        }

        // Defensive #2: hard ceiling on total passes. Catches a
        // pathological scenario where firstVisible jitters every frame
        // (so the stability counter never accumulates) without ever
        // satisfying exactHit or anchorVisible. The page renders
        // wherever the LM ended up — better than hanging the holder
        // indefinitely.
        if (++mTotalLayoutCount >= TOTAL_GIVE_UP_PASSES) {
            fireReached();
            return;
        }

        // Some intermediate layout pass (postpone release, view attach,
        // …) discarded mPendingScrollPosition. Re-issue it — the next
        // onLayoutCompleted will re-check.
        scrollToPositionWithOffset(mScrollTarget, 0);
    }

    private void fireReached() {
        Runnable cb = mOnReached;
        mScrollTarget = RecyclerView.NO_POSITION;
        mVisibleAnchor = RecyclerView.NO_POSITION;
        mOnReached = null;
        mLastFirstVisible = RecyclerView.NO_POSITION;
        mStableCount = 0;
        mTotalLayoutCount = 0;
        if (cb != null) cb.run();
    }
}
