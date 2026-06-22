package com.solarized.firedown.data.models;

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

                PagingData<Object> objData = PagingDataTransforms.map(filtered, mExecutor, entity -> (Object) entity);

                return applySeparators(objData, state.query, state.sortType);
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

                PagingData<Object> objData = PagingDataTransforms.map(filtered, mExecutor, entity -> (Object) entity);

                return applySeparators(objData, state.query, state.sortType);
            });
        });

        // Cache in scope to survive configuration changes.
        mDownloadData = PagingLiveData.cachedIn(mDownloadData, mViewModelScope);
        mSafeData = PagingLiveData.cachedIn(mSafeData, mViewModelScope);

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
     * Inserts DownloadSeparatorEntity between items when the sort category changes.
     * Skipped entirely when a search query is active.
     */
    private PagingData<Object> applySeparators(PagingData<Object> pagingData, String query, int sortType) {
        if (!TextUtils.isEmpty(query)) return pagingData;

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
        if (!TextUtils.isEmpty(query)) {
            return mRepository.getSearch(sortType, isSafe, "%" + query + "%");
        }

        return switch (sortType) {
            case Sorting.SORT_ALPHABET -> isSafe ? mRepository.getSafeName() : mRepository.getDownloadsName();
            case Sorting.SORT_SIZE -> isSafe ? mRepository.getSafeSize() : mRepository.getDownloadsSize();
            case Sorting.SORT_DOMAIN -> isSafe ? mRepository.getSafeDomain() : mRepository.getDownloadsDomain();
            default -> isSafe ? mRepository.getSafe() : mRepository.getDownloads();
        };
    }

    // --- Actions ---

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

    public int getCurrentSorting() {
        return mSorting.getCurrentSortLocal();
    }

    public void saveCurrentSorting(int type) {
        mSorting.saveCurrentSortingLocal(type);
    }

    private interface StateUpdater {
        DownloadsState update(DownloadsState current);
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