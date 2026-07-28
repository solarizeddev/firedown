package com.solarized.firedown.settings;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.text.format.Formatter;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
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
import androidx.appcompat.widget.Toolbar;
import androidx.core.graphics.Insets;
import androidx.core.content.ContextCompat;
import androidx.core.view.MenuProvider;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.Observer;
import androidx.lifecycle.ViewModelProvider;
import androidx.navigation.NavBackStackEntry;
import androidx.navigation.NavController;
import androidx.navigation.fragment.NavHostFragment;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;
import androidx.work.Constraints;
import androidx.work.Data;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkInfo;
import androidx.work.WorkManager;

import com.google.android.material.color.MaterialColors;
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
    /** Pull-to-refresh around the list. Present because this screen is
     *  NETWORK-backed — see the layout comment. */
    private SwipeRefreshLayout mSwipeRefresh;
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
    /** Screen state now lives in the ViewModel, so a rotation keeps the manifest
     *  (no network re-pull), keeps the selection, and keeps the load/error flags.
     *  See CloudBackupListViewModel for what deliberately did NOT move. */
    private CloudBackupListViewModel mViewModel;
    /** Latest quota (for the header's context line); null until loaded/offline. */
    private CloudBackupManager.Status mStatusInfo;
    private RecyclerView mRecycler;
    private CloudBackupFileAdapter mAdapter;

    /** Last rendered snapshot of the ViewModel's entries — read by the header,
     *  the search filter and the item paths. Replaced wholesale on each state
     *  emission; never mutated here (the VM owns the list). */
    private List<VaultEntry> mEntries = new ArrayList<>();
    private boolean mLoading = true;
    /** True when the last manifest pull FAILED (network/transient). render()
     *  reads it to show the honest error empty-state instead of "no backups
     *  yet"; reset on the next successful load. */
    private boolean mLoadFailed;

    /** Object ids whose server delete is IN FLIGHT (optimistically removed from the
     *  UI, not yet confirmed gone). A load() that lands before the delete's OCC push
     *  commits would otherwise RESURRECT the row (it's still in the pulled manifest);
     *  load() filters these out. The ordering semantics (why delete-SUCCESS does NOT
     *  clear an id — only a fresh pull or a delete-FAILURE does) live in
     *  {@link PendingRemovals}, where they're unit-tested. */

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
    /** Work id of the most recent single-file restore — the only consumer is
     *  {@link #startRestore}, which observes just that one request's outcome.
     *  The batch path never reads it (it reports a count up-front instead of
     *  per-file results). */
    private UUID mLastRestoreId;
    /** True while multi-select is active (drives the toolbar title/menu). */
    private boolean mSelectionMode;
    private Toolbar mToolbar;
    private OnBackPressedCallback mBackCallback;
    /** Live name filter from the toolbar search field ("" = show all). The full set
     *  stays in {@link #mEntries}; the adapter is submitted a filtered view. */
    private String mSearchQuery = "";
    /** The full-width in-toolbar search field (toolbar_search_field), added to the
     *  BORROWED activity toolbar so it matches the Downloads bar; removed in
     *  onDestroyView since the toolbar outlives this fragment's view. */
    private View mSearchBar;
    private EditText mSearchEdit;
    private CharSequence mTitleBeforeSearch;

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
        mViewModel = new ViewModelProvider(this).get(CloudBackupListViewModel.class);
        mHeader = view.findViewById(R.id.cb_header);
        mHeaderLine1 = view.findViewById(R.id.cb_header_line1);
        mHeaderLine2 = view.findViewById(R.id.cb_header_line2);
        mLcee = view.findViewById(R.id.cb_lcee);
        mRecycler = mLcee.getRecyclerView();
        mSwipeRefresh = view.findViewById(R.id.cb_swipe);
        mSwipeRefresh.setOnRefreshListener(this::load);
        // Brand the spinner. @color/progress_indicator, NOT colorPrimary: the
        // arc has to clear 3:1 against its own circle (WCAG 1.4.11) and the
        // brand coral #ff716c manages only 2.18:1 on a light circle — the same
        // maths that put every other determinate indicator in this app on this
        // resource (it IS the coral, #ff716c in dark; light theme gets the
        // deeper #C24941, which reads 3.95:1). The circle follows the theme so
        // the arc keeps that contrast in both.
        mSwipeRefresh.setColorSchemeColors(
                ContextCompat.getColor(requireContext(), R.color.progress_indicator));
        mSwipeRefresh.setProgressBackgroundColorSchemeColor(MaterialColors.getColor(
                mSwipeRefresh, com.google.android.material.R.attr.colorSurfaceContainerHigh));
        // The scrollable view is the RecyclerView INSIDE the LCEE container, not
        // the SwipeRefreshLayout's direct child — without this the default
        // callback would let a downward drag anywhere in the list trigger a
        // refresh instead of scrolling.
        mSwipeRefresh.setOnChildScrollUpCallback((parent, child) ->
                mRecycler != null && mRecycler.canScrollVertically(-1));
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
        // balloons read as a copy-paste here) + message. render()/applyEmptyState()
        // own the actual choice (default vs search-empty vs error); this is the
        // pre-load default so the view is configured before any showEmpty().
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
        // Add the full-width in-toolbar search field (same bar as Downloads),
        // hidden until the search action is tapped.
        setupSearchBar();

        // Toolbar menu: the grid/list toggle while browsing, the delete action
        // while multi-selecting (the two states swap on invalidateOptionsMenu,
        // fired by enter/exitSelection and the toggle).
        requireActivity().addMenuProvider(new MenuProvider() {
            @Override
            public void onCreateMenu(@NonNull Menu menu, @NonNull MenuInflater inflater) {
                if (mSelectionMode) {
                    // This screen's OWN selection menu, not the shared
                    // menu_action.xml: it adds select-all and batch restore
                    // beside delete (see menu_cloud_backup_action.xml).
                    inflater.inflate(R.menu.menu_cloud_backup_action, menu);
                    return;
                }
                inflater.inflate(R.menu.menu_cloud_backup, menu);
                if (isSearchActive()) {
                    // The search field owns the toolbar row — hide every icon
                    // (openSearchBar/closeSearchBar invalidate the menu to re-run
                    // this). Mirrors the Vault list's search behaviour.
                    for (int i = 0; i < menu.size(); i++) {
                        menu.getItem(i).setVisible(false);
                    }
                    return;
                }
                MenuItem toggle = menu.findItem(R.id.action_view);
                if (toggle != null) {
                    toggle.setIcon(mEnableGrid
                            ? R.drawable.ic_view_list_24 : R.drawable.ic_grid_view_24);
                }
            }

            @Override
            public boolean onMenuItemSelected(@NonNull MenuItem item) {
                if (mSelectionMode && item.getItemId() == R.id.action_delete) {
                    confirmDeleteSelected();
                    return true;
                }
                if (mSelectionMode && item.getItemId() == R.id.action_select_all) {
                    mViewModel.toggleSelectAll();
                    return true;
                }
                if (mSelectionMode && item.getItemId() == R.id.action_restore) {
                    restoreSelected();
                    return true;
                }
                if (!mSelectionMode && item.getItemId() == R.id.action_search) {
                    openSearchBar();
                    return true;
                }
                if (!mSelectionMode && item.getItemId() == R.id.action_view) {
                    toggleGrid();
                    return true;
                }
                if (!mSelectionMode && item.getItemId() == R.id.action_buy_credit) {
                    NavigationUtils.navigateSafe(
                            mNavController, R.id.action_cloud_backup_files_to_buy);
                    return true;
                }
                if (!mSelectionMode && item.getItemId() == R.id.action_cloud_settings) {
                    NavigationUtils.navigateSafe(
                            mNavController, R.id.action_cloud_backup_files_to_sync);
                    return true;
                }
                return false;
            }
        }, getViewLifecycleOwner());

        // System Back closes the search field first, then exits selection
        // (enabled whenever either is active).
        mBackCallback = new OnBackPressedCallback(false) {
            @Override
            public void handleOnBackPressed() {
                if (isSearchActive()) {
                    closeSearchBar();
                } else {
                    exitSelection();
                }
            }
        };
        requireActivity().getOnBackPressedDispatcher()
                .addCallback(getViewLifecycleOwner(), mBackCallback);

        observeSheetResult();
        observeTransfers();
        observeViewModel();
        // On a fresh ViewModel only: a rotation keeps the manifest, so re-pulling
        // here would put the network back in the rotation path — the whole point
        // of moving this state off the fragment.
        CloudBackupListViewModel.State state = mViewModel.getState().getValue();
        if (state == null || (state.entries.isEmpty() && !state.failed)) {
            load();
            mViewModel.loadStatus();
        }
    }

    /** Binds the ViewModel's streams to the views. Every emission is a complete
     *  snapshot, so the fragment never renders a torn combination. */
    private void observeViewModel() {
        mViewModel.getState().observe(getViewLifecycleOwner(), state -> {
            mEntries = state.entries;
            mLoading = state.loading;
            mLoadFailed = state.failed;
            submitVisible();
            render();
            updateHeader();
            backfillThumbnails();
        });
        mViewModel.getStatus().observe(getViewLifecycleOwner(), status -> {
            mStatusInfo = status;
            updateHeader();
        });
        mViewModel.getCloudOnly().observe(getViewLifecycleOwner(), mAdapter::setCloudOnly);
        mViewModel.getSelection().observe(getViewLifecycleOwner(), selection -> {
            mAdapter.setSelection(selection);
            refreshSelection();
        });
    }

    @Override
    public void onConfigurationChanged(@NonNull Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        // SettingsActivity declares configChanges="…orientation…", so a rotation
        // does NOT recreate the fragment — recompute the grid span from the new
        // orientation's resources (portrait 2 → landscape 4), the same as the
        // Downloads grid does. List mode stays a single column. gridSpanCount()
        // reads getResources() fresh, so it already reflects newConfig here.
        if (mLayoutManager != null) {
            mLayoutManager.setSpanCount(gridSpanCount(mEnableGrid));
        }
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
        // Restore the toolbar (title + Up behaviour) if we leave mid-search/selection,
        // then detach the search field — the toolbar is the ACTIVITY's and outlives
        // this fragment's view, so a left-behind bar would stack on re-entry.
        closeSearchBar();
        exitSelection();
        if (mToolbar != null && mSearchBar != null) {
            mToolbar.removeView(mSearchBar);
        }
        mSearchBar = null;
        mSearchEdit = null;
        mSearchQuery = "";
        // Drop the view refs the async callbacks touch (load()'s stopRefreshing,
        // the cloud-only resolve). Both are already isAdded()-guarded, but the
        // fragment instance outlives its view here.
        mSwipeRefresh = null;
        mRecycler = null;
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
                            String reason = failed
                                    ? failureText(wi.getOutputData().getString(
                                            VaultBackupWorker.KEY_ERROR_SLUG), total)
                                    : null;
                            transfers.add(new CloudBackupFileAdapter.Transfer(
                                    wi.getId().toString(), name, mime, done, total, failed,
                                    reason));
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

    /**
     * Asks the ViewModel to pull the manifest. The fragment keeps only the two
     * view-only concerns: stopping the refresh spinner (on BOTH outcomes — a
     * failed refresh that leaves it turning reads as a hang) and the error
     * snackbar.
     */
    private void load() {
        mViewModel.load(this::stopRefreshing,
                () -> snackbar(getString(R.string.cloud_backup_list_error)));
    }

    /** Clears the pull-to-refresh spinner, if one is spinning. */
    private void stopRefreshing() {
        if (mSwipeRefresh != null && mSwipeRefresh.isRefreshing()) {
            mSwipeRefresh.setRefreshing(false);
        }
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
        // COMMITTED rows, not getItemCount(): that counts in-progress TRANSFER
        // rows too, and those arrive instantly from WorkManager while the
        // manifest is still being pulled over the network. Treating one as
        // "the list is ready" is what made opening Backups mid-upload show the
        // uploading item ALONE for a beat and then pop the whole list in behind
        // it (reported on-device) — two transitions where there should be one.
        // The first fetch now holds the spinner until the manifest lands, so
        // the transfer row and the files appear together.
        boolean hasCommitted = !mEntries.isEmpty();
        boolean hasRows = mAdapter != null && mAdapter.getItemCount() > 0;
        if (mLoading && !hasCommitted) {
            mLcee.showLoading();
        } else if (hasRows) {
            mLcee.hideAll();          // show the list (rows carry the state)
        } else {
            applyEmptyState();
            mLcee.showEmpty();
        }
        updateHeader();
    }

    /** Picks the no-rows illustration + copy: an honest error if the last pull
     *  failed, a "No results found" search-empty when the user is filtering (so
     *  an empty search doesn't lie "No backups yet"), otherwise the cloud art. */
    private void applyEmptyState() {
        if (mLoadFailed) {
            mLcee.setEmptyImageView(R.drawable.ill_small_browser_error);
            mLcee.setEmptyText(R.string.cloud_backup_list_error);
        } else if (isSearchActive() && !mSearchQuery.isEmpty()) {
            mLcee.setEmptyImageView(R.drawable.ill_small_search);
            mLcee.setEmptyText(R.string.empty_list_query);
        } else {
            mLcee.setEmptyImageView(R.drawable.ill_cloud);
            mLcee.setEmptyText(R.string.cloud_backup_list_empty);
        }
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
        // Line 2 = account STATUS, then trust — status first so that if the line
        // ever has to wrap, it is the boilerplate tail that moves, not the fact.
        // The unmetered beta keeps its own composed string (its byte allowance
        // reads "of X GB included · …", not a bare figure).
        StorageApiClient.Quota quota = mStatusInfo != null ? mStatusInfo.quota : null;
        String trust = getString(R.string.cloud_backup_header_encrypted);
        String line2;
        if (quota != null && !quota.metered && quota.bytesLimit > 0) {
            line2 = getString(R.string.cloud_backup_header_beta,
                    Formatter.formatShortFileSize(requireContext(), quota.bytesLimit));
        } else {
            String status = headerStatus(quota);
            line2 = status == null ? trust : status + " · " + trust;
        }
        mHeaderLine2.setText(line2);
        mHeader.setVisibility(View.VISIBLE);
    }

    /**
     * The account's status phrase for header line 2, or null when the server
     * gave us no figure (offline, or funded with no projection) — never a stale
     * or invented number. Metered context is TIME, never the raw GB-months
     * ledger unit (the old "5409.5 GB-months" here was an on-device confusion
     * report).
     * <ul>
     *   <li>grace (ran out, read-only) → "Read-only";</li>
     *   <li>metered + a projection → "≈ 1 year of coverage";</li>
     *   <li>everything else → null, so line 2 is the trust phrase alone.</li>
     * </ul>
     *
     * <p>This used to be a tappable coral/amber chip that doubled as the top-up
     * door. The door moved to the toolbar overflow (an action belongs with the
     * screen's actions) and what stayed behind is plain STATUS text, which is
     * the half that has to be visible without opening a menu.
     *
     * <p>It carries NO colour. The chip tinted its grace label with
     * {@code backup_warning}, which measures <b>2.09:1</b> on the light surface —
     * the state most needing to be read was the least readable, the same defect
     * class as the old home pill's 1.37:1. The word "Read-only" carries the
     * state on its own (WCAG 1.4.1 requires that regardless), so the fix is to
     * drop the tint rather than to find a legible amber.
     */
    @Nullable
    private String headerStatus(@Nullable StorageApiClient.Quota quota) {
        if (quota == null || !quota.metered) {
            return null;
        }
        if (quota.readOnly) {
            return getString(R.string.cloud_status_chip_readonly);
        }
        return CloudStatusPreference.coverageLabel(requireContext(), quota);
    }


    /** Grid span for the given mode: 1 = list (single column), else the same
     *  orientation-aware grid span the Downloads grid uses
     *  ({@code image_grid_number} — portrait 2, landscape 4, more on tablets).
     *  Read fresh from resources so {@link #onConfigurationChanged} picks up the
     *  new orientation's value on rotation. */
    private int gridSpanCount(boolean grid) {
        return grid ? getResources().getInteger(R.integer.image_grid_number) : 1;
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

    /** Inflates the full-width in-toolbar search field (the same bar Downloads
     *  uses) and adds it to the BORROWED activity toolbar, hidden until the search
     *  action is tapped. The toolbar outlives this fragment's view, so the bar is
     *  removed in {@link #onDestroyView()}. */
    private void setupSearchBar() {
        if (mToolbar == null) {
            return;
        }
        // Guard against a stale bar from a previous view instance (the toolbar is
        // the activity's and persists across onDestroyView/onViewCreated).
        View stale = mToolbar.findViewById(R.id.search_bar);
        if (stale != null) {
            mToolbar.removeView(stale);
        }
        mSearchBar = LayoutInflater.from(mToolbar.getContext())
                .inflate(R.layout.toolbar_search_field, mToolbar, false);
        mToolbar.addView(mSearchBar);
        mSearchEdit = mSearchBar.findViewById(R.id.search_edit);
        View clear = mSearchBar.findViewById(R.id.search_clear);
        mSearchEdit.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {}

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {}

            @Override
            public void afterTextChanged(Editable s) {
                mSearchQuery = s.toString().trim();
                if (clear != null) {
                    clear.setVisibility(s.length() > 0 ? View.VISIBLE : View.GONE);
                }
                submitVisible();
                render();
            }
        });
        mSearchEdit.setOnEditorActionListener((v, actionId, event) -> {
            hideKeyboard(mSearchEdit);
            return true;
        });
        if (clear != null) {
            clear.setOnClickListener(v -> mSearchEdit.setText(""));
        }
    }

    private void openSearchBar() {
        if (mSearchBar == null || mSearchEdit == null || isSearchActive()) {
            return;
        }
        mTitleBeforeSearch = mToolbar.getTitle();
        mToolbar.setTitle("");
        mSearchBar.setVisibility(View.VISIBLE);
        requireActivity().invalidateOptionsMenu(); // hide the browse icons
        // Up closes the search field. Route Up restoration through the activity
        // in closeSearchBar (never a fragment-bound lambda) — same reason as
        // exitSelection's restoreToolbarUp.
        mToolbar.setNavigationOnClickListener(v -> closeSearchBar());
        if (mBackCallback != null) {
            mBackCallback.setEnabled(true);
        }
        mSearchEdit.requestFocus();
        mSearchEdit.post(() -> showKeyboard(mSearchEdit));
    }

    private void closeSearchBar() {
        if (!isSearchActive()) {
            return;
        }
        mSearchEdit.setText("");
        hideKeyboard(mSearchEdit);
        mSearchBar.setVisibility(View.GONE);
        if (mToolbar != null) {
            mToolbar.setTitle(mTitleBeforeSearch != null
                    ? mTitleBeforeSearch : getString(R.string.cloud_backup_files_title));
        }
        FragmentActivity activity = getActivity();
        if (activity instanceof SettingsActivity) {
            ((SettingsActivity) activity).restoreToolbarUp();
        }
        // Keep Back enabled only if selection is still active.
        if (mBackCallback != null) {
            mBackCallback.setEnabled(mSelectionMode);
        }
        if (activity != null) {
            activity.invalidateOptionsMenu();
        }
    }

    private boolean isSearchActive() {
        return mSearchBar != null && mSearchBar.getVisibility() == View.VISIBLE;
    }

    private void showKeyboard(View view) {
        InputMethodManager imm = (InputMethodManager)
                view.getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) {
            imm.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT);
        }
    }

    private void hideKeyboard(View view) {
        InputMethodManager imm = (InputMethodManager)
                view.getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) {
            imm.hideSoftInputFromWindow(view.getWindowToken(), 0);
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
            mViewModel.toggleSelected(entry.objectId);
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
        mViewModel.toggleSelected(entry.objectId);
        refreshSelection();
    }

    // ---- multi-select via the existing toolbar (the Downloads strategy, NOT a
    //      contextual ActionMode): the screen toolbar shows "N selected" + a
    //      delete action while selecting, and its Up button / system Back exit
    //      selection instead of leaving the screen. ----

    private void enterSelection() {
        // Search and selection both take over the toolbar row — never both at once.
        closeSearchBar();
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
        // The adapter no longer owns the selection, so clearing it is ours to do.
        // Safe against re-entry: this sets the selection empty, whose observer
        // calls refreshSelection(), which calls exitSelection() again — and the
        // mSelectionMode guard above returns immediately the second time.
        mViewModel.clearSelection();
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
        int n = mViewModel.selectionSnapshot().size();
        if (n == 0) {
            exitSelection();
            return;
        }
        if (mToolbar != null) {
            // "N selected · 1.2 GB" — the byte total is the number the user is
            // actually deciding on here: how much a delete frees, or how much a
            // restore will pull down. Composed rather than a new string (the
            // header already joins facts with the same separator).
            long bytes = mViewModel.selectedBytes();
            String title = getString(R.string.action_mode_selected, n);
            if (bytes > 0) {
                title = title + " · " + Formatter.formatShortFileSize(requireContext(), bytes);
            }
            mToolbar.setTitle(title);
        }
    }

    // Storage error slugs worth explaining to a user. Mirrors
    // firedown-api internal/storage/api/errors.go — keep in step with it.
    private static final String SLUG_PAYMENT_REQUIRED = "payment-required";
    private static final String SLUG_QUOTA_EXHAUSTED = "quota-exhausted";
    private static final String SLUG_PAYLOAD_TOO_LARGE = "payload-too-large";

    /**
     * A specific explanation for a terminal backup failure, or null to fall back
     * to the generic "Backup failed".
     *
     * <p>Driven ENTIRELY by the server's slug — deliberately not by guessing from
     * the file's size. Metered mode has NO byte cap (the gate is balance &gt; 0,
     * not a byte projection), so a large file failing there is almost never an
     * out-of-space problem, and a message asserting one would send the user to
     * buy credit they already have. Only the three slugs below get a claim; every
     * other failure stays generic, which is the honest answer when the reason is
     * a retry ceiling, a dropped upload or something we have not seen before.
     *
     * <p>Numbers are included only where they are true: the free-space figure
     * exists solely on the UNMETERED flat cap, so "quota-exhausted" gets it and
     * "payment-required" (metered) cannot — that one says the balance is out, and
     * says nothing about bytes.
     */
    @Nullable
    private String failureText(@Nullable String slug, long fileBytes) {
        if (slug == null || !isAdded()) {
            return null;
        }
        switch (slug) {
            case SLUG_PAYLOAD_TOO_LARGE:
                return fileBytes > 0
                        ? getString(R.string.cloud_backup_transfer_too_large_detail,
                                Formatter.formatShortFileSize(requireContext(), fileBytes))
                        : getString(R.string.cloud_backup_transfer_too_large);
            case SLUG_PAYMENT_REQUIRED:
                return getString(R.string.cloud_backup_transfer_no_credit);
            case SLUG_QUOTA_EXHAUSTED: {
                long free = freeCapBytes();
                return (fileBytes > 0 && free >= 0)
                        ? getString(R.string.cloud_backup_transfer_no_space_detail,
                                Formatter.formatShortFileSize(requireContext(), fileBytes),
                                Formatter.formatShortFileSize(requireContext(), free))
                        : getString(R.string.cloud_backup_transfer_no_space);
            }
            default:
                return null;
        }
    }

    /** Bytes left under the UNMETERED flat cap, or -1 when there is no such cap
     *  (metered mode) or the quota has not loaded. Usage is summed from the
     *  committed rows, which is the same figure the header reports. */
    private long freeCapBytes() {
        StorageApiClient.Quota quota = mStatusInfo != null ? mStatusInfo.quota : null;
        if (quota == null || quota.metered || quota.bytesLimit <= 0) {
            return -1;
        }
        long used = 0;
        for (VaultEntry e : mEntries) {
            used += e.size;
        }
        return Math.max(0, quota.bytesLimit - used);
    }

    /** Confirms then removes every selected file from the cloud (optimistically). */
    private void confirmDeleteSelected() {
        int n = mViewModel.selectionSnapshot().size();
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
        List<String> ids = new ArrayList<>(mViewModel.selectionSnapshot());
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
            mViewModel.removeOptimistic(e); // guards a racing load() from resurrecting it
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
                if (mViewModel.findByObjectId(e.objectId) == null) {
                    // restoreRow also stops guarding the id.
                    mViewModel.restoreRow(e, -1);
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
        final int pos = mViewModel.indexOf(entry);
        mViewModel.removeOptimistic(entry); // guards a racing load() from resurrecting it
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
            // restoreRow puts it back at its original index and stops guarding it.
            mViewModel.restoreRow(entry, pos);
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

    /**
     * Restores every selected file. Enqueues one {@link VaultRestoreWorker} per
     * entry — the same request {@link #startRestore} builds for a single file,
     * so each restore keeps its own progress, its own retry/backoff and its own
     * "already in your downloads" no-op; WorkManager serialises them under the
     * shared tag.
     *
     * <p>Deliberately NOT confirmed with a dialog, unlike the batch delete:
     * restoring is constructive and reversible (the files land in Downloads and
     * can be deleted), while a delete destroys the only remaining copy of
     * anything marked "Not on this device". The count + total size are already
     * in the toolbar title at the moment of the tap.
     *
     * <p>Selection is exited immediately so the list returns to normal and the
     * restores report through the usual snackbar/notification path.
     */
    private void restoreSelected() {
        List<VaultEntry> selected = mViewModel.selectedEntries();
        if (selected.isEmpty()) {
            return;
        }
        for (VaultEntry entry : selected) {
            enqueueRestore(entry);
        }
        exitSelection();
        snackbar(getResources().getQuantityString(
                R.plurals.cloud_restore_started_many, selected.size(), selected.size()));
    }

    /**
     * Builds and enqueues ONE restore work request, and nothing else — no
     * snackbar, no result observer. Split out of {@link #startRestore} so the
     * batch path can enqueue N of them without firing N snackbars and attaching
     * N per-work observers (which would talk over each other and, on a large
     * selection, spam the screen with one toast per file).
     */
    private void enqueueRestore(VaultEntry entry) {
        Data input = new Data.Builder()
                .putString(VaultRestoreWorker.KEY_OBJECT_ID, entry.objectId)
                .putString(VaultRestoreWorker.KEY_WRAPPED_DEK, entry.wrappedDek)
                .putString(VaultRestoreWorker.KEY_NAME, entry.name)
                .putString(VaultRestoreWorker.KEY_MIME, entry.mime)
                .putLong(VaultRestoreWorker.KEY_SIZE, entry.size)
                .putLong(VaultRestoreWorker.KEY_DOWNLOADED_AT, entry.downloadedAt)
                .putInt(VaultRestoreWorker.KEY_CHUNK_COUNT, entry.chunkCount)
                .putString(VaultRestoreWorker.KEY_ORIGIN, entry.origin)
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
        mLastRestoreId = request.getId();
    }

    /** Single-file restore: enqueue, then report this one's outcome. */
    private void startRestore(VaultEntry entry) {
        enqueueRestore(entry);
        WorkManager wm = WorkManager.getInstance(requireContext().getApplicationContext());
        snackbar(getString(R.string.cloud_restore_started));

        final LiveData<WorkInfo> live = wm.getWorkInfoByIdLiveData(mLastRestoreId);
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
