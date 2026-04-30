package com.solarized.firedown.data.models;

import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;

import androidx.annotation.Nullable;
import androidx.lifecycle.LiveData;
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

import com.solarized.firedown.Preferences;
import com.solarized.firedown.R;
import com.solarized.firedown.Sorting;
import com.solarized.firedown.data.entity.DownloadEntity;
import com.solarized.firedown.data.repository.DownloadDataRepository;
import com.solarized.firedown.utils.DownloadSortOrganizer;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.inject.Inject;

import dagger.hilt.android.lifecycle.HiltViewModel;
import kotlinx.coroutines.CoroutineScope;

@HiltViewModel
public class DownloadsViewModel extends ViewModel {

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

    public void addDownload(DownloadEntity download) {
        mRepository.add(download);
    }

    public void updateDownloadThumb(DownloadEntity download) {
        mRepository.updateDownloadThumb(download);
    }

    public List<DownloadEntity> getAllRaw() {
        return mRepository.getAllRaw();
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