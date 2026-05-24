package com.solarized.firedown.phone.fragments;


import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.appcompat.widget.SearchView;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleEventObserver;
import androidx.lifecycle.SavedStateHandle;
import androidx.navigation.NavBackStackEntry;
import androidx.navigation.NavDestination;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.ListPreloader;
import com.bumptech.glide.RequestBuilder;
import com.bumptech.glide.RequestManager;
import com.bumptech.glide.integration.recyclerview.RecyclerViewPreloader;
import com.bumptech.glide.request.RequestOptions;
import com.bumptech.glide.util.FixedPreloadSizeProvider;
import com.google.android.material.snackbar.Snackbar;
import com.solarized.firedown.GlideHelper;
import com.solarized.firedown.IntentActions;
import com.solarized.firedown.Keys;
import com.solarized.firedown.R;
import com.solarized.firedown.data.Download;
import com.solarized.firedown.data.entity.DownloadEntity;
import com.solarized.firedown.data.entity.OptionEntity;
import com.solarized.firedown.data.models.DownloadsViewModel;
import com.solarized.firedown.data.models.TaskViewModel;
import com.solarized.firedown.manager.ServiceActions;
import com.solarized.firedown.phone.DownloadsActivity;
import com.solarized.firedown.phone.SettingsActivity;
import com.solarized.firedown.phone.VaultActivity;
import com.solarized.firedown.ui.CardViewListItemDecoration;
import com.solarized.firedown.ui.EqualSpacingItemDecoration;
import com.solarized.firedown.ui.adapters.DownloadItemAdapter;
import com.solarized.firedown.utils.NavigationUtils;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import javax.inject.Inject;

import dagger.hilt.android.AndroidEntryPoint;

@AndroidEntryPoint
public abstract class BaseDownloadFragment extends BaseFocusFragment implements SearchView.OnQueryTextListener {


    @Inject
    protected SharedPreferences mSharedPreferences;

    protected DownloadsViewModel mDownloadsViewModel;

    protected TaskViewModel mTaskViewModel;

    protected DownloadItemAdapter mAdapter;

    protected GridLayoutManager mGridLayoutManager;

    protected boolean mEnableGrid;

    /** RecyclerView that {@link #installThumbnailPreloader} last attached the
     *  preload OnScrollListener to. configureRecyclerView() runs repeatedly on
     *  grid/list toggle (don't double-attach), but rotation rebuilds the
     *  fragment view and gives us a fresh RecyclerView (do attach again). */
    private RecyclerView mPreloaderInstalledOn;

    protected boolean mPaused;

    protected String mGridPreference;

    protected int mDestinationTitle;

    protected int mCurrentDestinationId;

    protected int mNotificationAction;


    @Override
    public void onConfigurationChanged(@NonNull Configuration newConfig) {
        super.onConfigurationChanged(newConfig);

        configureRecyclerView(mAdapter, mEnableGrid);

    }

    @Override
    public void onResume() {
        super.onResume();
        mPaused = false;
    }

    @Override
    public void onPause() {
        mPaused = true;
        super.onPause();
    }

    @Override
    public boolean onQueryTextSubmit(String query) {
        mDownloadsViewModel.search(query);
        return false;
    }

    @Override
    public boolean onQueryTextChange(String newText) { /* Search Logic */
        mDownloadsViewModel.search(newText);
        return false;
    }


    protected void setupBackPressLogic() {
        mActivity.getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (mActionModeEnabled) stopActionMode();
                else if (mSearchItem != null && mSearchItem.isActionViewExpanded()) closeSearchView();
                else if (mOperationActive) navigateToCancelDialog();
                else {
                    setEnabled(false);
                    mActivity.getOnBackPressedDispatcher().onBackPressed();
                }
            }
        });
    }


    protected void navigateToCancelDialog() {
        NavigationUtils.navigateSafe(mNavController, R.id.dialog_cancel_operation, mCurrentDestinationId);
    }

    protected void handleTaskCancellation() {
        if (mNotificationAction == ServiceActions.AUDIO_ENCODE.getValue()) handleItemAction(IntentActions.DOWNLOAD_CANCEL_AUDIO_ENCODE, null);
        else if (mNotificationAction == ServiceActions.MAKE_GIF.getValue()) handleItemAction(IntentActions.DOWNLOAD_CANCEL_MAKE_GIF, null);
        else if (mNotificationAction == ServiceActions.ENCRYPTION.getValue()) handleItemAction(IntentActions.CANCEL_ENCRYPTION, null);
        else if (mNotificationAction == ServiceActions.DECRYPTION.getValue()) handleItemAction(IntentActions.CANCEL_DECRYPTION, null);
    }

    protected void setupSearchView(Menu menu) {
        mSearchItem = menu.findItem(R.id.action_search);
        mSearchView = (SearchView) mSearchItem.getActionView();
        if(mSearchView != null){
            mSearchView.setOnQueryTextListener(this);
        }
    }

    protected void showActionSnackbar(int res, int count, boolean incognito) {
        Snackbar snackbar = makeSnackbar(mActivity.getSnackAnchorView(), getResources().getQuantityString(res, count, count), incognito);
        snackbar.show();
    }

    protected void showErrorSnackbar(int textResId) {
        if (mActivity == null) return;
        makeSnackbar(mActivity.getSnackAnchorView(), textResId,
                mCurrentDestinationId == R.id.vault).show();
    }

    protected void setupNavigationResultObserver() {
        NavDestination navDestination = mNavController.getCurrentDestination();

        if(navDestination == null)
            return;

        final NavBackStackEntry entry = mNavController.getBackStackEntry(navDestination.getId());

        LifecycleEventObserver observer = (source, event) -> {
            if (event == Lifecycle.Event.ON_RESUME) {
                if(mPaused && navDestination.getId() == R.id.vault){
                    NavigationUtils.navigateSafe(mNavController, R.id.action_vault_to_lock);
                }
                SavedStateHandle handle = entry.getSavedStateHandle();
                if (handle.contains(IntentActions.DOWNLOAD_SORT)) {
                    OptionEntity option = handle.get(IntentActions.DOWNLOAD_SORT);
                    if (option != null) mDownloadsViewModel.setSortType(option.getId());
                    handle.remove(IntentActions.DOWNLOAD_SORT);
                } else if (handle.contains(IntentActions.DOWNLOAD_ITEM)) {
                    OptionEntity option = handle.get(IntentActions.DOWNLOAD_ITEM);
                    handle.remove(IntentActions.DOWNLOAD_ITEM);
                    if (option != null) handleOptionSelection(option);
                } else if (handle.contains(IntentActions.ACTION_MODE)) {
                    stopActionMode();
                    handle.remove(IntentActions.ACTION_MODE);
                } else if (handle.contains(IntentActions.ACTION_TASK)) {
                    handleTaskCancellation();
                    handle.remove(IntentActions.ACTION_TASK);
                }
            }
        };
        entry.getLifecycle().addObserver(observer);
        getViewLifecycleOwner().getLifecycle().addObserver((LifecycleEventObserver) (s, e) -> {
            if (e == Lifecycle.Event.ON_DESTROY) entry.getLifecycle().removeObserver(observer);
        });
    }

    protected void handleOptionSelection(@NonNull OptionEntity option) {
        DownloadEntity entity = option.getDownloadEntity();
        int iconId = option.getId();
        Bundle bundle = new Bundle();
        bundle.putParcelable(Keys.ITEM_ID, entity);
        bundle.putBoolean(Keys.IS_INCOGNITO, mCurrentDestinationId == R.id.vault);
        if (iconId == R.drawable.ic_web_24) {
            openItemWith(entity);
        } else if (iconId == R.drawable.ic_baseline_image_24) {
            mDownloadsViewModel.updateDownloadThumb(entity);
        } else if (iconId == R.drawable.ic_share_24) {
            shareItem(entity);
        } else if (iconId == R.drawable.ic_edit_24) {
            NavigationUtils.navigateSafe(mNavController, R.id.dialog_rename, bundle);
        } else if (iconId == R.id.action_delete || iconId == R.drawable.ic_baseline_delete_24) {
            NavigationUtils.navigateSafe(mNavController, R.id.dialog_delete_files, bundle);
        } else if (iconId == R.drawable.ic_headphones_24) {
            handleItemAction(IntentActions.DOWNLOAD_START_AUDIO_ENCODE, entity);
            mOperationActive = true;
            startActionMode(option.getPosition());
        } else if (iconId == R.drawable.ic_gif_box_24) {
            NavigationUtils.navigateSafe(mNavController, R.id.gif_maker, bundle);
        } else if (iconId == R.drawable.ic_lock_24) {
            handleItemAction(IntentActions.LOCK_FOR_ENCRYPTION, entity);
            mOperationActive = true;
            startActionMode(option.getPosition());
        } else if (iconId == R.drawable.ic_lock_open_right_24) {
            handleItemAction(IntentActions.START_DECRYPTION, entity);
            mOperationActive = true;
            startActionMode(option.getPosition());
        }else if (iconId == R.drawable.ic_travel_explore_24) {
            openSourceUrl(entity);
        } else if (iconId == R.drawable.ic_info_24) {
            NavigationUtils.navigateSafe(mNavController, R.id.dialog_file_info, bundle);
        } else if (iconId == R.drawable.ic_download_done_24) {
            String action = IntentActions.DOWNLOAD_FINISH;
            handleItemAction(action, entity);
        } else if (iconId == R.drawable.ic_refresh_24) {
            String action = IntentActions.DOWNLOAD_RESTART;
            handleItemAction(action, entity);
        }
    }

    protected boolean handleMenuAction(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_sort) {
            Bundle bundle = new Bundle();
            bundle.putBoolean(Keys.IS_INCOGNITO, mCurrentDestinationId == R.id.vault);
            NavigationUtils.navigateSafe(mNavController, R.id.dialog_sort, mCurrentDestinationId, bundle);
        } else if (id == R.id.action_view) {
            mEnableGrid = !mEnableGrid;
            mAdapter.enableGrid(mEnableGrid);
            configureRecyclerView(mAdapter, mEnableGrid);
            item.setIcon(mEnableGrid ? R.drawable.ic_view_list_24 : R.drawable.ic_grid_view_24);
            mSharedPreferences.edit().putBoolean(mGridPreference, mEnableGrid).apply();
        } else if (id == R.id.action_delete) {
            ArrayList<DownloadEntity> list = mAdapter.getSelectedEntities();
            Bundle bundle = new Bundle();
            bundle.putParcelableArrayList(Keys.ITEM_LIST_ID, list);
            bundle.putBoolean(Keys.IS_INCOGNITO, mCurrentDestinationId == R.id.vault);
            NavigationUtils.navigateSafe(mNavController, R.id.dialog_delete_files, mCurrentDestinationId, bundle);
        } else if (id == R.id.action_cipher) {
            ArrayList<DownloadEntity> list = getSelectedItems();
            handleListItemsAction(IntentActions.LOCK_FOR_ENCRYPTION, list);
            mOperationActive = true;
        } else if (id == R.id.action_decipher) {
            ArrayList<DownloadEntity> list = getSelectedItems();
            handleListItemsAction(IntentActions.START_DECRYPTION, list);
            mOperationActive = true;
            return true;
        }else if (id == R.id.action_share) {
            ArrayList<String> paths = new ArrayList<>();
            for (DownloadEntity entity : mAdapter.getSelectedEntities()) {
                if (entity.getFilePath() != null) {
                    paths.add(entity.getFilePath());
                }
            }
            shareItems(paths);
        } else if (id == R.id.action_select_all) {
            mAdapter.selectAll();
            setActionModeTitle(mAdapter.getSelectedSize());
        } else if (id == R.id.action_deselect_all) {
            mAdapter.deselectAll();
            setActionModeTitle(mAdapter.getSelectedSize());
        } else if (id == R.id.action_safe) {
            Intent intent = new Intent(mActivity, VaultActivity.class);
            startActivity(intent);
        } else if (id == R.id.action_settings) {
            Intent intent = new Intent(mActivity, SettingsActivity.class);
            startActivity(intent);
        }
        return true;
    }

    protected ArrayList<DownloadEntity> getSelectedItems() {
        return mAdapter.getSelectedFinishedEntities();
    }


    protected void handleTransitionTiming() {
        final ViewGroup parent = (ViewGroup) requireView().getParent();
        if (parent != null) {
            parent.getViewTreeObserver().addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
                @Override public boolean onPreDraw() {
                    parent.getViewTreeObserver().removeOnPreDrawListener(this);
                    startPostponedEnterTransition();
                    return true;
                }
            });
        }
    }


    protected void handleTaskFinish(ServiceActions action, Object obj) {
        mOperationActive = false;
        stopActionMode();

        if (action == ServiceActions.AUDIO_ENCODE) {
            mBottomProgressView.setProgress(100);
            mBottomProgressView.setTitle(R.string.task_audio_finished);
            mBottomProgressView.setActionButtonVisibility(View.GONE);
        } else if (action == ServiceActions.MAKE_GIF) {
            mBottomProgressView.setProgress(100);
            mBottomProgressView.setTitle(R.string.task_gif_finished);
            /* GifMakerTask passes the just-created entity through the
             * Finished event so we can offer a one-tap View action that
             * launches PlayerActivity directly — same shape as the
             * Vault encryption/decryption finish UIs. */
            DownloadEntity gifEntity = obj instanceof DownloadEntity ? (DownloadEntity) obj : null;
            if (gifEntity != null) {
                mBottomProgressView.setActionButtonVisibility(View.VISIBLE);
                mBottomProgressView.setActionButtonText(R.string.file_view);
                mBottomProgressView.setActionButtonListener(v -> startPlayerActivity(gifEntity));
            } else {
                mBottomProgressView.setActionButtonVisibility(View.GONE);
            }
        } else if (action == ServiceActions.ERROR_AUDIO_ENCODE) {
            /* Native encoder rejected the input (jni_encoder_start
             * prepare error) — most commonly because the source has no
             * audio stream or uses a codec FFmpeg can't decode. Flip
             * the strip's title so it doesn't slide away mid-progress,
             * and pop a snackbar so the failure is actually noticed. */
            mBottomProgressView.setTitle(R.string.task_audio_failed);
            mBottomProgressView.setActionButtonVisibility(View.GONE);
            showErrorSnackbar(R.string.task_audio_failed);
        } else if (action == ServiceActions.ERROR_MAKE_GIF) {
            mBottomProgressView.setTitle(R.string.task_gif_failed);
            mBottomProgressView.setActionButtonVisibility(View.GONE);
            showErrorSnackbar(R.string.task_gif_failed);
        } else if (action == ServiceActions.ENCRYPTION) {
            setupEncryptionFinishUI((int) obj);
        } else if(action == ServiceActions.DECRYPTION) {
            setupDecryptionFinishUI((int) obj);
        }

        Animation anim = AnimationUtils.loadAnimation(getContext(), R.anim.slide_down);
        anim.setAnimationListener(new Animation.AnimationListener() {
            @Override public void onAnimationEnd(Animation a) {
                if (mBottomProgressView != null && !mOperationActive) mBottomProgressView.setVisibility(View.GONE);
            }
            @Override public void onAnimationStart(Animation a) {}
            @Override public void onAnimationRepeat(Animation a) {}
        });
        mBottomProgressView.startAnimation(anim);
    }


    protected void setupDecryptionFinishUI(int quantity) {
        boolean visible = quantity > 0;
        String tt = getResources().getQuantityString(R.plurals.complete_move_files_text, quantity, quantity);
        mBottomProgressView.setTitle(String.format("%s (%s)", getString(R.string.task_decryption_finished), tt));
        mBottomProgressView.setProgress(100);
        mBottomProgressView.setVisibility(visible ? View.VISIBLE : View.GONE);
        mBottomProgressView.setActionButtonVisibility(visible ? View.VISIBLE : View.GONE);
        if (visible) {
            mBottomProgressView.setActionButtonText(R.string.file_view);
            mBottomProgressView.setActionButtonListener(v -> startActivity(new Intent(getContext(), DownloadsActivity.class)));
        }
    }

    protected void setupEncryptionFinishUI(int quantity) {
        boolean visible = quantity > 0;
        String tt = getResources().getQuantityString(R.plurals.complete_move_files_text, quantity, quantity);
        mBottomProgressView.setTitle(String.format("%s (%s)", getString(R.string.task_encryption_finished), tt));
        mBottomProgressView.setProgress(100);
        mBottomProgressView.setVisibility(visible ? View.VISIBLE : View.GONE);
        mBottomProgressView.setActionButtonVisibility(visible ? View.VISIBLE : View.GONE);
        if (visible) {
            mBottomProgressView.setActionButtonText(R.string.file_view);
            mBottomProgressView.setActionButtonListener(v -> startActivity(new Intent(getContext(), VaultActivity.class)));
        }
    }

    /**
     * Number of single-span rows the subclass prepends via
     * {@link androidx.recyclerview.widget.ConcatAdapter} before the
     * paged DownloadItemAdapter. Used by the SpanSizeLookup so those
     * leading rows take the full grid width and the date-divider
     * lookup against the inner adapter is shifted accordingly.
     * Default 0 — overridden by {@code DownloadFragment} when its
     * incognito-in-progress header is visible.
     */
    protected int getLeadingHeaderCount() {
        return 0;
    }

    protected void configureRecyclerView(DownloadItemAdapter adapter, boolean isGrid) {
        if (mRecyclerView == null) return;

        // RecyclerView's own size doesn't depend on adapter contents (it's
        // match_parent in the layout). Telling it so skips extra measure passes.
        mRecyclerView.setHasFixedSize(true);
        // Default off-screen view cache is 2; bumping it means a quick
        // scroll-back doesn't re-bind (and re-trigger Glide loads) for the
        // rows that just left the viewport.
        mRecyclerView.setItemViewCacheSize(8);

        installThumbnailPreloader(adapter);

        // 1. Get or Create LayoutManager
        int spans = getResources().getInteger(isGrid ? R.integer.image_grid_number : R.integer.image_list_number);

        if (mGridLayoutManager == null) {
            // If it's null or the wrong type, set a new one
            mGridLayoutManager = new GridLayoutManager(requireContext(), spans);
            mRecyclerView.setLayoutManager(mGridLayoutManager);
        } else {
            // If it exists, just update the span count
            mGridLayoutManager.setSpanCount(spans);
        }

        // Headers must span the full width so they don't sit in a single grid cell.
        // Subclasses may prepend rows via ConcatAdapter (e.g. DownloadFragment's
        // incognito-in-progress hint) — getLeadingHeaderCount() reports how many
        // such rows exist so the lookup spans them full-width and shifts the
        // adapter position when querying for date-divider headers.
        mGridLayoutManager.setSpanSizeLookup(new GridLayoutManager.SpanSizeLookup() {
            @Override
            public int getSpanSize(int position) {
                int leading = getLeadingHeaderCount();
                if (position < leading) {
                    return mGridLayoutManager.getSpanCount();
                }
                int innerPos = position - leading;
                if (innerPos < 0 || innerPos >= adapter.getItemCount()) {
                    return 1;
                }
                if (adapter.getItemViewType(innerPos) == Download.HEADER) {
                    return mGridLayoutManager.getSpanCount();
                }
                return 1;
            }
        });

        // 2. Handle Decorations
        int spacing = getResources().getDimensionPixelSize(R.dimen.list_spacing);
        while (mRecyclerView.getItemDecorationCount() > 0) {
            mRecyclerView.removeItemDecorationAt(0);
        }

        mRecyclerView.addItemDecoration(isGrid
                ? new EqualSpacingItemDecoration(spacing)
                : new CardViewListItemDecoration(spacing));

        // 3. Update Adapter state
        adapter.enableGrid(isGrid);
    }


    /**
     * Warms Glide's memory cache 8 items ahead of the scroll direction so
     * the FFmpeg-decoded video frames (and image / PDF / APK thumbnails)
     * for rows about to enter the viewport are already in memory by the
     * time {@code onBindViewHolder} fires. Big win for fast scroll-back
     * after a long scroll: without preloading, the original top-row
     * bitmaps have been LRU-evicted and bind has to redo the FFmpeg
     * decode.
     *
     * No-op for non-thumbnail mime types — {@link GlideHelper#preloadDownload}
     * returns null for those and the preloader skips them.
     *
     * Mirrors the BrowserOptionFragment pattern but keyed on the paging
     * adapter's {@code peek(position)} rather than {@code getCurrentList()}
     * (PagingDataAdapter doesn't expose the latter).
     */
    private void installThumbnailPreloader(DownloadItemAdapter adapter) {
        if (mRecyclerView == null || mPreloaderInstalledOn == mRecyclerView) return;

        RequestManager glide = Glide.with(this);
        RequestOptions baseOptions = new RequestOptions();

        RecyclerViewPreloader<DownloadEntity> preloader = new RecyclerViewPreloader<>(
                glide,
                new ListPreloader.PreloadModelProvider<DownloadEntity>() {
                    @NonNull
                    @Override
                    public List<DownloadEntity> getPreloadItems(int position) {
                        if (position < 0 || position >= adapter.getItemCount()) {
                            return Collections.emptyList();
                        }
                        // peek() — like get() but never triggers placeholder
                        // resolution, so it's safe from a scroll listener.
                        Object item = adapter.peek(position);
                        if (!(item instanceof DownloadEntity entity)) {
                            return Collections.emptyList();
                        }
                        // Only finished downloads have a real thumbnail to
                        // warm; in-progress / errored / queued rows render
                        // a sync mime-type drawable in the bind path.
                        if (entity.getFileStatus() != Download.FINISHED) {
                            return Collections.emptyList();
                        }
                        return Collections.singletonList(entity);
                    }

                    @Nullable
                    @Override
                    public RequestBuilder<?> getPreloadRequestBuilder(@NonNull DownloadEntity entity) {
                        return GlideHelper.preloadDownload(glide, entity, baseOptions);
                    }
                },
                new FixedPreloadSizeProvider<>(
                        GlideHelper.downloadThumbWidth(),
                        GlideHelper.downloadThumbHeight()),
                8);
        mRecyclerView.addOnScrollListener(preloader);
        mPreloaderInstalledOn = mRecyclerView;
    }


    @Override
    public void onDestroyView() {
        // Drop the cached pointer to the destroyed RecyclerView before
        // BaseFocusFragment nulls mRecyclerView — otherwise this field
        // pins the dead view subtree until the fragment instance dies
        // (or until the next configureRecyclerView re-installs).
        mPreloaderInstalledOn = null;
        super.onDestroyView();
    }


    protected  void handleTaskStart(ServiceActions action) {
        mNotificationAction = action.getValue();
        mRecyclerView.suppressLayout(true);
        mLCEERecyclerView.showDimView();

        mBottomProgressView.clearAnimation();
        mBottomProgressView.setProgress(0);
        mBottomProgressView.setVisibility(View.VISIBLE);
        mBottomProgressView.setActionButtonVisibility(View.VISIBLE);
        mBottomProgressView.setActionButtonText(android.R.string.cancel);

        if(action == ServiceActions.ENCRYPTION) {
            mBottomProgressView.setTitle(R.string.vault_encrypting);
            mBottomProgressView.setActionButtonListener(v -> handleItemAction(IntentActions.START_ENCRYPTION, null));
        }else if(action == ServiceActions.DECRYPTION) {
            mBottomProgressView.setTitle(R.string.vault_decrypting);
            mBottomProgressView.setActionButtonListener(v -> handleItemAction(IntentActions.START_DECRYPTION, null));
        }else if(action == ServiceActions.AUDIO_ENCODE){
            mBottomProgressView.setTitle(R.string.download_saving_audio);
            mBottomProgressView.setActionButtonListener(v -> handleItemAction(IntentActions.DOWNLOAD_CANCEL_AUDIO_ENCODE, null));
        }else if(action == ServiceActions.MAKE_GIF){
            mBottomProgressView.setTitle(R.string.download_saving_gif);
            mBottomProgressView.setActionButtonListener(v -> handleItemAction(IntentActions.DOWNLOAD_CANCEL_MAKE_GIF, null));
        }
    }

    protected void stopActionMode() {
        mActionModeEnabled = false;
        if (mLCEERecyclerView != null) mLCEERecyclerView.hideDimView();
        if (mRecyclerView != null) mRecyclerView.suppressLayout(false);
        if (mAdapter != null) {
            mAdapter.clearSelected();
            mAdapter.setActionMode(false);
        }
        mToolbar.invalidateMenu();
        mToolbar.setTitle(mDestinationTitle);
    }


    protected boolean isSearchActive() {
        return mSearchItem != null && mSearchItem.isActionViewExpanded();
    }

    protected void startActionMode(int position) {
        mActionModeEnabled = true;
        mAdapter.setActionMode(true);
        mAdapter.setSelected(position);
        setActionModeTitle(mAdapter.getSelectedSize());
        mToolbar.invalidateMenu();
    }

    /**
     * missing method: Displays the bottom progress view with an entrance animation.
     */
    protected void showBottomProgress(String title) {
        if (mBottomProgressView != null) {
            mBottomProgressView.setTitle(title);
            mBottomProgressView.setProgress(0);
            mBottomProgressView.setVisibility(View.VISIBLE);
        }
    }


    /**
     * Helper to safely get the first visible item position, preventing NullPointerExceptions.
     */
    protected int getFirstVisiblePosition() {
        if (mRecyclerView != null) {
            RecyclerView.LayoutManager layoutManager = mRecyclerView.getLayoutManager();
            if (layoutManager instanceof GridLayoutManager) {
                return ((GridLayoutManager) layoutManager).findFirstVisibleItemPosition();
            }
        }
        return RecyclerView.NO_POSITION;
    }
}