package com.solarized.firedown.phone.fragments;

import android.content.ActivityNotFoundException;
import android.content.Intent;
import androidx.recyclerview.widget.ConcatAdapter;
import android.provider.DocumentsContract;
import android.net.Uri;
import android.content.Context;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.util.Log;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.graphics.drawable.DrawableCompat;
import androidx.core.view.MenuProvider;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.ViewModelProvider;
import androidx.paging.LoadState;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.chip.ChipGroup;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;
import com.solarized.firedown.BuildConfig;
import com.solarized.firedown.Preferences;
import com.solarized.firedown.R;
import com.solarized.firedown.data.Download;
import com.solarized.firedown.data.DownloadBackupMirror;
import com.solarized.firedown.data.DownloadDatabase;
import com.solarized.firedown.data.TaskEvent;
import com.solarized.firedown.data.entity.DownloadEntity;
import com.solarized.firedown.data.models.DownloadsViewModel;
import com.solarized.firedown.data.models.TaskViewModel;
import com.solarized.firedown.manager.ServiceActions;
import com.solarized.firedown.phone.SettingsActivity;
import com.solarized.firedown.phone.VaultActivity;
import com.solarized.firedown.ui.adapters.DownloadItemAdapter;
import com.solarized.firedown.ui.adapters.IncognitoInProgressHeaderAdapter;
import com.solarized.firedown.ui.adapters.SyncBannerAdapter;
import com.solarized.firedown.ui.OnItemClickListener;
import com.solarized.firedown.ui.diffs.DownloadDiffCallback;
import com.solarized.firedown.IntentActions;
import com.solarized.firedown.Keys;
import com.solarized.firedown.utils.GroupAggregate;
import com.solarized.firedown.utils.NavigationUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javax.inject.Inject;

import dagger.hilt.android.AndroidEntryPoint;

/* @AndroidEntryPoint must be on THIS class, not only on BaseDownloadFragment:
 * Hilt members-injection only covers fields declared on the annotated class
 * (and its supers) — an unannotated subclass's own @Inject fields are left
 * null. mDownloadDatabase lives here (handed to the ViewModel's restore), so
 * without the annotation the SAF restore callback NPE'd the moment the folder
 * picker returned. */
@AndroidEntryPoint
public class DownloadFragment extends BaseDownloadFragment implements
        EditText.OnEditorActionListener,
        ChipGroup.OnCheckedStateChangeListener,
        SyncBannerAdapter.OnBannerListener,
        OnItemClickListener {

    private static final String TAG = DownloadFragment.class.getSimpleName();
    private ChipGroup mChipGroup;
    /** The filter chip rail's root (the include in fragment_download.xml). It
     *  shares the second app-bar row with the search bar, so it's hidden while
     *  search is open. */
    private View mChipRail;
    /** The chip checked when search opened, restored when it closes. Search
     *  runs globally (chip cleared) so we never apply a hidden filter. */
    private int mSavedChipId = View.NO_ID;

    @Inject
    DownloadDatabase mDownloadDatabase;

    /** SAF folder picker for the empty-state "Restore previous downloads"
     *  flow — the transport-free recovery path (see DownloadBackupMirror).
     *  Registered as a field initializer so it exists before the fragment
     *  reaches STARTED, as the activity-result API requires. */
    private final ActivityResultLauncher<Uri> mRestoreFolderPicker =
            registerForActivityResult(new ActivityResultContracts.OpenDocumentTree(),
                    this::onRestoreTreePicked);

    /** Separate SAF folder picker for the delete-a-restored-file flow: a
     *  restored entry's public file is foreign-owned (Android 11+ scoped
     *  storage), so {@code File.delete()} can't remove it — we need a folder
     *  WRITE grant. Distinct from the restore picker because the after-action
     *  differs (retry the delete, not run a restore). */
    private final ActivityResultLauncher<Uri> mDeleteGrantPicker =
            registerForActivityResult(new ActivityResultContracts.OpenDocumentTree(),
                    this::onDeleteGrantPicked);

    /** Entities from a {@link TaskEvent.NeedsDeleteGrant} awaiting a folder
     *  WRITE grant; consumed by {@link #onDeleteGrantPicked}. */
    private ArrayList<DownloadEntity> mPendingDeleteGrant;

    /** Single-item header surfaced via {@link androidx.recyclerview.widget.ConcatAdapter}
     *  when the user has vault (incognito-tab) downloads in flight while
     *  looking at the regular Downloads page. Tap → open VaultActivity.
     *  Scrolls with the list (no AppBar pin); driven by
     *  {@code TaskViewModel#getSafeCount} LiveData. */
    private IncognitoInProgressHeaderAdapter mIncognitoHeaderAdapter;

    /** Latest TaskViewModel#getSafeCount value — the incognito header's
     *  visibility input. */
    private int mSafeCount = 0;

    /** One-time announce banner for Cloud Backup, prepended via the same
     *  {@link androidx.recyclerview.widget.ConcatAdapter} as the incognito
     *  header. Shown only while Cloud Backup is NOT set up, the list has rows,
     *  and the banner hasn't been retired — see updateCloudBannerVisibility.
     *  The bookmarks list carries the twin of this for bookmark sync. */
    private SyncBannerAdapter mCloudBannerAdapter;

    /** Latest "the current query matched at least one row" value, fed by the
     *  aggregates stream — the Cloud Backup banner's second visibility input. */
    private boolean mHasRows = false;

    /** Set when a new query has been dispatched; consumed on the next successful refresh. */
    private boolean mPendingScrollToTop = false;

    /** Set on a filter-chip change; consumed by the load-state listener
     *  once the new generation is presented — see applyPendingPresentation. */
    private boolean mPendingPresentation = false;

    /** Section-header aggregates (per-group count + total bytes) held back
     *  while a filter-chip change is in flight (mPendingPresentation). The
     *  aggregates stream and the paging stream are separate LiveData that
     *  land on independent frames, so applying new (e.g. all-types) counts
     *  the moment they arrive rebinds the headers over the OLD, still-filtered
     *  generation — the recording's glitch frame: "Last 7 days · 25 files"
     *  shown above the old image mosaic with "Today" not yet inserted. Stash
     *  the latest map here and apply it atomically with the new generation in
     *  applyPendingPresentation (mirrors the mime-suppression/density flip).
     *  Null when nothing is deferred — emits with no chip change in flight
     *  (ordinary table mutations) apply immediately, keeping live counts. */
    private Map<Integer, GroupAggregate> mPendingAggregates = null;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mGridPreference = Preferences.SORT_DOWNLOADS_LIST;
        mDestinationTitle = R.string.navigation_downloads;
        mCurrentDestinationId = R.id.downloads;
        // Grid is the default view for Downloads. No semantic inversion (the
        // stored bool still means grid=true) and the default isn't persisted, so
        // an untouched install gets grid while anyone who explicitly toggled to
        // list keeps their stored choice — no new pref key needed.
        mEnableGrid = mSharedPreferences.getBoolean(mGridPreference, true);

        mDownloadsViewModel = new ViewModelProvider(this).get(DownloadsViewModel.class);
        mTaskViewModel = new ViewModelProvider(this).get(TaskViewModel.class);

        setupBackPressLogic();
    }

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_download, container, false);
        initViews(view);
        setupRecyclerView();
        setupToolbar();
        return view;
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        postponeEnterTransition();
        observeViewModelData();
        setupNavigationResultObserver();
    }

    @Override
    protected int getLeadingHeaderCount() {
        // ConcatAdapter prepends the incognito in-flight hint header at the top
        // of the list. Report its count to the base's SpanSizeLookup so the rows
        // span the full grid width and the date-divider lookup against the paged
        // adapter is shifted accordingly.
        int headers = 0;
        if (mIncognitoHeaderAdapter != null) {
            headers += mIncognitoHeaderAdapter.getItemCount();
        }
        if (mCloudBannerAdapter != null) {
            headers += mCloudBannerAdapter.getItemCount();
        }
        return headers;
    }

    @Override
    public void onDestroyView() {
        mAdapter = null;
        mIncognitoHeaderAdapter = null;
        mCloudBannerAdapter = null;
        mGridLayoutManager = null;
        mBottomProgressView = null;
        mChipGroup = null;
        mChipRail = null;
        super.onDestroyView();
    }

    private void initViews(View view) {
        mBottomProgressView = view.findViewById(R.id.bottom_progress_view);
        mAppBarLayout = view.findViewById(R.id.appbar_layout);
        mLCEERecyclerView = view.findViewById(R.id.list_recycler_lcee);
        mChipGroup = view.findViewById(R.id.chip_group);
        mChipRail = view.findViewById(R.id.chip_rail);
        mToolbar = view.findViewById(R.id.toolbar);

        mChipGroup.setOnCheckedStateChangeListener(this);
        setupSearchBar(view);
    }

    private void setupRecyclerView() {
        mRecyclerView = mLCEERecyclerView.getRecyclerView();

        mLCEERecyclerView.setEmptyImageView(R.drawable.ill_baloons);
        mAdapter = new DownloadItemAdapter(getContext(), new DownloadDiffCallback(), this, mEnableGrid);
        seedGroupingSort();
        mIncognitoHeaderAdapter = new IncognitoInProgressHeaderAdapter(() ->
                startActivity(new Intent(requireContext(), VaultActivity.class)));
        mCloudBannerAdapter = new SyncBannerAdapter(this, R.string.cloud_banner_title,
                R.string.cloud_banner_subtitle, R.drawable.cloud_outline_24);
        // ConcatAdapter puts the incognito in-flight hint header at the top so
        // it scrolls with the list; it hides itself (getItemCount == 0) so
        // positions don't shift for the paginated list when it retires. The
        // Cloud Backup announce banner sits below it — live in-flight state
        // outranks a one-time promo — and hides itself the same way.
        mRecyclerView.setAdapter(new ConcatAdapter(
                mIncognitoHeaderAdapter, mCloudBannerAdapter, mAdapter));
        mRecyclerView.setVerticalScrollBarEnabled(true);

        configureRecyclerView(mAdapter, mEnableGrid);

        mAdapter.addLoadStateListener(loadStates -> {
            if (mAdapter == null || mLCEERecyclerView == null) return null;
            // Third diagnostic checkpoint: the differ's observable output.
            // A refresh that reaches NotLoading here presented the new
            // generation; a generation that was submitted but never gets
            // past Loading stalled in the page-event transforms.
            if (BuildConfig.DEBUG) {
                Log.d(TAG, "loadState refresh=" + loadStates.getRefresh()
                        + " items=" + mAdapter.getItemCount());
            }
            if (loadStates.getRefresh() instanceof LoadState.NotLoading) {
                // A filter change deferred its presentation flip to here —
                // the new generation is presented, flip atomically with it.
                applyPendingPresentation();
                if (mAdapter.getItemCount() == 0) {
                    // There is NO "All" chip in the rail — unfiltered means no
                    // chip is checked, i.e. getCheckedChipId() == View.NO_ID
                    // (R.id.chip_all is only the ViewModel's no-filter
                    // sentinel, set in onCheckedChanged; it is never a real
                    // checked id here).
                    int chipId = mChipGroup.getCheckedChipId();
                    boolean unfiltered = chipId == View.NO_ID || chipId == R.id.chip_all;
                    if (isSearchActive()) {
                        // Live search with no matches — NOT the post-reinstall
                        // empty state (search clears the chip, so the unfiltered
                        // test below would otherwise offer the restore button on
                        // every no-result query). "No results" + the search
                        // illustration, same as the History/Bookmarks search-empty.
                        mLCEERecyclerView.setEmptyText(R.string.empty_list_query);
                        mLCEERecyclerView.setEmptyImageView(R.drawable.ill_small_search);
                        mLCEERecyclerView.setEmptyButtonVisibility(View.GONE);
                    } else {
                        mLCEERecyclerView.setEmptyText(unfiltered ? R.string.empty_list : R.string.empty_list_type);
                        mLCEERecyclerView.setEmptyImageView(getEmptyIcon(chipId));
                        // Empty + unfiltered is the post-reinstall sight: offer the
                        // transport-free SAF restore (reads the encrypted mirror
                        // surviving in Download/Firedown — see DownloadBackupMirror).
                        // Idempotent, so offering it to a genuinely-new user is
                        // harmless ("no backup found"). Filtered-empty keeps the
                        // plain message — the list isn't actually empty.
                        //
                        // BUT only until the user has actually RUN a restore on this
                        // install: once attempted (any outcome — 0 restored / wrong
                        // device / no backup), re-tapping the same flow can't help,
                        // so stop re-offering it here. The flag is excluded from
                        // backup, so a genuine reinstall offers it afresh; Settings →
                        // "Restore previous downloads" stays for a deliberate retry.
                        if (unfiltered
                                && !DownloadBackupMirror.isRestoreAttempted(requireContext())) {
                            mLCEERecyclerView.setEmptyButtonText(R.string.restore_downloads_button);
                            mLCEERecyclerView.setEmptyButtonVisibility(View.VISIBLE);
                            mLCEERecyclerView.setButtonListener(id -> showRestoreDownloadsDialog());
                        } else {
                            mLCEERecyclerView.setEmptyButtonVisibility(View.GONE);
                        }
                    }
                    mLCEERecyclerView.showEmpty();
                } else {
                    mLCEERecyclerView.hideAll();
                    if (mPendingScrollToTop && mRecyclerView != null) {
                        mPendingScrollToTop = false;
                        mRecyclerView.scrollToPosition(0);
                    }
                }
                handleTransitionTiming();
            }
            return null;
        });
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
            else if (isSearchActive()) closeSearchBar();
            else mActivity.finish();
        });

        mToolbar.addMenuProvider(new MenuProvider() {
            @Override
            public void onCreateMenu(@NonNull Menu menu, @NonNull MenuInflater inflater) {
                if (mActionModeEnabled) {
                    inflater.inflate(mOperationActive ? R.menu.menu_action_empty : R.menu.menu_action_download, menu);
                } else {
                    inflater.inflate(R.menu.menu_download, menu);
                    if (isSearchActive()) {
                        // Search field owns the toolbar row — hide every icon
                        // (open/close calls invalidateMenu to re-evaluate).
                        for (int i = 0; i < menu.size(); i++) menu.getItem(i).setVisible(false);
                    } else {
                        MenuItem actionView = menu.findItem(R.id.action_view);
                        if (actionView != null) actionView.setIcon(mEnableGrid ? R.drawable.ic_view_list_24 : R.drawable.ic_grid_view_24);
                    }
                }
            }
            @Override
            public boolean onMenuItemSelected(@NonNull MenuItem item) {
                return handleMenuAction(item);
            }
        }, getViewLifecycleOwner(), Lifecycle.State.RESUMED);
    }

    private void observeViewModelData() {
        // Surface ongoing vault (incognito-tab) downloads via the
        // bottom-anchored hint card. When the user has only vault
        // downloads in flight the in-progress notification already
        // routes them to VaultActivity directly (see
        // RunnableManager#startNotification); this card covers the
        // mixed case where they end up here looking for a vault file.
        mTaskViewModel.getSafeCount().observe(getViewLifecycleOwner(), count -> {
            if (mIncognitoHeaderAdapter == null) return;
            mSafeCount = count != null ? count : 0;
            mIncognitoHeaderAdapter.setCount(mSafeCount);
        });

        mTaskViewModel.getObservableEvent().observe(getViewLifecycleOwner(), event -> {
            if (event instanceof TaskEvent.Started started) {
                if (started.getAction() == ServiceActions.DECRYPTION) return;
                handleTaskStart(started.getAction());

            } else if (event instanceof TaskEvent.Progress progress) {
                mBottomProgressView.setProgress(progress.getPercent());

            } else if (event instanceof TaskEvent.Finished finished) {
                if (finished.getAction() == ServiceActions.DECRYPTION) return;
                handleTaskFinish(finished.getAction(), finished.getResult());

            } else if (event instanceof TaskEvent.Deleted deleted) {
                showActionSnackbar(R.plurals.complete_delete_files_text, deleted.getCount(), mCurrentDestinationId == R.id.vault);

            } else if (event instanceof TaskEvent.Error error) {
                showActionSnackbar(R.plurals.move_file_fail, error.getCount(), mCurrentDestinationId == R.id.vault);

            } else if (event instanceof TaskEvent.Cancelled) {
                mOperationActive = false;

            } else if (event instanceof TaskEvent.NeedsDeleteGrant needsGrant) {
                promptDeleteGrant(needsGrant.getEntities());
            }
        });

        // Restore progress/result live in the ViewModel so they survive view
        // recreation (leave Downloads and return mid-restore): a fresh observer
        // replays the latest in-flight value → the bar re-appears; the result is
        // a single-shot event → the refresh + snackbar fire on whatever view is
        // current, even if the restore finished while the view was gone.
        mDownloadsViewModel.getRestoreInFlight().observe(getViewLifecycleOwner(), inFlight -> {
            if (Boolean.TRUE.equals(inFlight)) {
                showRestoreProgress();
            }
            // Deliberately NOT hiding on false here: the initial/false value
            // would stomp a legitimately-running task progress bar. The hide is
            // done in the result observer, which only fires for an actual restore
            // completion.
        });
        mDownloadsViewModel.getRestoreResult().observe(getViewLifecycleOwner(), event -> {
            Integer result = event == null ? null : event.consume();
            if (result == null) {
                return; // already handled (config change / re-entry after snackbar)
            }
            hideRestoreProgress();
            // Reload the Paging source: the import writes through
            // getOpenHelper() (raw SQLite), bypassing Room's InvalidationTracker,
            // so the list wouldn't otherwise update; on an empty/failed result it
            // re-runs the load-state listener, re-showing the empty-state button.
            if (mAdapter != null) {
                mAdapter.refresh();
            }
            if (!isAdded() || mActivity == null) {
                return;
            }
            if (result >= 0) {
                makeSnackbar(mActivity.getSnackAnchorView(),
                        getString(R.string.restore_downloads_done, result), false).show();
            } else if (result == DownloadBackupMirror.RESTORE_NO_BACKUP) {
                showErrorSnackbar(R.string.restore_downloads_none);
            } else {
                showErrorSnackbar(R.string.restore_downloads_wrong_device);
            }
        });

        mDownloadsViewModel.getDownloads().observe(getViewLifecycleOwner(), data -> {
            // Diagnostic checkpoint (pairs with DownloadsViewModel's
            // "new paging generation" log): a generation that was emitted
            // but never logged here died in the cachedIn/LiveData leg; one
            // logged here but never followed by the load-state refresh
            // below stalled in the transform executor / differ.
            if (BuildConfig.DEBUG) {
                Log.d(TAG, "submitData: new paging generation");
            }
            mAdapter.submitData(getLifecycle(), data);
        });

        // Push per-group aggregates (count + total size) so the adapter
        // can fill section-header subtitles. Re-fires whenever the
        // download table changes or the sort mode changes.
        mDownloadsViewModel.getDownloadAggregates().observe(getViewLifecycleOwner(),
                this::applyAggregates);

        // Scroll the list back to the top whenever a new (distinct) query is dispatched.
        // distinctUntilChanged on the VM side suppresses the spurious initial emission
        // caused by chip/sort changes, but the first observation still fires once — which
        // is harmless because the list is already at position 0 on first load.
        mDownloadsViewModel.getDispatchedQuery().observe(getViewLifecycleOwner(),
                q -> mPendingScrollToTop = true);
    }

    @Override
    public void onResume() {
        super.onResume();
        // Returning from the Cloud screen may have set Cloud Backup up, which
        // retires the banner; nothing else observes that flag.
        updateCloudBannerVisibility();
    }

    @Override
    public boolean onEditorAction(TextView v, int aId, KeyEvent e) {
        return false;
    }

    @Override
    public void onItemClick(int position, int resId) {
        if (position == RecyclerView.NO_POSITION)
            return;

        Object item = mAdapter.peek(position);
        if (!(item instanceof DownloadEntity entity))
            return;

        if (mActionModeEnabled) {
            mAdapter.setSelected(position);
            setActionModeTitle(mAdapter.getSelectedSize());
            return;
        }

        if (resId == R.id.item) {
            int status = entity.getFileStatus();
            if (status == Download.ERROR) openSourceUrl(entity);
            else openItem(entity, mRecyclerView.findViewHolderForAdapterPosition(
                    position + getLeadingHeaderCount()));
        } else if (resId == R.id.item_download_action) {
            if (entity.getFileStatus() == Download.QUEUED) {
                handleItemAction(IntentActions.DOWNLOAD_DELETE, entity);
            } else {
                Bundle b = new Bundle();
                b.putParcelable(Keys.ITEM_ID, entity);
                b.putInt(Keys.ITEM_POSITION, position);
                NavigationUtils.navigateSafe(mNavController, R.id.dialog_download_options, R.id.downloads, b);
            }
        }
    }

    @Override
    public void onLongClick(int position, int resId) {
        Object item = mAdapter.peek(position);
        if (!(item instanceof DownloadEntity)) return;
        if (isSearchActive()) return;
        startActionMode(position);
    }

    @Override
    public void onItemVariantClick(int position, int variant, int resId) {

    }

    // ── Transport-free restore (SAF) ────────────────────────────────────
    // The encrypted public mirror in Download/Firedown survives uninstall;
    // after a reinstall the file is foreign-owned and only reachable through
    // a user-granted document tree. One folder pick restores the full
    // download list. See DownloadBackupMirror for the data side.

    private void showRestoreDownloadsDialog() {
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.restore_downloads_button)
                .setMessage(R.string.restore_downloads_message)
                .setPositiveButton(R.string.restore_downloads_choose, (dialog, which) -> launchRestoreFolderPicker())
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void launchRestoreFolderPicker() {
        // Pre-point the system picker at Download/Firedown — the user only
        // has to confirm "Use this folder". Providers that don't honour the
        // initial URI just open at their default root; the scan also accepts
        // the backup/ subfolder if that's what gets picked.
        Uri initial = DocumentsContract.buildDocumentUri(
                "com.android.externalstorage.documents", "primary:Download/Firedown");
        try {
            mRestoreFolderPicker.launch(initial);
        } catch (ActivityNotFoundException e) {
            showErrorSnackbar(R.string.restore_downloads_none);
        }
    }

    // ── Delete a restored (foreign-owned) file ──────────────────────────
    // A restored entry's public file isn't owned by this install (scoped
    // storage), so File.delete() silently fails and the file lingers after the
    // row is gone. The repository keeps the row and reports the entity via
    // TaskEvent.NeedsDeleteGrant; we take a folder WRITE grant and retry — the
    // retry deletes the file through SAF and removes the row with it.

    /** Offer a folder WRITE grant so a restored file can be deleted. */
    private void promptDeleteGrant(ArrayList<DownloadEntity> entities) {
        if (entities == null || entities.isEmpty() || mActivity == null) {
            return;
        }
        mPendingDeleteGrant = entities;
        Snackbar snackbar = makeSnackbar(mActivity.getSnackAnchorView(),
                getString(R.string.delete_needs_grant), false);
        snackbar.setAction(R.string.delete_grant_action, v -> launchDeleteGrantPicker());
        snackbar.show();
    }

    private void launchDeleteGrantPicker() {
        // Same Download/Firedown tree the restore flow uses — one confirm.
        Uri initial = DocumentsContract.buildDocumentUri(
                "com.android.externalstorage.documents", "primary:Download/Firedown");
        try {
            mDeleteGrantPicker.launch(initial);
        } catch (ActivityNotFoundException e) {
            mPendingDeleteGrant = null;
            showErrorSnackbar(R.string.restore_downloads_none);
        }
    }

    private void onDeleteGrantPicked(@Nullable Uri treeUri) {
        ArrayList<DownloadEntity> pending = mPendingDeleteGrant;
        mPendingDeleteGrant = null;
        if (treeUri == null || pending == null || pending.isEmpty()) {
            return; // user backed out — entries stay; files remain on disk
        }
        try {
            requireContext().getContentResolver().takePersistableUriPermission(
                    treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                            | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            DownloadBackupMirror.rememberRestoreTree(requireContext(), treeUri);
        } catch (SecurityException e) {
            // Without a persistable WRITE grant the retry can't delete the file.
        }
        // Retry the SAME delete: with the WRITE grant the file delete now
        // succeeds via SAF and the row is removed with it (same pipeline).
        mTaskViewModel.requestDelete(requireContext(), pending);
    }

    private void onRestoreTreePicked(@Nullable Uri treeUri) {
        if (treeUri == null) {
            return; // user backed out of the picker
        }
        try {
            // Persist the grant READ+WRITE: it is the future content-URI access
            // path for the restored files on Android 13+ (the reinstalled app no
            // longer owns them) — read to view/play, WRITE so they can be DELETED
            // (File.delete() can't remove a foreign-owned public file; SAF can).
            requireContext().getContentResolver().takePersistableUriPermission(
                    treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                            | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            DownloadBackupMirror.rememberRestoreTree(requireContext(), treeUri);
        } catch (SecurityException e) {
            // Non-persistable grant — the one-shot read below still works.
        }
        Context appContext = requireContext().getApplicationContext();
        // The scan + AES-GCM decrypt + DB import run off the main thread and can
        // take a few seconds. Hand the work to the ViewModel: it drives the
        // progress bar and the result snackbar through LiveData, so the restore
        // is decoupled from this fragment's view — leaving Downloads and coming
        // back mid-restore keeps the progress bar and still delivers the result
        // (see the getRestoreInFlight/getRestoreResult observers). Hide the
        // empty-state button now so a second tap can't launch a concurrent run.
        showRestoreProgress();
        mDownloadsViewModel.runRestore(appContext, mDownloadDatabase, treeUri);
    }

    /** Bottom-bar progress for the in-flight restore. Indeterminate (the work
     *  gives no progress signal) and action-button-less (the DB import can't be
     *  safely cancelled mid-flight). Also hides the empty-state restore button
     *  so a second tap can't launch a concurrent restore. Driven directly, NOT
     *  through the RunnableManager/TaskViewModel task system — restore is a
     *  one-shot DB op, not a cancellable ffmpeg/encode task. */
    private void showRestoreProgress() {
        if (mLCEERecyclerView != null) {
            mLCEERecyclerView.setEmptyButtonVisibility(View.GONE);
        }
        if (mBottomProgressView != null) {
            mBottomProgressView.setActionButtonVisibility(View.GONE);
            // setIndeterminate must be flipped while the bar is GONE (Material
            // throws if switched to indeterminate while shown); it is GONE here
            // because restore is launched from the empty list, no task running.
            mBottomProgressView.setIndeterminate(true);
            mBottomProgressView.setTitle(R.string.restore_downloads_progress);
            mBottomProgressView.setVisibility(View.VISIBLE);
        }
    }

    private void hideRestoreProgress() {
        if (mBottomProgressView != null) {
            mBottomProgressView.setVisibility(View.GONE);
            // Back to determinate so the shared bar is clean for the task
            // observers (encrypt/compress/…) that drive setProgress.
            mBottomProgressView.setIndeterminate(false);
        }
    }

    private int getEmptyIcon(int chipId) {
        if (chipId == R.id.chip_video)
            return R.drawable.ill_small_video;
        else if (chipId == R.id.chip_audio)
            return R.drawable.ill_small_audio;
        else if (chipId == R.id.chip_image)
            return R.drawable.ill_small_image;
        else if (chipId == R.id.chip_doc)
            return R.drawable.ill_small_doc;
        else if (chipId == R.id.chip_gif)
            return R.drawable.ill_small_gif;
        else if (chipId == R.id.chip_apk)
            return R.drawable.ill_small_apk;
        else if (chipId == R.id.chip_zip)
            return R.drawable.ill_small_zip;
        else
            return R.drawable.ill_baloons;
    }

    private void setChipEnable(boolean status) {
        for (int i = 0; i < mChipGroup.getChildCount(); i++) {
            mChipGroup.getChildAt(i).setEnabled(status);
        }
        mChipGroup.setEnabled(status);
    }

    @Override
    protected void stopActionMode() {
        super.stopActionMode();
        setChipEnable(true);
    }

    @Override
    protected void startActionMode(int position) {
        super.startActionMode(position);
        setChipEnable(false);
    }

    @Override
    public void onCheckedChanged(@NonNull ChipGroup g, @NonNull List<Integer> checkedIds) {
        if (checkedIds.isEmpty()) {
            mDownloadsViewModel.setFilterChip(R.id.chip_all);
        } else {
            mDownloadsViewModel.setFilterChip(checkedIds.get(0));
        }
        // Presentation (mime suppression + dense mosaic span) is NOT
        // flipped here: the paging requery is async, and flipping the
        // adapter now re-renders the OLD list in the NEW presentation
        // first (the images mosaic collapses to normal span-2 tiles for a
        // beat before the videos arrive — same race fixed in
        // BrowserOptionFragment.submitWithPresentation). The flag is
        // consumed by the load-state listener once the new generation has
        // been presented.
        mPendingPresentation = true;
    }

    /**
     * Applies the chip-derived presentation (mime suppression + dense
     * mosaic) AFTER a refresh presented the new generation — called from
     * the load-state listener on refresh NotLoading, in the same
     * main-thread dispatch as the presentation, so no intermediate frame
     * shows the new content in the old presentation. Both calls no-op
     * when nothing actually changed, so the listener firing on every
     * ordinary refresh costs nothing.
     */
    private void applyPendingPresentation() {
        if (!mPendingPresentation || mAdapter == null) {
            return;
        }
        mPendingPresentation = false;
        // Apply the header aggregates held back during the requery FIRST, in
        // the same main-thread dispatch as the presentation flip, so the new
        // generation and its section-header counts land on the same frame —
        // no transient where the new totals sit above the old grouping.
        if (mPendingAggregates != null) {
            mAdapter.setAggregates(mPendingAggregates);
            mPendingAggregates = null;
        }
        int chipId = mChipGroup != null ? mChipGroup.getCheckedChipId() : View.NO_ID;
        mAdapter.setMimeSuppressed(chipId != View.NO_ID);
        refreshGridDensityIfChanged();
    }

    /**
     * Routes a section-header aggregates emission to the adapter, deferring it
     * while a filter-chip change is in flight. The aggregates LiveData and the
     * paging LiveData are independent streams that settle on separate frames;
     * applying new (e.g. all-types) counts the instant they arrive rebinds the
     * headers over the OLD, still-filtered generation (the recording's glitch
     * frame). When mPendingPresentation is set, stash the latest map and let
     * applyPendingPresentation flush it atomically with the new generation;
     * otherwise (ordinary table mutations, no chip change) apply immediately so
     * the counts stay live.
     */
    private void applyAggregates(@Nullable Map<Integer, GroupAggregate> aggregates) {
        if (aggregates == null || mAdapter == null) {
            return;
        }
        // The aggregates map is the "does this list have rows" signal, and it
        // arrives on a plain LiveData observer — safe to mutate the ConcatAdapter
        // from. (The paging load-state listener also knows the row count, but it
        // can fire while the RecyclerView is computing layout, where a
        // notifyItemInserted on a sibling adapter throws.) It is read BEFORE the
        // mPendingPresentation stash below on purpose: that deferral exists to
        // keep section-header COUNTS in step with the generation they label, and
        // the banner labels nothing.
        mHasRows = !aggregates.isEmpty();
        updateCloudBannerVisibility();
        if (mPendingPresentation) {
            mPendingAggregates = aggregates;
            return;
        }
        mAdapter.setAggregates(aggregates);
    }

    /**
     * Shows the one-time Cloud Backup announce banner while the feature is not
     * set up, the list actually has something worth backing up, and the banner
     * hasn't been retired. Retires it permanently once Cloud Backup IS set up —
     * the same shape as the bookmarks sync banner (WebBookmarkFragment).
     *
     * <p>The rows gate is deliberate: promoting a backup feature on an empty
     * Downloads list is noise, and that list already carries its own
     * empty-state CTA (the SAF restore button).
     */
    private void updateCloudBannerVisibility() {
        if (mCloudBannerAdapter == null) {
            return;
        }
        if (mCloudBackup.isSetUp()) {
            if (!isCloudBannerDismissed()) {
                setCloudBannerDismissed(true); // retire once it's set up
            }
            mCloudBannerAdapter.setVisible(false);
            return;
        }
        mCloudBannerAdapter.setVisible(mHasRows && !isCloudBannerDismissed());
    }

    private boolean isCloudBannerDismissed() {
        return mSharedPreferences.getBoolean(Preferences.CLOUD_BACKUP_BANNER_DISMISSED, false);
    }

    private void setCloudBannerDismissed(boolean dismissed) {
        mSharedPreferences.edit()
                .putBoolean(Preferences.CLOUD_BACKUP_BANNER_DISMISSED, dismissed).apply();
    }

    @Override
    public void onSyncBannerClicked() {
        // The merged Cloud screen — the recovery-code / credit roadmap lives
        // there, and setting Cloud Backup up starts with a code. popUpTo is
        // handled by SettingsActivity so Back returns here, not into settings.
        Intent cloudIntent = new Intent(requireContext(), SettingsActivity.class);
        cloudIntent.putExtra(SettingsActivity.EXTRA_OPEN_CLOUD_BACKUP, true);
        startActivity(cloudIntent);
    }

    @Override
    public void onSyncBannerDismissed() {
        setCloudBannerDismissed(true);
        if (mCloudBannerAdapter != null) {
            mCloudBannerAdapter.setVisible(false);
        }
    }

    /**
     * Images and GIF chips both narrow the list to image-mime entries
     * (FileUriHelper.isImage covers GIF/SVG — the same rule that already
     * hides grid titles), so both get the dense mosaic.
     */
    @Override
    protected boolean isDenseImageFilterActive() {
        if (mChipGroup == null) {
            return false;
        }
        int chipId = mChipGroup.getCheckedChipId();
        return chipId == R.id.chip_image || chipId == R.id.chip_gif;
    }

    /**
     * Back deselects an active filter chip (reverting to the unfiltered
     * list) instead of leaving the screen. With no chip checked there's
     * nothing to clear, so Back exits as normal. {@code clearCheck()} fires
     * {@link #onCheckedChanged} with an empty list, which resets the filter.
     */
    @Override
    protected boolean clearFilterOnBack() {
        if (mChipGroup != null && mChipGroup.getCheckedChipId() != View.NO_ID) {
            mChipGroup.clearCheck();
            return true;
        }
        return false;
    }

    /**
     * Search opens → hide the chip rail and drop the active chip so search
     * runs globally (across all types). The chips share the second app-bar
     * row with the search bar, so they'd be hidden anyway; clearing the chip
     * is what actually makes the search global rather than scoped to a filter
     * the user can no longer see. {@code clearCheck()} fires
     * {@link #onCheckedChanged}, which resets the ViewModel filter.
     */
    @Override
    protected void onSearchBarOpening() {
        if (mChipGroup == null) {
            return;
        }
        mSavedChipId = mChipGroup.getCheckedChipId();
        if (mSavedChipId != View.NO_ID) {
            mChipGroup.clearCheck();
        }
        if (mChipRail != null) {
            mChipRail.setVisibility(View.GONE);
        }
    }

    /** Search closes → restore the chip rail and the chip that was active. */
    @Override
    protected void onSearchBarClosing() {
        if (mChipRail != null) {
            mChipRail.setVisibility(View.VISIBLE);
        }
        if (mChipGroup != null && mSavedChipId != View.NO_ID) {
            mChipGroup.check(mSavedChipId);
        }
        mSavedChipId = View.NO_ID;
    }
}