package com.solarized.firedown.data.models;

import android.content.Context;
import androidx.annotation.Nullable;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.ViewModel;
import androidx.lifecycle.ViewModelKt;
import androidx.paging.Pager;
import androidx.paging.PagingConfig;
import androidx.paging.PagingData;
import androidx.paging.PagingDataTransforms;
import androidx.paging.PagingLiveData;

import com.solarized.firedown.Preferences;
import com.solarized.firedown.R;
import com.solarized.firedown.data.di.Qualifiers;
import com.solarized.firedown.data.entity.TabStateArchivedEntity;
import com.solarized.firedown.data.entity.TabStateHeaderArchivedEntity;
import com.solarized.firedown.data.repository.TabStateArchivedRepository;
import com.solarized.firedown.utils.DateOrganizer;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.inject.Inject;

import dagger.hilt.android.lifecycle.HiltViewModel;
import dagger.hilt.android.qualifiers.ApplicationContext;
import kotlinx.coroutines.CoroutineScope;

@HiltViewModel
public class TabsArchiveViewModel extends ViewModel {

    private final TabStateArchivedRepository mTabStateArchivedRepository;
    private final LiveData<PagingData<Object>> mArchiveData;
    private final ExecutorService mExecutor;
    private final DateOrganizer mDateOrganizer;
    private final Context mAppContext;

    @Inject
    public TabsArchiveViewModel(@ApplicationContext Context context, TabStateArchivedRepository repository) {

        this.mTabStateArchivedRepository = repository;
        this.mAppContext = context;
        this.mDateOrganizer = new DateOrganizer();
        this.mExecutor = Executors.newSingleThreadExecutor();

        // Configure Paging
        PagingConfig pagingConfig = new PagingConfig(Preferences.LIST_LIMIT);
        CoroutineScope viewModelScope = ViewModelKt.getViewModelScope(this);

        // Initialize Pager
        Pager<Integer, TabStateArchivedEntity> pager = new Pager<>(
                pagingConfig,
                mTabStateArchivedRepository::getTabsArchive
        );

        // 1. Get the raw LiveData
        LiveData<PagingData<TabStateArchivedEntity>> rawPagingData = PagingLiveData.getLiveData(pager);

        // 2. Transform the data to insert separators using our internal executor
        // We map the PagingData to a version that includes Headers (Object)
        mArchiveData = PagingLiveData.cachedIn(
                androidx.lifecycle.Transformations.map(rawPagingData, pagingData -> {
                    mDateOrganizer.reset(); // Reset organizer state for each new page generation
                    return PagingDataTransforms.insertSeparators(
                            pagingData,
                            mExecutor,
                            this::createSeparator
                    );
                }),
                viewModelScope
        );
    }

    /**
     * Logic for creating date-based separators.
     * This runs on the background thread provided by mExecutor.
     */
    @Nullable
    private Object createSeparator(@Nullable TabStateArchivedEntity before,
                                   @Nullable TabStateArchivedEntity after) {
        if (after == null) return null;

        String title = null;
        long date = after.getCreationDate();

        if (mDateOrganizer.isToday(date)) title = mAppContext.getString(R.string.interval_today);
        else if (mDateOrganizer.isYesterday(date)) title = mAppContext.getString(R.string.interval_yesterday);
        else if (mDateOrganizer.isSevenDaysRange(date)) title = mAppContext.getString(R.string.interval_week);
        else if (mDateOrganizer.isThirtyDaysRange(date)) title = mAppContext.getString(R.string.interval_month);
        else if (mDateOrganizer.isOlder(date)) title = mAppContext.getString(R.string.interval_older);

        // Only return a separator if the date range has changed
        // (DateOrganizer handles the logic of "is this the first time we see this range")
        if (title != null) {
            TabStateHeaderArchivedEntity separator = new TabStateHeaderArchivedEntity();
            separator.setId(title.hashCode());
            separator.setTitle(title);
            return separator;
        }
        return null;
    }

    public LiveData<PagingData<Object>> getTabArchive() {
        return mArchiveData;
    }

    public void deleteAll() {
        mTabStateArchivedRepository.deleteAll();
    }

    public void delete(TabStateArchivedEntity tabStateArchivedEntity) {
        mTabStateArchivedRepository.delete(tabStateArchivedEntity);
    }

    @Override
    protected void onCleared() {
        super.onCleared();
        // Clean up the background thread to prevent leaks
        if (mExecutor != null) {
            mExecutor.shutdown();
        }
    }
}
