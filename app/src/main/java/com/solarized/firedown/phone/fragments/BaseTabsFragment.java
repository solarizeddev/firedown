package com.solarized.firedown.phone.fragments;

import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.RecyclerView;

import com.solarized.firedown.Preferences;
import com.solarized.firedown.R;
import com.solarized.firedown.data.entity.GeckoStateEntity;
import com.solarized.firedown.data.models.BrowserURIViewModel;
import com.solarized.firedown.data.models.GeckoStateViewModel;
import com.solarized.firedown.data.models.IncognitoStateViewModel;
import com.solarized.firedown.geckoview.GeckoRuntimeHelper;
import com.solarized.firedown.geckoview.GeckoState;
import com.solarized.firedown.geckoview.media.GeckoMediaController;
import com.solarized.firedown.ui.EqualSpacingItemDecoration;
import com.solarized.firedown.ui.OnItemClickListener;
import com.solarized.firedown.ui.adapters.BrowserTabsAdapter;
import com.solarized.firedown.ui.diffs.GeckoStateDiffCallback;

import org.mozilla.geckoview.GeckoSession;
import org.mozilla.geckoview.WebExtensionController;

import java.util.List;

import javax.inject.Inject;

/**
 * Base class for tab switcher fragments (regular and incognito).
 *
 * <p>Contains all shared logic: RecyclerView setup, adapter management,
 * media indicator observation, grid/list toggle, tab selection, tab closing,
 * and swipe-to-dismiss. Subclasses provide their specific tab data source,
 * selection/close callbacks, and any extra UI (e.g. archive banner, undo snackbar).</p>
 */
public abstract class BaseTabsFragment extends BaseFocusFragment implements OnItemClickListener {

    private static final String TAG = BaseTabsFragment.class.getSimpleName();

    public static final String ARG_ENABLE_GRID = "enable_grid";

    protected GeckoStateViewModel mGeckoStateViewModel;
    protected IncognitoStateViewModel mIncognitoStateViewModel;
    protected BrowserURIViewModel mBrowserURIViewModel;
    protected BrowserTabsAdapter mBrowserTabsAdapter;
    protected TabsGridLayoutManager mGridLayoutManager;
    protected boolean mEnableGrid;

    /** Tracks the most recently seen active tab id so the tab-list
     *  observer can detect when the active tab identity changes (new
     *  tab created, user switched tabs elsewhere) versus an unrelated
     *  re-submission (title updates, thumb loads). */
    protected int mLastTabActive;

    // ── First-snapshot machinery (Chromium tab-switcher pattern) ─────
    // Architecture (see PR following #158): the recycler's adapter is
    // NOT attached in setupRecyclerView. Both LiveData sources (tab
    // list + archive banner) feed their first emissions into a pending
    // snapshot. When the snapshot is "ready" (tabs non-null, banner
    // signal arrived if the subclass needs one), applyFirstSnapshot()
    // runs synchronously on the main thread:
    //   1. set the banner state in the adapter (silently)
    //   2. setAdapter on the RecyclerView
    //   3. submitList(tabs) — the FIRST call dispatches inserts
    //      synchronously per AsyncListDiffer contract
    //   4. scrollToPositionWithOffset(activeIdx + banner - spanCount, 0)
    //   5. LCEE hideAll / showEmpty
    // RecyclerView's very first onLayoutChildren pass sees both the
    // inserted items AND the pending scroll position; the LM uses the
    // pending position as its initial anchor. One layout pass, items
    // anchored at the active row, no flash, no post-layout scroll.
    //
    // After mFirstSnapshotApplied flips to true, subsequent LiveData
    // emissions take the normal path (submitList / showBanner /
    // dismissBanner with their notify events).
    private boolean mFirstSnapshotApplied = false;
    @Nullable private List<GeckoStateEntity> mPendingTabs = null;
    private boolean mPendingBannerSignalled = false;

    @Inject
    GeckoRuntimeHelper mGeckoRuntimeHelper;

    @Inject
    GeckoMediaController mGeckoMediaController;

    @Inject
    SharedPreferences mSharedPreferences;


    // ── Abstract hooks for subclasses ────────────────────────────────

    /** The LiveData that provides the tab list (regular or incognito). */
    protected abstract LiveData<List<GeckoStateEntity>> getTabsLiveData();

    /** Look up a GeckoState by tab ID from the appropriate repository. */
    protected abstract GeckoState getGeckoState(int tabId);

    /** Activate a tab in the appropriate repository. */
    protected abstract void activateGeckoState(GeckoState geckoState);

    /** Remove a tab from the appropriate repository. */
    protected abstract void removeGeckoState(GeckoState geckoState);

    /** Called after a tab is selected — navigate via fragment result. */
    protected abstract void onTabSelected(GeckoStateEntity entity, GeckoState geckoState);

    /** Called after a tab is closed — subclass handles undo, auto-switch, etc. */
    protected abstract void onTabClosed(GeckoStateEntity entity, GeckoState geckoState);

    /** Empty state text resource. */
    protected abstract int getEmptyTextRes();

    /** Empty-state illustration. Defaults to the neutral tabs art; the
     *  incognito subclass overrides it with the private-browsing mascot
     *  so the empty incognito switcher reads as distinctly private. */
    protected int getEmptyImageRes() {
        return R.drawable.ill_small_tabs2;
    }

    /**
     * Adapter-space → tab-list-index. The only header the adapter ever
     * renders is the archive-banner row at position 0 (regular tabs
     * only), and the adapter exposes that as
     * {@link com.solarized.firedown.ui.adapters.BrowserTabsAdapter#getPositionOffset()}.
     * Going through the adapter keeps "is the banner there?" in a single
     * place — there's no second source of truth in the fragment to drift
     * out of sync.
     *
     * <p>A negative return means the click landed on the banner row;
     * callers ({@link #onItemClick}, swipe) treat that as a no-op for
     * the tab-list operations they own.</p>
     */
    protected int adjustPosition(int position) {
        if (mBrowserTabsAdapter == null) return position;
        return position - mBrowserTabsAdapter.getPositionOffset();
    }

    /**
     * Tab-list-index → adapter-space. Inverse of {@link #adjustPosition}.
     */
    protected int getLeadingAdapterCount() {
        if (mBrowserTabsAdapter == null) return 0;
        return mBrowserTabsAdapter.getPositionOffset();
    }

    /**
     * Called after a new tab list is submitted to the adapter.
     *
     * <p>The base implementation:
     * <ol>
     *   <li>Detects whether the <em>active tab identity</em> has changed
     *       (new tab created, remote selection change) as opposed to an
     *       unrelated list re-submission (title/thumb update).</li>
     *   <li>Scrolls to the active tab only when the identity actually
     *       changed <em>and</em> the user is not currently scrolling.
     *       This avoids yanking a dragging/flinging user around, and
     *       avoids re-scrolling on every tab-field change.</li>
     * </ol>
     *
     * <p>Subclasses may override to add additional behavior but should
     * typically still call {@code super}.</p>
     */
    /**
     * Called when a subsequent (post-snapshot) tab list arrives. Scrolls
     * to the active tab if its identity changed (new tab created, user
     * switched tabs elsewhere) and the user isn't actively scrolling.
     * Title / thumb updates that keep the same active id don't trigger
     * a scroll.
     */
    private void onSubsequentTabsSubmitted(@Nullable List<GeckoStateEntity> tabs) {
        if (mRecyclerView == null || mGridLayoutManager == null) return;
        if (tabs == null || tabs.isEmpty()) {
            mLastTabActive = 0;
            return;
        }
        int activePosition = -1;
        int activeId = -1;
        for (int i = 0; i < tabs.size(); i++) {
            GeckoStateEntity entity = tabs.get(i);
            if (entity.isActive()) {
                activePosition = i;
                activeId = entity.getId();
                break;
            }
        }
        boolean activeChanged = activeId != -1 && activeId != mLastTabActive;
        boolean userTouching =
                mRecyclerView.getScrollState() != RecyclerView.SCROLL_STATE_IDLE;
        if (activeId != -1) mLastTabActive = activeId;
        if (activeChanged && !userTouching) {
            int spanCount = mGridLayoutManager.getSpanCount();
            int adapterTarget = activePosition + getLeadingAdapterCount();
            int scrollTarget = Math.max(0, adapterTarget - spanCount);
            mGridLayoutManager.scrollToPositionWithOffset(scrollTarget, 0);
        }
    }

    // ── First-snapshot application (Chromium pattern) ────────────────

    /**
     * Subclasses that show a banner row (TabsFragment) override to
     * return true; the first snapshot won't be applied until that
     * subclass has called {@link #signalBannerReady}. Incognito
     * (default false) skips the wait — its snapshot is just the tab
     * list.
     */
    protected boolean awaitsBannerSignal() {
        return false;
    }

    /** Subclass calls this from its banner observer's first emission
     *  (after configuring the banner state on the adapter via
     *  {@link BrowserTabsAdapter#setBannerSilently}). Idempotent —
     *  subsequent banner changes go through the regular
     *  {@code showBanner}/{@code dismissBanner} path. */
    protected void signalBannerReady() {
        if (mPendingBannerSignalled) return;
        mPendingBannerSignalled = true;
        tryApplyFirstSnapshot();
    }

    /** True after the first-snapshot setAdapter/submitList/scroll has
     *  run; subclasses use it to route banner LiveData emissions
     *  between the pre-snapshot setBannerSilently path and the
     *  post-snapshot showBanner/dismissBanner path. */
    protected boolean isFirstSnapshotApplied() {
        return mFirstSnapshotApplied;
    }

    /**
     * Apply the first-snapshot atomically when both signals are in:
     * tabs LiveData has fired and (if the subclass needs one) the
     * banner observer has reported. Per the Chromium tab-switcher
     * pattern, the order is:
     *
     * <ol>
     *   <li>Banner state is already set on the adapter via
     *       {@link BrowserTabsAdapter#setBannerSilently} (subclass
     *       did that before calling signalBannerReady).</li>
     *   <li>Attach the adapter to the RecyclerView.</li>
     *   <li>{@code submitList(tabs)} — first call dispatches inserts
     *       synchronously (AsyncListDiffer contract for null→non-null
     *       transitions).</li>
     *   <li>{@code scrollToPositionWithOffset(target, 0)} — sets
     *       {@code mPendingScrollPosition} on the LM.</li>
     *   <li>LCEE {@code hideAll()} / {@code showEmpty()}.</li>
     * </ol>
     *
     * <p>RecyclerView's <em>first</em> {@code onLayoutChildren} pass
     * after this sees the inserted items AND the pending scroll
     * position together — the LM uses the pending position as its
     * initial anchor. One layout pass, items anchored at the active
     * row, no flash, no post-layout scroll-to-active.</p>
     */
    private void tryApplyFirstSnapshot() {
        if (mFirstSnapshotApplied) return;
        if (mPendingTabs == null) return;
        if (awaitsBannerSignal() && !mPendingBannerSignalled) return;
        if (mRecyclerView == null || mBrowserTabsAdapter == null
                || mGridLayoutManager == null) return;

        final List<GeckoStateEntity> tabs = mPendingTabs;
        mPendingTabs = null;
        mFirstSnapshotApplied = true;

        // 1. Attach the adapter. Until this point the RV had no
        //    adapter, so no layout pass ran with an empty data set
        //    (which would have consumed the pending-scroll slot).
        mRecyclerView.setAdapter(mBrowserTabsAdapter);

        // 2. First submitList — AsyncListDiffer's null-to-non-null
        //    fast path dispatches onInserted(0, N) synchronously on
        //    this thread, before submitList returns. The notifies
        //    queue up in the RV without triggering layout yet.
        mBrowserTabsAdapter.submitList(tabs);

        // 3. Compute scroll target and set the pending position. The
        //    RV's first onLayoutChildren (scheduled by the inserts +
        //    setAdapter above) will pick this up as its initial
        //    anchor — no second layout.
        int activePosition = -1;
        int activeId = -1;
        for (int i = 0; i < tabs.size(); i++) {
            GeckoStateEntity entity = tabs.get(i);
            if (entity.isActive()) {
                activePosition = i;
                activeId = entity.getId();
                break;
            }
        }
        if (activeId != -1) mLastTabActive = activeId;
        if (activePosition >= 0) {
            int spanCount = mGridLayoutManager.getSpanCount();
            int adapterTarget = activePosition + getLeadingAdapterCount();
            int scrollTarget = Math.max(0, adapterTarget - spanCount);
            mGridLayoutManager.scrollToPositionWithOffset(scrollTarget, 0);
        }

        // 4. Reveal LCEE state. hideAll if there's content (tab rows
        //    or just a banner row); showEmpty if both are absent.
        updateEmptyVisibility(tabs);
    }


    // ── Lifecycle ────────────────────────────────────────────────────

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (getArguments() != null && getArguments().containsKey(ARG_ENABLE_GRID)) {
            mEnableGrid = getArguments().getBoolean(ARG_ENABLE_GRID, false);
        } else {
            mEnableGrid = mSharedPreferences.getBoolean(Preferences.SORT_TABS_LIST, false);
        }
        mGeckoStateViewModel = new ViewModelProvider(mActivity).get(GeckoStateViewModel.class);
        mIncognitoStateViewModel = new ViewModelProvider(mActivity).get(IncognitoStateViewModel.class);
        mBrowserURIViewModel = new ViewModelProvider(mActivity).get(BrowserURIViewModel.class);
    }

    @Override
    public void onConfigurationChanged(@NonNull Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (mGridLayoutManager != null) {
            mGridLayoutManager.setSpanCount(getSpanCount());
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        mLCEERecyclerView = null;
        mRecyclerView = null;
        mGridLayoutManager = null;
        mBrowserTabsAdapter = null;
        // Reset the first-snapshot state so a re-created view (config
        // change, ViewPager2 page recycle) goes through the snapshot
        // flow again on its own LiveData re-emissions.
        mFirstSnapshotApplied = false;
        mPendingTabs = null;
        mPendingBannerSignalled = false;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {

        View view = inflater.inflate(R.layout.fragment_tabs, container, false);

        mLCEERecyclerView = view.findViewById(R.id.list_recycler_lcee);
        mLCEERecyclerView.setEmptyText(getEmptyTextRes());
        mLCEERecyclerView.setEmptyImageView(getEmptyImageRes());

        mRecyclerView = mLCEERecyclerView.getRecyclerView();
        // LCEE starts in showLoading() — RV is GONE, loading view
        // visible. We deliberately leave the RV with no adapter
        // attached at this point (see setupRecyclerView); the
        // first-snapshot flow attaches it later. With no adapter the
        // LM doesn't try to lay anything out and the GONE visibility
        // is harmless.

        mBrowserTabsAdapter = new BrowserTabsAdapter(mActivity, new GeckoStateDiffCallback(), this, mEnableGrid);

        mGridLayoutManager = new TabsGridLayoutManager(requireContext(), getSpanCount());

        setupRecyclerView();

        ItemTouchHelper itemTouchHelper = new ItemTouchHelper(mSwipeCallback);
        itemTouchHelper.attachToRecyclerView(mRecyclerView);

        return view;
    }

    /**
     * Sets up the RecyclerView with layout manager, adapter, span lookup
     * and item decoration. The banner row (if any) spans the full grid
     * width; tab rows take a single span. Lookup is keyed off the
     * adapter's view type so this works untouched in incognito (which
     * never shows a banner) and in any future header types.
     */
    protected void setupRecyclerView() {
        mGridLayoutManager.setSpanSizeLookup(new GridLayoutManager.SpanSizeLookup() {
            @Override
            public int getSpanSize(int position) {
                if (mBrowserTabsAdapter == null) return 1;
                if (position < 0 || position >= mBrowserTabsAdapter.getItemCount()) return 1;
                int viewType = mBrowserTabsAdapter.getItemViewType(position);
                if (viewType == BrowserTabsAdapter.TYPE_BANNER) {
                    return mGridLayoutManager.getSpanCount();
                }
                return 1;
            }
        });
        mRecyclerView.setLayoutManager(mGridLayoutManager);
        // NOTE: setAdapter is deliberately deferred to tryApplyFirstSnapshot().
        // Attaching an empty adapter here would trigger an empty
        // layout pass on the RV, which "consumes" the pending-scroll
        // slot — any later scrollToPositionWithOffset would then need
        // a second layout pass to take effect, and the user sees the
        // first frame (unscrolled) before the second (scrolled). By
        // not attaching until we have data + banner state in hand,
        // the very first layout pass already has both items and a
        // pending scroll position, and the LM uses the position as
        // its initial anchor.
        mRecyclerView.addItemDecoration(new EqualSpacingItemDecoration(
                getResources().getDimensionPixelSize(R.dimen.list_item_margin)));
    }

    /**
     * Updates the LCEE empty-state visibility against the current tab
     * list <em>plus</em> the adapter's banner state. The recycler is
     * considered "empty" only when there are no tabs <em>and</em> the
     * archive banner isn't showing — otherwise the banner row keeps the
     * list non-empty even with zero tabs.
     */
    protected void updateEmptyVisibility(@Nullable List<GeckoStateEntity> tabs) {
        if (mLCEERecyclerView == null) return;
        boolean tabsEmpty = tabs == null || tabs.isEmpty();
        boolean bannerVisible = mBrowserTabsAdapter != null
                && mBrowserTabsAdapter.isBannerVisible();
        if (tabsEmpty && !bannerVisible) {
            mLCEERecyclerView.showEmpty();
        } else {
            mLCEERecyclerView.hideAll();
        }
    }

    /** Re-check empty state using the adapter's current tab list. No-op
     *  before the first snapshot is applied — the snapshot path owns
     *  the first reveal. */
    protected void refreshEmptyVisibility() {
        if (mBrowserTabsAdapter == null) return;
        if (!mFirstSnapshotApplied) return;
        updateEmptyVisibility(mBrowserTabsAdapter.getCurrentList());
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        // Horizontal (cutout) insets only. Bottom clearance is owned by the
        // HOLDER, which pads the ViewPager by the bottom action bar's full
        // height (and that height already includes the nav-bar inset, since
        // the bar pads itself). Adding the bottom system-bar inset here too
        // double-reserved it — visible as a gap between the last card row and
        // the bar (worst in landscape, where the short grid makes the clipped
        // padding obvious).
        if (mRecyclerView != null) {
            ViewCompat.setOnApplyWindowInsetsListener(mRecyclerView, (v, windowInsets) -> {
                Insets insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars()
                        | WindowInsetsCompat.Type.displayCutout());
                v.setPadding(insets.left, 0, insets.right, 0);
                return WindowInsetsCompat.CONSUMED;
            });
        }

        // Media indicator
        mGeckoMediaController.getActiveSessionIdsLiveData().observe(getViewLifecycleOwner(),
                sessionIds -> mBrowserTabsAdapter.setMediaSessionIds(sessionIds));

        // Tab list. Before the first snapshot is applied, we just store
        // the latest tabs and try to apply (which gates on banner
        // signal for subclasses that need one). After the snapshot has
        // been applied, this falls through to the regular
        // submitList + active-changed scroll path.
        getTabsLiveData().observe(getViewLifecycleOwner(), tabs -> {
            if (!mFirstSnapshotApplied) {
                mPendingTabs = tabs;
                tryApplyFirstSnapshot();
                return;
            }
            updateEmptyVisibility(tabs);
            mBrowserTabsAdapter.submitList(tabs, () -> {
                if (mRecyclerView == null) return;
                onSubsequentTabsSubmitted(tabs);
            });
        });
    }


    // ── RecyclerView access ──────────────────────────────────────────

    /**
     * Exposes the RecyclerView so the parent holder can drive AppBarLayout
     * lift-on-scroll state manually. liftOnScrollTargetViewId can't resolve
     * across the ViewPager2 boundary, so the holder attaches a scroll listener
     * to whichever page is visible.
     */
    @Nullable
    public RecyclerView getRecyclerView() {
        return mRecyclerView;
    }


    // ── OnItemClickListener ─────────────────────────────────────────

    @Override
    public void onItemClick(int position, int resId) {
        if (position == RecyclerView.NO_POSITION) return;

        int tabPosition = adjustPosition(position);
        if (tabPosition < 0) return;

        GeckoStateEntity entity = mBrowserTabsAdapter.getCurrentList().get(tabPosition);
        if (resId == R.id.tab_close) {
            closeTab(entity);
        } else {
            selectTab(entity);
        }
    }

    @Override
    public void onLongClick(int position, int resId) { }

    @Override
    public void onItemVariantClick(int position, int variant, int resId) { }


    // ── Tab operations ──────────────────────────────────────────────

    protected void selectTab(GeckoStateEntity entity) {
        GeckoState geckoState = getGeckoState(entity.getId());
        if (geckoState == null) return;
        activateGeckoState(geckoState);
        onTabSelected(entity, geckoState);
    }

    protected void closeTab(GeckoStateEntity entity) {
        int sessionId = entity.getId();
        GeckoState geckoState = getGeckoState(sessionId);
        if (geckoState == null) return;

        // Clear cached thumb
        geckoState.getGeckoStateEntity().setCachedThumb(null);

        // Remove from repository
        removeGeckoState(geckoState);
        stopMedia(mGeckoMediaController, geckoState);

        // Deactivate in WebExtensionController
        GeckoSession session = geckoState.getGeckoSession();
        try {
            WebExtensionController controller =
                    mGeckoRuntimeHelper.getGeckoRuntime().getWebExtensionController();
            if (session != null) {
                controller.setTabActive(session, false);
            }
        } catch (NullPointerException e) {
            Log.e(TAG, "closeTab", e);
        }

        onTabClosed(entity, geckoState);
    }


    // ── Grid/List toggle ────────────────────────────────────────────

    public void setGridLayoutMode(boolean enableGrid) {
        mEnableGrid = enableGrid;
        if (mGridLayoutManager != null) {
            mGridLayoutManager.setSpanCount(getSpanCount());
            mBrowserTabsAdapter.enableGrid(enableGrid);
        }
    }

    protected int getSpanCount() {
        return getResources().getInteger(
                !mEnableGrid ? R.integer.image_grid_number : R.integer.image_list_number);
    }


    // ── Swipe to close ──────────────────────────────────────────────

    private final ItemTouchHelper.SimpleCallback mSwipeCallback =
            new ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT | ItemTouchHelper.RIGHT) {

                @Override
                public int getSwipeDirs(@NonNull RecyclerView recyclerView,
                                        @NonNull RecyclerView.ViewHolder viewHolder) {
                    // Banner row is not a tab — block swipe so the user
                    // can't drag it off-screen (it would just snap back,
                    // and the onSwiped guard below would no-op anyway).
                    if (viewHolder.getItemViewType() == BrowserTabsAdapter.TYPE_BANNER) {
                        return 0;
                    }
                    return super.getSwipeDirs(recyclerView, viewHolder);
                }

                @Override
                public boolean onMove(@NonNull RecyclerView recyclerView,
                                      @NonNull RecyclerView.ViewHolder viewHolder,
                                      @NonNull RecyclerView.ViewHolder target) {
                    return false;
                }

                @Override
                public void onSwiped(RecyclerView.ViewHolder viewHolder, int direction) {
                    int position = viewHolder.getAbsoluteAdapterPosition();
                    int tabPosition = adjustPosition(position);
                    if (tabPosition < 0) return;
                    GeckoStateEntity entity = mBrowserTabsAdapter.getCurrentList().get(tabPosition);
                    closeTab(entity);
                }
            };
}