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
import com.solarized.firedown.data.di.Qualifiers;
import com.solarized.firedown.data.TaskEvent;
import com.solarized.firedown.data.entity.DownloadEntity;
import com.solarized.firedown.data.models.DownloadsViewModel;
import com.solarized.firedown.data.models.TaskViewModel;
import com.solarized.firedown.manager.ServiceActions;
import com.solarized.firedown.phone.VaultActivity;
import com.solarized.firedown.ui.adapters.DownloadItemAdapter;
import com.solarized.firedown.ui.adapters.IncognitoInProgressHeaderAdapter;
import com.solarized.firedown.ui.adapters.RestoreBannerAdapter;
import com.solarized.firedown.ui.OnItemClickListener;
import com.solarized.firedown.ui.diffs.DownloadDiffCallback;
import com.solarized.firedown.IntentActions;
import com.solarized.firedown.Keys;
import com.solarized.firedown.utils.NavigationUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;

import javax.inject.Inject;

import dagger.hilt.android.AndroidEntryPoint;

/* @AndroidEntryPoint must be on THIS class, not only on BaseDownloadFragment:
 * Hilt members-injection only covers fields declared on the annotated class
 * (and its supers) — an unannotated subclass's own @Inject fields are left
 * null. mDiskExecutor/mDownloadDatabase live here, so without the annotation
 * the SAF restore callback NPE'd the moment the folder picker returned. */
@AndroidEntryPoint
public class DownloadFragment extends BaseDownloadFragment implements
        EditText.OnEditorActionListener,
        ChipGroup.OnCheckedStateChangeListener,
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
    @Inject
    @Qualifiers.DiskIO
    Executor mDiskExecutor;

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

    /** "Reinstalled? Restore your previous downloads" header — armed only by
     *  DownloadBackupMirror's detected-reinstall path, retired permanently on
     *  dismiss or any completed restore. Yields to the incognito header so at
     *  most one informational banner shows at a time. */
    private RestoreBannerAdapter mRestoreBannerAdapter;

    /** Latest TaskViewModel#getSafeCount value — the incognito header's
     *  visibility input, which the restore banner yields to. */
    private int mSafeCount = 0;

    /** Set when a new query has been dispatched; consumed on the next successful refresh. */
    private boolean mPendingScrollToTop = false;

    /** Set on a filter-chip change; consumed by the load-state listener
     *  once the new generation is presented — see applyPendingPresentation. */
    private boolean mPendingPresentation = false;

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
        // ConcatAdapter prepends the informational headers (incognito
        // in-flight hint, restore-after-reinstall banner) at the top of the
        // list. Report their combined count to the base's SpanSizeLookup so
        // the rows span the full grid width and the date-divider lookup
        // against the paged adapter is shifted accordingly.
        int headers = 0;
        if (mIncognitoHeaderAdapter != null) {
            headers += mIncognitoHeaderAdapter.getItemCount();
        }
        if (mRestoreBannerAdapter != null) {
            headers += mRestoreBannerAdapter.getItemCount();
        }
        return headers;
    }

    @Override
    public void onDestroyView() {
        mAdapter = null;
        mIncognitoHeaderAdapter = null;
        mRestoreBannerAdapter = null;
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
        mIncognitoHeaderAdapter = new IncognitoInProgressHeaderAdapter(() ->
                startActivity(new Intent(requireContext(), VaultActivity.class)));
        mRestoreBannerAdapter = new RestoreBannerAdapter(new RestoreBannerAdapter.OnBannerListener() {
            @Override
            public void onRestoreBannerClicked() {
                showRestoreDownloadsDialog();
            }

            @Override
            public void onRestoreBannerDismissed() {
                retireRestoreBanner();
            }
        });
        // ConcatAdapter puts the informational headers at the top so they
        // scroll with the list; each adapter hides itself (getItemCount == 0)
        // so positions don't shift for the paginated list when they retire.
        // Order = priority: the incognito hint (live state) above the restore
        // banner — and the fragment additionally keeps at most ONE visible at
        // a time (updateRestoreBannerVisibility yields to the incognito one).
        mRecyclerView.setAdapter(new ConcatAdapter(
                mIncognitoHeaderAdapter, mRestoreBannerAdapter, mAdapter));
        updateRestoreBannerVisibility();
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
                        // every no-result query).
                        mLCEERecyclerView.setEmptyText(R.string.empty_list_type);
                        mLCEERecyclerView.setEmptyImageView(R.drawable.ill_baloons);
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
                        if (unfiltered) {
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
            // The restore banner yields to the incognito header — re-evaluate
            // whenever that header's visibility input changes.
            updateRestoreBannerVisibility();
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
                aggregates -> { if (aggregates != null) mAdapter.setAggregates(aggregates); });

        // Scroll the list back to the top whenever a new (distinct) query is dispatched.
        // distinctUntilChanged on the VM side suppresses the spurious initial emission
        // caused by chip/sort changes, but the first observation still fires once — which
        // is harmless because the list is already at position 0 on first load.
        mDownloadsViewModel.getDispatchedQuery().observe(getViewLifecycleOwner(),
                q -> mPendingScrollToTop = true);
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
        // The scan + AES-GCM decrypt + DB import below run off the main thread and
        // can take a few seconds; show the bottom progress bar (indeterminate)
        // so the gap between the folder pick and the result snackbar isn't dead
        // air, and hide the empty-state restore button while it runs.
        showRestoreProgress();
        mDiskExecutor.execute(() -> {
            int result = DownloadBackupMirror.restoreFromTree(appContext, mDownloadDatabase, treeUri);
            View view = getView();
            if (view == null) {
                return; // fragment view gone — the progress bar went with it
            }
            view.post(() -> {
                hideRestoreProgress();
                if (!isAdded() || mActivity == null) {
                    return;
                }
                // A completed restore attempt — whatever the outcome — retires
                // the reinstall banner: the user has now been through the flow,
                // re-prompting adds nothing ("no backup" stays reachable from
                // Settings).
                retireRestoreBanner();
                // Always reload the Paging source. On success it surfaces the
                // restored rows — the import writes through getOpenHelper() (raw
                // SQLite), bypassing Room's InvalidationTracker, so the list
                // wouldn't otherwise update. On an empty/failed result it re-runs
                // the load-state listener, which re-shows the empty-state restore
                // button hidden by showRestoreProgress(). Cheap when empty.
                if (mAdapter != null) {
                    mAdapter.refresh();
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
        });
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

    /** Show the detected-reinstall banner iff it's armed (DownloadBackupMirror)
     *  AND the incognito in-flight header isn't occupying the banner slot —
     *  at most one informational banner at a time. */
    private void updateRestoreBannerVisibility() {
        if (mRestoreBannerAdapter == null) {
            return;
        }
        boolean show = mSafeCount == 0
                && DownloadBackupMirror.isRestoreBannerPending(requireContext());
        mRestoreBannerAdapter.setVisible(show);
    }

    /** Permanently retire the reinstall banner (dismissed, or a restore ran). */
    private void retireRestoreBanner() {
        DownloadBackupMirror.clearRestoreBanner(requireContext());
        if (mRestoreBannerAdapter != null) {
            mRestoreBannerAdapter.setVisible(false);
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
        int chipId = mChipGroup != null ? mChipGroup.getCheckedChipId() : View.NO_ID;
        mAdapter.setMimeSuppressed(chipId != View.NO_ID);
        refreshGridDensityIfChanged();
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