package com.solarized.firedown.data.models;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.Transformations;
import androidx.lifecycle.ViewModel;

import com.solarized.firedown.Sorting;
import com.solarized.firedown.data.entity.BrowserDownloadEntity;
import com.solarized.firedown.data.repository.BrowserDownloadRepository;
import com.solarized.firedown.geckoview.GeckoRuntimeHelper;
import com.solarized.firedown.utils.BuildUtils;

import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import javax.inject.Inject;

import dagger.hilt.android.lifecycle.HiltViewModel;

@HiltViewModel
public class BrowserDownloadViewModel extends ViewModel {

    private final BrowserDownloadRepository mBrowserDownloadRepository;
    private final GeckoRuntimeHelper mGeckoRuntimeHelper;
    private final Sorting mSorting;
    private final LiveData<List<BrowserDownloadEntity>> mObservableBrowser;
    private final MutableLiveData<Integer> mObservableBrowserType = new MutableLiveData<>();

    @Inject
    public BrowserDownloadViewModel(
            BrowserDownloadRepository repository,
            GeckoRuntimeHelper geckoRuntimeHelper,
            Sorting sorting) {

        this.mBrowserDownloadRepository = repository;
        this.mGeckoRuntimeHelper = geckoRuntimeHelper;
        this.mSorting = sorting;

        // Use Transformations.switchMap to react to limit changes
        mObservableBrowser = Transformations.switchMap(mObservableBrowserType, limit ->
                Transformations.map(mBrowserDownloadRepository.getData(), entities ->
                        filter(entities, limit)
                )
        );
    }

    public LiveData<List<BrowserDownloadEntity>> getBrowserDownloads(int limit) {
        mObservableBrowserType.postValue(limit);
        return mObservableBrowser;
    }

    private List<BrowserDownloadEntity> filter(List<BrowserDownloadEntity> entities, int limit) {
        if (entities == null) return null;

        // Note: Using the injected mGeckoRuntimeHelper instead of static call
        int currentTabId = mGeckoRuntimeHelper.getTabId();

        var stream = entities.stream()
                .filter(entity -> mSorting.getPredicateBrowser(entity) && entity.getTabId() == currentTabId);

        if (limit > 0) {
            stream = stream.limit(limit).sorted(Collections.reverseOrder());
        }

        if (BuildUtils.hasAndroid14()) {
            return stream.toList();
        } else {
            return stream.collect(Collectors.toList());
        }
    }

    public int getCurrentSortBrowserId(){
        return mSorting.getCurrentSortBrowserId();
    }

    public String getCurrentSortForIds(int selectedIds){
        return mSorting.getCurrentSortForIds(selectedIds);
    }

    public void setCurrentSortBrowser(int type){
        mSorting.setCurrentSortBrowser(type);
    }

    public void setCurrentSortBrowser(String type){
        mSorting.setCurrentSortBrowser(type);
    }

    public void clearBrowserDownloads() {
        mBrowserDownloadRepository.postClear();
    }

    public void sortBrowserDownloads(String sorting) {
        mSorting.setCurrentSortBrowser(sorting);
        mBrowserDownloadRepository.postComplete();
    }

    public void update() {
        mBrowserDownloadRepository.postComplete();
    }

    public void loadMore(int limit) {
        mObservableBrowserType.postValue(limit);
    }
}