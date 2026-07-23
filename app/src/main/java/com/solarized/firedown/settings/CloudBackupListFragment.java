package com.solarized.firedown.settings;

import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.text.format.Formatter;
import android.widget.TextView;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.SearchView;
import androidx.appcompat.widget.Toolbar;
import androidx.core.graphics.Insets;
import androidx.core.view.MenuProvider;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.Observer;
import androidx.navigation.NavBackStackEntry;
import androidx.navigation.NavController;
import androidx.navigation.fragment.NavHostFragment;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.work.Constraints;
import androidx.work.Data;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkInfo;
import androidx.work.WorkManager;

import com.google.android.material.appbar.AppBarLayout;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;
import com.solarized.firedown.ApplicationLifeCycleHandler;
import com.solarized.firedown.phone.CloudBackupStreamActivity;
import com.solarized.firedown.phone.SettingsActivity;
import com.solarized.firedown.R;
import com.solarized.firedown.sync.CloudBackupManager;
import com.solarized.firedown.sync.PendingRemovals;
import com.solarized.firedown.sync.StorageApiClient;
import com.solarized.firedown.sync.VaultBackupWorker;
import com.solarized.firedown.sync.VaultRestoreWorker;
import com.solarized.firedown.sync.VaultThumbnail;
import com.solarized.firedown.sync.model.VaultEntry;
import com.solarized.firedown.ui.EqualSpacingItemDecoration;
import com.solarized.firedown.ui.LCEERecyclerView;
import com.solarized.firedown.utils.NavigationUtils;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
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

    @Inject
    SharedPreferences mPrefs;

    /** Persisted grid/list choice for the Backups list (own key — independent of
     *  the Downloads list's grid pref). */
    private static final String PREF_GRID = "cloud_backup_grid";
    private GridLayoutManager mLayoutManager;
    /** Grid vs list for the committed file rows (transfer rows stay full-width). */
    private boolean mEnableGrid;

    /** The app's ONE registered ComponentCallbacks2 — the memory-trim signal is
     *  fanned out through it (see {@code TrimMemoryListener} there) instead of
     *  every screen registering its own callbacks on the app context. */
    @Inject
    ApplicationLifeCycleHandler mAppLifecycle;

    private NavController mNavController;
    private LCEERecyclerView mLcee;
    /** Text-only header: inventory (count · size) + quota/trust context. Hidden
     *  while there are no committed rows — the LCEE illustrations own the
     *  empty/loading/error states, a header over them would just restate them. */
    private View mHeader;
    private TextView mHeaderLine1;
    private TextView mHeaderLine2;
    /** Vertical offset of the fragment's inner appbar (the scroll-away header):
     *  0 = fully shown, negative = scrolled off — one of the two lift signals
     *  bridged to the ACTIVITY appbar (see onViewCreated). */
    private int mInnerAppbarOffset;
    /** Latest quota (for the header's context line); null until loaded/offline. */
    private CloudBackupManager.Status mStatusInfo;
    private RecyclerView mRecycler;
    private CloudBackupFileAdapter mAdapter;

    private final List<VaultEntry> mEntries = new ArrayList<>();
    private boolean mLoading = true;
    /** Bumped on every load() so a slower earlier network pull can't overwrite a
     *  newer one (two concurrent loads complete in network order, not call order). */
    private int mLoadGen;
    /** Object ids whose server delete is IN FLIGHT (optimistically removed from the
     *  UI, not yet confirmed gone). A load() that lands before the delete's OCC push
     *  commits would otherwise RESURRECT the row (it's still in the pulled manifest);
     *  load() filters these out. The ordering semantics (why delete-SUCCESS does NOT
     *  clear an id — only a fresh pull or a delete-FAILURE does) live in
     *  {@link PendingRemovals}, where they're unit-tested. */
    private final PendingRemovals mPendingRemovals = new PendingRemovals();
    /** True while any backup transfer is running OR enqueued (drives the
     *  finished→reload logic and the hero's active state). */
    private boolean mTransferActive;
    /** True only while one is actually RUNNING — picks the header prefix
     *  ("Backing up…" vs "Waiting to back up"), same honesty split as the
     *  home pill: an enqueued-only worker (constraints unmet / retry backoff)
     *  may be hours from transferring. */
    private boolean mTransferRunning;
    /** Work IDs whose SUCCESS has already triggered a manifest reload. Each
     *  finished backup pulls in its committed row IMMEDIATELY (while siblings
     *  keep uploading) — reloading only when the WHOLE batch went idle made every
     *  finished upload vanish until the last one completed. Add-returns-false
     *  keeps it to one reload per worker across the observer's repeat ticks;
     *  grows with the finished-transfer count, bounded by the fragment's life. */
    private final Set<String> mReloadedTransferIds = new HashSet<>();
    /** True while multi-select is active (drives the toolbar title/menu). */
    private boolean mSelectionMode;
    private Toolbar mToolbar;
    private OnBackPressedCallback mBackCallback;
    /** Live name filter from the toolbar SearchView ("" = show all). The full set
     *  stays in {@link #mEntries}; the adapter is submitted a filtered view. */
    private String mSearchQuery = "";

    /**
     * Drops the adapter's decoded-thumb cache under memory pressure. Registered
     * with {@link ApplicationLifeCycleHandler} (the app's one
     * {@code ComponentCallbacks2}; the which-levels-count policy lives there)
     * for exactly the VIEW lifetime (onViewCreated → onDestroyView). Outside
     * that window no registration is needed: leaving the screen makes the
     * adapter — and its cache — unreachable, and plain GC reclaims it.
     */
    private final ApplicationLifeCycleHandler.TrimMemoryListener mTrimListener = () -> {
        if (mAdapter != null) {
            mAdapter.trimThumbCache();
        }
    };

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
        mHeader = view.findViewById(R.id.cb_header);
        mHeaderLine1 = view.findViewById(R.id.cb_header_line1);
        mHeaderLine2 = view.findViewById(R.id.cb_header_line2);
        mLcee = view.findViewById(R.id.cb_lcee);
        mRecycler = mLcee.getRecyclerView();
        mAdapter = new CloudBackupFileAdapter(this);
        mRecycler.setAdapter(mAdapter);
        // List ↔ grid, persisted. One GridLayoutManager drives both: span 1 for
        // list, browser_grid_number for grid. In-progress TRANSFER rows always
        // span the full width (SpanSizeLookup) so an uploading file still reads
        // as a row even in grid mode. The EqualSpacingItemDecoration below is
        // span-aware (re-reads the live span), so the same decoration fits both.
        mEnableGrid = mPrefs.getBoolean(PREF_GRID, false);
        mLayoutManager = new GridLayoutManager(requireContext(), gridSpanCount(mEnableGrid));
        mLayoutManager.setSpanSizeLookup(new GridLayoutManager.SpanSizeLookup() {
            @Override
            public int getSpanSize(int position) {
                // In LIST mode a transfer row spans the (single) column; in GRID
                // mode it's a tile (span 1) among the committed tiles, like the
                // Downloads grid. Committed files are always one cell.
                if (mAdapter.isTransferPosition(position) && !mAdapter.isGrid()) {
                    return mLayoutManager.getSpanCount();
                }
                return 1;
            }
        });
        mRecycler.setLayoutManager(mLayoutManager);
        mAdapter.enableGrid(mEnableGrid);
        // Trim the adapter's decoded-thumb cache under memory pressure while
        // this screen exists (see mTrimListener); unregistered in onDestroyView.
        mAppLifecycle.addTrimListener(mTrimListener);
        // Same gutter as the Downloads list (and Bookmarks/History/Captured):
        // EqualSpacingItemDecoration at list_spacing.
        mRecycler.addItemDecoration(
                new EqualSpacingItemDecoration(requireContext(), R.dimen.list_spacing));
        // Empty state (LCEE) — the cloud illustration (restored from the
        // retired P2P-send header art; this screen IS the cloud, the Downloads
        // balloons read as a copy-paste here) + message.
        mLcee.setEmptyImageView(R.drawable.ill_cloud);
        mLcee.setEmptyText(R.string.cloud_backup_list_empty);

        // Same inset treatment as DownloadFragment: the list scrolls under the
        // nav bar, the last row clears it (recycler bottom padding,
        // clipToPadding=false), and the opaque navigation_scrim overlay covers
        // the gesture area so rows don't ghost through it.
        View navScrim = view.findViewById(R.id.navigation_scrim);
        ViewCompat.setOnApplyWindowInsetsListener(view, (v, windowInsets) -> {
            Insets insets = windowInsets.getInsets(
                    WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            v.setPadding(insets.left, 0, insets.right, 0);
            mRecycler.setPadding(mRecycler.getPaddingLeft(), mRecycler.getPaddingTop(),
                    mRecycler.getPaddingRight(), insets.bottom);
            if (navScrim != null) {
                navScrim.getLayoutParams().height = insets.bottom;
                navScrim.requestLayout();
            }
            return WindowInsetsCompat.CONSUMED;
        });

        // The ACTIVITY toolbar lifts on scroll here, like every other settings
        // screen. Elsewhere that's automatic (a preference screen's RecyclerView
        // nested-scrolls straight into the activity's CoordinatorLayout), but
        // this fragment's own inner CoordinatorLayout — needed for the
        // scroll-away header — is not a nested-scrolling CHILD, so scroll events
        // stop at it and the shared toolbar stayed flat while rows slid under
        // it. Bridge the lift state manually: lifted whenever the header has
        // scrolled off (inner appbar offset) OR the list itself has scrolled.
        AppBarLayout activityAppbar = requireActivity().findViewById(R.id.appbar_layout);
        AppBarLayout innerAppbar = view.findViewById(R.id.cb_appbar);
        if (activityAppbar != null) {
            activityAppbar.setLiftable(true);
            Runnable syncLift = () -> activityAppbar.setLifted(
                    mInnerAppbarOffset < 0 || mRecycler.canScrollVertically(-1));
            if (innerAppbar != null) {
                innerAppbar.addOnOffsetChangedListener((bar, offset) -> {
                    mInnerAppbarOffset = offset;
                    syncLift.run();
                });
            }
            mRecycler.addOnScrollListener(new RecyclerView.OnScrollListener() {
                @Override
                public void onScrolled(@NonNull RecyclerView rv, int dx, int dy) {
                    syncLift.run();
                }
            });
        }

        // The screen's toolbar drives the selection chrome (Downloads strategy).
        mToolbar = requireActivity().findViewById(R.id.toolbar);

        // Toolbar menu: the grid/list toggle while browsing, the delete action
        // while multi-selecting (the two states swap on invalidateOptionsMenu,
        // fired by enter/exitSelection and the toggle).
        requireActivity().addMenuProvider(new MenuProvider() {
            @Override
            public void onCreateMenu(@NonNull Menu menu, @NonNull MenuInflater inflater) {
                if (mSelectionMode) {
                    inflater.inflate(R.menu.menu_action, menu);
                    return;
                }
                inflater.inflate(R.menu.menu_cloud_backup, menu);
                MenuItem toggle = menu.findItem(R.id.action_view);
                if (toggle != null) {
                    toggle.setIcon(mEnableGrid
                            ? R.drawable.ic_view_list_24 : R.drawable.ic_grid_view_24);
                }
                setupSearch(menu.findItem(R.id.action_search));
            }

            @Override
            public boolean onMenuItemSelected(@NonNull MenuItem item) {
                if (mSelectionMode && item.getItemId() == R.id.action_delete) {
                    confirmDeleteSelected();
                    return true;
                }
                if (!mSelectionMode && item.getItemId() == R.id.action_view) {
                    toggleGrid();
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
        // Quota for the header's context line ("of X GB included" / GB-months).
        // Piggybacks the reconcile-heal; the header renders without it (trust
        // line only) until it arrives, and never shows a stale number offline.
        mCloudBackup.loadStatus(status -> {
            if (isAdded()) {
                mStatusInfo = status;
                updateHeader();
            }
        });
    }

    @Override
    public void onResume() {
        super.onResume();
        // Refresh on return: the manifest can change while away (a worker commits,
        // a delete elsewhere), and a load that FAILED (offline / saturated uplink
        // mid-upload) gets its retry here. Gen-guarded, and render() keeps the
        // current rows while the pull runs, so this never blanks a good list.
        load();
    }

    @Override
    public void onDestroyView() {
        // The lift state was driven manually — hand the ACTIVITY appbar back
        // flat, or the settings screen behind us keeps a stale shadow.
        AppBarLayout activityAppbar = requireActivity().findViewById(R.id.appbar_layout);
        if (activityAppbar != null) {
            activityAppbar.setLifted(false);
        }
        mAppLifecycle.removeTrimListener(mTrimListener);
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
                    boolean anyRunning = false;
                    boolean newlyFinished = false;
                    Set<String> seenNames = new HashSet<>();
                    if (infos != null) {
                        for (WorkInfo wi : infos) {
                            WorkInfo.State s = wi.getState();
                            // A backup that reached SUCCEEDED has already committed its
                            // manifest entry (the OCC push runs inside doWork, before
                            // the worker returns), so reload ONCE per finished worker to
                            // surface its committed row immediately — otherwise a
                            // finished upload's progress row is dropped here but its
                            // committed row isn't pulled until the whole batch goes idle
                            // (justFinished), so it disappears until the last one lands.
                            // A restore also succeeds under WORK_TAG but doesn't touch
                            // the manifest, so its reload is a harmless idempotent pull.
                            if (s == WorkInfo.State.SUCCEEDED
                                    && mReloadedTransferIds.add(wi.getId().toString())) {
                                newlyFinished = true;
                            }
                            boolean running = s == WorkInfo.State.RUNNING
                                    || s == WorkInfo.State.ENQUEUED;
                            // Terminally-FAILED backups render as an ERROR row —
                            // a background failure used to leave NO trace here
                            // (the failure snackbar only shows while the Downloads
                            // fragment is alive). Identity comes from the request
                            // tags (a finished worker's progress is cleared); ✕
                            // dismisses via pruneWork. Doesn't count as active.
                            boolean failed = s == WorkInfo.State.FAILED;
                            if (!running && !failed) {
                                continue;
                            }
                            if (running) {
                                active = true;
                            }
                            if (s == WorkInfo.State.RUNNING) {
                                anyRunning = true;
                            }
                            Data p = wi.getProgress();
                            String name = p.getString(VaultBackupWorker.KEY_NAME);
                            String mime = p.getString(VaultBackupWorker.KEY_MIME);
                            long done = p.getLong(VaultBackupWorker.KEY_PROGRESS_DONE, 0);
                            long total = p.getLong(VaultBackupWorker.KEY_PROGRESS_TOTAL, 0);
                            if (name == null) {
                                // No progress yet (ENQUEUED / just-started worker,
                                // pre-first-publish) — read the identity off the
                                // request TAGS the backup enqueue stamps, so the
                                // batch shows rows IMMEDIATELY (the snackbar's
                                // View lands here seconds after enqueue, which
                                // used to render a fully empty screen). A restore
                                // carries no identity tags, so it stays row-less
                                // (it already has its committed row).
                                name = tagValue(wi, VaultBackupWorker.TAG_NAME);
                                mime = tagValue(wi, VaultBackupWorker.TAG_MIME);
                                String size = tagValue(wi, VaultBackupWorker.TAG_SIZE);
                                if (size != null) {
                                    try {
                                        total = Long.parseLong(size);
                                    } catch (NumberFormatException ignored) {
                                        // display-only — 0 total renders as 0%
                                    }
                                }
                            }
                            // Still nameless (a restore) or already committed (a
                            // re-backup keeps its existing row) — no transfer row.
                            if (name == null || isCommitted(name)) {
                                continue;
                            }
                            // One row per file name — collapse any transient
                            // multi-worker state into a single transfer row.
                            if (!seenNames.add(name)) {
                                continue;
                            }
                            transfers.add(new CloudBackupFileAdapter.Transfer(
                                    wi.getId().toString(), name, mime, done, total, failed));
                        }
                    }
                    boolean justFinished = mTransferActive && !active;
                    mTransferActive = active;
                    mTransferRunning = anyRunning;
                    mAdapter.setTransfers(transfers);
                    render();
                    // Reload on EACH completion (newlyFinished), not only when the
                    // whole batch goes idle (justFinished) — the latter alone is what
                    // made every finished upload disappear until the last one landed.
                    // justFinished is kept as the backstop for a batch whose last
                    // worker FAILED/was CANCELLED (no SUCCEEDED tick to reload on).
                    if (newlyFinished || justFinished) {
                        load(); // pull in the newly-committed entry (or final resync)
                    }
                });
    }

    /** Reads a prefixed identity tag off a WorkInfo (null when absent — e.g. a
     *  restore worker, which carries only the shared WORK_TAG). */
    private static String tagValue(WorkInfo wi, String prefix) {
        for (String tag : wi.getTags()) {
            if (tag.startsWith(prefix)) {
                return tag.substring(prefix.length());
            }
        }
        return null;
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
            WorkManager wm = WorkManager.getInstance(requireContext().getApplicationContext());
            wm.cancelWorkById(UUID.fromString(workId));
            // Also DISMISS a terminally-failed row: cancel is a no-op on finished
            // work, and the FAILED record (which drives the error row) lingers
            // until pruned. pruneWork only removes FINISHED work, so an active
            // upload's cancel is unaffected.
            wm.pruneWork();
            snackbar(getString(R.string.cloud_backup_transfer_cancelled));
        } catch (IllegalArgumentException ignored) {
            // malformed id — nothing to cancel
        }
    }

    private void load() {
        final int gen = ++mLoadGen;
        mLoading = true;
        render();
        mCloudBackup.loadEntries(entries -> {
            // Ignore a stale pull whose result lost the race to a newer load().
            if (!isAdded() || gen != mLoadGen) {
                return;
            }
            mLoading = false;
            // A successful pull owns the empty state again ("no backups yet") —
            // a prior failed load may have swapped in the error art below.
            mLcee.setEmptyImageView(R.drawable.ill_cloud);
            mLcee.setEmptyText(R.string.cloud_backup_list_empty);
            mEntries.clear();
            // Skip rows whose delete is still in flight (the manifest pull can
            // pre-date the delete's OCC commit — re-adding one would flicker a ghost
            // back) and reconcile the guard set against this fresh pull. The full
            // ordering rationale lives in PendingRemovals.
            mEntries.addAll(mPendingRemovals.filterAndReconcile(entries));
            submitVisible();
            render();
            backfillThumbnails();
        }, () -> {
            if (!isAdded() || gen != mLoadGen) {
                return;
            }
            mLoading = false;
            // The pull FAILED. With rows on screen they stay (render keeps
            // content); with none, the old behaviour fell through to the
            // "No backups yet" illustration — a LIE that on-device read as
            // "my three uploads vanished" (the pull failed on the saturated
            // uplink right as the last one finished). Show an honest error
            // empty-state instead; onResume + the next transfer tick retry.
            mLcee.setEmptyImageView(R.drawable.ill_small_browser_error);
            mLcee.setEmptyText(R.string.cloud_backup_list_error);
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
            // Already backfilled on a prior load — the adapter now keeps resolved
            // thumbs across submit(), so don't re-run the DB lookup + decode (it
            // would also fire a needless row rebind / thumbnail flicker).
            if (mAdapter.resolvedThumb(entry.objectId) != null) {
                continue;
            }
            mCloudBackup.resolveLocalThumb(entry, thumb -> {
                if (isAdded() && thumb != null) {
                    mAdapter.setResolvedThumb(entry.objectId, thumb);
                }
            });
        }
    }

    /** Drives the LCEE state: content (any adapter row — committed entries AND
     *  transfer rows both count in getItemCount), the loading spinner (initial
     *  fetch only), or the empty illustration. Deliberately NOT gated on
     *  mTransferActive: an active worker that renders NO row (a restore, or a
     *  legacy backup enqueued before identity tags existed) used to hold the
     *  screen in the content state with a 0-item adapter — a completely BLANK
     *  page (seen on-device after deleting the last entry while such a worker
     *  lingered). mTransferActive still drives the finished→reload logic. */
    private void render() {
        boolean hasRows = mAdapter != null && mAdapter.getItemCount() > 0;
        if (hasRows) {
            mLcee.hideAll();          // show the list (rows carry the state)
        } else if (mLoading) {
            mLcee.showLoading();      // spinner on the first fetch only
        } else {
            mLcee.showEmpty();        // balloons + "nothing backed up yet"
        }
        updateHeader();
    }

    /** The text-only header over the list: line 1 = inventory ("12 files ·
     *  51 MB", led by "Backing up…" while a transfer runs), line 2 = quota
     *  context + trust ("of 11 GB included · encrypted end-to-end" unmetered,
     *  the GB-months balance metered, just the trust line when the quota is
     *  unknown/offline — never a stale number). Facts come from the LOADED
     *  entries (instant, no network), so it updates with every delete/commit;
     *  hidden without committed rows (the illustrations own those states). */
    private void updateHeader() {
        if (mHeader == null || !isAdded()) {
            return;
        }
        if (mEntries.isEmpty()) {
            mHeader.setVisibility(View.GONE);
            return;
        }
        long totalBytes = 0;
        for (VaultEntry e : mEntries) {
            totalBytes += e.size;
        }
        String line1 = getResources().getQuantityString(
                R.plurals.settings_cloud_backup_file_count, mEntries.size(), mEntries.size())
                + " · " + Formatter.formatShortFileSize(requireContext(), totalBytes);
        if (mTransferActive) {
            line1 = getString(mTransferRunning
                    ? R.string.home_cloud_backing_up
                    : R.string.home_cloud_waiting) + " · " + line1;
        }
        mHeaderLine1.setText(line1);
        String line2;
        StorageApiClient.Quota quota = mStatusInfo != null ? mStatusInfo.quota : null;
        // Metered context is TIME, never the raw GB-months ledger unit (the
        // old "5409.5 GB-months" here was one of the on-device confusion
        // reports). No projection (nothing backed up / effectively-never
        // runout) → just the trust line.
        String coverage = CloudStatusPreference.coverageLabel(requireContext(), quota);
        if (coverage != null) {
            line2 = coverage + " · " + getString(R.string.cloud_backup_header_encrypted);
        } else if (quota != null && quota.metered) {
            line2 = getString(R.string.cloud_backup_header_encrypted);
        } else if (quota != null && quota.bytesLimit > 0) {
            line2 = getString(R.string.cloud_backup_header_beta,
                    Formatter.formatShortFileSize(requireContext(), quota.bytesLimit));
        } else {
            line2 = getString(R.string.cloud_backup_header_encrypted);
        }
        mHeaderLine2.setText(line2);
        mHeader.setVisibility(View.VISIBLE);
    }


    /** Grid span for the given mode: 1 = list (single column), else the shared
     *  Captured/Downloads grid span. */
    private int gridSpanCount(boolean grid) {
        return grid ? getResources().getInteger(R.integer.browser_grid_number) : 1;
    }

    /** Flips list ↔ grid: persists the choice, re-spans the layout, rebinds the
     *  file rows, and swaps the toolbar icon. */
    private void toggleGrid() {
        mEnableGrid = !mEnableGrid;
        mPrefs.edit().putBoolean(PREF_GRID, mEnableGrid).apply();
        if (mLayoutManager != null) {
            mLayoutManager.setSpanCount(gridSpanCount(mEnableGrid));
        }
        mAdapter.enableGrid(mEnableGrid);
        requireActivity().invalidateOptionsMenu(); // swap the grid/list icon
    }

    /** Wires the toolbar SearchView to the in-memory name filter. Re-applies the
     *  live query when the menu is rebuilt (grid toggle / selection exit) so the
     *  filter survives an {@code invalidateOptionsMenu}. */
    private void setupSearch(MenuItem searchItem) {
        if (searchItem == null) {
            return;
        }
        SearchView search = (SearchView) searchItem.getActionView();
        if (search == null) {
            return;
        }
        search.setQueryHint(getString(R.string.search));
        search.setOnQueryTextListener(new SearchView.OnQueryTextListener() {
            @Override
            public boolean onQueryTextSubmit(String query) {
                return false; // filtering is live; nothing extra on submit
            }

            @Override
            public boolean onQueryTextChange(String newText) {
                mSearchQuery = newText != null ? newText.trim() : "";
                submitVisible();
                render();
                return true;
            }
        });
        // Restore an active query across a menu rebuild.
        if (!mSearchQuery.isEmpty()) {
            searchItem.expandActionView();
            search.setQuery(mSearchQuery, false);
            search.clearFocus();
        }
    }

    /** The entries to show: all of {@link #mEntries}, or those whose name matches
     *  the live search query (case-insensitive substring). */
    private List<VaultEntry> visibleEntries() {
        if (mSearchQuery.isEmpty()) {
            return new ArrayList<>(mEntries);
        }
        String needle = mSearchQuery.toLowerCase(Locale.getDefault());
        List<VaultEntry> out = new ArrayList<>();
        for (VaultEntry e : mEntries) {
            if (e.name != null && e.name.toLowerCase(Locale.getDefault()).contains(needle)) {
                out.add(e);
            }
        }
        return out;
    }

    /** Submits the filtered view to the adapter (the single content-update path,
     *  so search + optimistic edits + reloads all agree). */
    private void submitVisible() {
        mAdapter.submit(visibleEntries());
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
        // A pre-preview entry (entry.thumb == null) may still have a
        // display-backfilled thumbnail in the adapter — hand THAT to the sheet,
        // re-encoded in the manifest-thumb shape, so the sheet header matches
        // the row instead of degrading to the mime glyph (on-device report).
        String thumb = entry.thumb;
        if (thumb == null) {
            Bitmap resolved = mAdapter != null ? mAdapter.resolvedThumb(entry.objectId) : null;
            if (resolved != null) {
                thumb = VaultThumbnail.encode(resolved);
            }
        }
        args.putString(CloudBackupItemSheetDialogFragment.ARG_THUMB, thumb);
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
        FragmentActivity activity = getActivity();
        if (mToolbar != null) {
            mToolbar.setTitle(R.string.cloud_backup_files_title);
            // Restore the activity's Up behaviour THROUGH the activity — never a
            // fragment-bound lambda. The shared toolbar outlives this fragment
            // (onDestroyView routes here), so a lambda touching mNavController /
            // requireActivity() stayed installed after detach and crashed the
            // next root Up click with "Fragment … not attached" (field report).
            if (activity instanceof SettingsActivity) {
                ((SettingsActivity) activity).restoreToolbarUp();
            }
        }
        if (activity != null) {
            activity.invalidateOptionsMenu();
        }
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
        for (VaultEntry e : targets) {
            mEntries.remove(e);
            mPendingRemovals.add(e.objectId); // a racing load() must not resurrect them
        }
        submitVisible();
        render();
        snackbar(getString(R.string.cloud_backup_remove_done));
        // ONE batched delete — a single manifest mutation removes them all, so N
        // deletes don't fire N concurrent OCC mutations that contend on the
        // manifest version (which spuriously exhausted retries on a big batch).
        mCloudBackup.deleteEntries(targets, ok -> {
            if (!isAdded() || ok) {
                // Success (or view gone): leave the ids guarded; a subsequent load()
                // reconciles the guard set (see load()). Must NOT rely on load() to
                // RESYNC on failure — offline (the common failure) it also fails and
                // would leave the rows wrongly pruned.
                return;
            }
            // Failed — the entries are still on the server. Restore them ADDITIVELY
            // (re-add only those actually missing) rather than clobbering mEntries
            // with a stale snapshot, so a concurrent load()/finished-transfer that
            // changed the list meanwhile isn't lost. Stop guarding them.
            boolean changed = false;
            for (VaultEntry e : targets) {
                mPendingRemovals.clear(e.objectId);
                if (findEntry(e.objectId) == null) {
                    mEntries.add(e);
                    changed = true;
                }
            }
            if (changed) {
                submitVisible();
            }
            render();
            snackbar(getString(R.string.cloud_backup_remove_failed));
        });
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
            } else if (action == CloudBackupItemSheetDialogFragment.ACTION_OPEN) {
                openStream(target);
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
        int pos = mEntries.indexOf(entry);
        mEntries.remove(entry);
        mPendingRemovals.add(entry.objectId); // a racing load() must not resurrect it
        submitVisible();
        render();
        snackbar(getString(R.string.cloud_backup_remove_done));
        mCloudBackup.deleteEntry(entry, ok -> {
            if (!isAdded() || ok) {
                // Success (or view gone): leave the id guarded; a subsequent load()
                // reconciles it away once a fresh pull confirms it's gone. Clearing
                // it HERE would let a load() whose pull pre-dated this delete run
                // afterward and resurrect the row (the reverse-order race).
                return;
            }
            // Failed — the entry is still on the server; stop guarding + put it back
            // at its original index (submitVisible re-diffs the filtered view).
            mPendingRemovals.clear(entry.objectId);
            int p = pos < 0 ? mEntries.size() : Math.min(pos, mEntries.size());
            mEntries.add(p, entry);
            submitVisible();
            render();
            snackbar(getString(R.string.cloud_backup_remove_failed));
        });
    }

    /**
     * Restore is decided UP-FRONT, in the background, between "already in
     * Downloads" and a real restore. Two things this fixes:
     * <ul>
     *   <li>No enqueue-then-immediately-"already present": {@link
     *       VaultRestoreWorker} does the same name+size+exists check and no-ops,
     *       so doing it here avoids flashing "Restore started" then "Already in
     *       your downloads" for a file that's already local.</li>
     *   <li>Snackbar timing: the item sheet's result is delivered SYNCHRONOUSLY
     *       while the sheet is still on screen, so a snackbar shown straight from
     *       the observer flashed behind the closing sheet. The check's background
     *       round-trip defers the snackbar to AFTER the sheet has dismissed.</li>
     * </ul>
     * The worker keeps its own already-present check as the backstop for the
     * race where the file appears between this check and the worker running.
     */
    private void restore(VaultEntry entry) {
        mCloudBackup.isAlreadyDownloaded(entry, present -> {
            if (!isAdded()) {
                return;
            }
            if (present) {
                snackbar(getString(R.string.cloud_restore_already_present));
            } else {
                startRestore(entry);
            }
        });
    }

    /**
     * Streams a cloud-only media file (no local copy) in-app: launches
     * {@link CloudBackupStreamActivity}, which decrypts the object's chunks on
     * demand into a player/viewer — no restore-to-disk needed. Only reached for a
     * streamable type (the item sheet only offers Open for video/audio/image
     * without a local copy; a local copy opens directly from the sheet).
     */
    private void openStream(VaultEntry entry) {
        startActivity(CloudBackupStreamActivity.newIntent(requireContext(),
                entry.objectId, entry.wrappedDek, entry.name, entry.mime,
                entry.size, entry.chunkCount));
    }

    private void startRestore(VaultEntry entry) {
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
