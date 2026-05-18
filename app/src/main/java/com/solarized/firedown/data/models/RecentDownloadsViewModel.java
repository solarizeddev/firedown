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
 *   <li>{@link #getActive()} — live list of in-flight regular
 *       downloads (PROGRESS / QUEUED, non-vault). Drives the
 *       active-download strip; also read synchronously by the
 *       bottom-bar long-press handler to decide whether to open
 *       the quick-access sheet or jump straight to DownloadsActivity.</li>
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

    /** Cap for the {@link #getRecent()} list — surfaces on the
     *  DownloadsQuickAccessSheet (long-press of the Downloads button).
     *  Three rows fit the bottom-sheet peek height on most devices. */
    public static final int LIMIT = 3;

    private final LiveData<List<DownloadEntity>> mRecent;
    private final LiveData<List<DownloadEntity>> mActive;
    private final LiveData<Integer> mFinishedCount;
    private final LiveData<Long> mFinishedSize;
    private final LiveData<Integer> mVaultCount;

    @Inject
    public RecentDownloadsViewModel(DownloadDataRepository repository) {
        mRecent = repository.getDownloadsLimit(LIMIT);
        mActive = repository.getActiveRegular();
        mFinishedCount = repository.getRegularFinishedCount();
        mFinishedSize = repository.getRegularFinishedSize();
        mVaultCount = repository.getSafeCount();
    }

    /** Most recent N downloads regardless of status. Backs the
     *  long-press DownloadsQuickAccessSheet. */
    public LiveData<List<DownloadEntity>> getRecent() {
        return mRecent;
    }

    public LiveData<List<DownloadEntity>> getActive() {
        return mActive;
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
