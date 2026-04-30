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
import androidx.fragment.app.Fragment;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
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
    protected GridLayoutManager mGridLayoutManager;
    protected boolean mEnableGrid;

    /** Tracks the most recently seen active tab id so onTabListSubmitted can
     *  detect when the active tab identity changes (new tab created, user
     *  switched tabs elsewhere, etc.) versus when the list is just being
     *  re-submitted for unrelated reasons (title updates, thumb loads). */
    protected int mLastTabActive;

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

    /**
     * Adjusts the raw adapter position to account for any header adapters
     * (e.g. banner in ConcatAdapter). Default returns position unchanged.
     *
     * <p>Data-space → adapter-space conversion is the inverse: use
     * {@link #getLeadingAdapterCount()}.</p>
     */
    protected int adjustPosition(int position) {
        return position;
    }

    /**
     * Number of rows the RecyclerView's adapter renders *before* the tab
     * data starts — for ConcatAdapter setups with a banner/header. Used
     * when translating a data-space index (e.g. active tab position) to
     * an adapter-space index for {@code scrollToPosition}.
     *
     * <p>Default is 0. Subclasses that wrap {@code mBrowserTabsAdapter}
     * in a ConcatAdapter override this.</p>
     */
    protected int getLeadingAdapterCount() {
        return 0;
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
    protected void onTabListSubmitted(List<GeckoStateEntity> tabs) {
        if (tabs == null || tabs.isEmpty() || mRecyclerView == null) {
            // Empty list → reset tracking so the next populated emission
            // registers its active tab as a change.
            mLastTabActive = 0;
            return;
        }

        // DIAGNOSTIC — how many tabs are flagged active?
        int activeCount = 0;
        StringBuilder activeIds = new StringBuilder();
        for (GeckoStateEntity e : tabs) {
            if (e.isActive()) {
                activeCount++;
                activeIds.append(e.getId()).append(" ");
            }
        }
        Log.d("BaseTabsFragment", "onTabListSubmitted: " + tabs.size() + " tabs, "
                + activeCount + " active (ids: " + activeIds + ")");

        // Locate the active tab in data-space.
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

        // Snapshot BEFORE updating mLastTabActive so the comparison works.
        boolean activeChanged = activeId != -1 && activeId != mLastTabActive;
        boolean userTouching =
                mRecyclerView.getScrollState() != RecyclerView.SCROLL_STATE_IDLE;

        if (activeId != -1) {
            mLastTabActive = activeId;
        }

        if (activeChanged
                && !userTouching
                && activePosition >= 0
                && mRecyclerView.getLayoutManager() instanceof LinearLayoutManager) {
            final int adapterTarget = activePosition + getLeadingAdapterCount();
            Log.d(TAG, "scrollToPosition(" + adapterTarget + ") "
                    + "dataPos=" + activePosition + " activeId=" + activeId);
            final RecyclerView target = mRecyclerView;
            target.post(() -> {
                if (target != mRecyclerView) return;
                target.scrollToPosition(adapterTarget);

                // scrollToPosition doesn't fire onScrolled, so the holder's
                // scroll listener won't see this move. Nudge the holder to
                // re-sample after the layout pass settles so the lift scrim
                // reflects the new scroll position.
                target.post(() -> {
                    if (target != mRecyclerView) return;
                    Fragment parent = getParentFragment();
                    if (parent instanceof TabsHolderFragment) {
                        ((TabsHolderFragment) parent).refreshAppBarLiftFor(target);
                    }
                });
            });
        }
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
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {

        View view = inflater.inflate(R.layout.fragment_tabs, container, false);

        mLCEERecyclerView = view.findViewById(R.id.list_recycler_lcee);
        mLCEERecyclerView.setEmptyText(getEmptyTextRes());
        mLCEERecyclerView.setEmptyImageView(R.drawable.ill_small_tabs2);

        mRecyclerView = mLCEERecyclerView.getRecyclerView();

        mBrowserTabsAdapter = new BrowserTabsAdapter(mActivity, new GeckoStateDiffCallback(), this, mEnableGrid);

        mGridLayoutManager = new GridLayoutManager(requireContext(), getSpanCount());

        setupRecyclerView();

        ItemTouchHelper itemTouchHelper = new ItemTouchHelper(mSwipeCallback);
        itemTouchHelper.attachToRecyclerView(mRecyclerView);

        return view;
    }

    /**
     * Sets up the RecyclerView with layout manager, adapter, and decoration.
     * Override to wrap the adapter in a ConcatAdapter, add scroll listeners, etc.
     */
    protected void setupRecyclerView() {
        mRecyclerView.setLayoutManager(mGridLayoutManager);
        mRecyclerView.setAdapter(mBrowserTabsAdapter);
        mRecyclerView.addItemDecoration(new EqualSpacingItemDecoration(
                getResources().getDimensionPixelSize(R.dimen.list_item_margin)));
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        // Media indicator
        mGeckoMediaController.getActiveSessionIdsLiveData().observe(getViewLifecycleOwner(),
                sessionIds -> mBrowserTabsAdapter.setMediaSessionIds(sessionIds));

        // Tab list
        getTabsLiveData().observe(getViewLifecycleOwner(), tabs -> {
            if (tabs == null || tabs.isEmpty()) {
                mLCEERecyclerView.showEmpty();
            } else {
                mLCEERecyclerView.hideAll();
            }
            mBrowserTabsAdapter.submitList(tabs, () -> {
                if (mRecyclerView == null) return;
                onTabListSubmitted(tabs);
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