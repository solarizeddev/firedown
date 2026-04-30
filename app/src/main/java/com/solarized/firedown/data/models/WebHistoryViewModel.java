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
import androidx.paging.PagingLiveData;
import androidx.paging.PagingDataTransforms;

import com.solarized.firedown.Preferences;
import com.solarized.firedown.data.entity.WebHistoryEntity;
import com.solarized.firedown.data.entity.WebHistorySeparatorEntity;
import com.solarized.firedown.data.repository.WebHistoryDataRepository;
import com.solarized.firedown.utils.DateOrganizer;

import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.inject.Inject;

import dagger.hilt.android.lifecycle.HiltViewModel;
import kotlinx.coroutines.CoroutineScope;

@HiltViewModel
public class WebHistoryViewModel extends ViewModel {

    private static final long SEARCH_DEBOUNCE_MS = 250L;

    private final WebHistoryDataRepository mRepository;
    private final MutableLiveData<String> mFilterData = new MutableLiveData<>("");
    private final LiveData<PagingData<Object>> mData;
    private final ExecutorService mExecutor;

    private final Handler mDebounceHandler = new Handler(Looper.getMainLooper());
    private Runnable mPendingDebounce;
    private String mLastDispatchedQuery = "";

    @Inject
    public WebHistoryViewModel(WebHistoryDataRepository repository) {
        this.mRepository = repository;
        this.mExecutor = Executors.newSingleThreadExecutor();

        CoroutineScope scope = ViewModelKt.getViewModelScope(this);
        PagingConfig config = new PagingConfig(Preferences.LIST_LIMIT);

        // 1. Base switchMap driven by debounced query
        LiveData<PagingData<WebHistoryEntity>> sourceLiveData = Transformations.switchMap(mFilterData, query -> {
            Pager<Integer, WebHistoryEntity> pager = TextUtils.isEmpty(query)
                    ? new Pager<>(config, mRepository::get)
                    : new Pager<>(config, () -> mRepository.getSearch("%" + query + "%"));

            return PagingLiveData.getLiveData(pager);
        });

        // 2. Object mapping + separator insertion
        LiveData<PagingData<Object>> transformedLiveData = Transformations.map(sourceLiveData, pagingData -> {
            PagingData<Object> objPagingData = PagingDataTransforms.map(pagingData, mExecutor, entity -> (Object) entity);
            return applySeparators(objPagingData, mFilterData.getValue());
        });

        // 3. cachedIn once, on the final stream, so it survives config changes
        this.mData = PagingLiveData.cachedIn(transformedLiveData, scope);
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

    private PagingData<Object> applySeparators(PagingData<Object> pagingData, String query) {
        if (!TextUtils.isEmpty(query)) return pagingData;

        DateOrganizer dateOrganizer = new DateOrganizer();

        return PagingDataTransforms.insertSeparators(pagingData, mExecutor,
                (@Nullable Object before, @Nullable Object after) -> {

                    if (after instanceof WebHistoryEntity afterEntity) {
                        int afterCategory = dateOrganizer.getCategory(afterEntity.getDate());

                        if (before instanceof WebHistoryEntity beforeEntity) {
                            int beforeCategory = dateOrganizer.getCategory(beforeEntity.getDate());
                            if (beforeCategory == afterCategory) {
                                return null;
                            }
                        }

                        int resId = dateOrganizer.getResIdForCategory(afterCategory);
                        if (resId != 0) {
                            WebHistorySeparatorEntity separator = new WebHistorySeparatorEntity();
                            separator.setId(resId);
                            separator.setTitleResId(resId);
                            return separator;
                        }
                    }
                    return null;
                });
    }

    /**
     * Debounced search. Collapses typing bursts so we don't spin up a new Pager per keystroke.
     */
    public void search(String query) {
        String normalized = query == null ? "" : query;

        if (mPendingDebounce != null) {
            mDebounceHandler.removeCallbacks(mPendingDebounce);
        }
        mPendingDebounce = () -> {
            if (!Objects.equals(mLastDispatchedQuery, normalized)) {
                mLastDispatchedQuery = normalized;
                mFilterData.setValue(normalized);
            }
            mPendingDebounce = null;
        };
        mDebounceHandler.postDelayed(mPendingDebounce, SEARCH_DEBOUNCE_MS);
    }

    /** Emits post-debounce, when a new query has actually been dispatched. */
    public LiveData<String> getDispatchedQuery() {
        return mFilterData;
    }

    public void deleteAll() { mRepository.deleteAll(); }

    public LiveData<PagingData<Object>> getWebHistory() {
        return mData;
    }

    public void delete(WebHistoryEntity entity) { mRepository.delete(entity); }
    public void delete(int id) { mRepository.delete(id); }
    public void deleteSelection(int selection) { mRepository.deleteSelection(selection); }
}