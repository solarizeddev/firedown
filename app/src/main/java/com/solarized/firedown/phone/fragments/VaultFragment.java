package com.solarized.firedown.phone.fragments;

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
import androidx.core.graphics.drawable.DrawableCompat;
import androidx.core.view.MenuProvider;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.ViewModelProvider;
import androidx.paging.LoadState;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.color.MaterialColors;
import com.google.android.material.snackbar.Snackbar;
import com.solarized.firedown.Preferences;
import com.solarized.firedown.R;
import com.solarized.firedown.data.TaskEvent;
import com.solarized.firedown.data.entity.DownloadEntity;
import com.solarized.firedown.data.models.DownloadsViewModel;
import com.solarized.firedown.data.models.TaskViewModel;
import com.solarized.firedown.manager.ServiceActions;
import com.solarized.firedown.ui.adapters.DownloadItemAdapter;
import com.solarized.firedown.ui.diffs.DownloadDiffCallback;
import com.solarized.firedown.ui.OnItemClickListener;
import com.solarized.firedown.utils.FileUriHelper;
import com.solarized.firedown.Keys;
import com.solarized.firedown.utils.NavigationUtils;


public class VaultFragment extends BaseDownloadFragment implements OnItemClickListener {

    private static final String TAG = VaultFragment.class.getSimpleName();

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mGridPreference = Preferences.SORT_VAULT_LIST;
        mDestinationTitle = R.string.navigation_vault;
        mCurrentDestinationId = R.id.vault;
        mEnableGrid = mSharedPreferences.getBoolean(mGridPreference, false);

        mDownloadsViewModel = new ViewModelProvider(this).get(DownloadsViewModel.class);
        mTaskViewModel = new ViewModelProvider(this).get(TaskViewModel.class);

        // Ensure the vault opens with no search filter
        mDownloadsViewModel.search(null);

        setupBackPressLogic();

    }

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View v = inflater.inflate(R.layout.fragment_vault, container, false);
        initViews(v);
        setupRecyclerView();
        setupToolbar();
        return v;
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
                if(mActionModeEnabled){
                    inflater.inflate(mOperationActive ? R.menu.menu_action_empty : R.menu.menu_action_vault, menu);
                }else{
                    inflater.inflate(R.menu.menu_vault, menu);
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
    private void initViews(View v) {
        mBottomProgressView = v.findViewById(R.id.bottom_progress_view);
        mToolbar = v.findViewById(R.id.toolbar);
        mLCEERecyclerView = v.findViewById(R.id.list_recycler_lcee);
    }

    private void setupRecyclerView() {
        mRecyclerView = mLCEERecyclerView.getRecyclerView();

        mLCEERecyclerView.setEmptyImageView(R.drawable.ill_presents);
        mAdapter = new DownloadItemAdapter(getContext(), new DownloadDiffCallback(), this, mEnableGrid);
        mRecyclerView.setAdapter(mAdapter);
        mRecyclerView.setVerticalScrollBarEnabled(true);

        // Use optimized base class logic for spans and decorations
        configureRecyclerView(mAdapter, mEnableGrid);

        mAdapter.addLoadStateListener(loadStates -> {
            if (mAdapter == null || mLCEERecyclerView == null) return null;
            if (loadStates.getRefresh() instanceof LoadState.NotLoading) {
                if (mAdapter.getItemCount() == 0) {
                    mLCEERecyclerView.showEmpty();
                }else{
                    mLCEERecyclerView.hideAll();
                }
            }
            handleTransitionTiming();
            return null;
        });
    }


    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        postponeEnterTransition();
        observeViewModelData();
        setupNavigationResultObserver();
    }


    private void observeViewModelData(){
        // Handle decryption progress using base class
        mTaskViewModel.getObservableEvent().observe(getViewLifecycleOwner(), event -> {
            if (event instanceof TaskEvent.Started started) {
                if (started.getAction() == ServiceActions.ENCRYPTION) return;
                handleTaskStart(started.getAction());

            } else if (event instanceof TaskEvent.Progress progress) {
                mBottomProgressView.setProgress(progress.getPercent());

            } else if (event instanceof TaskEvent.Finished finished) {
                if (finished.getAction() == ServiceActions.ENCRYPTION) return;
                handleTaskFinish(finished.getAction(), finished.getResult());

            } else if (event instanceof TaskEvent.Deleted deleted) {
                showActionSnackbar(R.plurals.complete_delete_files_text, deleted.getCount(), mCurrentDestinationId == R.id.vault);

            } else if (event instanceof TaskEvent.Error error) {
                showActionSnackbar(R.plurals.move_file_fail, error.getCount(), mCurrentDestinationId == R.id.vault);

            } else if (event instanceof TaskEvent.Cancelled) {
                mOperationActive = false;
            }
        });


        // Observe the specific "Safe" data stream
        mDownloadsViewModel.getSafe().observe(getViewLifecycleOwner(), data ->
                mAdapter.submitData(getLifecycle(), data));
    }

    @Override
    public void onItemClick(int position, int resId) {

        if (position == RecyclerView.NO_POSITION)
            return;

        Object item = mAdapter.peek(position);
        if (!(item instanceof DownloadEntity e))
            return;

        if (mActionModeEnabled) {
            mAdapter.setSelected(position);
            setActionModeTitle(mAdapter.getSelectedSize());
            return;
        }

        Log.d(TAG, "Vault id: " + getResources().getResourceEntryName(resId));

        if (resId == R.id.item) {
            if (FileUriHelper.isVideo(e.getFileMimeType()) || FileUriHelper.isImage(e.getFileMimeType())) {
                startPlayerActivity(e);
            } else {
                Snackbar.make(mActivity.getSnackAnchorView(), R.string.vault_unsupported, Snackbar.LENGTH_LONG).show();
            }
        } else if (resId == R.id.item_download_action) {
            Bundle bundle = new Bundle();
            bundle.putParcelable(Keys.ITEM_ID, e);
            bundle.putInt(Keys.ITEM_POSITION, position);
            bundle.putBoolean(Keys.IS_INCOGNITO, true);
            NavigationUtils.navigateSafe(mNavController, R.id.dialog_download_options, R.id.vault, bundle);
        }
    }


    @Override
    public void onLongClick(int p, int r) {
        Object item = mAdapter.peek(p);
        if (!(item instanceof DownloadEntity)) return;

        // Don't enter action mode while searching — the two modes conflict
        if (isSearchActive()) return;

        startActionMode(p);
    }

    @Override
    public void onItemVariantClick(int p, int v, int r) {}


}