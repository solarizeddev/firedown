package com.solarized.firedown.data.models;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MediatorLiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.Transformations;
import androidx.lifecycle.ViewModel;
import androidx.lifecycle.ViewModelKt;
import androidx.paging.Pager;
import androidx.paging.PagingConfig;
import androidx.paging.PagingData;
import androidx.paging.PagingDataTransforms;
import androidx.paging.PagingLiveData;
import androidx.paging.PagingSource;

import com.solarized.firedown.BuildConfig;
import com.solarized.firedown.Preferences;
import com.solarized.firedown.R;
import com.solarized.firedown.Sorting;
import com.solarized.firedown.data.DownloadBackupMirror;
import com.solarized.firedown.data.DownloadDatabase;
import com.solarized.firedown.data.entity.DownloadEntity;
import com.solarized.firedown.data.repository.DownloadDataRepository;
import com.solarized.firedown.utils.DownloadAggregator;
import com.solarized.firedown.utils.DownloadSortOrganizer;
import com.solarized.firedown.utils.GroupAggregate;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.inject.Inject;

import dagger.hilt.android.lifecycle.HiltViewModel;
import kotlinx.coroutines.CoroutineScope;

@HiltViewModel
public class DownloadsViewModel extends ViewModel {

    private static final String TAG = "DownloadsViewModel";

    private static final long SEARCH_DEBOUNCE_MS = 250L;

    private final DownloadDataRepository mRepository;
    private final Sorting mSorting;
    private final ExecutorService mExecutor;

    // Single source of truth for all list parameters
    private final MutableLiveData<DownloadsState> mStateTrigger = new MutableLiveData<>();

    private final Handler mDebounceHandler = new Handler(Looper.getMainLooper());
    private Runnable mPendingDebounce;

    // SAF restore state lives in the ViewModel (not the fragment) so it survives
    // the view being destroyed and recreated — leaving Downloads and coming back
    // mid-restore must not lose the progress bar or drop the completion (list
    // refresh + result snackbar). mRestoreInFlight drives the progress bar;
    // mRestoreResult is a single-shot event consumed by whatever view is current
    // when the restore finishes (or on the next re-entry if it finished away).
    private final MutableLiveData<Boolean> mRestoreInFlight = new MutableLiveData<>(false);
    private final MutableLiveData<RestoreEvent> mRestoreResult = new MutableLiveData<>();

    // Grid vs list, pushed in by the fragment's configureRecyclerView. Drives
    // ONLY the section-header transform (a FILTERED grid gets none — see
    // applySeparators), which is why it is a stream of its own rather than a field of
    // DownloadsState: a state change rebuilds the Pager and re-queries the
    // database, and toggling the view mode must not do that. See
    // withSectionHeaders for how the two are combined after cachedIn.
    private final MutableLiveData<Boolean> mGridMode = new MutableLiveData<>(false);

    // Changed from PagingData<DownloadEntity> to PagingData<Object> to support separators
    private LiveData<PagingData<Object>> mDownloadData;
    private LiveData<PagingData<Object>> mSafeData;

    /**
     * Emits the current query string whenever it changes. Chip or sort changes do not
     * re-emit because we deduplicate in the fragment using distinctUntilChanged semantics
     * (the stored value is compared). Backed by mStateTrigger.
     */
    private final LiveData<String> mDispatchedQuery;

    /** Per-category aggregates (count + total bytes) for the current sort, computed
     *  off a separate full-list LiveData (not the paging stream) so the adapter can
     *  render header subtitles without consuming the entire paged source. */
    private final LiveData<Map<Integer, GroupAggregate>> mDownloadAggregates;
    private final LiveData<Map<Integer, GroupAggregate>> mSafeAggregates;

    @Inject
    public DownloadsViewModel(DownloadDataRepository repository, Sorting sorting) {
        this.mRepository = repository;
        this.mSorting = sorting;
        this.mExecutor = Executors.newSingleThreadExecutor();
        CoroutineScope mViewModelScope = ViewModelKt.getViewModelScope(this);

        // Initial State: No query, Default Sort, "All" Chip
        mStateTrigger.setValue(new DownloadsState(null, mSorting.getCurrentSortLocal(), R.id.chip_all));

        PagingConfig config = new PagingConfig(Preferences.LIST_LIMIT);

        // --- Downloads Stream ---
        mDownloadData = Transformations.switchMap(mStateTrigger, state -> {

            // 1. Create the Base Pager (Database Query)
            Pager<Integer, DownloadEntity> pager = new Pager<>(config, () ->
                    createPagingSource(state.query, state.sortType, false)
            );

            // 2. Convert to LiveData
            LiveData<PagingData<DownloadEntity>> rawData = PagingLiveData.getLiveData(pager);

            // 3. Apply the "Chip" Filter, then map to Object, then insert separators
            return Transformations.map(rawData, pagingData -> {

                // Diagnostic checkpoint: Room invalidation (insert/update/
                // delete on the download table) must land here as a new
                // generation. A delete whose rowsRemoved log fired but never
                // produced this line means the invalidation→Pager leg is
                // broken; this line without a subsequent adapter refresh
                // means the transform/differ leg is.
                if (BuildConfig.DEBUG) {
                    Log.d(TAG, "downloads: new paging generation");
                }

                PagingData<DownloadEntity> filtered = PagingDataTransforms.filter(pagingData, mExecutor,
                        entity -> mSorting.getPredicateDownloads(entity, state.chipId));

                // Separators are NOT inserted here — they are a presentation
                // transform applied after cachedIn (see withSectionHeaders), so
                // a grid/list toggle can re-run them without re-querying.
                return PagingDataTransforms.map(filtered, mExecutor, entity -> (Object) entity);
            });
        });

        // --- Safe/Vault Stream ---
        mSafeData = Transformations.switchMap(mStateTrigger, state -> {

            Pager<Integer, DownloadEntity> pager = new Pager<>(config, () ->
                    createPagingSource(state.query, state.sortType, true)
            );

            LiveData<PagingData<DownloadEntity>> rawData = PagingLiveData.getLiveData(pager);

            return Transformations.map(rawData, pagingData -> {

                PagingData<DownloadEntity> filtered = PagingDataTransforms.filter(pagingData, mExecutor,
                        mSorting::getPredicateVault);

                return PagingDataTransforms.map(filtered, mExecutor, entity -> (Object) entity);
            });
        });

        // Cache in scope to survive configuration changes, THEN insert the
        // section headers. Order matters: cachedIn first means the header
        // transform is a presentation step that can re-run against the cached
        // pages (on a grid toggle) instead of forcing a fresh database read.
        mDownloadData = withSectionHeaders(PagingLiveData.cachedIn(mDownloadData, mViewModelScope));
        mSafeData = withSectionHeaders(PagingLiveData.cachedIn(mSafeData, mViewModelScope));

        // Query-only signal: re-emit only when the query string changes.
        // Transformations.map emits on every state change, so we filter by tracking
        // the last value and skipping duplicates via a mediator-like pattern.
        mDispatchedQuery = Transformations.distinctUntilChanged(
                Transformations.map(mStateTrigger, state -> state == null ? "" : (state.query == null ? "" : state.query))
        );

        // Aggregate streams. Each sort change re-aggregates the full list
        // under the new category function; each insert/update/delete in the
        // download table re-fires the underlying LiveData and the aggregates
        // recompute. Heavier than the paging path on raw row count, but the
        // aggregator runs in ~O(N) over a small projection and only when the
        // sort actually changed or the table mutated.
        //
        // Off-main: Transformations.map(...) invokes the function on the
        // main thread. The aggregator walks the full download table —
        // hundreds to thousands of rows on a long-running install — and
        // that walk was blocking cold-start (Room emits the list LiveData
        // after a background fetch, but the .map() handler ran inline on
        // the dispatching thread, which is main for LiveData observers).
        // mExecutor is the same single-threaded executor the paging
        // filter / separator path already runs on, so re-using it here
        // serialises all heavy paging-side work onto one background
        // thread instead of fragmenting it across the main looper.
        mDownloadAggregates = Transformations.switchMap(mStateTrigger, state ->
                aggregatesOffMain(mRepository.getAllRegularLive(), state.sortType, state.chipId));
        mSafeAggregates = Transformations.switchMap(mStateTrigger, state ->
                aggregatesOffMain(mRepository.getAllSafeLive(), state.sortType, R.id.chip_all));
    }

    private LiveData<Map<Integer, GroupAggregate>> aggregatesOffMain(
            LiveData<List<DownloadEntity>> source, int sortType, int chipId) {
        MediatorLiveData<Map<Integer, GroupAggregate>> mediator = new MediatorLiveData<>();
        mediator.addSource(source, list -> {
            if (list == null) {
                mediator.postValue(Collections.emptyMap());
                return;
            }
            // Dispatch off the main looper; postValue routes the result
            // back to observers on the main thread, which is what
            // Transformations.map would normally provide.
            //
            // Filter by the active chip FIRST, using the SAME predicate the
            // paging list filters with (Sorting.getPredicateDownloads) — the
            // section headers ("Today · N files · X MB") sum getFileSize over
            // these aggregates, so without the chip filter they'd report the
            // unfiltered totals even while the list below shows only the
            // filtered subset (e.g. filtering to Images still showed all 3
            // files / 1.3 GB). chip_all short-circuits to no filtering (the
            // vault has no chip rail, so it always passes chip_all).
            mExecutor.execute(() ->
                    mediator.postValue(DownloadAggregator.aggregate(
                            filterByChip(list, chipId), sortType)));
        });
        return mediator;
    }

    /** Applies the active downloads chip predicate to the full list before
     *  aggregation. Returns the list unchanged for the no-filter sentinel
     *  (chip_all) so the common unfiltered case allocates nothing. */
    private List<DownloadEntity> filterByChip(List<DownloadEntity> list, int chipId) {
        if (chipId == R.id.chip_all) {
            return list;
        }
        List<DownloadEntity> filtered = new ArrayList<>(list.size());
        for (DownloadEntity entity : list) {
            if (mSorting.getPredicateDownloads(entity, chipId)) {
                filtered.add(entity);
            }
        }
        return filtered;
    }

    @Override
    protected void onCleared() {
        if (mPendingDebounce != null) {
            mDebounceHandler.removeCallbacks(mPendingDebounce);
            mPendingDebounce = null;
        }
        if (mExecutor != null) {
            mExecutor.shutdown();
        }
        super.onCleared();
    }

    /**
     * Re-applies the section-header transform whenever either the cached pages
     * or the view mode changes, without touching the Pager.
     *
     * <p>This is the Paging 3 rule — cache first, presentation transforms after
     * — and it is what makes the grid's header-free layout affordable: a
     * grid/list toggle re-emits a newly transformed {@link PagingData} derived
     * from the SAME cached flow, so no database read and no page reload. Doing
     * it the obvious way (a {@code grid} field on {@link DownloadsState}) would
     * rebuild the Pager on every toggle and reset the scroll position.
     *
     * <p>The query/sort inputs come from {@link #mStateTrigger}'s current value
     * rather than being threaded through: the state always changes before the
     * pages it produced arrive, so the pair is consistent at emit time.
     */
    private LiveData<PagingData<Object>> withSectionHeaders(LiveData<PagingData<Object>> cached) {
        MediatorLiveData<PagingData<Object>> presented = new MediatorLiveData<>();
        SectionHeaders headers = new SectionHeaders(presented);
        presented.addSource(cached, headers::setData);
        presented.addSource(mGridMode, headers::setGrid);
        return presented;
    }

    /** Holds the two inputs {@link #withSectionHeaders} combines. Main-thread
     *  only — MediatorLiveData delivers its source callbacks there, which is
     *  also what makes the setValue below legal. */
    private final class SectionHeaders {
        private final MediatorLiveData<PagingData<Object>> mPresented;
        private @Nullable PagingData<Object> mData;
        private boolean mGrid;

        SectionHeaders(MediatorLiveData<PagingData<Object>> presented) {
            this.mPresented = presented;
        }

        void setData(@Nullable PagingData<Object> pagingData) {
            mData = pagingData;
            emit();
        }

        void setGrid(@Nullable Boolean grid) {
            mGrid = grid != null && grid;
            emit();
        }

        private void emit() {
            if (mData == null) {
                return;
            }
            DownloadsState state = mStateTrigger.getValue();
            String query = state == null ? null : state.query;
            int sortType = state == null ? mSorting.getCurrentSortLocal() : state.sortType;
            int chipId = state == null ? R.id.chip_all : state.chipId;
            mPresented.setValue(applySeparators(mData, query, sortType, mGrid, chipId));
        }
    }

    /**
     * Inserts DownloadSeparatorEntity between items when the sort category changes.
     * Skipped when a search query is active, and in a FILTERED GRID.
     *
     * <p>A header is a full-width item, so every section starts a fresh row and
     * a section holding one file <em>is</em> a half-empty row. Downloads is
     * date-grouped and recent groups are usually one or two files, so those
     * ragged rows land on the top of the screen — the part always in view.
     * Dropping the headers makes every row fill, which is exactly why the
     * images mosaic (one section, many files) never rags.
     *
     * <p><b>But only while a filter chip is active.</b> The UNFILTERED grid is
     * the library view — it's where the date grouping and the per-section
     * aggregates ("Today · 1 file · 49.9 MB") do their work, and losing them
     * there costs more than the ragged rows do. Under a chip you are scanning
     * one type, the run is shorter, and the grouping earns its space less.
     * Stated honestly: this does NOT follow from the raggedness argument —
     * unfiltered has just as many one-file sections — it's a product call that
     * the grouping is worth the void when you're browsing everything and isn't
     * when you're hunting one type.
     *
     * <p>Consequence to keep in mind: the adapter's "drop the fact the header
     * already states" suppressions (see DownloadItemAdapter#setGroupingSort)
     * must not fire on tiles that have no header above them. That is why the
     * grid's size suppression keys on the CHIP rather than the grouping sort —
     * the two conditions now coincide exactly.
     */
    private PagingData<Object> applySeparators(PagingData<Object> pagingData, String query,
                                               int sortType, boolean grid, int chipId) {
        if (!TextUtils.isEmpty(query)) return pagingData;
        if (grid && chipId != R.id.chip_all) return pagingData;

        DownloadSortOrganizer organizer = new DownloadSortOrganizer(sortType);

        return PagingDataTransforms.insertSeparators(pagingData, mExecutor,
                (@Nullable Object before, @Nullable Object after) -> {

                    if (after instanceof DownloadEntity afterEntity) {
                        int afterCategory = organizer.getCategory(afterEntity);

                        if (before instanceof DownloadEntity beforeEntity) {
                            int beforeCategory = organizer.getCategory(beforeEntity);
                            if (beforeCategory == afterCategory) {
                                return null;
                            }
                        }

                        return organizer.createSeparator(afterCategory);
                    }
                    return null;
                });
    }

    private PagingSource<Integer, DownloadEntity> createPagingSource(String query, int sortType, boolean isSafe) {
        PagingSource<Integer, DownloadEntity> source;
        if (!TextUtils.isEmpty(query)) {
            source = mRepository.getSearch(sortType, isSafe, "%" + query + "%");
        } else {
            source = switch (sortType) {
                case Sorting.SORT_ALPHABET -> isSafe ? mRepository.getSafeName() : mRepository.getDownloadsName();
                case Sorting.SORT_SIZE -> isSafe ? mRepository.getSafeSize() : mRepository.getDownloadsSize();
                case Sorting.SORT_DOMAIN -> isSafe ? mRepository.getSafeDomain() : mRepository.getDownloadsDomain();
                default -> isSafe ? mRepository.getSafe() : mRepository.getDownloads();
            };
        }
        // Direct-invalidation belt: the repository pokes registered sources
        // after every DiskIO write, because Room's InvalidationTracker was
        // observed dropping the LAST write of a burst (a user-finished
        // download's FINISHED write landed in the DB but never produced a
        // paging generation — the row sat on "Finishing…" until re-entry).
        // See DownloadDataRepository.mActivePagingSources.
        mRepository.registerActivePagingSource(source);
        return source;
    }

    // --- Actions ---

    /**
     * Grid vs list, pushed in from the fragment's configureRecyclerView (both
     * at setup and on the toolbar toggle). Guarded so a re-configure that
     * didn't change the mode — a dense-mosaic density flip, a rotation — costs
     * no re-present.
     */
    public void setGridMode(boolean grid) {
        if (!Objects.equals(mGridMode.getValue(), grid)) {
            mGridMode.setValue(grid);
        }
    }

    public void setFilterChip(int chipId) {
        updateState(currentState -> new DownloadsState(currentState.query, currentState.sortType, chipId));
    }

    /**
     * Debounced search. Typing rapidly only dispatches one state update after the user pauses,
     * avoiding a new Pager + filter + separator pipeline per keystroke. Identical queries
     * are deduplicated by DownloadsState.equals().
     */
    public void search(String query) {
        if (mPendingDebounce != null) {
            mDebounceHandler.removeCallbacks(mPendingDebounce);
        }
        mPendingDebounce = () -> {
            updateState(currentState -> new DownloadsState(query, currentState.sortType, currentState.chipId));
            mPendingDebounce = null;
        };
        mDebounceHandler.postDelayed(mPendingDebounce, SEARCH_DEBOUNCE_MS);
    }

    public void setSortType(int sortType) {
        updateState(currentState -> new DownloadsState(currentState.query, sortType, currentState.chipId));
    }

    public void refresh() {
        DownloadsState current = mStateTrigger.getValue();
        if (current != null) {
            mStateTrigger.setValue(current);
        }
    }

    private void updateState(StateUpdater updater) {
        DownloadsState current = mStateTrigger.getValue();
        if (current != null) {
            DownloadsState next = updater.update(current);
            if (!next.equals(current)) {
                mStateTrigger.setValue(next);
            }
        }
    }

    public LiveData<PagingData<Object>> getDownloads() {
        return mDownloadData;
    }

    public LiveData<PagingData<Object>> getSafe() {
        return mSafeData;
    }

    /** Emits only when the query string changes (not on chip/sort changes). */
    public LiveData<String> getDispatchedQuery() {
        return mDispatchedQuery;
    }

    public LiveData<Map<Integer, GroupAggregate>> getDownloadAggregates() {
        return mDownloadAggregates;
    }

    public LiveData<Map<Integer, GroupAggregate>> getSafeAggregates() {
        return mSafeAggregates;
    }

    public void addDownload(DownloadEntity download) {
        mRepository.add(download);
    }

    public void updateDownloadThumb(DownloadEntity download) {
        mRepository.updateDownloadThumb(download);
    }

    /** True while a SAF restore is running. Observe to show/hide the restore
     *  progress bar; replays the latest value to a fresh observer, so a view
     *  recreated mid-restore re-shows progress. */
    public LiveData<Boolean> getRestoreInFlight() {
        return mRestoreInFlight;
    }

    /** Single-shot restore result. {@link RestoreEvent#consume()} returns the
     *  code once (then null), so a config change / re-entry after the snackbar
     *  fired doesn't re-fire it — but a restore that finished while the view was
     *  gone is still delivered to the first observer that comes back. */
    public LiveData<RestoreEvent> getRestoreResult() {
        return mRestoreResult;
    }

    /**
     * Run a SAF folder restore off the main thread. Progress + result flow
     * through {@link #getRestoreInFlight()} / {@link #getRestoreResult()} rather
     * than a fragment callback, so the work is decoupled from the view's
     * lifetime. A second call while one is in flight is ignored (the empty-state
     * button is also hidden while running, so this is just belt-and-suspenders).
     */
    public void runRestore(Context appContext, DownloadDatabase database, Uri treeUri) {
        if (Boolean.TRUE.equals(mRestoreInFlight.getValue())) {
            return;
        }
        mRestoreInFlight.setValue(true);
        mExecutor.execute(() -> {
            int result = DownloadBackupMirror.restoreFromTree(appContext, database, treeUri);
            mRestoreResult.postValue(new RestoreEvent(result));
            mRestoreInFlight.postValue(false);
        });
    }

    public int getCurrentSorting() {
        return mSorting.getCurrentSortLocal();
    }

    public void saveCurrentSorting(int type) {
        mSorting.saveCurrentSortingLocal(type);
    }

    private interface StateUpdater {
        DownloadsState update(DownloadsState current);
    }

    /** Single-shot wrapper for a restore result code (a row count, or one of the
     *  {@code RESTORE_*} sentinels). {@link #consume()} yields the code exactly
     *  once so re-observing on a config change / re-entry doesn't re-show the
     *  snackbar; a result posted while no observer was attached is still
     *  delivered to the first one that comes back. */
    public static final class RestoreEvent {
        private final int result;
        private boolean handled;

        RestoreEvent(int result) {
            this.result = result;
        }

        @Nullable
        public Integer consume() {
            if (handled) {
                return null;
            }
            handled = true;
            return result;
        }
    }

    /**
     * Immutable state holding all parameters affecting the list
     */
    private static class DownloadsState {
        final String query;
        final int sortType;
        final int chipId;

        DownloadsState(String query, int sortType, int chipId) {
            this.query = query;
            this.sortType = sortType;
            this.chipId = chipId;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            DownloadsState that = (DownloadsState) o;
            return sortType == that.sortType && chipId == that.chipId && Objects.equals(query, that.query);
        }

        @Override
        public int hashCode() {
            return Objects.hash(query, sortType, chipId);
        }
    }
}