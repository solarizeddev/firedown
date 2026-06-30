package com.solarized.firedown.settings;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.graphics.Insets;
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

import com.google.android.material.snackbar.Snackbar;
import com.solarized.firedown.R;
import com.solarized.firedown.sync.CloudBackupManager;
import com.solarized.firedown.sync.VaultBackupWorker;
import com.solarized.firedown.sync.VaultRestoreWorker;
import com.solarized.firedown.sync.model.VaultEntry;
import com.solarized.firedown.utils.NavigationUtils;

import java.util.ArrayList;
import java.util.List;
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
    private RecyclerView mRecycler;
    private View mEmptyView;
    private View mEmptyImage;
    private TextView mEmpty;
    private CloudBackupFileAdapter mAdapter;

    private final List<VaultEntry> mEntries = new ArrayList<>();
    private boolean mLoading = true;
    /** True while any backup/restore transfer is running. */
    private boolean mTransferActive;

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
        mRecycler = view.findViewById(R.id.cb_recycler);
        mEmptyView = view.findViewById(R.id.cb_empty_view);
        mEmptyImage = view.findViewById(R.id.cb_empty_image);
        mEmpty = view.findViewById(R.id.cb_empty);
        mAdapter = new CloudBackupFileAdapter(this);
        mRecycler.setAdapter(mAdapter);

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

        observeSheetResult();
        observeTransfers();
        load();
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

    /** Shows the list, or the loading/empty placeholder. */
    private void render() {
        // Any row — a committed file OR an in-progress transfer — counts as content.
        boolean hasRows = mAdapter != null && mAdapter.getItemCount() > 0;
        mRecycler.setVisibility(hasRows ? View.VISIBLE : View.GONE);
        // While a transfer runs an in-progress row carries the state — don't paint
        // the "nothing backed up yet" illustration (e.g. during the first upload,
        // when the manifest is still empty and the row is only just being built).
        if (hasRows || mTransferActive) {
            mEmptyView.setVisibility(View.GONE);
            return;
        }
        mEmptyView.setVisibility(View.VISIBLE);
        // The illustration is the "nothing backed up yet" state; while loading,
        // show just the text (no balloons under a transient spinner-less wait).
        mEmptyImage.setVisibility(mLoading ? View.GONE : View.VISIBLE);
        mEmpty.setText(mLoading
                ? R.string.cloud_backup_list_loading
                : R.string.cloud_backup_list_empty);
    }

    @Override
    public void onItemClick(VaultEntry entry) {
        Bundle args = new Bundle();
        args.putString(CloudBackupItemSheetDialogFragment.ARG_OBJECT_ID, entry.objectId);
        args.putString(CloudBackupItemSheetDialogFragment.ARG_NAME, entry.name);
        NavigationUtils.navigateSafe(mNavController,
                R.id.action_cloud_backup_files_to_item_sheet, args);
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
