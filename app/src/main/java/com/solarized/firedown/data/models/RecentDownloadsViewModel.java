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

    /** Sized for the popup: four 96dp tiles fit on a phone screen
     *  without horizontal scroll. */
    public static final int LIMIT = 4;

    private final LiveData<List<DownloadEntity>> mRecent;

    @Inject
    public RecentDownloadsViewModel(DownloadDataRepository repository) {
        // Includes all statuses (FINISHED, PROGRESS, QUEUED, ERROR) so
        // the popup mirrors the main DownloadFragment list — long-pressing
        // the bar is a quick way to see in-flight downloads too, not
        // just a 'recently completed' list.
        mRecent = repository.getDownloadsLimit(LIMIT);
    }

    public LiveData<List<DownloadEntity>> getRecent() {
        return mRecent;
    }
}
