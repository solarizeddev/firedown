package com.solarized.firedown.data.models;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.ViewModel;

import com.solarized.firedown.data.repository.DownloadDataRepository;

import javax.inject.Inject;

import dagger.hilt.android.lifecycle.HiltViewModel;

/**
 * Small home-page metadata view-model. Exposes {@link #getFinishedSize()} — the
 * live total bytes of finished regular (non-vault) downloads, which drives the
 * "N saved" half of the home subtitle line.
 */
@HiltViewModel
public class RecentDownloadsViewModel extends ViewModel {

    private final LiveData<Long> mFinishedSize;

    @Inject
    public RecentDownloadsViewModel(DownloadDataRepository repository) {
        mFinishedSize = repository.getRegularFinishedSize();
    }

    public LiveData<Long> getFinishedSize() {
        return mFinishedSize;
    }
}
