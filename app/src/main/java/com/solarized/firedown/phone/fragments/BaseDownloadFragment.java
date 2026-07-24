package com.solarized.firedown.phone.fragments;


import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.TextView;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleEventObserver;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.Observer;
import androidx.lifecycle.SavedStateHandle;
import androidx.navigation.NavBackStackEntry;
import androidx.navigation.NavDestination;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.work.Constraints;
import androidx.work.Data;
import androidx.work.ExistingWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkInfo;
import androidx.work.WorkManager;

import com.bumptech.glide.Glide;
import com.bumptech.glide.ListPreloader;
import com.bumptech.glide.RequestBuilder;
import com.bumptech.glide.RequestManager;
import com.bumptech.glide.integration.recyclerview.RecyclerViewPreloader;
import com.bumptech.glide.request.RequestOptions;
import com.bumptech.glide.util.FixedPreloadSizeProvider;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;
import com.solarized.firedown.GlideHelper;
import com.solarized.firedown.IntentActions;
import com.solarized.firedown.Keys;
import com.solarized.firedown.R;
import com.solarized.firedown.Sorting;
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
import com.solarized.firedown.sync.CloudBackupManager;
import com.solarized.firedown.sync.VaultBackupWorker;
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

    /** Source of the persisted section GROUPING, so a freshly created adapter
     *  starts on the user's stored sort instead of the SORT_DATE default (see
     *  {@link #seedGroupingSort}). */
    @Inject
    protected Sorting mSorting;

    @Inject
    protected CloudBackupManager mCloudBackup;

    protected DownloadsViewModel mDownloadsViewModel;

    protected TaskViewModel mTaskViewModel;

    protected DownloadItemAdapter mAdapter;

    /**
     * Seeds a freshly created adapter with the persisted grouping so its rows
     * drop the field the section headers already state from the FIRST bind —
     * the same value {@link com.solarized.firedown.data.models.DownloadsViewModel}
     * seeds its own initial state from. Without this the adapter would sit on
     * its SORT_DATE default until the user next changed the sort, which is only
     * correct for users who never left the default.
     */
    protected void seedGroupingSort() {
        if (mAdapter != null && mSorting != null) {
            mAdapter.setGroupingSort(mSorting.getCurrentSortLocal());
        }
    }

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
        refreshCloudBadges();
    }

    /**
     * Whether this list shows the "backed up to cloud" thumbnail badge. The Safe
     * Folder never leaves the device, so {@code VaultFragment} overrides this to
     * false — the badge is meaningless there.
     */
    protected boolean showsCloudBadges() {
        return true;
    }

    /**
     * Refreshes the set of backed-up content keys that drives the per-row cloud
     * badge (see {@link DownloadItemAdapter#setBackedUpKeys}). Runs on resume so a
     * file backed up while away picks up its badge on return. Cheap + gated: a
     * non-cloud-backup user gets an empty set with no network touch, and the
     * adapter no-ops when the set is unchanged.
     */
    private void refreshCloudBadges() {
        if (!showsCloudBadges() || mAdapter == null) {
            return;
        }
        mCloudBackup.loadBackedUpKeys(keys -> {
            if (mAdapter != null) {
                mAdapter.setBackedUpKeys(keys);
            }
        });
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

    /** Route the live search query to the downloads/vault list. */
    @Override
    protected void onSearchQueryChanged(String query) {
        mDownloadsViewModel.search(query);
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
                    if (option != null) {
                        mDownloadsViewModel.setSortType(option.getId());
                        // The rows must also drop whatever the NEW headers state
                        // (see DownloadItemAdapter.setGroupingSort). Applied here
                        // rather than deferred to the new paging generation: this
                        // only adds/removes a text token — it can't reflow spans
                        // the way the dense-mosaic flip can, so there's no
                        // old-list-in-new-presentation frame to avoid.
                        if (mAdapter != null) mAdapter.setGroupingSort(option.getId());
                    }
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
        } else if (iconId == R.drawable.ic_p2p_send_24) {
            // "Send directly" — P2P WebRTC share, a full nav destination
            // (same pattern as gif_maker/frame_grabber). The engine session's
            // lifetime is the fragment's view lifetime.
            NavigationUtils.navigateSafe(mNavController, R.id.p2p_send, bundle);
        } else if (iconId == R.drawable.ic_cloud_upload_24) {
            // "Back up to cloud" — encrypt + upload this finished download to
            // Cloud Backup. Action-driven: sets up the (shared) recovery code on
            // demand if the user has none yet.
            startCloudBackup(entity);
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

    /**
     * Backs a finished download up to Cloud Backup. Reuses the shared recovery
     * code when one exists (bookmark sync or a prior backup); otherwise runs a
     * one-time setup that mints and shows the code first ("it's the only key").
     */
    private void startCloudBackup(DownloadEntity entity) {
        if (entity == null || entity.getFilePath() == null) {
            return;
        }
        startCloudBackup(Collections.singletonList(entity));
    }

    /**
     * Backs up one OR MORE finished downloads (the multi-select path passes the
     * selected set). The shared recovery code is minted on first use with ONE
     * setup dialog for the whole batch — not one per file.
     */
    private void startCloudBackup(List<DownloadEntity> entities) {
        List<DownloadEntity> targets = new ArrayList<>();
        for (DownloadEntity e : entities) {
            if (e != null && e.getFilePath() != null) {
                targets.add(e);
            }
        }
        if (targets.isEmpty()) {
            return;
        }
        if (mCloudBackup.hasAccount()) {
            enqueueCloudBackup(targets);
        } else {
            showCloudBackupSetupDialog(targets);
        }
    }

    /** First-time setup confirm before minting the recovery code. */
    private void showCloudBackupSetupDialog(List<DownloadEntity> targets) {
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.cloud_backup_setup_title)
                .setMessage(R.string.cloud_backup_setup_message)
                .setPositiveButton(R.string.cloud_backup_setup_create, (dialog, which) -> {
                    String grouped = mCloudBackup.createNewCode();
                    showCloudBackupCodeDialog(grouped, targets);
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    /**
     * Shows the freshly-minted recovery code with a "save this, no recovery" gate
     * (reuses the bookmark-sync show-code layout), then starts the backup. The
     * code is the only key to the cloud backup, so the user must acknowledge they
     * saved it before the upload begins.
     */
    private void showCloudBackupCodeDialog(String grouped, List<DownloadEntity> targets) {
        if (grouped == null) {
            showErrorSnackbar(R.string.settings_cloud_backup_code_unavailable);
            return;
        }
        View view = getLayoutInflater().inflate(R.layout.dialog_sync_show_code, null);
        TextView codeText = view.findViewById(R.id.sync_code_text);
        codeText.setText(grouped);
        CheckBox savedCheck = view.findViewById(R.id.sync_code_saved_check);
        savedCheck.setVisibility(View.VISIBLE);

        AlertDialog dialog = new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.settings_sync_code_created_title)
                .setView(view)
                .setNeutralButton(R.string.settings_sync_code_copy, null)
                .setPositiveButton(R.string.settings_sync_code_done, null)
                .setCancelable(false)
                .create();
        dialog.show();
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v ->
                copyRecoveryCode(grouped));
        Button done = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
        done.setEnabled(false);
        savedCheck.setOnCheckedChangeListener((b, checked) -> done.setEnabled(checked));
        done.setOnClickListener(v -> {
            dialog.dismiss();
            enqueueCloudBackup(targets);
        });
    }

    private void copyRecoveryCode(String grouped) {
        // A clipboard write is a binder call into system_server; never let a
        // dying clipboard service crash the app (see AutoCompleteView hardening).
        try {
            ClipboardManager cm = (ClipboardManager)
                    requireContext().getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText(
                        getString(R.string.settings_cloud_backup_title), grouped));
                showErrorSnackbar(R.string.settings_sync_code_copied);
            }
        } catch (RuntimeException ignored) {
            // Clipboard unavailable — the code is still shown for manual copy.
        }
    }

    /** Enqueues a backup worker for each target and shows ONE "Backing up…"
     *  snackbar for the whole batch (per-file dedup is handled by the unique
     *  content key, so a re-tapped file never double-uploads). */
    private void enqueueCloudBackup(List<DownloadEntity> targets) {
        WorkManager wm = WorkManager.getInstance(requireContext().getApplicationContext());
        for (DownloadEntity entity : targets) {
            enqueueOneBackup(wm, entity);
        }
        // "Backing up…" with a View action → the backed-up files list (live
        // per-item progress shows there). No success snackbar — the list is the
        // confirmation; only a failure is surfaced per file below.
        showBackupStartedSnackbar();
    }

    /** Caps a tag payload (a null value becomes ""; a huge filename is trimmed —
     *  the tag identifies a transfer row, so display fidelity is enough). */
    private static String truncateTag(String value) {
        if (value == null) {
            return "";
        }
        return value.length() <= 120 ? value : value.substring(0, 120);
    }

    /** Enqueues the foreground backup worker for one download and reports a
     *  terminal FAILURE with a snackbar (the batch caller owns the started one). */
    private void enqueueOneBackup(WorkManager wm, DownloadEntity entity) {
        // The origin URL to preserve in the manifest, so a restored file's row shows
        // its real MIME · domain — same source the list adapter parses the domain
        // from (origin URL first, media URL as the fallback).
        String origin = entity.getOriginUrl();
        if (origin == null || origin.isEmpty()) {
            origin = entity.getFileUrl();
        }
        Data input = new Data.Builder()
                .putString(VaultBackupWorker.KEY_PATH, entity.getFilePath())
                .putString(VaultBackupWorker.KEY_MIME, entity.getFileMimeType())
                .putString(VaultBackupWorker.KEY_NAME, entity.getFileName())
                .putString(VaultBackupWorker.KEY_ORIGIN, origin)
                // Reuse the exact frame the Downloads list renders, so the stored
                // preview is the same (precise) frame, not a guessed offset.
                .putLong(VaultBackupWorker.KEY_FRAME_US, GlideHelper.thumbnailFrameUs(entity))
                .build();
        Constraints constraints = new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build();
        OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(VaultBackupWorker.class)
                .setInputData(input)
                .setConstraints(constraints)
                .addTag(CloudBackupManager.WORK_TAG)
                // Identity tags so the backed-up-files list can render a transfer
                // row while the worker is still ENQUEUED — WorkInfo exposes tags
                // but NOT input data, and progress is empty until the worker
                // starts, which made the list look EMPTY right after "Back up to
                // cloud" (the moment the snackbar's View opens it). Name payload
                // capped (tags are DB rows, filenames can be huge; display-only).
                .addTag(VaultBackupWorker.TAG_NAME + truncateTag(entity.getFileName()))
                .addTag(VaultBackupWorker.TAG_MIME + truncateTag(entity.getFileMimeType()))
                .addTag(VaultBackupWorker.TAG_SIZE + entity.getFileSize())
                .build();
        // UNIQUE per file CONTENT (name + size), REPLACE. Keyed on content — NOT
        // the path — because the SAME video downloaded twice lands at two
        // different paths with the same name+size; a path key let both run
        // concurrently (4 workers all uploading the same 665 MB file were seen
        // on-device, spamming "setProgressAsync must complete before Result" and
        // making cancel useless). name+size matches the engine's own dedup key,
        // so unique work still collapses every backup of the same content to ONE
        // worker. REPLACE (was KEEP) so a re-tap means RETRY NOW: under KEEP a
        // wedged/back-off worker made "Back up to cloud" a silent no-op — there
        // was NO way to kick a stuck backup (on-device: legacy pre-fix workers
        // spinning in retry backoff for hours, un-cancellable rows). REPLACE
        // cancels the old attempt and starts fresh; rapid duplicate taps still
        // collapse (each replaces the last, one survivor), a replaced partial
        // upload is just a pending orphan the server's ReapPending sweeps, and a
        // re-tap after SUCCESS re-runs into the engine's commit-time dedup which
        // returns the existing entry (no duplicate, no re-upload of the object).
        String uniqueName = "cloud_backup:" + entity.getFileName() + ":" + entity.getFileSize();
        wm.enqueueUniqueWork(uniqueName, ExistingWorkPolicy.REPLACE, request);

        final LiveData<WorkInfo> live = wm.getWorkInfoByIdLiveData(request.getId());
        live.observe(getViewLifecycleOwner(), new Observer<WorkInfo>() {
            @Override
            public void onChanged(WorkInfo info) {
                if (info == null || !info.getState().isFinished()) {
                    return;
                }
                live.removeObserver(this);
                if (!isAdded()) {
                    return;
                }
                if (info.getState() == WorkInfo.State.FAILED) {
                    showErrorSnackbar(R.string.cloud_backup_failed);
                }
            }
        });
    }

    /** "Backing up…" snackbar with a View action → the backed-up-files list. */
    private void showBackupStartedSnackbar() {
        if (mActivity == null) {
            return;
        }
        Snackbar bar = makeSnackbar(mActivity.getSnackAnchorView(),
                R.string.cloud_backup_started, mCurrentDestinationId == R.id.vault);
        bar.setAction(R.string.cloud_backup_view, v -> {
            Intent intent = new Intent(mActivity, SettingsActivity.class);
            intent.putExtra(SettingsActivity.EXTRA_OPEN_CLOUD_BACKUP_FILES, true);
            startActivity(intent);
        });
        bar.show();
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
        } else if (id == R.id.action_cloud_backup_selected) {
            // Back up the selected finished, non-vault downloads to Cloud Backup.
            // Mirror the per-item gating (no error, not in-progress, not safe, has
            // a file) so an in-progress / errored selection is skipped, not sent.
            List<DownloadEntity> eligible = new ArrayList<>();
            boolean hadSafe = false;
            for (DownloadEntity e : mAdapter.getSelectedEntities()) {
                if (e.isFileSafe()) {
                    // The vault never leaves the device (the mirror/P2P-send
                    // contract) — but say so, don't silently drop the file.
                    hadSafe = true;
                } else if (e.getFileErrorType() == Download.PROGRESS
                        && e.getFileStatus() != Download.PROGRESS
                        && e.getFilePath() != null) {
                    eligible.add(e);
                }
            }
            if (eligible.isEmpty()) {
                showErrorSnackbar(hadSafe
                        ? R.string.cloud_backup_safe_excluded
                        : R.string.cloud_backup_none_eligible);
            } else {
                startCloudBackup(eligible);
            }
            stopActionMode();
        } else if (id == R.id.action_select_all) {
            mAdapter.selectAll();
            setActionModeTitle(mAdapter.getSelectedSize());
        } else if (id == R.id.action_deselect_all) {
            mAdapter.deselectAll();
            setActionModeTitle(mAdapter.getSelectedSize());
        } else if (id == R.id.action_receive) {
            NavigationUtils.navigateSafe(mNavController, R.id.p2p_receive);
        } else if (id == R.id.action_safe) {
            Intent intent = new Intent(mActivity, VaultActivity.class);
            startActivity(intent);
        } else if (id == R.id.action_cloud_backup) {
            Intent intent = new Intent(mActivity, SettingsActivity.class);
            // Routing, two ways:
            //  - in use (has backups) → the backed-up-files list (useful);
            //  - everything else      → the merged Cloud screen, which handles
            //    both remaining states itself: a keyed-but-empty account gets the
            //    status hero + "Add storage credit" CTA, a keyless user gets the
            //    Create / "I have a recovery code" gateway (key-first gate).
            String extra = mCloudBackup.isSetUp()
                    ? SettingsActivity.EXTRA_OPEN_CLOUD_BACKUP_FILES
                    : SettingsActivity.EXTRA_OPEN_CLOUD_BACKUP;
            intent.putExtra(extra, true);
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