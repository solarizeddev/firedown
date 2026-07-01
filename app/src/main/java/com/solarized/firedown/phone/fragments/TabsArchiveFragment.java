package com.solarized.firedown.phone.fragments;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.view.MenuProvider;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleEventObserver;
import androidx.lifecycle.ViewModelProvider;
import androidx.navigation.NavBackStackEntry;
import androidx.navigation.NavDestination;
import androidx.paging.LoadState;
import androidx.recyclerview.widget.RecyclerView;

import com.solarized.firedown.BuildConfig;
import com.solarized.firedown.R;
import com.solarized.firedown.data.entity.GeckoStateEntity;
import com.solarized.firedown.data.entity.TabStateArchivedEntity;
import com.solarized.firedown.data.models.BrowserURIViewModel;
import com.solarized.firedown.data.models.GeckoStateViewModel;
import com.solarized.firedown.data.models.TabsArchiveViewModel;
import com.solarized.firedown.geckoview.GeckoState;
import com.solarized.firedown.ui.adapters.TabArchiveAdapter;
import com.solarized.firedown.ui.CardViewListItemDecoration;
import com.solarized.firedown.ui.OnItemClickListener;
import com.solarized.firedown.ui.diffs.TabStateArchivedDiffCallback;
import com.solarized.firedown.IntentActions;
import com.solarized.firedown.Keys;
import com.solarized.firedown.utils.NavigationUtils;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

import dagger.hilt.android.AndroidEntryPoint;

@AndroidEntryPoint
public class TabsArchiveFragment extends BaseFocusFragment implements OnItemClickListener {

    private static final String TAG = TabsArchiveFragment.class.getSimpleName();

    private TabArchiveAdapter mAdapter;
    private TabsArchiveViewModel mTabArchiveViewModel;

    // ── P1 migration: activity-scoped ViewModels for direct tab activation ──
    private GeckoStateViewModel mGeckoStateViewModel;
    private BrowserURIViewModel mBrowserURIViewModel;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mTabArchiveViewModel = new ViewModelProvider(this).get(TabsArchiveViewModel.class);

        // Activity-scoped — shared with BrowserFragment, HomeFragment, TabsFragment
        mGeckoStateViewModel = new ViewModelProvider(mActivity).get(GeckoStateViewModel.class);
        mBrowserURIViewModel = new ViewModelProvider(mActivity).get(BrowserURIViewModel.class);
    }

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        View v = inflater.inflate(R.layout.fragment_archive, container, false);
        mToolbar = v.findViewById(R.id.toolbar);
        mLCEERecyclerView = v.findViewById(R.id.list_recycler_lcee);
        mRecyclerView = mLCEERecyclerView.getRecyclerView();


        setupUI();
        setupAdapter();
        setupMenu();

        return v;
    }

    private void setupUI() {
        mRecyclerView.addItemDecoration(new CardViewListItemDecoration(
                getResources().getDimensionPixelSize(R.dimen.list_spacing)));

        mLCEERecyclerView.setEmptyImageView(R.drawable.ill_small_tabs);
        mLCEERecyclerView.setEmptyText(R.string.browser_tabs_empty_archive);


        mToolbar.setContentInsetsAbsolute(getResources().getDimensionPixelSize(R.dimen.address_bar_inset), 0);
        mToolbar.setNavigationOnClickListener(v1 -> {
            if (mActionModeEnabled) {
                stopActionMode();
            } else {
                NavigationUtils.popBackStackSafe(mNavController, R.id.tabs_archive);
            }
        });
    }

    private void setupAdapter() {
        // Ensure this matches the Object-typed constructor
        mAdapter = new TabArchiveAdapter(mActivity, new TabStateArchivedDiffCallback(), this);
        mRecyclerView.setAdapter(mAdapter);
        mRecyclerView.setVerticalScrollBarEnabled(true);
    }

    private void setupMenu() {
        mToolbar.addMenuProvider(new MenuProvider() {
            @Override
            public void onCreateMenu(@NonNull Menu menu, @NonNull MenuInflater menuInflater) {
                menuInflater.inflate(mActionModeEnabled ? R.menu.menu_action : R.menu.menu_tabs_archive, menu);
            }

            @Override
            public boolean onMenuItemSelected(@NonNull MenuItem menuItem) {
                int id = menuItem.getItemId();
                if (id == R.id.action_delete) {
                    handleDeleteAction();
                    return true;
                } else if (id == android.R.id.home) {
                    NavigationUtils.popBackStackSafe(mNavController, R.id.tabs_archive);
                    return true;
                }
                return false;
            }
        }, getViewLifecycleOwner(), Lifecycle.State.RESUMED);
    }

    private void handleDeleteAction() {
        if (mActionModeEnabled) {
            ArrayList<TabStateArchivedEntity> selectedItems = new ArrayList<>();
            HashSet<Integer> selectedPositions = mAdapter.getSelected();

            // Fixed: Use List<Object> because the snapshot contains both headers and items
            List<Object> snapshot = mAdapter.snapshot().getItems();

            for (int position : selectedPositions) {
                if (position < snapshot.size()) {
                    Object item = snapshot.get(position);
                    // Only add if it's an actual Tab, not a Header
                    if (item instanceof TabStateArchivedEntity) {
                        selectedItems.add((TabStateArchivedEntity) item);
                    }
                }
            }
            Bundle bundle = new Bundle();
            bundle.putParcelableArrayList(Keys.ITEM_LIST_ID, selectedItems);
            NavigationUtils.navigateSafe(mNavController, R.id.dialog_delete_tabs, R.id.tabs_archive, bundle);
        } else {
            NavigationUtils.navigateSafe(mNavController, R.id.dialog_delete_tabs, R.id.tabs_archive);
        }
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        postponeEnterTransition();
        releaseEnterTransitionWhenReady(view);
        setupLoadStateListener();
        observeViewModel();
        setupNavigationObservers();
    }

    /**
     * Release the postponed enter transition once the view is ready to
     * draw, DECOUPLED from the paging load state.
     *
     * <p>The previous version started the transition only inside the
     * {@link LoadState.NotLoading} branch of the load-state listener. If
     * that first paging load errored or stalled (never reaching
     * NotLoading), {@code startPostponedEnterTransition} was never called
     * and the screen froze: the new fragment stayed postponed (invisible)
     * over the still-shown previous fragment, so opening "recently closed
     * tabs" looked like "nothing happened" with the prior screen frozen,
     * and a back press — which cancels the pending FragmentManager
     * transaction — was the only way out.</p>
     *
     * <p>Mirrors {@link BaseDownloadFragment#handleTransitionTiming()}:
     * an onPreDraw release for the normal path (so the populated list is
     * shown rather than a blank window) plus a hard timeout so a slow /
     * failed upstream can never wedge the transition.
     * {@code startPostponedEnterTransition} is idempotent, so racing the
     * two paths is safe.</p>
     */
    private void releaseEnterTransitionWhenReady(View view) {
        final ViewGroup parent = (ViewGroup) view.getParent();
        if (parent == null) {
            startPostponedEnterTransition();
            return;
        }
        parent.getViewTreeObserver().addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
            @Override
            public boolean onPreDraw() {
                parent.getViewTreeObserver().removeOnPreDrawListener(this);
                startPostponedEnterTransition();
                return true;
            }
        });
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            if (isAdded()) startPostponedEnterTransition();
        }, TRANSITION_RELEASE_TIMEOUT_MS);
    }

    /** Upper bound on how long the enter transition stays postponed while
     *  waiting for the first paging page. Matches BaseDownloadFragment's
     *  budget — comfortably past a fast query / slow cold DB open without
     *  leaving the user on a blank window if something upstream wedges. */
    private static final long TRANSITION_RELEASE_TIMEOUT_MS = 350L;

    private void observeViewModel() {
        // ViewModel now handles all transformation logic internally
        mTabArchiveViewModel.getTabArchive().observe(getViewLifecycleOwner(), pagingData -> {
            mAdapter.submitData(getViewLifecycleOwner().getLifecycle(), pagingData);
        });
    }

    private void setupLoadStateListener() {
        // Drives ONLY the empty/loaded/error UI — the enter-transition
        // release is handled separately by releaseEnterTransitionWhenReady,
        // so a load that errors or never reaches NotLoading can't freeze the
        // screen. The spinner (LCEE default) is dismissed on EITHER terminal
        // refresh state so a failed load can't leave it spinning forever.
        mAdapter.addLoadStateListener(loadStates -> {
            if (mAdapter == null || mLCEERecyclerView == null) return null;

            LoadState refresh = loadStates.getRefresh();
            if (refresh instanceof LoadState.NotLoading) {
                if (mAdapter.getItemCount() == 0) mLCEERecyclerView.showEmpty();
                else mLCEERecyclerView.hideAll();
            } else if (refresh instanceof LoadState.Error error) {
                // Don't strand the user on the spinner if the paging refresh
                // fails — surface the empty state instead, and log the cause
                // (debug builds only) so the failure is diagnosable.
                if (BuildConfig.DEBUG) {
                    Log.e(TAG, "archive paging refresh error", error.getError());
                }
                mLCEERecyclerView.showEmpty();
            }
            return null;
        });
    }

    private void setupNavigationObservers() {

        final NavDestination navDestination = mNavController.getCurrentDestination();

        if(navDestination == null || navDestination.getId() != R.id.tabs_archive)
            return;

        final NavBackStackEntry navBackStackEntry = mNavController.getBackStackEntry(R.id.tabs_archive);
        final LifecycleEventObserver observer = (source, event) -> {
            if (event.equals(Lifecycle.Event.ON_RESUME)) {
                stopActionMode();
                navBackStackEntry.getSavedStateHandle().remove(IntentActions.ACTION_MODE);
            }
        };

        navBackStackEntry.getLifecycle().addObserver(observer);
        getViewLifecycleOwner().getLifecycle().addObserver((LifecycleEventObserver) (source, event) -> {
            if (event.equals(Lifecycle.Event.ON_DESTROY)) {
                navBackStackEntry.getLifecycle().removeObserver(observer);
            }
        });
    }

    @Override
    public void onItemClick(int position, int resId) {
        if (position == RecyclerView.NO_POSITION) return;

        // Fixed: Check type from snapshot before processing
        Object item = mAdapter.snapshot().get(position);
        if (!(item instanceof TabStateArchivedEntity entity)) return; // Ignore clicks on headers

        if (mActionModeEnabled) {
            mAdapter.setSelected(position);
            setActionModeTitle(mAdapter.getSelectedSize());
        } else {
            if (resId == R.id.file_more) {
                mTabArchiveViewModel.delete(entity);
            } else {
                openArchivedSession(entity);
            }
        }
    }

    /**
     * Opens an archived tab session.
     *
     * <h3>P1 Migration</h3>
     * <p>Previously called {@code setSessionResult(geckoEntity, OPEN_SESSION)} which
     * parceled the entity across the Binder and finished TabsActivity.  Now we
     * activate the tab directly via shared ViewModels and pop the nav stack.</p>
     *
     * <p>Back stack before: {@code Home → [Browser] → Tabs → TabsArchive}<br>
     * After: lands on BrowserFragment with the archived session loaded.</p>
     */
    private void openArchivedSession(TabStateArchivedEntity entity) {
        GeckoStateEntity geckoEntity = new GeckoStateEntity(false);
        geckoEntity.setUri(entity.getUri());
        geckoEntity.setSessionState(entity.getSessionState());
        geckoEntity.setIcon(entity.getIcon());
        // Preserve the tab's ORIGINAL creation date — the archived row kept it
        // (TabStateArchivedRepository.mapToArchivedEntity). Stamping "now" made
        // a restored years-old tab look brand-new and mis-sorted it among the
        // live tabs (which order by creation date).
        geckoEntity.setCreationDate(entity.getCreationDate());

        // 1. Create the GeckoState and activate it in the repository
        GeckoState geckoState = new GeckoState(geckoEntity);
        mGeckoStateViewModel.setGeckoState(geckoState, true);

        // 2. Fire the event so BrowserFragment wires up the session
        mBrowserURIViewModel.onEventSelected(geckoEntity, IntentActions.OPEN_SESSION);

        // 3. Navigate to BrowserFragment, clearing Tabs and TabsArchive from the stack.
        //    The action uses popUpTo="home" (inclusive=false) so the back stack
        //    becomes Home → Browser regardless of entry path.
        Log.d(TAG, "openArchivedSession: navigating to browser, uri=" + entity.getUri());
        NavigationUtils.navigateSafe(mNavController, R.id.action_archive_to_browser);
    }

    @Override
    public void onLongClick(int position, int resId) {
        // Fixed: Ensure we only start action mode on items, not headers
        Object item = mAdapter.snapshot().get(position);
        if (!(item instanceof TabStateArchivedEntity)) return;

        if (mActionModeEnabled) {
            setActionModeTitle(mAdapter.getSelectedSize());
            return;
        }
        mActionModeEnabled = true;
        mToolbar.invalidateMenu();
        mAdapter.setActionMode(true);
        mAdapter.setSelected(position);
        setActionModeTitle(mAdapter.getSelectedSize());
    }

    @Override public void onItemVariantClick(int position, int variant, int resId) {}

    private void stopActionMode() {
        mActionModeEnabled = false;
        mToolbar.invalidateMenu();
        mToolbar.setTitle(R.string.navigation_tabs_archive);
        mAdapter.resetSelected();
        mAdapter.setActionMode(false);
    }
}