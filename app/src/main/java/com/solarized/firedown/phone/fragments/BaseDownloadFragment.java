package com.solarized.firedown.phone.fragments;


import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
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
import com.solarized.firedown.manager.tasks.TaskManager;
import com.solarized.firedown.phone.DownloadsActivity;
import com.solarized.firedown.phone.SettingsActivity;
import com.solarized.firedown.phone.VaultActivity;
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
public abstract class BaseDownloadFragment extends BaseFocusFragment {


    @Inject
    protected SharedPreferences mSharedPreferences;

    protected DownloadsViewModel mDownloadsViewModel;

    /** In-place Material search bar (R.id.search_bar in the Downloads/Vault
     *  app bar) and its input. Replaces the old collapsible appcompat
     *  SearchView action view — a bare underline that read as "dull / barely
     *  visible" when expanded. Tapping the toolbar search icon reveals it in
     *  the second app-bar row; the list filters live below (no overlay) and
     *  the type-filter chips hide while it's open (see DownloadFragment). */
    protected View mSearchBar;
    protected EditText mSearchEdit;

    protected TaskViewModel mTaskViewModel;

    protected DownloadItemAdapter mAdapter;

    protected GridLayoutManager mGridLayoutManager;

    protected boolean mEnableGrid;

    /** Whether the last configureRecyclerView ran in dense-mosaic mode
     *  (grid + images-only filter). Lets refreshGridDensityIfChanged()
     *  reconfigure only on an actual density transition. */
    private boolean mDenseGrid;

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

    protected void setupBackPressLogic() {
        mActivity.getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (mActionModeEnabled) stopActionMode();
                else if (isSearchActive()) closeSearchBar();
                else if (mOperationActive) navigateToCancelDialog();
                else if (clearFilterOnBack()) { /* a filter was active; clearing it consumes Back */ }
                else {
                    setEnabled(false);
                    mActivity.getOnBackPressedDispatcher().onBackPressed();
                }
            }
        });
    }

    /**
     * Hook for unwinding a transient filter on Back before leaving the
     * screen. Default: nothing to clear. {@code DownloadFragment} overrides
     * it to deselect an active filter chip (reverting to the unfiltered
     * list) so Back widens the view back rather than ejecting the user.
     *
     * @return true if a filter was active and got cleared (Back consumed).
     */
    protected boolean clearFilterOnBack() {
        return false;
    }


    protected void navigateToCancelDialog() {
        NavigationUtils.navigateSafe(mNavController, R.id.dialog_cancel_operation, mCurrentDestinationId);
    }

    protected void handleTaskCancellation() {
        if (mNotificationAction == ServiceActions.AUDIO_ENCODE.getValue()) handleItemAction(IntentActions.DOWNLOAD_CANCEL_AUDIO_ENCODE, null);
        else if (mNotificationAction == ServiceActions.MAKE_GIF.getValue()) handleItemAction(IntentActions.DOWNLOAD_CANCEL_MAKE_GIF, null);
        else if (mNotificationAction == ServiceActions.COMPRESS.getValue()) handleItemAction(IntentActions.DOWNLOAD_CANCEL_COMPRESS, null);
        else if (mNotificationAction == ServiceActions.EXTRACT.getValue()) handleItemAction(IntentActions.DOWNLOAD_CANCEL_EXTRACT, null);
        else if (mNotificationAction == ServiceActions.SAVE_FRAME.getValue()) handleItemAction(IntentActions.DOWNLOAD_CANCEL_SAVE_FRAME, null);
        else if (mNotificationAction == ServiceActions.ENCRYPTION.getValue()) handleItemAction(IntentActions.CANCEL_ENCRYPTION, null);
        else if (mNotificationAction == ServiceActions.DECRYPTION.getValue()) handleItemAction(IntentActions.CANCEL_DECRYPTION, null);
    }

    /**
     * Wires the in-place Material search bar (R.id.search_bar). Each concrete
     * fragment calls this from its {@code initViews} once the view tree
     * exists. Typing drives the same debounced {@link DownloadsViewModel#search};
     * the trailing clear button only empties the field (it does not exit
     * search — that's the toolbar up-arrow / system Back).
     */
    protected void setupSearchBar(View root) {
        mSearchBar = root.findViewById(R.id.search_bar);
        mSearchEdit = root.findViewById(R.id.search_edit);
        if (mSearchBar == null || mSearchEdit == null) {
            return;
        }
        final View clear = root.findViewById(R.id.search_clear);
        mSearchEdit.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                mDownloadsViewModel.search(s.toString());
                if (clear != null) {
                    clear.setVisibility(s.length() > 0 ? View.VISIBLE : View.GONE);
                }
            }
            @Override public void afterTextChanged(Editable s) {}
        });
        // Results filter live as you type, so the IME action just dismisses the
        // keyboard rather than running a separate query.
        mSearchEdit.setOnEditorActionListener((v, actionId, event) -> {
            hideKeyboard(mSearchEdit);
            return true;
        });
        if (clear != null) {
            clear.setOnClickListener(v -> mSearchEdit.setText(""));
        }
    }

    /** Reveal the search bar and focus it. */
    protected void openSearchBar() {
        if (mSearchBar == null || mSearchEdit == null) {
            return;
        }
        // Re-entrancy guard: tapping the (still-visible) toolbar search icon
        // again must NOT re-run onSearchBarOpening — that would re-snapshot the
        // now-cleared chip and lose the one to restore on close. Just re-focus.
        if (mSearchBar.getVisibility() != View.VISIBLE) {
            onSearchBarOpening();
            mSearchBar.setVisibility(View.VISIBLE);
        }
        mSearchEdit.requestFocus();
        mSearchEdit.post(() -> showKeyboard(mSearchEdit));
    }

    /** Hide the search bar and clear the query (back to the full list). */
    protected void closeSearchBar() {
        if (mSearchBar == null || mSearchEdit == null
                || mSearchBar.getVisibility() != View.VISIBLE) {
            return;
        }
        // Emptying the field drives search("") through the watcher, so the list
        // drops back to the unfiltered view.
        mSearchEdit.setText("");
        hideKeyboard(mSearchEdit);
        mSearchBar.setVisibility(View.GONE);
        onSearchBarClosing();
    }

    /**
     * Hook: the search bar is opening. {@code DownloadFragment} hides the
     * filter-chip rail and drops the active chip so search runs GLOBALLY
     * across all types — we never apply a filter the user can't see.
     */
    protected void onSearchBarOpening() {}

    /**
     * Hook: the search bar is closing. {@code DownloadFragment} restores the
     * chip rail and the previously-active chip.
     */
    protected void onSearchBarClosing() {}

    private void showKeyboard(View view) {
        if (mActivity == null) {
            return;
        }
        InputMethodManager imm = (InputMethodManager) mActivity.getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) {
            imm.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT);
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
                } else if (handle.contains(IntentActions.DOWNLOAD_START_MAKE_GIF)) {
                    // GifMakerFragment hands its params back here instead
                    // of starting the service itself, so the resulting
                    // TaskEvent.Started lands while DownloadFragment's
                    // observer is active and handleTaskStart actually
                    // gets to show the bottom progress bar (mirrors the
                    // audio-encode flow). See the explanation comment in
                    // GifMakerFragment.startGifMakerTask.
                    Bundle gifArgs = handle.get(IntentActions.DOWNLOAD_START_MAKE_GIF);
                    handle.remove(IntentActions.DOWNLOAD_START_MAKE_GIF);
                    if (gifArgs != null && mActivity != null) {
                        Intent gifIntent = new Intent(mActivity, TaskManager.class);
                        gifIntent.setAction(IntentActions.DOWNLOAD_START_MAKE_GIF);
                        gifIntent.putExtras(gifArgs);
                        mActivity.startService(gifIntent);
                        mOperationActive = true;
                    }
                } else if (handle.contains(IntentActions.DOWNLOAD_START_SAVE_FRAME)) {
                    // FrameGrabberFragment hands its params back here, same
                    // as the GIF flow above, so the bottom progress bar
                    // shows for the resulting task.
                    Bundle frameArgs = handle.get(IntentActions.DOWNLOAD_START_SAVE_FRAME);
                    handle.remove(IntentActions.DOWNLOAD_START_SAVE_FRAME);
                    if (frameArgs != null && mActivity != null) {
                        Intent frameIntent = new Intent(mActivity, TaskManager.class);
                        frameIntent.setAction(IntentActions.DOWNLOAD_START_SAVE_FRAME);
                        frameIntent.putExtras(frameArgs);
                        mActivity.startService(frameIntent);
                        mOperationActive = true;
                    }
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
        } else if (iconId == R.drawable.ic_send_lan_24) {
            // "Send to browser" — LAN share, a full nav destination (same
            // pattern as gif_maker/frame_grabber). The share server's
            // lifetime is the fragment's view lifetime.
            NavigationUtils.navigateSafe(mNavController, R.id.lan_share, bundle);
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
        } else if (iconId == R.drawable.ic_photo_camera_24) {
            NavigationUtils.navigateSafe(mNavController, R.id.frame_grabber, bundle);
        } else if (iconId == R.drawable.ic_lock_24) {
            handleItemAction(IntentActions.LOCK_FOR_ENCRYPTION, entity);
            mOperationActive = true;
            startActionMode(option.getPosition());
        } else if (iconId == R.drawable.ic_lock_open_right_24) {
            handleItemAction(IntentActions.START_DECRYPTION, entity);
            mOperationActive = true;
            startActionMode(option.getPosition());
        } else if (iconId == R.drawable.ic_baseline_archive_24) {
            handleItemAction(IntentActions.DOWNLOAD_START_COMPRESS, entity);
            mOperationActive = true;
            startActionMode(option.getPosition());
        } else if (iconId == R.drawable.ic_baseline_unarchive_24) {
            handleItemAction(IntentActions.DOWNLOAD_START_EXTRACT, entity);
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
        if (id == R.id.action_search) {
            openSearchBar();
        } else if (id == R.id.action_sort) {
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
        } else if (id == R.id.action_compress) {
            ArrayList<DownloadEntity> list = getSelectedItems();
            handleListItemsAction(IntentActions.DOWNLOAD_START_COMPRESS, list);
            mOperationActive = true;
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


    /**
     * Releases the postponed enter transition once the list has laid
     * out the first paging page. Two release paths:
     *
     * <ul>
     *   <li>onPreDraw — fires after the first frame's measure/layout
     *       completes, so the user sees the populated list rather
     *       than a blank window during the transition.</li>
     *   <li>Hard timeout — guards against pathological slow DB / IO
     *       on cold-start. Without it, the postponed transition could
     *       leave the user staring at a blank window for hundreds of
     *       ms if the first paging page is unusually slow.
     *       startPostponedEnterTransition is idempotent on a fragment
     *       that's already released, so racing the two paths is safe.</li>
     * </ul>
     */
    protected void handleTransitionTiming() {
        final ViewGroup parent = (ViewGroup) requireView().getParent();
        if (parent == null) {
            startPostponedEnterTransition();
            return;
        }
        parent.getViewTreeObserver().addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
            @Override public boolean onPreDraw() {
                parent.getViewTreeObserver().removeOnPreDrawListener(this);
                startPostponedEnterTransition();
                return true;
            }
        });
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            if (isAdded()) startPostponedEnterTransition();
        }, TRANSITION_RELEASE_TIMEOUT_MS);
    }

    /** Upper bound on how long we'll keep the cold-start enter transition
     *  postponed while waiting for the first paging page to land. Picked
     *  to comfortably exceed a fast SSD-backed query (~50 ms) and a slow
     *  cold DB open (~150 ms) without leaving the user staring at a
     *  blank window if something goes wrong upstream. */
    private static final long TRANSITION_RELEASE_TIMEOUT_MS = 350L;


    /**
     * Shows a "View" button on the completion bar that opens the just-created
     * file, when the Finished event carried the produced {@link DownloadEntity}.
     * A task's output is often a different type than the active filter chip
     * (save-frame → image under a video filter, extract-audio → audio, etc.),
     * so the new item can be hidden in the list; {@code openItem} opens it
     * directly and sidesteps the filter. Hides the button when there's no
     * single artifact to show (e.g. multi-file extract passes a count).
     */
    private void offerViewAction(Object obj) {
        DownloadEntity entity = obj instanceof DownloadEntity ? (DownloadEntity) obj : null;
        if (entity != null) {
            mBottomProgressView.setActionButtonVisibility(View.VISIBLE);
            mBottomProgressView.setActionButtonText(R.string.file_view);
            mBottomProgressView.setActionButtonListener(v -> openItem(entity, null));
        } else {
            mBottomProgressView.setActionButtonVisibility(View.GONE);
        }
    }

    protected void handleTaskFinish(ServiceActions action, Object obj) {
        mOperationActive = false;
        stopActionMode();

        if (action == ServiceActions.AUDIO_ENCODE) {
            mBottomProgressView.setProgress(100);
            mBottomProgressView.setTitle(R.string.task_audio_finished);
            /* The extracted audio is a different type than a video filter,
             * so it may be hidden in the list. Offer a View that opens it
             * directly, sidestepping the active filter. */
            offerViewAction(obj);
        } else if (action == ServiceActions.MAKE_GIF) {
            mBottomProgressView.setProgress(100);
            mBottomProgressView.setTitle(R.string.task_gif_finished);
            /* GifMakerTask passes the just-created entity through the
             * Finished event so we can offer a one-tap View action that
             * launches PlayerActivity directly — same shape as the
             * Vault encryption/decryption finish UIs. */
            offerViewAction(obj);
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
        } else if (action == ServiceActions.CANCEL_AUDIO_ENCODE) {
            /* User-initiated cancel — brief acknowledgment in the bar
             * itself before the existing slide-down hides it. No
             * snackbar: the user just tapped Cancel, they know what
             * they did; the title flip is enough to confirm the task
             * actually stopped (vs. e.g. failing silently). Cancel
             * button hidden because there's nothing left to cancel. */
            mBottomProgressView.setTitle(R.string.task_audio_cancelled);
            mBottomProgressView.setActionButtonVisibility(View.GONE);
        } else if (action == ServiceActions.CANCEL_MAKE_GIF) {
            mBottomProgressView.setTitle(R.string.task_gif_cancelled);
            mBottomProgressView.setActionButtonVisibility(View.GONE);
        } else if (action == ServiceActions.COMPRESS) {
            mBottomProgressView.setProgress(100);
            mBottomProgressView.setTitle(R.string.task_compress_finished);
            offerViewAction(obj);
        } else if (action == ServiceActions.ERROR_COMPRESS) {
            mBottomProgressView.setTitle(R.string.task_compress_failed);
            mBottomProgressView.setActionButtonVisibility(View.GONE);
            showErrorSnackbar(R.string.task_compress_failed);
        } else if (action == ServiceActions.CANCEL_COMPRESS) {
            mBottomProgressView.setTitle(R.string.task_compress_cancelled);
            mBottomProgressView.setActionButtonVisibility(View.GONE);
        } else if (action == ServiceActions.EXTRACT) {
            /* Extracted files land in this very list (the repository feeds
             * it), so there's nothing to "View" — just report how many came
             * out and let the slide-down hide the bar. */
            int count = obj instanceof Integer ? (Integer) obj : 0;
            mBottomProgressView.setProgress(100);
            mBottomProgressView.setTitle(String.format("%s (%d)", getString(R.string.task_extract_finished), count));
            mBottomProgressView.setActionButtonVisibility(View.GONE);
        } else if (action == ServiceActions.ERROR_EXTRACT) {
            mBottomProgressView.setTitle(R.string.task_extract_failed);
            mBottomProgressView.setActionButtonVisibility(View.GONE);
            showErrorSnackbar(R.string.task_extract_failed);
        } else if (action == ServiceActions.CANCEL_EXTRACT) {
            mBottomProgressView.setTitle(R.string.task_extract_cancelled);
            mBottomProgressView.setActionButtonVisibility(View.GONE);
        } else if (action == ServiceActions.SAVE_FRAME) {
            mBottomProgressView.setProgress(100);
            mBottomProgressView.setTitle(R.string.task_frame_finished);
            /* Saved frame is an image — hidden under a video filter. Offer
             * a View that opens it directly. */
            offerViewAction(obj);
        } else if (action == ServiceActions.ERROR_SAVE_FRAME) {
            mBottomProgressView.setTitle(R.string.task_frame_failed);
            mBottomProgressView.setActionButtonVisibility(View.GONE);
            showErrorSnackbar(R.string.task_frame_failed);
        } else if (action == ServiceActions.CANCEL_SAVE_FRAME) {
            mBottomProgressView.setTitle(R.string.task_frame_cancelled);
            mBottomProgressView.setActionButtonVisibility(View.GONE);
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

    /**
     * Whether the active filter narrows the list to images only (the
     * images/GIF chips) — the one filtered view whose grid tiles carry no
     * text at all, so it renders as a denser square mosaic. Default false;
     * {@code DownloadFragment} overrides by reading its chip rail (the
     * vault has no chip rail and stays at the normal density).
     */
    protected boolean isDenseImageFilterActive() {
        return false;
    }

    /**
     * Re-runs {@link #configureRecyclerView} when a filter change crossed
     * the dense-mosaic boundary (images filter checked/unchecked in grid
     * mode). Gated on an actual transition so the chip taps that don't
     * change density (video → audio) skip the notifyDataSetChanged the
     * reconfigure implies.
     */
    protected void refreshGridDensityIfChanged() {
        if (mAdapter == null || mRecyclerView == null) {
            return;
        }
        boolean dense = mEnableGrid && isDenseImageFilterActive();
        if (dense != mDenseGrid) {
            configureRecyclerView(mAdapter, mEnableGrid);
        }
    }

    protected void configureRecyclerView(DownloadItemAdapter adapter, boolean isGrid) {
        if (mRecyclerView == null) return;

        // RecyclerView's own size doesn't depend on adapter contents (it's
        // match_parent in the layout). Telling it so skips extra measure passes.
        mRecyclerView.setHasFixedSize(true);
        // Default off-screen view cache is 2; bumping it means a quick
        // scroll-back doesn't re-bind (and re-trigger Glide loads) for the
        // rows that just left the viewport. Sized to roughly half the
        // paging page size (Preferences.LIST_LIMIT = 25) so the cache
        // typically holds the previous viewport plus a row or two of
        // headroom — past that the recycled-view pool takes over and
        // re-bind is cheap.
        mRecyclerView.setItemViewCacheSize(12);

        installThumbnailPreloader(adapter);

        // 1. Get or Create LayoutManager
        // Dense mode: grid + images-only filter → square bare tiles at a
        // denser span. Density there costs no information — every tile is
        // already a pure thumbnail (no title by the hide-title-for-images
        // rule; the active filter chip states the type, so no mime chip).
        boolean dense = isGrid && isDenseImageFilterActive();
        mDenseGrid = dense;
        // Field-only setter; the enableGrid(isGrid) below notifies and
        // re-resolves every view type, so the dense flag must be set first.
        adapter.setDenseImages(dense);
        int spans = getResources().getInteger(dense
                ? R.integer.image_grid_dense_number
                : isGrid ? R.integer.image_grid_number : R.integer.image_list_number);

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

        // 2. Handle Decorations — same EqualSpacingItemDecoration for
        // list and grid. List rows are 1-span, full-width; the decoration
        // gives them list_spacing on every side and halfSpacing between,
        // matching the gutter grid tiles get. Was CardViewListItemDecoration
        // for list (which only emitted top/bottom on the first/last item
        // and relied on per-card marginStart/marginEnd for horizontal
        // spacing). With the card margins removed, list items lost their
        // gutter; switching the list to EqualSpacingItemDecoration too
        // restores it without re-adding per-row layout margins.
        int spacing = getResources().getDimensionPixelSize(R.dimen.list_spacing);
        while (mRecyclerView.getItemDecorationCount() > 0) {
            mRecyclerView.removeItemDecorationAt(0);
        }

        mRecyclerView.addItemDecoration(new EqualSpacingItemDecoration(spacing));

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
                // 8 ahead — kept conservative on purpose. Bumping this
                // floods Glide with concurrent FFmpeg decodes during
                // fast cold-start scroll (every preload kicks a fresh
                // MediaMetadataRetriever / FFmpegThumbnailer chain),
                // and the decode contention hurts the visible bind more
                // than the prefetch helps the next viewport. A larger
                // window only pays off if Glide's decoders can finish
                // ahead of the scroll, which isn't true with FFmpeg in
                // the chain.
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
        mSearchBar = null;
        mSearchEdit = null;
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
        }else if(action == ServiceActions.COMPRESS){
            mBottomProgressView.setTitle(R.string.download_saving_compress);
            mBottomProgressView.setActionButtonListener(v -> handleItemAction(IntentActions.DOWNLOAD_CANCEL_COMPRESS, null));
        }else if(action == ServiceActions.EXTRACT){
            mBottomProgressView.setTitle(R.string.download_saving_extract);
            mBottomProgressView.setActionButtonListener(v -> handleItemAction(IntentActions.DOWNLOAD_CANCEL_EXTRACT, null));
        }else if(action == ServiceActions.SAVE_FRAME){
            mBottomProgressView.setTitle(R.string.download_saving_frame);
            mBottomProgressView.setActionButtonListener(v -> handleItemAction(IntentActions.DOWNLOAD_CANCEL_SAVE_FRAME, null));
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
        return mSearchBar != null && mSearchBar.getVisibility() == View.VISIBLE;
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