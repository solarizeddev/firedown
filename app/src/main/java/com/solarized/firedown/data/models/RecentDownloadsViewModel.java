package com.solarized.firedown.data.models;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.ViewModel;

import com.solarized.firedown.data.entity.DownloadEntity;
import com.solarized.firedown.data.repository.DownloadDataRepository;

import java.util.List;

import javax.inject.Inject;

import dagger.hilt.android.lifecycle.HiltViewModel;

/**
 * Exposes the most recent finished regular (non-vault) downloads for
 * the bottom-bar Downloads long-press quick-access popup. Kept
 * separate from {@link DownloadsViewModel} (which is paging-/sort-/
 * chip-stateful for DownloadFragment) so this surface stays a tiny
 * fixed-size LiveData with no extra wiring.
 */
@HiltViewModel
public class RecentDownloadsViewModel extends ViewModel {

    /** Three rows is the sweet spot for both surfaces: the home-card
     *  embed (where four rows pushed the brand glyph off the bottom on
     *  short phones) and the long-press quick-access sheet (where the
     *  fourth row was already off-screen at the bottom-sheet peek
     *  height on most devices). */
    public static final int LIMIT = 3;

    private final LiveData<List<DownloadEntity>> mRecent;
    private final LiveData<Integer> mVaultCount;

    @Inject
    public RecentDownloadsViewModel(DownloadDataRepository repository) {
        // Includes all statuses (FINISHED, PROGRESS, QUEUED, ERROR) so
        // the popup mirrors the main DownloadFragment list — long-pressing
        // the bar is a quick way to see in-flight downloads too, not
        // just a 'recently completed' list.
        mRecent = repository.getDownloadsLimit(LIMIT);
        // Lightweight count of file_safe=1 rows; drives the home
        // empty-hero vault tile's count badge.
        mVaultCount = repository.getSafeCount();
    }

    public LiveData<List<DownloadEntity>> getRecent() {
        return mRecent;
    }

    public LiveData<Integer> getVaultCount() {
        return mVaultCount;
    }
}
