package com.solarized.firedown.phone.fragments;

import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.graphics.drawable.DrawableCompat;
import androidx.core.view.MenuProvider;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.ViewModelProvider;
import androidx.paging.LoadState;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.chip.ChipGroup;
import com.google.android.material.color.MaterialColors;
import com.solarized.firedown.Preferences;
import com.solarized.firedown.R;
import com.solarized.firedown.data.Download;
import com.solarized.firedown.data.TaskEvent;
import com.solarized.firedown.data.entity.DownloadEntity;
import com.solarized.firedown.data.models.DownloadsViewModel;
import com.solarized.firedown.data.models.TaskViewModel;
import com.solarized.firedown.manager.ServiceActions;
import com.solarized.firedown.ui.adapters.DownloadItemAdapter;
import com.solarized.firedown.ui.OnItemClickListener;
import com.solarized.firedown.ui.diffs.DownloadDiffCallback;
import com.solarized.firedown.IntentActions;
import com.solarized.firedown.Keys;
import com.solarized.firedown.utils.NavigationUtils;

import java.util.List;

public class DownloadFragment extends BaseDownloadFragment implements
        EditText.OnEditorActionListener,
        ChipGroup.OnCheckedStateChangeListener,
        OnItemClickListener {

    private static final String TAG = DownloadFragment.class.getSimpleName();
    private ChipGroup mChipGroup;

    /** Set when a new query has been dispatched; consumed on the next successful refresh. */
    private boolean mPendingScrollToTop = false;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mGridPreference = Preferences.SORT_DOWNLOADS_LIST;
        mDestinationTitle = R.string.navigation_downloads;
        mCurrentDestinationId = R.id.downloads;
        mEnableGrid = mSharedPreferences.getBoolean(mGridPreference, false);

        mDownloadsViewModel = new ViewModelProvider(this).get(DownloadsViewModel.class);
        mTaskViewModel = new ViewModelProvider(this).get(TaskViewModel.class);

        setupBackPressLogic();
    }

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_download, container, false);
        initViews(view);
        setupRecyclerView();
        setupToolbar();
        return view;
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        postponeEnterTransition();
        observeViewModelData();
        setupNavigationResultObserver();
    }

    @Override
    public void onDestroyView() {
        mAdapter = null;
        mGridLayoutManager = null;
        mBottomProgressView = null;
        mChipGroup = null;
        mSearchView = null;
        mSearchItem = null;
        super.onDestroyView();
    }

    private void initViews(View view) {
        mBottomProgressView = view.findViewById(R.id.bottom_progress_view);
        mAppBarLayout = view.findViewById(R.id.appbar_layout);
        mLCEERecyclerView = view.findViewById(R.id.list_recycler_lcee);
        mChipGroup = view.findViewById(R.id.chip_group);
        mToolbar = view.findViewById(R.id.toolbar);

        mChipGroup.setOnCheckedStateChangeListener(this);
    }

    private void setupRecyclerView() {
        mRecyclerView = mLCEERecyclerView.getRecyclerView();

        mLCEERecyclerView.setEmptyImageView(R.drawable.ill_baloons);
        mAdapter = new DownloadItemAdapter(getContext(), new DownloadDiffCallback(), this, mEnableGrid);
        mRecyclerView.setAdapter(mAdapter);
        mRecyclerView.setVerticalScrollBarEnabled(true);

        configureRecyclerView(mAdapter, mEnableGrid);

        mAdapter.addLoadStateListener(loadStates -> {
            if (mAdapter == null || mLCEERecyclerView == null) return null;
            if (loadStates.getRefresh() instanceof LoadState.NotLoading) {
                if (mAdapter.getItemCount() == 0) {
                    int chipId = mChipGroup.getCheckedChipId();
                    mLCEERecyclerView.setEmptyText(chipId == R.id.chip_all ? R.string.empty_list : R.string.empty_list_type);
                    mLCEERecyclerView.setEmptyImageView(getEmptyIcon(chipId));
                    mLCEERecyclerView.showEmpty();
                } else {
                    mLCEERecyclerView.hideAll();
                    if (mPendingScrollToTop && mRecyclerView != null) {
                        mPendingScrollToTop = false;
                        mRecyclerView.scrollToPosition(0);
                    }
                }
                handleTransitionTiming();
            }
            return null;
        });
    }

    private void setupToolbar() {
        Drawable overflowIcon = mToolbar.getOverflowIcon();
        if (overflowIcon != null) {
            DrawableCompat.setTint(overflowIcon, MaterialColors.getColor(mToolbar, com.google.android.material.R.attr.colorOnSurface));
        }
        mToolbar.setContentInsetsAbsolute(getResources().getDimensionPixelSize(R.dimen.address_bar_inset), 0);
        mToolbar.setNavigationOnClickListener(v -> {
            if (mOperationActive && mActionModeEnabled) navigateToCancelDialog();
            else if (mActionModeEnabled) stopActionMode();
            else mActivity.finish();
        });

        mToolbar.addMenuProvider(new MenuProvider() {
            @Override
            public void onCreateMenu(@NonNull Menu menu, @NonNull MenuInflater inflater) {
                if (mActionModeEnabled) {
                    inflater.inflate(mOperationActive ? R.menu.menu_action_empty : R.menu.menu_action_download, menu);
                } else {
                    inflater.inflate(R.menu.menu_download, menu);
                    setupSearchView(menu);
                    MenuItem actionView = menu.findItem(R.id.action_view);
                    if (actionView != null) actionView.setIcon(mEnableGrid ? R.drawable.ic_view_list_24 : R.drawable.ic_grid_view_24);
                }
            }
            @Override
            public boolean onMenuItemSelected(@NonNull MenuItem item) {
                return handleMenuAction(item);
            }
        }, getViewLifecycleOwner(), Lifecycle.State.RESUMED);
    }

    private void observeViewModelData() {
        mTaskViewModel.getObservableEvent().observe(getViewLifecycleOwner(), event -> {
            if (event instanceof TaskEvent.Started started) {
                if (started.getAction() == ServiceActions.DECRYPTION) return;
                handleTaskStart(started.getAction());

            } else if (event instanceof TaskEvent.Progress progress) {
                mBottomProgressView.setProgress(progress.getPercent());

            } else if (event instanceof TaskEvent.Finished finished) {
                if (finished.getAction() == ServiceActions.DECRYPTION) return;
                handleTaskFinish(finished.getAction(), finished.getResult());

            } else if (event instanceof TaskEvent.Deleted deleted) {
                showActionSnackbar(R.plurals.complete_delete_files_text, deleted.getCount(), mCurrentDestinationId == R.id.vault);

            } else if (event instanceof TaskEvent.Error error) {
                showActionSnackbar(R.plurals.move_file_fail, error.getCount(), mCurrentDestinationId == R.id.vault);

            } else if (event instanceof TaskEvent.Cancelled) {
                mOperationActive = false;
            }
        });

        mDownloadsViewModel.getDownloads().observe(getViewLifecycleOwner(), data ->
                mAdapter.submitData(getLifecycle(), data));

        // Scroll the list back to the top whenever a new (distinct) query is dispatched.
        // distinctUntilChanged on the VM side suppresses the spurious initial emission
        // caused by chip/sort changes, but the first observation still fires once — which
        // is harmless because the list is already at position 0 on first load.
        mDownloadsViewModel.getDispatchedQuery().observe(getViewLifecycleOwner(),
                q -> mPendingScrollToTop = true);
    }

    @Override
    public boolean onEditorAction(TextView v, int aId, KeyEvent e) {
        return false;
    }

    @Override
    public void onItemClick(int position, int resId) {
        if (position == RecyclerView.NO_POSITION)
            return;

        Object item = mAdapter.peek(position);
        if (!(item instanceof DownloadEntity entity))
            return;

        if (mActionModeEnabled) {
            mAdapter.setSelected(position);
            setActionModeTitle(mAdapter.getSelectedSize());
            return;
        }

        if (resId == R.id.item) {
            int status = entity.getFileStatus();
            if (status == Download.ERROR) openSourceUrl(entity);
            else openItem(entity, mRecyclerView.findViewHolderForAdapterPosition(position));
        } else if (resId == R.id.item_download_action) {
            if (entity.getFileStatus() == Download.QUEUED) {
                handleItemAction(IntentActions.DOWNLOAD_DELETE, entity);
            } else {
                Bundle b = new Bundle();
                b.putParcelable(Keys.ITEM_ID, entity);
                b.putInt(Keys.ITEM_POSITION, position);
                NavigationUtils.navigateSafe(mNavController, R.id.dialog_download_options, R.id.downloads, b);
            }
        }
    }

    @Override
    public void onLongClick(int position, int resId) {
        Object item = mAdapter.peek(position);
        if (!(item instanceof DownloadEntity)) return;
        if (isSearchActive()) return;
        startActionMode(position);
    }

    @Override
    public void onItemVariantClick(int position, int variant, int resId) {

    }

    private int getEmptyIcon(int chipId) {
        if (chipId == R.id.chip_video)
            return R.drawable.ill_small_video;
        else if (chipId == R.id.chip_audio)
            return R.drawable.ill_small_audio;
        else if (chipId == R.id.chip_image)
            return R.drawable.ill_small_image;
        else if (chipId == R.id.chip_doc)
            return R.drawable.ill_small_doc;
        else if (chipId == R.id.chip_gif)
            return R.drawable.ill_small_gif;
        else if (chipId == R.id.chip_apk)
            return R.drawable.ill_small_apk;
        else
            return R.drawable.ill_baloons;
    }

    private void setChipEnable(boolean status) {
        for (int i = 0; i < mChipGroup.getChildCount(); i++) {
            mChipGroup.getChildAt(i).setEnabled(status);
        }
        mChipGroup.setEnabled(status);
    }

    @Override
    protected void stopActionMode() {
        super.stopActionMode();
        setChipEnable(true);
    }

    @Override
    protected void startActionMode(int position) {
        super.startActionMode(position);
        setChipEnable(false);
    }

    @Override
    public void onCheckedChanged(@NonNull ChipGroup g, @NonNull List<Integer> checkedIds) {
        if (checkedIds.isEmpty()) {
            mDownloadsViewModel.setFilterChip(R.id.chip_all);
            return;
        }
        int checkedId = checkedIds.get(0);
        mDownloadsViewModel.setFilterChip(checkedId);
    }
}