package com.solarized.firedown.data.models;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.ViewModel;

import com.solarized.firedown.data.repository.DownloadDataRepository;

import javax.inject.Inject;

import dagger.hilt.android.lifecycle.HiltViewModel;

/**
 * Small home-page metadata view-model. Exposes:
 *
 * <ul>
 *   <li>{@link #getFinishedCount()} + {@link #getFinishedSize()} —
 *       count and total bytes of finished regular downloads. Drives
 *       the home Downloads card subtitle ('N files saved · X.Y GB').</li>
 *   <li>{@link #getVaultCount()} — count of vault-saved downloads.
 *       Drives the home Safe Folder card subtitle.</li>
 * </ul>
 *
 * Kept separate from {@link DownloadsViewModel} (which is paging-,
 * sort- and chip-stateful for DownloadFragment) so this surface
 * stays small fixed-size LiveData with no extra wiring.
 */
@HiltViewModel
public class RecentDownloadsViewModel extends ViewModel {

    private final LiveData<Integer> mFinishedCount;
    private final LiveData<Long> mFinishedSize;
    private final LiveData<Integer> mVaultCount;

    @Inject
    public RecentDownloadsViewModel(DownloadDataRepository repository) {
        mFinishedCount = repository.getRegularFinishedCount();
        mFinishedSize = repository.getRegularFinishedSize();
        mVaultCount = repository.getSafeCount();
    }

    public LiveData<Integer> getFinishedCount() {
        return mFinishedCount;
    }

    public LiveData<Long> getFinishedSize() {
        return mFinishedSize;
    }

    public LiveData<Integer> getVaultCount() {
        return mVaultCount;
    }
}
