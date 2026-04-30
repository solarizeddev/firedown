package com.solarized.firedown.phone.fragments;

import android.os.Bundle;
import android.util.Log;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.LiveData;
import androidx.recyclerview.widget.ConcatAdapter;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.snackbar.Snackbar;
import com.solarized.firedown.IntentActions;
import com.solarized.firedown.Preferences;
import com.solarized.firedown.R;
import com.solarized.firedown.data.entity.GeckoStateEntity;
import com.solarized.firedown.geckoview.GeckoState;
import com.solarized.firedown.ui.adapters.ArchiveBannerAdapter;
import com.solarized.firedown.utils.NavigationUtils;


import java.util.List;
import java.util.concurrent.TimeUnit;

import dagger.hilt.android.AndroidEntryPoint;

/**
 * Tab switcher for regular (non-incognito) tabs.
 *
 * <p>Adds archive banner via ConcatAdapter, undo-on-close snackbar,
 * scroll-to-active behavior, and auto-archive of inactive tabs.</p>
 */
@AndroidEntryPoint
public class TabsFragment extends BaseTabsFragment {

    private static final String TAG = TabsFragment.class.getSimpleName();

    private ArchiveBannerAdapter mBannerAdapter;
    private Snackbar mActiveSnackbar;

    @Override
    public void onDestroyView() {
        if (mActiveSnackbar != null) {
            mActiveSnackbar.dismiss();
            mActiveSnackbar = null;
        }
        mBannerAdapter = null;
        mToolbar = null;
        super.onDestroyView();
    }


    // ── Abstract implementations ─────────────────────────────────────

    @Override
    protected LiveData<List<GeckoStateEntity>> getTabsLiveData() {
        return mGeckoStateViewModel.getTabs();
    }

    @Override
    protected GeckoState getGeckoState(int tabId) {
        return mGeckoStateViewModel.getGeckoState(tabId);
    }

    @Override
    protected void activateGeckoState(GeckoState geckoState) {
        mGeckoStateViewModel.setGeckoState(geckoState, true);
    }

    @Override
    protected void removeGeckoState(GeckoState geckoState) {
        mGeckoStateViewModel.closeGeckoState(geckoState);
    }

    @Override
    protected int getEmptyTextRes() {
        return R.string.browser_tabs_empty;
    }

    @Override
    protected void onTabSelected(GeckoStateEntity entity, GeckoState geckoState) {
        if (entity.isHome()) {
            getParentFragmentManager().setFragmentResult(
                    TabsHolderFragment.KEY_SELECT_HOME, Bundle.EMPTY);
        } else {
            mBrowserURIViewModel.onEventSelected(
                    geckoState.getGeckoStateEntity(), IntentActions.OPEN_SESSION);
            Bundle args = new Bundle();
            args.putBoolean("incognito", false);
            getParentFragmentManager().setFragmentResult(
                    TabsHolderFragment.KEY_SELECT_BROWSER, args);
        }
    }

    @Override
    protected void onTabClosed(GeckoStateEntity entity, GeckoState geckoState) {
        mActiveSnackbar = makeSnackbar(getString(R.string.snackbar_tab_closed));
        mActiveSnackbar.setAction(R.string.snackbar_deleted_undo, view ->
                mGeckoStateViewModel.setGeckoState(geckoState, true));
        mActiveSnackbar.addCallback(new Snackbar.Callback() {
            @Override
            public void onDismissed(Snackbar transientBottomBar, int event) {
                if (event != DISMISS_EVENT_ACTION) {
                    geckoState.closeGeckoSession();
                }
            }
        });
        mActiveSnackbar.show();
    }

    @Override
    protected int adjustPosition(int position) {
        return position - mBannerAdapter.getItemCount();  // negative means banner was clicked
    }

    @Override
    protected void setupRecyclerView() {
        // Banner adapter
        mBannerAdapter = new ArchiveBannerAdapter(new ArchiveBannerAdapter.OnBannerActionListener() {
            @Override
            public void onViewArchive() {
                mBannerAdapter.dismiss();
                mGeckoStateViewModel.clearArchivedCountEvent();
                NavigationUtils.navigateSafe(mNavController, R.id.tabs_archive);
            }

            @Override
            public void onDismiss() {
                mBannerAdapter.dismiss();
                mGeckoStateViewModel.clearArchivedCountEvent();
            }
        });

        // ConcatAdapter wraps banner + tabs
        ConcatAdapter.Config config = new ConcatAdapter.Config.Builder()
                .setIsolateViewTypes(true)
                .build();
        ConcatAdapter concatAdapter = new ConcatAdapter(config, mBannerAdapter, mBrowserTabsAdapter);

        // Banner spans full width in grid mode
        mGridLayoutManager.setSpanSizeLookup(new GridLayoutManager.SpanSizeLookup() {
            @Override
            public int getSpanSize(int position) {
                if (position < mBannerAdapter.getItemCount()) {
                    return mGridLayoutManager.getSpanCount();
                }
                return 1;
            }
        });

        mRecyclerView.setLayoutManager(mGridLayoutManager);
        mRecyclerView.setAdapter(concatAdapter);
        mRecyclerView.addItemDecoration(new com.solarized.firedown.ui.EqualSpacingItemDecoration(
                getResources().getDimensionPixelSize(R.dimen.list_item_margin)));
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        Log.d(TAG, "onViewCreated");

        // Archive banner observer
        mGeckoStateViewModel.getArchivedCountEvent().observe(getViewLifecycleOwner(), count -> {
            if (count != null && count > 0) {
                mBannerAdapter.show(count);
            }
        });

        // Debounced auto-archive trigger
        boolean autoArchiveEnabled = mSharedPreferences.getBoolean(
                Preferences.SETTINGS_TABS_ARCHIVE, true);

        if (autoArchiveEnabled) {
            long lastRun = mSharedPreferences.getLong(Preferences.SETTINGS_TABS_ARCHIVE_LAST_RUN, 0);
            long now = System.currentTimeMillis();
            if (now - lastRun > TimeUnit.HOURS.toMillis(6)) {
                long thresholdMillis = mSharedPreferences.getLong(
                        Preferences.SETTINGS_TABS_ARCHIVE_INTERVAL,
                        Preferences.ONE_WEEK_INTERVAL);
                mGeckoStateViewModel.archiveInactiveTabs(thresholdMillis);
                mSharedPreferences.edit()
                        .putLong(Preferences.SETTINGS_TABS_ARCHIVE_LAST_RUN, now)
                        .apply();
            }
        }
    }


    // ── Snackbar helper ─────────────────────────────────────────────

    private Snackbar makeSnackbar(String text) {
        View coordinator = getParentFragment() != null && getParentFragment().getView() != null
                ? getParentFragment().getView().findViewById(R.id.coordinator_root)
                : mRecyclerView;
        View fab = getParentFragment() != null && getParentFragment().getView() != null
                ? getParentFragment().getView().findViewById(R.id.fab_new_tab)
                : null;

        Snackbar snackbar = Snackbar.make(coordinator, text, Snackbar.LENGTH_LONG);
        if (fab != null) snackbar.setAnchorView(fab);
        return snackbar;
    }
}