package com.solarized.firedown.data.models;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.ViewModel;

import com.solarized.firedown.data.entity.DownloadEntity;
import com.solarized.firedown.data.repository.DownloadDataRepository;

import java.util.List;

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

    /** How many finished downloads the home thumbnail carousel shows. */
    private static final int RECENT_FINISHED_LIMIT = 10;

    private final LiveData<Integer> mFinishedCount;
    private final LiveData<Long> mFinishedSize;
    private final LiveData<Integer> mVaultCount;
    private final LiveData<List<DownloadEntity>> mActiveDownloads;
    private final LiveData<List<DownloadEntity>> mRecentFinished;

    @Inject
    public RecentDownloadsViewModel(DownloadDataRepository repository) {
        mFinishedCount = repository.getRegularFinishedCount();
        mFinishedSize = repository.getRegularFinishedSize();
        mVaultCount = repository.getSafeCount();
        mActiveDownloads = repository.getActiveDownloads();
        mRecentFinished = repository.getRecentFinished(RECENT_FINISHED_LIMIT);
    }

    /** The currently active (in-progress / queued) regular downloads, newest
     *  first — drives the home active-download banner. */
    public LiveData<List<DownloadEntity>> getActiveDownloads() {
        return mActiveDownloads;
    }

    /** The most recent finished regular downloads (newest first, capped) —
     *  drives the home 'Latest downloads' thumbnail carousel. */
    public LiveData<List<DownloadEntity>> getRecentFinished() {
        return mRecentFinished;
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
