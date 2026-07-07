package com.solarized.firedown.data.models;

import android.content.Context;
import androidx.annotation.Nullable;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.Transformations;
import androidx.lifecycle.ViewModel;
import androidx.lifecycle.ViewModelKt;
import androidx.paging.Pager;
import androidx.paging.PagingConfig;
import androidx.paging.PagingData;
import androidx.paging.PagingDataTransforms;
import androidx.paging.PagingLiveData;

import com.solarized.firedown.Preferences;
import com.solarized.firedown.data.entity.TabStateArchivedEntity;
import com.solarized.firedown.data.entity.TabStateHeaderArchivedEntity;
import com.solarized.firedown.data.repository.TabStateArchivedRepository;
import com.solarized.firedown.utils.DateOrganizer;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

import javax.inject.Inject;

import dagger.hilt.android.lifecycle.HiltViewModel;
import dagger.hilt.android.qualifiers.ApplicationContext;
import kotlinx.coroutines.CoroutineScope;

@HiltViewModel
public class TabsArchiveViewModel extends ViewModel {

    private final TabStateArchivedRepository mTabStateArchivedRepository;
    private final LiveData<PagingData<Object>> mArchiveData;
    private final ExecutorService mExecutor;
    private final Context mAppContext;

    @Inject
    public TabsArchiveViewModel(@ApplicationContext Context context, TabStateArchivedRepository repository) {

        this.mTabStateArchivedRepository = repository;
        this.mAppContext = context;
        this.mExecutor = Executors.newSingleThreadExecutor();

        PagingConfig pagingConfig = new PagingConfig(Preferences.LIST_LIMIT);
        CoroutineScope viewModelScope = ViewModelKt.getViewModelScope(this);

        Pager<Integer, TabStateArchivedEntity> pager = new Pager<>(
                pagingConfig,
                mTabStateArchivedRepository::getTabsArchive
        );

        // Mirrors WebHistoryViewModel exactly — the proven-working pattern
        // for a paginated list with date-range separators:
        //
        //   1. raw Room paging stream,
        //   2. map entities to Object, THEN insert separators,
        //   3. cachedIn once, on the final stream.
        //
        // The previous version ran insertSeparators directly on
        // PagingData<TabStateArchivedEntity> and, critically, drove the
        // separator generator from a SHARED, MUTABLE DateOrganizer field:
        // reset() on the main thread inside this map lambda while
        // createSeparator read/mutated its isToday/isWeek/... flags on the
        // executor thread. insertSeparators invokes the generator
        // repeatedly and out of order across page + diff events, so a
        // stateful generator is both racy and order-dependent — the list
        // never settled into a NotLoading refresh, leaving the screen stuck
        // on the LCEE spinner. A FRESH DateOrganizer per stream + a pure
        // (before, after) → category-boundary generator fixes it, matching
        // history.
        LiveData<PagingData<TabStateArchivedEntity>> sourceLiveData =
                PagingLiveData.getLiveData(pager);

        LiveData<PagingData<Object>> transformedLiveData =
                Transformations.map(sourceLiveData, pagingData -> {
                    PagingData<Object> objPagingData = PagingDataTransforms.map(
                            pagingData, mExecutor, entity -> (Object) entity);
                    return applySeparators(objPagingData);
                });

        mArchiveData = PagingLiveData.cachedIn(transformedLiveData, viewModelScope);
    }

    /**
     * Inserts a date-range header ahead of the first row of each new range.
     * A FRESH {@link DateOrganizer} per call keeps the generator a pure
     * function of {@code (before, after)} — see the constructor note on why
     * the old shared-field organizer wedged the paging refresh.
     */
    private PagingData<Object> applySeparators(PagingData<Object> pagingData) {
        DateOrganizer dateOrganizer = new DateOrganizer();

        return PagingDataTransforms.insertSeparators(pagingData, mExecutor,
                (@Nullable Object before, @Nullable Object after) -> {

                    if (after instanceof TabStateArchivedEntity afterTab) {
                        int afterCategory = dateOrganizer.getCategory(afterTab.getCreationDate());

                        if (before instanceof TabStateArchivedEntity beforeTab) {
                            int beforeCategory = dateOrganizer.getCategory(beforeTab.getCreationDate());
                            if (beforeCategory == afterCategory) {
                                return null;
                            }
                        }

                        int resId = dateOrganizer.getResIdForCategory(afterCategory);
                        if (resId != 0) {
                            TabStateHeaderArchivedEntity separator = new TabStateHeaderArchivedEntity();
                            separator.setId(resId);
                            separator.setTitle(mAppContext.getString(resId));
                            return separator;
                        }
                    }
                    return null;
                });
    }

    public LiveData<PagingData<Object>> getTabArchive() {
        return mArchiveData;
    }

    /**
     * Loads an archived tab's session state (excluded from the list rows —
     * see the DAO) and delivers it on the main thread. Null means no stored
     * state; the caller falls back to loading the tab's URI fresh.
     */
    public void loadSessionState(int id, Consumer<String> mainThreadCallback) {
        mTabStateArchivedRepository.getSessionState(id, mainThreadCallback);
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
