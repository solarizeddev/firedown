package com.solarized.firedown.data.models;

import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.Transformations;
import androidx.lifecycle.ViewModel;
import androidx.lifecycle.ViewModelKt;
import androidx.paging.Pager;
import androidx.paging.PagingConfig;
import androidx.paging.PagingData;
import androidx.paging.PagingLiveData;

import com.solarized.firedown.Preferences;
import com.solarized.firedown.data.DataCallback;
import com.solarized.firedown.data.entity.WebBookmarkEntity;
import com.solarized.firedown.data.repository.WebBookmarkDataRepository;
import com.solarized.firedown.geckoview.GeckoState;

import java.util.Objects;

import javax.inject.Inject;

import dagger.hilt.android.lifecycle.HiltViewModel;
import kotlinx.coroutines.CoroutineScope;

@HiltViewModel
public class WebBookmarkViewModel extends ViewModel {

    private static final long SEARCH_DEBOUNCE_MS = 250L;

    private final WebBookmarkDataRepository mRepository;

    /** Raw query stream, updated on every keystroke. */
    private final MutableLiveData<String> mRawQuery = new MutableLiveData<>("");

    /** Debounced query stream, drives the Pager. */
    private final MutableLiveData<String> mFilterData = new MutableLiveData<>("");

    private final Handler mDebounceHandler = new Handler(Looper.getMainLooper());
    private Runnable mPendingDebounce;
    private String mLastDispatchedQuery = "";

    private final LiveData<PagingData<WebBookmarkEntity>> mData;

    @Inject
    public WebBookmarkViewModel(WebBookmarkDataRepository repository) {
        this.mRepository = repository;
        CoroutineScope viewModelScope = ViewModelKt.getViewModelScope(this);
        PagingConfig pagingConfig = new PagingConfig(Preferences.LIST_LIMIT);

        // switchMap produces raw (uncached) PagingData on each filter change.
        // cachedIn is applied ONCE to the final stream so it survives config changes.
        LiveData<PagingData<WebBookmarkEntity>> rawData = Transformations.switchMap(mFilterData, input -> {
            if (TextUtils.isEmpty(input)) {
                Pager<Integer, WebBookmarkEntity> pager = new Pager<>(pagingConfig, mRepository::get);
                return PagingLiveData.getLiveData(pager);
            } else {
                Pager<Integer, WebBookmarkEntity> searchPager = new Pager<>(pagingConfig,
                        () -> mRepository.getSearch("%" + input + "%"));
                return PagingLiveData.getLiveData(searchPager);
            }
        });
        mData = PagingLiveData.cachedIn(rawData, viewModelScope);
    }

    /**
     * Debounced search. Typing rapidly only dispatches one query after the user pauses,
     * avoiding a new Pager per keystroke. An identical query is never re-dispatched.
     */
    public void search(String query) {
        String normalized = query == null ? "" : query;
        mRawQuery.setValue(normalized);

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

    /** Observable the fragment can watch to know when a new query has actually been dispatched. */
    public LiveData<String> getDispatchedQuery() {
        return mFilterData;
    }

    public LiveData<PagingData<WebBookmarkEntity>> getWebBookmark() {
        return mData;
    }

    public void delete(WebBookmarkEntity web) { mRepository.delete(web); }
    public void delete(int id) { mRepository.delete(id); }
    public void add(WebBookmarkEntity web) { mRepository.add(web); }
    public void add(GeckoState gecko) { mRepository.add(gecko); }
    public void deleteAll() { mRepository.deleteAll(); }
    public boolean contains(GeckoState gecko) { return mRepository.contains(gecko); }

    public void getId(int id, DataCallback<WebBookmarkEntity> callback) {
        mRepository.getId(id, callback);
    }

    @Override
    protected void onCleared() {
        if (mPendingDebounce != null) {
            mDebounceHandler.removeCallbacks(mPendingDebounce);
            mPendingDebounce = null;
        }
        super.onCleared();
    }
}