package com.solarized.firedown.settings;

import androidx.annotation.Nullable;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

import com.solarized.firedown.sync.CloudBackupManager;
import com.solarized.firedown.sync.PendingRemovals;
import com.solarized.firedown.sync.model.VaultEntry;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.inject.Inject;

import dagger.hilt.android.lifecycle.HiltViewModel;

/**
 * State for the Backed-up files screen ({@link CloudBackupListFragment}).
 *
 * <p>This screen had NO ViewModel: the manifest, the load/error flags, the
 * in-flight-delete guard and the selection all lived as fields on the fragment,
 * so every rotation destroyed them and re-pulled the manifest over the network.
 * Owning them here fixes that and, incidentally, is what lets the adapter stop
 * being a state holder (see {@link #getSelection()}).
 *
 * <p>What did NOT move, deliberately:
 * <ul>
 *   <li><b>The generation guard stays</b> ({@link #mLoadGen}). It is tempting to
 *       say a single observed stream subsumes it — that is true of a Flow, but
 *       {@link CloudBackupManager} is CALLBACK-based, so two concurrent pulls
 *       still complete in network order rather than call order and a stale one
 *       would still overwrite a newer list. The guard is simply in its right
 *       home now instead of on the fragment.</li>
 *   <li><b>Search and grid stay on the fragment.</b> The search field is part of
 *       the borrowed activity toolbar and is already torn down with the view;
 *       the grid choice is persisted in prefs. Neither is fragment state that a
 *       rotation loses.</li>
 * </ul>
 */
@HiltViewModel
public class CloudBackupListViewModel extends ViewModel {

    /** Snapshot of the screen's data state, published as ONE value so the
     *  fragment can never render a torn combination (e.g. loading=false with the
     *  previous list still in place). */
    public static final class State {
        public final List<VaultEntry> entries;
        public final boolean loading;
        /** True when the last pull FAILED — render() shows the honest error
         *  empty-state instead of "no backups yet", which on-device read as
         *  "my uploads vanished". */
        public final boolean failed;

        State(List<VaultEntry> entries, boolean loading, boolean failed) {
            this.entries = entries;
            this.loading = loading;
            this.failed = failed;
        }
    }

    private final CloudBackupManager mCloudBackup;

    private final MutableLiveData<State> mState =
            new MutableLiveData<>(new State(Collections.emptyList(), true, false));
    private final MutableLiveData<CloudBackupManager.Status> mStatus = new MutableLiveData<>();
    private final MutableLiveData<Set<String>> mCloudOnly =
            new MutableLiveData<>(Collections.emptySet());
    private final MutableLiveData<Set<String>> mSelection =
            new MutableLiveData<>(Collections.emptySet());

    /** Live entries, mutated in place by the optimistic delete/restore paths and
     *  republished. Kept separate from the published State so a caller can never
     *  hold a reference that mutates under it. */
    private final List<VaultEntry> mEntries = new ArrayList<>();

    /** See the class doc — still required with a callback-based manager. */
    private int mLoadGen;

    /** Ids whose server delete is in flight. Ordering semantics (why a
     *  delete-SUCCESS does NOT clear an id) live in {@link PendingRemovals}. */
    private final PendingRemovals mPendingRemovals = new PendingRemovals();

    @Inject
    public CloudBackupListViewModel(CloudBackupManager cloudBackup) {
        this.mCloudBackup = cloudBackup;
    }

    public LiveData<State> getState() {
        return mState;
    }

    public LiveData<CloudBackupManager.Status> getStatus() {
        return mStatus;
    }

    public LiveData<Set<String>> getCloudOnly() {
        return mCloudOnly;
    }

    /**
     * Selected object ids. Held HERE rather than in the adapter so a rotation
     * keeps the selection — and so the adapter is a pure renderer that is handed
     * what to draw, which is what let {@code setActionMode}'s blanket
     * {@code notifyDataSetChanged} become a bounded range.
     */
    public LiveData<Set<String>> getSelection() {
        return mSelection;
    }

    public Set<String> selectionSnapshot() {
        Set<String> s = mSelection.getValue();
        return s == null ? Collections.emptySet() : s;
    }

    public List<VaultEntry> entriesSnapshot() {
        return new ArrayList<>(mEntries);
    }

    /** The selected entries themselves — the batch restore needs each one's
     *  wrapped DEK, chunk count and origin to build its work request. */
    public List<VaultEntry> selectedEntries() {
        Set<String> sel = selectionSnapshot();
        List<VaultEntry> out = new ArrayList<>();
        for (VaultEntry e : mEntries) {
            if (sel.contains(e.objectId)) {
                out.add(e);
            }
        }
        return out;
    }

    /** Total bytes of the current selection — what a delete frees, what a
     *  restore pulls down. */
    public long selectedBytes() {
        Set<String> sel = selectionSnapshot();
        long total = 0;
        for (VaultEntry e : mEntries) {
            if (sel.contains(e.objectId)) {
                total += e.size;
            }
        }
        return total;
    }

    public void toggleSelected(String objectId) {
        Set<String> next = new HashSet<>(selectionSnapshot());
        if (!next.remove(objectId)) {
            next.add(objectId);
        }
        mSelection.setValue(next);
    }

    /** Selects every currently-loaded entry, or clears when all are already
     *  selected. Returns true when the result is "all selected". */
    public boolean toggleSelectAll() {
        boolean selectAll = selectionSnapshot().size() < mEntries.size();
        Set<String> next = new HashSet<>();
        if (selectAll) {
            for (VaultEntry e : mEntries) {
                next.add(e.objectId);
            }
        }
        mSelection.setValue(next);
        return selectAll;
    }

    public void clearSelection() {
        if (!selectionSnapshot().isEmpty()) {
            mSelection.setValue(Collections.emptySet());
        }
    }

    // ---- loading ----

    /**
     * Pulls the manifest. Safe to call repeatedly; a slower earlier pull can
     * never overwrite a newer one.
     *
     * @param onSettled runs on BOTH outcomes, for view-only concerns the
     *                  ViewModel must not own (stopping the refresh spinner).
     * @param onError   runs on failure only, for the error snackbar.
     */
    public void load(Runnable onSettled, Runnable onError) {
        final int gen = ++mLoadGen;
        publish(true, false);
        mCloudBackup.loadEntries(entries -> {
            if (gen != mLoadGen) {
                return;
            }
            mEntries.clear();
            // Skip rows whose delete is still in flight (a pull can pre-date the
            // delete's OCC commit — re-adding one would flicker a ghost back) and
            // reconcile the guard set against this fresh pull.
            mEntries.addAll(mPendingRemovals.filterAndReconcile(entries));
            dropSelectionOfMissingRows();
            publish(false, false);
            onSettled.run();
            resolveCloudOnly();
        }, () -> {
            if (gen != mLoadGen) {
                return;
            }
            publish(false, true);
            onSettled.run();
            onError.run();
        });
    }

    /** Loads the quota for the header's context line. Never shows a stale number
     *  offline — the manager serves its cached status or nothing. */
    public void loadStatus() {
        mCloudBackup.loadStatus(mStatus::setValue);
    }

    /** Marks the rows whose file is no longer on the device, in ONE batch pass
     *  per load (never per bound row). Generation-guarded like the load itself so
     *  a slow lookup from a superseded pull can't stamp the current list. */
    private void resolveCloudOnly() {
        final int gen = mLoadGen;
        mCloudBackup.resolveCloudOnly(entriesSnapshot(), ids -> {
            if (gen == mLoadGen) {
                mCloudOnly.setValue(ids);
            }
        });
    }

    /** A fresh pull can drop rows another device deleted; a selection pointing at
     *  a row that no longer exists would silently no-op on the next action. */
    private void dropSelectionOfMissingRows() {
        Set<String> sel = selectionSnapshot();
        if (sel.isEmpty()) {
            return;
        }
        Set<String> live = new HashSet<>();
        for (VaultEntry e : mEntries) {
            live.add(e.objectId);
        }
        if (sel.retainAll(live)) {
            mSelection.setValue(new HashSet<>(sel));
        }
    }

    // ---- optimistic mutations (the fragment owns the network calls + undo UI) ----

    /** Optimistically drops a row and guards it against a racing pull. */
    public void removeOptimistic(VaultEntry entry) {
        mEntries.remove(entry);
        mPendingRemovals.add(entry.objectId);
        publishCurrent();
    }

    /** Re-inserts a row whose delete FAILED, at its original index when that is
     *  still valid. */
    public void restoreRow(VaultEntry entry, int index) {
        if (!mEntries.contains(entry)) {
            if (index >= 0 && index <= mEntries.size()) {
                mEntries.add(index, entry);
            } else {
                mEntries.add(entry);
            }
        }
        mPendingRemovals.clear(entry.objectId);
        publishCurrent();
    }

    public boolean contains(VaultEntry entry) {
        return mEntries.contains(entry);
    }

    public int indexOf(VaultEntry entry) {
        return mEntries.indexOf(entry);
    }

    @Nullable
    public VaultEntry findByObjectId(String objectId) {
        for (VaultEntry e : mEntries) {
            if (e.objectId.equals(objectId)) {
                return e;
            }
        }
        return null;
    }

    private void publish(boolean loading, boolean failed) {
        mState.setValue(new State(new ArrayList<>(mEntries), loading, failed));
    }

    private void publishCurrent() {
        State s = mState.getValue();
        publish(s != null && s.loading, s != null && s.failed);
    }
}
