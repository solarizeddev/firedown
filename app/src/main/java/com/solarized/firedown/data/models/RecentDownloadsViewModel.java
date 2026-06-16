package com.solarized.firedown.data.models;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.ViewModel;

import com.solarized.firedown.data.repository.DownloadDataRepository;

import javax.inject.Inject;

import dagger.hilt.android.lifecycle.HiltViewModel;

/**
 * Small home-page metadata view-model. Exposes {@link #getFinishedSize()} —
 * the live total bytes of finished regular (non-vault) downloads, which drives
 * the home "N saved" stat chip.
 *
 * <p>Kept separate from {@link DownloadsViewModel} (which is paging-, sort- and
 * chip-stateful for DownloadFragment) so this surface stays a small fixed-size
 * LiveData with no extra wiring.</p>
 */
@HiltViewModel
public class RecentDownloadsViewModel extends ViewModel {

    private final LiveData<Long> mFinishedSize;
    private final LiveData<Integer> mSafeCount;

    @Inject
    public RecentDownloadsViewModel(DownloadDataRepository repository) {
        mFinishedSize = repository.getRegularFinishedSize();
        mSafeCount = repository.getSafeCount();
    }

    public LiveData<Long> getFinishedSize() {
        return mFinishedSize;
    }

    /** Live count of Safe Folder (vault) items — home stats card's third column. */
    public LiveData<Integer> getSafeCount() {
        return mSafeCount;
    }
}
