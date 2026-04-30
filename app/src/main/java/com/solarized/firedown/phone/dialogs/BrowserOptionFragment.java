package com.solarized.firedown.phone.dialogs;

import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.Toolbar;
import androidx.core.content.res.ResourcesCompat;
import androidx.core.graphics.drawable.DrawableCompat;
import androidx.core.view.MenuProvider;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.chip.ChipGroup;
import com.solarized.firedown.Keys;
import com.solarized.firedown.Preferences;
import com.solarized.firedown.R;
import com.solarized.firedown.Sorting;
import com.solarized.firedown.data.entity.BrowserDownloadEntity;
import com.solarized.firedown.data.entity.OptionEntity;
import com.solarized.firedown.data.models.BrowserDownloadViewModel;
import com.solarized.firedown.data.models.FragmentsOptionsViewModel;
import com.solarized.firedown.manager.DownloadRequest;
import com.solarized.firedown.phone.DownloadsActivity;
import com.solarized.firedown.phone.SettingsActivity;
import com.solarized.firedown.phone.VaultActivity;
import com.solarized.firedown.phone.fragments.BaseFocusFragment;
import com.solarized.firedown.ui.EqualSpacingItemDecoration;
import com.solarized.firedown.ui.IncognitoColors;
import com.solarized.firedown.ui.OnItemClickListener;
import com.solarized.firedown.ui.adapters.BrowserOptionAdapter;
import com.solarized.firedown.ui.diffs.BrowserDownloadsDiffCallback;
import com.solarized.firedown.utils.NavigationUtils;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

import javax.inject.Inject;
import dagger.hilt.android.AndroidEntryPoint;

@AndroidEntryPoint
public class BrowserOptionFragment extends BaseFocusFragment implements OnItemClickListener, ChipGroup.OnCheckedStateChangeListener {

    private static final String TAG = BrowserOptionFragment.class.getName();
    @Inject SharedPreferences mSharedPreferences;
    private BrowserOptionAdapter mAdapter;
    private BrowserDownloadViewModel mBrowserDownloadViewModel;
    private FragmentsOptionsViewModel mFragmentsViewModel;
    private GridLayoutManager mLayoutManager;
    private ChipGroup mChipGroup;
    private Toolbar mToolbar;
    private boolean mEnableGrid;
    private boolean mIsIncognito;
    private int mScrollLimit;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mScrollLimit = Preferences.LIST_LIMIT;
        mEnableGrid = mSharedPreferences.getBoolean(Preferences.SORT_LIST, false);
        mIsIncognito = getArguments() != null && getArguments().getBoolean(Keys.IS_INCOGNITO, false);
        mBrowserDownloadViewModel = new ViewModelProvider(this).get(BrowserDownloadViewModel.class);
        mFragmentsViewModel = new ViewModelProvider(mActivity).get(FragmentsOptionsViewModel.class);
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {

        LayoutInflater themedInflater = container != null
                ? LayoutInflater.from(container.getContext())
                : inflater;

        View view = themedInflater.inflate(R.layout.fragment_dialog_browser_options, container, false);

        mToolbar = view.findViewById(R.id.toolbar);
        mChipGroup = view.findViewById(R.id.chip_group);
        mLCEERecyclerView = view.findViewById(R.id.list_recycler_lcee);
        mLCEERecyclerView.setEmptyButtonVisibility(View.VISIBLE);

        if (mIsIncognito) {
            mToolbar.setPopupTheme(R.style.Theme_FireDown_Popup_Vault);
            int onSurface = IncognitoColors.getOnSurface(requireContext(), true);
            mToolbar.setTitleTextColor(onSurface);
            Drawable navIcon = mToolbar.getNavigationIcon();
            if (navIcon != null) DrawableCompat.setTint(navIcon, onSurface);
        }

        mChipGroup.setOnCheckedStateChangeListener(this);
        mChipGroup.check(mBrowserDownloadViewModel.getCurrentSortBrowserId());

        mToolbar.setContentInsetsAbsolute(getResources().getDimensionPixelSize(R.dimen.list_spacing), 0);

        mLCEERecyclerView.setButtonListener(id -> {
            OptionEntity option = new OptionEntity();
            option.setId(id);
            mFragmentsViewModel.onOptionsSelected(option);
        });

        mRecyclerView = mLCEERecyclerView.getRecyclerView();
        mLayoutManager = new GridLayoutManager(requireContext(), getSpanCount());
        mRecyclerView.setLayoutManager(mLayoutManager);

        mAdapter = new BrowserOptionAdapter(mActivity, new BrowserDownloadsDiffCallback(), this, mEnableGrid);
        mRecyclerView.setAdapter(mAdapter);

        Log.d("SpanDebug", "listMode=" + mEnableGrid
                + " span=" + getSpanCount()
                + " adapter.mList=" + mAdapter.mList
                + " listCount=" + mAdapter.getCurrentList().size());

        updateItemDecoration();

        mRecyclerView.addOnScrollListener(new RecyclerView.OnScrollListener() {

            @Override
            public void onScrollStateChanged(@NonNull RecyclerView recyclerView, int newState) {
                if (!recyclerView.canScrollVertically(1) && newState == RecyclerView.SCROLL_STATE_IDLE) {
                    mScrollLimit += Preferences.LIST_LIMIT;
                    mBrowserDownloadViewModel.loadMore(mScrollLimit);
                }
            }
        });

        mToolbar.invalidateMenu();
        mToolbar.addMenuProvider(new MenuProvider() {
            @Override
            public void onCreateMenu(@NonNull Menu menu, @NonNull MenuInflater menuInflater) {
                if(mIsIncognito){
                    menuInflater.inflate(mActionModeEnabled ? R.menu.menu_capture_action : R.menu.menu_capture_incognito, menu);
                }else{
                    menuInflater.inflate(mActionModeEnabled ? R.menu.menu_capture_action : R.menu.menu_capture, menu);
                }
                MenuItem actionView = menu.findItem(R.id.action_view);
                if (actionView != null) {
                    actionView.setIcon(mEnableGrid ? R.drawable.ic_grid_view_24 : R.drawable.ic_view_list_24);
                }
                if (mIsIncognito) {
                    int onSurface = IncognitoColors.getOnSurface(requireContext(), true);
                    for (int i = 0; i < menu.size(); i++) {
                        Drawable icon = menu.getItem(i).getIcon();
                        if (icon != null) DrawableCompat.setTint(icon, onSurface);
                    }
                    Drawable overflow = mToolbar.getOverflowIcon();
                    if (overflow != null) DrawableCompat.setTint(overflow, onSurface);
                }
            }

            @Override
            public boolean onMenuItemSelected(@NonNull MenuItem menuItem) {
                int id = menuItem.getItemId();
                if (id == R.id.action_view) {
                    toggleViewMode(menuItem);
                    return true;
                } else if (id == R.id.action_clear) {
                    mBrowserDownloadViewModel.clearBrowserDownloads();
                    return true;
                } else if (id == R.id.action_download) {
                    processBatchDownload();
                    return true;
                } else if (id == R.id.action_select_all) {
                    mAdapter.selectAll();
                    updateActionModeTitle();
                    return true;
                } else if (id == R.id.action_select_all_not) {
                    mAdapter.clearSelection();
                    updateActionModeTitle();
                    return true;
                } else if (id == R.id.action_downloads) {
                    mStartForResult.launch(new Intent(requireContext(), DownloadsActivity.class));
                    return true;
                } else if (id == R.id.action_safe) {
                    mStartForResult.launch(new Intent(requireContext(), VaultActivity.class));
                    return true;
                } else if (id == R.id.action_settings) {
                    mStartForResult.launch(new Intent(requireContext(), SettingsActivity.class));
                    return true;
                }
                return false;
            }
        }, getViewLifecycleOwner(), Lifecycle.State.RESUMED);

        return view;
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        mBrowserDownloadViewModel.getBrowserDownloads(mScrollLimit).observe(getViewLifecycleOwner(), downloads -> {

            Log.d(TAG, "getBrowserDownloads: " + (downloads != null ? downloads.size() : 0));

            if (mActionModeEnabled) return;

            if (downloads == null || downloads.isEmpty()) {
                configureEmptyState();
                mLCEERecyclerView.showEmpty();
            } else {
                mLCEERecyclerView.hideAll();
            }

            mAdapter.submitList(downloads);
        });

        mBrowserDownloadViewModel.update();
    }


    private void toggleViewMode(MenuItem item) {
        mEnableGrid = !mEnableGrid;
        mAdapter.enableGrid(mEnableGrid);
        mLayoutManager.setSpanCount(getSpanCount());
        updateItemDecoration();
        item.setIcon(mEnableGrid ? R.drawable.ic_grid_view_24 : R.drawable.ic_view_list_24);
    }

    private void updateItemDecoration() {
        while (mRecyclerView.getItemDecorationCount() > 0) mRecyclerView.removeItemDecorationAt(0);
        if (!mEnableGrid) {
            mRecyclerView.addItemDecoration(new EqualSpacingItemDecoration(requireContext(), R.dimen.list_spacing));
        }
    }

    private int getSpanCount() {
        return getResources().getInteger(mEnableGrid ? R.integer.browser_list_number : R.integer.browser_grid_number);
    }

    private void configureEmptyState() {
        int selectedId = mChipGroup.getCheckedChipId();
        int textRes = R.string.capture_empty_message;
        int imgRes = R.drawable.ill_baloons;

        if (selectedId == R.id.chip_video) {
            textRes = R.string.capture_empty_video_message;
            imgRes = R.drawable.ill_small_video;
        } else if (selectedId == R.id.chip_audio) {
            textRes = R.string.capture_empty_audio_message;
            imgRes = R.drawable.ill_small_audio;
        } else if (selectedId == R.id.chip_image) {
            textRes = R.string.capture_empty_images_message;
            imgRes = R.drawable.ill_small_image;
        } else if (selectedId == R.id.chip_svg) {
            textRes = R.string.capture_empty_svgs_message;
            imgRes = R.drawable.ill_small_doc;
        } else if (selectedId == R.id.chip_gif) {
            textRes = R.string.capture_empty_gifs_message;
            imgRes = R.drawable.ill_small_gif;
        } else if (selectedId == R.id.chip_subtitle) {
            textRes = R.string.capture_empty_subtitles_message;
            imgRes = R.drawable.ill_small_doc;
        }

        mLCEERecyclerView.setEmptyText(textRes);
        mLCEERecyclerView.setEmptySubText(R.string.capture_empty_subtext);
        mLCEERecyclerView.setEmptyImageView(imgRes);
    }

    // ========================================================================
    // Click handling
    // ========================================================================

    @Override
    public void onItemClick(int position, int resId) {
        if (position == RecyclerView.NO_POSITION) return;

        if (mActionModeEnabled) {
            mAdapter.toggleSelected(position);
            updateActionModeTitle();
            return;
        }

        BrowserDownloadEntity entity = mAdapter.getCurrentList().get(position);

        if (resId == R.id.item) {
            handlePrimaryItemClick(entity);
        } else if (resId == R.id.item_download_more) {
            // "More options" — opens variant picker or details
            sendOptionEvent(resId, entity);
        }
    }

    private void handlePrimaryItemClick(BrowserDownloadEntity entity) {
        if (mSharedPreferences.getBoolean(Preferences.SETTINGS_SAVE_ASK, Preferences.DEFAULT_SETTINGS_SAVE_ASK)) {
            // "Ask filename" setting is on — navigate to save dialog (entity still needed for display)
            Bundle bundle = new Bundle();
            bundle.putParcelable(Keys.ITEM_ID, entity);
            NavigationUtils.navigateSafe(mNavController, R.id.dialog_save_file, bundle);
        } else {
            // Direct download — build DownloadRequest from entity (best stream auto-selected)
            dispatchDownload(entity);
        }
    }

    /**
     * Build a DownloadRequest from the entity and dispatch it.
     * Uses getSelectedStream() which defaults to best quality (first variant).
     * No entity mutation — the request is immutable.
     */
    private void dispatchDownload(BrowserDownloadEntity entity) {
        DownloadRequest request = DownloadRequest.from(entity);

        OptionEntity option = new OptionEntity();
        option.setId(R.id.start_download);
        option.setDownloadRequest(request);
        mFragmentsViewModel.onOptionsSelected(option);
    }

    // ========================================================================
    // Batch download
    // ========================================================================

    private void processBatchDownload() {
        ArrayList<DownloadRequest> requests = new ArrayList<>();
        List<BrowserDownloadEntity> list = mAdapter.getCurrentList();
        HashSet<Integer> selectedIds = mAdapter.getSelected();

        for (BrowserDownloadEntity entity : list) {
            if (selectedIds.contains(entity.getUid())) {
                requests.add(DownloadRequest.from(entity));
            }
        }

        OptionEntity option = new OptionEntity();
        option.setId(R.id.start_multiple_download);
        option.setDownloadRequests(requests);
        mFragmentsViewModel.onOptionsSelected(option);
        invalidateActionMode();
    }

    // ========================================================================
    // Action mode
    // ========================================================================

    @Override
    public void onLongClick(int position, int resId) {
        if (!mActionModeEnabled) {
            sendOptionEvent(R.id.divider, null);
            mActionModeEnabled = true;
            mAdapter.setActionMode(true);
            mAdapter.toggleSelected(position);
            setChipsEnabled(false);
            updateActionModeTitle();
            mToolbar.invalidateMenu();
        }
    }

    public boolean isActionMode() {
        return mActionModeEnabled;
    }

    public void invalidateActionMode() {
        mActionModeEnabled = false;
        mAdapter.clearSelection();
        mAdapter.setActionMode(false);
        setChipsEnabled(true);
        mToolbar.setTitle(R.string.navigation_captured);
        mToolbar.setBackgroundColor(ResourcesCompat.getColor(getResources(), R.color.transparent, null));
        mToolbar.invalidateMenu();
        mBrowserDownloadViewModel.update();
        sendOptionEvent(R.id.divider, null);
    }

    // ========================================================================
    // Helpers
    // ========================================================================

    private void sendOptionEvent(int id, BrowserDownloadEntity entity) {
        OptionEntity option = new OptionEntity();
        option.setId(id);
        option.setBrowserDownloadEntity(entity);
        mFragmentsViewModel.onOptionsSelected(option);
    }

    private void updateActionModeTitle() {
        mToolbar.setTitle(getString(R.string.action_mode_selected, mAdapter.getSelectedSize()));
    }

    private void setChipsEnabled(boolean enabled) {
        for (int i = 0; i < mChipGroup.getChildCount(); i++) {
            mChipGroup.getChildAt(i).setEnabled(enabled);
        }
        mChipGroup.setEnabled(enabled);
    }

    @Override
    public void onCheckedChanged(@NonNull ChipGroup group, @NonNull List<Integer> checkedIds) {
        if (!checkedIds.isEmpty()) {
            String type = mBrowserDownloadViewModel.getCurrentSortForIds(checkedIds.get(0));
            mBrowserDownloadViewModel.sortBrowserDownloads(type);
        }
    }


    @Override
    public void onStop() {
        super.onStop();
        mBrowserDownloadViewModel.setCurrentSortBrowser(mChipGroup.getCheckedChipId());
        mSharedPreferences.edit().putBoolean(Preferences.SORT_LIST, mEnableGrid).apply();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        mChipGroup = null;
        mToolbar = null;
        mAdapter = null;
        mLayoutManager = null;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        mBrowserDownloadViewModel.setCurrentSortBrowser(Sorting.SORT_TYPE_ALL);
    }

    @Override
    public void onItemVariantClick(int position, int variant, int resId) {
        if (position == RecyclerView.NO_POSITION) return;
        BrowserDownloadEntity entity = mAdapter.getCurrentList().get(position);
        handlePrimaryItemClick(entity);
    }
}