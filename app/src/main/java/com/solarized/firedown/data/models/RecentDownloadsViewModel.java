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
 * {@code HomeFragment}'s "Recently downloaded" strip. Stays separate
 * from {@link DownloadsViewModel} (which is paging-focused for the
 * full DownloadFragment list) so the home strip can be a plain
 * fixed-size LiveData with no chip/sort state.
 */
@HiltViewModel
public class RecentDownloadsViewModel extends ViewModel {

    public static final int LIMIT = 8;

    private final LiveData<List<DownloadEntity>> mRecent;

    @Inject
    public RecentDownloadsViewModel(DownloadDataRepository repository) {
        mRecent = repository.getRecentFinishedDownloads(LIMIT);
    }

    public LiveData<List<DownloadEntity>> getRecent() {
        return mRecent;
    }
}
