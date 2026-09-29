package com.solarized.firedown.phone.fragments;

import android.animation.ValueAnimator;
import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.format.Formatter;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import com.solarized.firedown.R;
import com.solarized.firedown.data.di.Qualifiers;
import com.solarized.firedown.data.entity.DownloadEntity;
import com.solarized.firedown.data.repository.DownloadDataRepository;
import com.solarized.firedown.data.repository.TaskRepository;
import com.solarized.firedown.sync.CloudBackupManager;
import com.solarized.firedown.ui.EqualSpacingItemDecoration;
import com.solarized.firedown.ui.adapters.StorageReviewAdapter;
import com.solarized.firedown.utils.NavigationUtils;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executor;

import javax.inject.Inject;

import dagger.hilt.android.AndroidEntryPoint;

/**
 * Storage → Review storage: the triage list.
 *
 * <p>Every comparable storage screen has one — Signal's "Review storage",
 * WhatsApp's "Larger than 5 MB", Files by Google's "Large files", iOS's
 * "Review Large Attachments" — and they share a shape: LARGEST FIRST,
 * selection is the resting state (checkboxes from the first frame), a
 * running "N files · 1.2 GB" total, and delete as the one primary verb. None
 * of them re-sort the main list. This screen used to do exactly that (persist
 * SORT_SIZE as the user's Downloads sort preference and pop back), which read
 * as "the button does nothing" — and silently rewrote a setting the button
 * never mentioned.
 *
 * <p>What it lists: finished, non-vault downloads whose file is actually on
 * disk (a missing or foreign-owned file frees nothing — same rule as the
 * "Free up space" scan), sorted by size descending. Rows already in the cloud
 * backup carry the inline cloud mark, so "safe to delete" is visible at the
 * decision point; the confirm dialog counts them too.
 *
 * <p>Delete goes through the normal download delete
 * ({@link TaskRepository#requestDelete} → the download service), chunked so a
 * big selection can't overflow the intent's Binder transaction, and the rows
 * leave the list at once (the service removes the row + file; a re-scan on
 * resume reconciles anything that failed).
 *
 * <p>The legend rows on the Storage screen keep their own door (→ the
 * Downloads list filtered by type); only the review button changed.
 */
@AndroidEntryPoint
public class StorageReviewFragment extends BaseFocusFragment {

    @Inject
    DownloadDataRepository mRepository;

    @Inject
    CloudBackupManager mCloudBackup;

    @Inject
    TaskRepository mTaskRepository;

    @Inject
    @Qualifiers.HeavyIO
    Executor mHeavyExecutor;

    /** Entities per delete intent — keeps each Binder transaction small. */
    private static final int DELETE_CHUNK = 50;

    private final Handler mMainHandler = new Handler(Looper.getMainLooper());

    /** Orders scans: a stale result (an older scan finishing after a newer
     *  one, or after the view died) is dropped. */
    private int mScanGen;

    private StorageReviewAdapter mAdapter;
    private View mBar;
    /** Bottom system-bar inset, kept so the list padding can flip with the bar. */
    private int mBottomInset;
    private MaterialButton mDeleteButton;
    private MenuItem mSelectAll;
    private final List<DownloadEntity> mRows = new ArrayList<>();
    private long mTotalBytes;

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_storage_review, container, false);
        mToolbar = view.findViewById(R.id.toolbar);
        mLCEERecyclerView = view.findViewById(R.id.lcee_recycler_view);
        // Handing the recycler to the base class gets the Downloads inset
        // treatment for free: bottom padding = the navigation-bar inset (the
        // LCEE recycler is clipToPadding=false) and the navigation_scrim
        // sized to cover that area.
        mRecyclerView = mLCEERecyclerView.getRecyclerView();
        mBar = view.findViewById(R.id.review_bar);
        mDeleteButton = view.findViewById(R.id.review_delete);
        return view;
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        mToolbar.setNavigationOnClickListener(v ->
                NavigationUtils.popBackStackSafe(mNavController, R.id.storage_review_list));
        mToolbar.inflateMenu(R.menu.menu_storage_review);
        mSelectAll = mToolbar.getMenu().findItem(R.id.action_select_all);
        mToolbar.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == R.id.action_select_all && mAdapter != null) {
                mAdapter.toggleSelectAll();
                return true;
            }
            return false;
        });

        mAdapter = new StorageReviewAdapter(requireContext(), this::onSelectionChanged);
        RecyclerView recycler = mRecyclerView;
        recycler.setLayoutManager(new LinearLayoutManager(requireContext()));
        recycler.addItemDecoration(
                new EqualSpacingItemDecoration(requireContext(), R.dimen.list_spacing));
        recycler.setAdapter(mAdapter);
        mLCEERecyclerView.setEmptyImageView(R.drawable.ill_small_folder);
        mLCEERecyclerView.setEmptyText(R.string.storage_review_empty);
        mLCEERecyclerView.showLoading();

        mDeleteButton.setOnClickListener(v -> confirmDelete());

        // The list's inset listener replaces the base class's for one reason:
        // the base returns CONSUMED, and the recycler precedes the bar in the
        // column, so the bar would never see the insets. Same padding, insets
        // passed on. The bottom padding is dropped while the bar is up — the
        // bar then owns the bottom edge and the inset-sized padding would be
        // dead space above it (applyListBottomPadding).
        ViewCompat.setOnApplyWindowInsetsListener(recycler, (v, windowInsets) -> {
            Insets insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars()
                    | WindowInsetsCompat.Type.displayCutout());
            mBottomInset = insets.bottom;
            v.setPadding(insets.left, 0, insets.right, 0);
            applyListBottomPadding();
            return windowInsets;
        });

        // The bar sits at the bottom edge of an edge-to-edge window; grow its
        // bottom padding by the navigation bar so the button clears it. Its
        // ground then covers the gesture area itself, which is why showBar
        // hides the navigation_scrim while the bar is visible — the scrim's
        // colorBackground would otherwise stack over the bar's
        // colorSurfaceContainer.
        View bar = mBar;
        int basePadding = bar.getPaddingBottom();
        ViewCompat.setOnApplyWindowInsetsListener(bar, (v, windowInsets) -> {
            Insets bars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(v.getPaddingLeft(), v.getPaddingTop(), v.getPaddingRight(),
                    basePadding + bars.bottom);
            return windowInsets;
        });

        onSelectionChanged(new HashSet<>());
    }

    @Override
    public void onResume() {
        super.onResume();
        // Resume, not view creation only: a file deleted or backed up while
        // away changes the rows and the marks.
        scan();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        mScanGen++;
        mAdapter = null;
        mBar = null;
        mDeleteButton = null;
        mSelectAll = null;
    }

    // ── Data ────────────────────────────────────────────────────────────

    private void scan() {
        final int gen = ++mScanGen;
        mHeavyExecutor.execute(() -> {
            List<DownloadEntity> rows = new ArrayList<>();
            for (DownloadEntity e : mRepository.getRegularFinishedSync()) {
                String path = e.getFilePath();
                if (path == null) {
                    continue;
                }
                File file = new File(path);
                if (file.isFile() && file.length() > 0) {
                    rows.add(e);
                }
            }
            rows.sort((a, b) -> Long.compare(b.getFileSize(), a.getFileSize()));
            mMainHandler.post(() -> {
                if (gen == mScanGen) {
                    show(rows);
                }
            });
        });
        if (mCloudBackup.isSetUp()) {
            // Cached-first (the Storage card's rule): the last successful
            // pull paints the marks with the rows; the fresh pull updates.
            Set<String> cached = mCloudBackup.cachedBackedUpKeys();
            if (cached != null && mAdapter != null) {
                mAdapter.setBackedUpKeys(cached);
            }
            mCloudBackup.loadBackedUpKeys(keys -> {
                if (gen == mScanGen && mAdapter != null) {
                    mAdapter.setBackedUpKeys(keys);
                }
            });
        }
    }

    private void show(List<DownloadEntity> rows) {
        if (mAdapter == null) {
            return;
        }
        mRows.clear();
        mRows.addAll(rows);
        mTotalBytes = 0;
        Set<Integer> liveIds = new HashSet<>();
        for (DownloadEntity e : rows) {
            mTotalBytes += Math.max(0, e.getFileSize());
            liveIds.add(e.getId());
        }
        mAdapter.submitList(new ArrayList<>(rows));
        mAdapter.retainSelection(liveIds);
        if (rows.isEmpty()) {
            mLCEERecyclerView.showEmpty();
        } else {
            mLCEERecyclerView.hideAll();
        }
        onSelectionChanged(mAdapter.getSelectedIds());
    }

    // ── Selection ───────────────────────────────────────────────────────

    private void onSelectionChanged(Set<Integer> selected) {
        if (mDeleteButton == null) {
            return;
        }
        Context context = requireContext();
        long bytes = 0;
        for (DownloadEntity e : mRows) {
            if (selected.contains(e.getId())) {
                bytes += Math.max(0, e.getFileSize());
            }
        }
        int n = selected.size();
        String size = Formatter.formatShortFileSize(context, bytes);
        if (n == 0) {
            showBar(false);
            int total = mRows.size();
            mToolbar.setSubtitle(total == 0 ? null
                    : getResources().getQuantityString(
                            R.plurals.settings_cloud_backup_file_count, total, total)
                    + " · " + Formatter.formatShortFileSize(context, mTotalBytes));
        } else {
            mDeleteButton.setText(getResources().getQuantityString(
                    R.plurals.storage_review_delete_button, n, n, size));
            showBar(true);
            mToolbar.setSubtitle(getString(R.string.action_mode_selected, n) + " · " + size);
        }
        if (mSelectAll != null) {
            boolean all = !mRows.isEmpty() && n == mRows.size();
            mSelectAll.setTitle(all ? R.string.select_all_not : R.string.select_all);
            mSelectAll.setVisible(!mRows.isEmpty());
        }
    }

    /**
     * The bottom bar exists only while something is ticked (Files by Google's
     * shape — it slides in with the first selection). A docked, disabled
     * "Delete" spent ~70dp saying nothing; the count + size on the label is
     * the whole point of the bar, and there is none to state at zero.
     */
    private void showBar(boolean show) {
        if (mBar == null) {
            return;
        }
        boolean shown = mBar.getVisibility() == View.VISIBLE;
        if (show == shown) {
            return;
        }
        // The bar and the navigation_scrim take turns owning the bottom edge
        // (see onViewCreated): scrim hidden + no list bottom padding while the
        // bar is up, scrim back + inset padding once it leaves.
        if (mNavScrim != null) {
            mNavScrim.setVisibility(show ? View.INVISIBLE : View.VISIBLE);
        }
        if (!ValueAnimator.areAnimatorsEnabled()) {
            mBar.setVisibility(show ? View.VISIBLE : View.GONE);
            applyListBottomPadding();
            return;
        }
        mBar.animate().cancel();
        if (show) {
            mBar.setVisibility(View.VISIBLE);
            applyListBottomPadding();
            mBar.setTranslationY(mBar.getHeight() > 0 ? mBar.getHeight() : mBar.getMeasuredHeight());
            mBar.setAlpha(0f);
            mBar.animate().translationY(0f).alpha(1f).setDuration(200).start();
        } else {
            mBar.animate().translationY(mBar.getHeight()).alpha(0f).setDuration(150)
                    .withEndAction(() -> {
                        if (mBar != null) {
                            mBar.setVisibility(View.GONE);
                            mBar.setTranslationY(0f);
                            mBar.setAlpha(1f);
                            applyListBottomPadding();
                        }
                    }).start();
        }
    }

    // ── Delete ──────────────────────────────────────────────────────────

    /**
     * The list's bottom padding is the navigation-bar inset while the list
     * meets the window edge (rows scroll under the bar, the scrim covers the
     * gesture area), and zero while the delete bar is up and owns that edge.
     */
    private void applyListBottomPadding() {
        RecyclerView list = mRecyclerView;
        if (list == null) {
            return;
        }
        boolean barUp = mBar != null && mBar.getVisibility() == View.VISIBLE;
        list.setPadding(list.getPaddingLeft(), list.getPaddingTop(),
                list.getPaddingRight(), barUp ? 0 : mBottomInset);
    }

    private void confirmDelete() {
        if (mAdapter == null) {
            return;
        }
        Set<Integer> selected = mAdapter.getSelectedIds();
        if (selected.isEmpty()) {
            return;
        }
        final ArrayList<DownloadEntity> targets = new ArrayList<>();
        long bytes = 0;
        int backed = 0;
        for (DownloadEntity e : mRows) {
            if (selected.contains(e.getId())) {
                targets.add(e);
                bytes += Math.max(0, e.getFileSize());
                if (mAdapter.isBackedUp(e)) {
                    backed++;
                }
            }
        }
        int n = targets.size();
        // The loop accumulated `bytes`, so it is not effectively final; the
        // lambda below needs a frozen copy.
        final long freed = bytes;
        Context context = requireContext();
        String size = Formatter.formatShortFileSize(context, freed);
        StringBuilder message = new StringBuilder(
                getString(R.string.storage_review_confirm_message, size));
        if (backed > 0) {
            // The one reassurance worth a sentence: a copy survives in the
            // cloud backup. Counted, not implied — a mixed selection must not
            // read as if every file were safe.
            message.append(' ').append(getResources().getQuantityString(
                    R.plurals.storage_review_confirm_backed, backed, backed));
        }
        new MaterialAlertDialogBuilder(context)
                .setTitle(getResources().getQuantityString(
                        R.plurals.storage_review_confirm_title, n, n))
                .setMessage(message)
                .setPositiveButton(R.string.delete, (d, w) -> performDelete(targets, freed))
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void performDelete(ArrayList<DownloadEntity> targets, long bytes) {
        final Context app = requireContext().getApplicationContext();
        for (int i = 0; i < targets.size(); i += DELETE_CHUNK) {
            ArrayList<DownloadEntity> chunk = new ArrayList<>(
                    targets.subList(i, Math.min(i + DELETE_CHUNK, targets.size())));
            mTaskRepository.requestDelete(app, chunk);
        }
        // Optimistic: the rows leave now; the service removes row + file, and
        // the resume re-scan reconciles anything it could not.
        Set<Integer> gone = new HashSet<>();
        for (DownloadEntity e : targets) {
            gone.add(e.getId());
        }
        List<DownloadEntity> remaining = new ArrayList<>();
        for (DownloadEntity e : mRows) {
            if (!gone.contains(e.getId())) {
                remaining.add(e);
            }
        }
        if (mAdapter != null) {
            mAdapter.clearSelection();
        }
        show(remaining);
        View root = getView();
        if (root != null) {
            makeSnackbar(root, getString(R.string.storage_review_done,
                    Formatter.formatShortFileSize(app, bytes)), false).show();
        }
    }
}
