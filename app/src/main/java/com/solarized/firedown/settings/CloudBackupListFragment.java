package com.solarized.firedown.settings;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.Toolbar;
import androidx.core.graphics.Insets;
import androidx.core.view.MenuProvider;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.Observer;
import androidx.navigation.NavBackStackEntry;
import androidx.navigation.NavController;
import androidx.navigation.fragment.NavHostFragment;
import androidx.recyclerview.widget.RecyclerView;
import androidx.work.Constraints;
import androidx.work.Data;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkInfo;
import androidx.work.WorkManager;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;
import com.solarized.firedown.R;
import com.solarized.firedown.sync.CloudBackupManager;
import com.solarized.firedown.sync.VaultBackupWorker;
import com.solarized.firedown.sync.VaultRestoreWorker;
import com.solarized.firedown.sync.model.VaultEntry;
import com.solarized.firedown.ui.EqualSpacingItemDecoration;
import com.solarized.firedown.ui.LCEERecyclerView;
import com.solarized.firedown.utils.NavigationUtils;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import javax.inject.Inject;

import dagger.hilt.android.AndroidEntryPoint;

/**
 * The "Backed-up files" screen — a RecyclerView of every file in Cloud Backup
 * (stored preview / mime fallback + name + "size · date"), styled like the
 * Downloads list. Tapping a row opens {@link CloudBackupItemSheetDialogFragment}
 * (Restore / Remove). Reached from the Cloud Backup settings screen and the
 * Downloads toolbar overflow.
 *
 * <p>Remove is <b>optimistic</b>: the row disappears immediately (the slow server
 * delete runs in the background) and only re-appears with an error snackbar if the
 * delete fails — so the user isn't left staring at an unchanged list for seconds.
 */
@AndroidEntryPoint
public class CloudBackupListFragment extends Fragment
        implements CloudBackupFileAdapter.OnItemClickListener {

    @Inject
    CloudBackupManager mCloudBackup;

    private NavController mNavController;
    private LCEERecyclerView mLcee;
    private RecyclerView mRecycler;
    private CloudBackupFileAdapter mAdapter;

    private final List<VaultEntry> mEntries = new ArrayList<>();
    private boolean mLoading = true;
    /** True while any backup/restore transfer is running. */
    private boolean mTransferActive;
    /** True while multi-select is active (drives the toolbar title/menu). */
    private boolean mSelectionMode;
    private Toolbar mToolbar;
    private OnBackPressedCallback mBackCallback;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_cloud_backup_files, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        mNavController = NavHostFragment.findNavController(this);
        mLcee = view.findViewById(R.id.cb_lcee);
        mRecycler = mLcee.getRecyclerView();
        mAdapter = new CloudBackupFileAdapter(this);
        mRecycler.setAdapter(mAdapter);
        // Same gutter as the Downloads list (and Bookmarks/History/Captured):
        // EqualSpacingItemDecoration at list_spacing.
        mRecycler.addItemDecoration(
                new EqualSpacingItemDecoration(requireContext(), R.dimen.list_spacing));
        // Empty state (LCEE) — the Downloads balloons illustration + message.
        mLcee.setEmptyImageView(R.drawable.ill_baloons);
        mLcee.setEmptyText(R.string.cloud_backup_list_empty);

        // Same inset treatment as the preference screens: list scrolls under the
        // nav bar but the last row clears it.
        ViewCompat.setOnApplyWindowInsetsListener(view, (v, windowInsets) -> {
            Insets insets = windowInsets.getInsets(
                    WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            v.setPadding(insets.left, 0, insets.right, 0);
            mRecycler.setPadding(mRecycler.getPaddingLeft(), mRecycler.getPaddingTop(),
                    mRecycler.getPaddingRight(), insets.bottom);
            return WindowInsetsCompat.CONSUMED;
        });

        // The screen's toolbar drives the selection chrome (Downloads strategy).
        mToolbar = requireActivity().findViewById(R.id.toolbar);

        // Delete action, contributed to the toolbar only while selecting.
        requireActivity().addMenuProvider(new MenuProvider() {
            @Override
            public void onCreateMenu(@NonNull Menu menu, @NonNull MenuInflater inflater) {
                if (mSelectionMode) {
                    inflater.inflate(R.menu.menu_action, menu);
                }
            }

            @Override
            public boolean onMenuItemSelected(@NonNull MenuItem item) {
                if (mSelectionMode && item.getItemId() == R.id.action_delete) {
                    confirmDeleteSelected();
                    return true;
                }
                return false;
            }
        }, getViewLifecycleOwner());

        // System Back exits selection first (disabled until selecting).
        mBackCallback = new OnBackPressedCallback(false) {
            @Override
            public void handleOnBackPressed() {
                exitSelection();
            }
        };
        requireActivity().getOnBackPressedDispatcher()
                .addCallback(getViewLifecycleOwner(), mBackCallback);

        observeSheetResult();
        observeTransfers();
        load();
    }

    @Override
    public void onDestroyView() {
        // Restore the toolbar (title + Up behaviour) if we leave mid-selection.
        exitSelection();
        super.onDestroyView();
    }

    /**
     * Builds an in-progress upload ROW (with a determinate per-item bar + cancel)
     * for each running backup, shown above the committed files — a file isn't in
     * the manifest until its upload finishes, so the list would otherwise look idle
     * mid-upload. Reloads the manifest when a transfer COMPLETES so the new entry
     * appears without re-entering. Only uploads of NEW files render a row: a
     * transfer whose name is already a committed entry (a re-backup or a restore)
     * keeps its existing row, no duplicate progress row.
     */
    private void observeTransfers() {
        WorkManager.getInstance(requireContext().getApplicationContext())
                .getWorkInfosByTagLiveData(CloudBackupManager.WORK_TAG)
                .observe(getViewLifecycleOwner(), infos -> {
                    List<CloudBackupFileAdapter.Transfer> transfers = new ArrayList<>();
                    boolean active = false;
                    Set<String> seenNames = new HashSet<>();
                    if (infos != null) {
                        for (WorkInfo wi : infos) {
                            WorkInfo.State s = wi.getState();
                            boolean running = s == WorkInfo.State.RUNNING
                                    || s == WorkInfo.State.ENQUEUED;
                            if (!running) {
                                continue;
                            }
                            active = true;
                            Data p = wi.getProgress();
                            String name = p.getString(VaultBackupWorker.KEY_NAME);
                            // Nameless (not-yet-started, or a restore that doesn't
                            // publish) — no row; a restore already has its committed
                            // row, and a re-backup of an existing file likewise.
                            if (name == null || isCommitted(name)) {
                                continue;
                            }
                            // One row per file name — collapse any transient
                            // multi-worker state into a single transfer row.
                            if (!seenNames.add(name)) {
                                continue;
                            }
                            transfers.add(new CloudBackupFileAdapter.Transfer(
                                    wi.getId().toString(), name,
                                    p.getString(VaultBackupWorker.KEY_MIME),
                                    p.getLong(VaultBackupWorker.KEY_PROGRESS_DONE, 0),
                                    p.getLong(VaultBackupWorker.KEY_PROGRESS_TOTAL, 0)));
                        }
                    }
                    boolean justFinished = mTransferActive && !active;
                    mTransferActive = active;
                    mAdapter.setTransfers(transfers);
                    render();
                    if (justFinished) {
                        load(); // a transfer completed — pull in the new entry
                    }
                });
    }

    /** Whether a committed manifest entry already has this file name. */
    private boolean isCommitted(String name) {
        for (VaultEntry e : mEntries) {
            if (name.equals(e.name)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void onCancelTransfer(String workId) {
        try {
            WorkManager.getInstance(requireContext().getApplicationContext())
                    .cancelWorkById(UUID.fromString(workId));
            snackbar(getString(R.string.cloud_backup_transfer_cancelled));
        } catch (IllegalArgumentException ignored) {
            // malformed id — nothing to cancel
        }
    }

    private void load() {
        mLoading = true;
        render();
        mCloudBackup.loadEntries(entries -> {
            if (!isAdded()) {
                return;
            }
            mLoading = false;
            mEntries.clear();
            mEntries.addAll(entries);
            mAdapter.submit(mEntries);
            render();
            backfillThumbnails();
        }, () -> {
            if (!isAdded()) {
                return;
            }
            mLoading = false;
            render();
            snackbar(getString(R.string.cloud_backup_list_error));
        });
    }

    /**
     * For entries with no stored preview (backed up before thumbnails existed),
     * regenerate one from the local copy if it's still present and slot it into
     * the row. Display-only — the manifest isn't touched.
     */
    private void backfillThumbnails() {
        for (VaultEntry entry : mEntries) {
            if (entry.thumb != null || entry.name == null) {
                continue;
            }
            mCloudBackup.resolveLocalThumb(entry, thumb -> {
                if (isAdded() && thumb != null) {
                    mAdapter.setResolvedThumb(entry.objectId, thumb);
                }
            });
        }
    }

    /** Drives the LCEE state: content (rows or an in-progress transfer), the
     *  loading spinner (initial fetch only), or the empty illustration. */
    private void render() {
        boolean hasRows = mAdapter != null && mAdapter.getItemCount() > 0;
        if (hasRows || mTransferActive) {
            mLcee.hideAll();          // show the list (rows carry the state)
        } else if (mLoading) {
            mLcee.showLoading();      // spinner on the first fetch only
        } else {
            mLcee.showEmpty();        // balloons + "nothing backed up yet"
        }
    }

    @Override
    public void onItemClick(VaultEntry entry) {
        // In multi-select a tap toggles selection instead of opening the sheet.
        if (mSelectionMode) {
            mAdapter.toggleSelected(entry.objectId);
            refreshSelection();
            return;
        }
        Bundle args = new Bundle();
        args.putString(CloudBackupItemSheetDialogFragment.ARG_OBJECT_ID, entry.objectId);
        args.putString(CloudBackupItemSheetDialogFragment.ARG_NAME, entry.name);
        args.putString(CloudBackupItemSheetDialogFragment.ARG_MIME, entry.mime);
        args.putLong(CloudBackupItemSheetDialogFragment.ARG_SIZE, entry.size);
        args.putLong(CloudBackupItemSheetDialogFragment.ARG_DOWNLOADED_AT, entry.downloadedAt);
        args.putString(CloudBackupItemSheetDialogFragment.ARG_THUMB, entry.thumb);
        NavigationUtils.navigateSafe(mNavController,
                R.id.action_cloud_backup_files_to_item_sheet, args);
    }

    @Override
    public void onItemLongClick(VaultEntry entry) {
        if (!mSelectionMode) {
            enterSelection();
        }
        mAdapter.toggleSelected(entry.objectId);
        refreshSelection();
    }

    // ---- multi-select via the existing toolbar (the Downloads strategy, NOT a
    //      contextual ActionMode): the screen toolbar shows "N selected" + a
    //      delete action while selecting, and its Up button / system Back exit
    //      selection instead of leaving the screen. ----

    private void enterSelection() {
        mSelectionMode = true;
        mAdapter.setActionMode(true);
        if (mBackCallback != null) {
            mBackCallback.setEnabled(true);
        }
        if (mToolbar != null) {
            mToolbar.setNavigationOnClickListener(v -> exitSelection());
        }
        requireActivity().invalidateOptionsMenu(); // surface the delete action
    }

    private void exitSelection() {
        if (!mSelectionMode) {
            return;
        }
        mSelectionMode = false;
        mAdapter.setActionMode(false);
        if (mBackCallback != null) {
            mBackCallback.setEnabled(false);
        }
        if (mToolbar != null) {
            mToolbar.setTitle(R.string.cloud_backup_files_title);
            // Restore the activity's Up behaviour (pop, or finish at the root).
            mToolbar.setNavigationOnClickListener(v -> {
                if (!mNavController.popBackStack()) {
                    requireActivity().finish();
                }
            });
        }
        requireActivity().invalidateOptionsMenu();
    }

    /** Updates the "N selected" title, or exits selection when none remain. */
    private void refreshSelection() {
        int n = mAdapter.getSelectedCount();
        if (n == 0) {
            exitSelection();
            return;
        }
        if (mToolbar != null) {
            mToolbar.setTitle(getString(R.string.action_mode_selected, n));
        }
    }

    /** Confirms then removes every selected file from the cloud (optimistically). */
    private void confirmDeleteSelected() {
        int n = mAdapter.getSelectedCount();
        if (n == 0) {
            return;
        }
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.cloud_backup_delete_title)
                .setMessage(R.string.cloud_backup_delete_message)
                .setPositiveButton(R.string.delete, (d, w) -> deleteSelected())
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void deleteSelected() {
        List<String> ids = mAdapter.getSelectedIds();
        List<VaultEntry> targets = new ArrayList<>();
        for (String id : ids) {
            VaultEntry e = findEntry(id);
            if (e != null) {
                targets.add(e);
            }
        }
        exitSelection();
        if (targets.isEmpty()) {
            return;
        }
        // Optimistic: drop the rows now; the slow server deletes run in the
        // background and a failure resyncs the truth via load().
        for (VaultEntry e : targets) {
            mAdapter.removeByObjectId(e.objectId);
            mEntries.remove(e);
        }
        render();
        snackbar(getString(R.string.cloud_backup_remove_done));
        final int[] remaining = {targets.size()};
        final boolean[] anyFailed = {false};
        for (VaultEntry e : targets) {
            mCloudBackup.deleteEntry(e, ok -> {
                if (!ok) {
                    anyFailed[0] = true;
                }
                if (--remaining[0] == 0 && anyFailed[0] && isAdded()) {
                    snackbar(getString(R.string.cloud_backup_remove_failed));
                    load(); // resync with the server truth
                }
            });
        }
    }

    /** Observes the per-item bottom sheet's result (Restore / Remove). */
    private void observeSheetResult() {
        NavBackStackEntry entry = mNavController.getCurrentBackStackEntry();
        if (entry == null) {
            return;
        }
        LiveData<Bundle> live = entry.getSavedStateHandle()
                .getLiveData(CloudBackupItemSheetDialogFragment.RESULT);
        live.observe(getViewLifecycleOwner(), result -> {
            if (result == null) {
                return;
            }
            // Consume by setting null, NOT remove(): SavedStateHandle.remove()
            // DETACHES the cached LiveData, so this observer would stop receiving
            // every subsequent result — the second remove/restore would silently
            // do nothing. set(null) reuses the same LiveData and the guard above
            // ignores the null tick.
            entry.getSavedStateHandle()
                    .set(CloudBackupItemSheetDialogFragment.RESULT, null);
            int action = result.getInt(CloudBackupItemSheetDialogFragment.RESULT_ACTION);
            String objectId = result.getString(
                    CloudBackupItemSheetDialogFragment.RESULT_OBJECT_ID);
            VaultEntry target = findEntry(objectId);
            if (target == null) {
                return;
            }
            if (action == CloudBackupItemSheetDialogFragment.ACTION_RESTORE) {
                restore(target);
            } else {
                removeOptimistic(target);
            }
        });
    }

    private VaultEntry findEntry(String objectId) {
        if (objectId == null) {
            return null;
        }
        for (VaultEntry e : mEntries) {
            if (objectId.equals(e.objectId)) {
                return e;
            }
        }
        return null;
    }

    /** Removes the row immediately; the slow server delete runs in the background
     *  and only the failure path restores the row (with an error snackbar). */
    private void removeOptimistic(VaultEntry entry) {
        int pos = mAdapter.removeByObjectId(entry.objectId);
        mEntries.remove(entry);
        render();
        snackbar(getString(R.string.cloud_backup_remove_done));
        mCloudBackup.deleteEntry(entry, ok -> {
            if (!isAdded() || ok) {
                return; // success: the row is already gone
            }
            // Failed — put it back where it was.
            int p = pos < 0 ? mEntries.size() : Math.min(pos, mEntries.size());
            mEntries.add(p, entry);
            mAdapter.insertAt(p, entry);
            render();
            snackbar(getString(R.string.cloud_backup_remove_failed));
        });
    }

    private void restore(VaultEntry entry) {
        Data input = new Data.Builder()
                .putString(VaultRestoreWorker.KEY_OBJECT_ID, entry.objectId)
                .putString(VaultRestoreWorker.KEY_WRAPPED_DEK, entry.wrappedDek)
                .putString(VaultRestoreWorker.KEY_NAME, entry.name)
                .putString(VaultRestoreWorker.KEY_MIME, entry.mime)
                .putLong(VaultRestoreWorker.KEY_SIZE, entry.size)
                .putLong(VaultRestoreWorker.KEY_DOWNLOADED_AT, entry.downloadedAt)
                .putInt(VaultRestoreWorker.KEY_CHUNK_COUNT, entry.chunkCount)
                .build();
        Constraints constraints = new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build();
        OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(VaultRestoreWorker.class)
                .setInputData(input)
                .setConstraints(constraints)
                .addTag(CloudBackupManager.WORK_TAG)
                .build();
        WorkManager wm = WorkManager.getInstance(requireContext().getApplicationContext());
        wm.enqueue(request);
        snackbar(getString(R.string.cloud_restore_started));

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
                boolean ok = info.getState() == WorkInfo.State.SUCCEEDED;
                if (ok && info.getOutputData().getBoolean(
                        VaultRestoreWorker.KEY_ALREADY_PRESENT, false)) {
                    snackbar(getString(R.string.cloud_restore_already_present));
                    return;
                }
                snackbar(getString(ok
                        ? R.string.cloud_restore_done
                        : R.string.cloud_restore_failed));
            }
        });
    }

    private void snackbar(String text) {
        View view = getView();
        if (view != null) {
            Snackbar.make(view, text, Snackbar.LENGTH_LONG).show();
        }
    }
}
