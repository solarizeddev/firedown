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

    /** Rolling "this week" window for the Saved trend line. */
    private static final long WEEK_MILLIS = 7L * 24 * 60 * 60 * 1000;

    private final LiveData<Long> mFinishedSize;
    private final LiveData<Integer> mSafeCount;
    private final LiveData<Long> mSafeSize;
    private final LiveData<Integer> mFinishedThisWeek;

    @Inject
    public RecentDownloadsViewModel(DownloadDataRepository repository) {
        mFinishedSize = repository.getRegularFinishedSize();
        mSafeCount = repository.getSafeCount();
        mSafeSize = repository.getSafeTotalSize();
        // The 7-day cutoff is fixed at VM creation (this surface is recreated
        // each time Home is shown), which is plenty fresh for a trend line.
        mFinishedThisWeek = repository.getRegularFinishedCountSince(
                System.currentTimeMillis() - WEEK_MILLIS);
    }

    public LiveData<Long> getFinishedSize() {
        return mFinishedSize;
    }

    /** Live count of Safe Folder (vault) items — home stats card's third
     *  column footer line. */
    public LiveData<Integer> getSafeCount() {
        return mSafeCount;
    }

    /** Live total bytes of Safe Folder (vault) items — home stats card's third
     *  column headline (mirrors Saved). */
    public LiveData<Long> getSafeSize() {
        return mSafeSize;
    }

    /** Live count of files finished in the last 7 days — home Saved column's
     *  "N this week" trend line. */
    public LiveData<Integer> getFinishedThisWeekCount() {
        return mFinishedThisWeek;
    }
}
