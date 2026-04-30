package com.solarized.firedown.data.repository;

import android.webkit.URLUtil;
import androidx.paging.PagingSource;

import com.solarized.firedown.data.dao.TabStateArchivedDao;
import com.solarized.firedown.data.di.Qualifiers;
import com.solarized.firedown.data.entity.GeckoStateEntity;
import com.solarized.firedown.data.entity.TabStateArchivedEntity;

import java.util.concurrent.Executor;

import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Repository for managing archived tabs.
 * Refactored for Hilt with direct DAO injection.
 */
@Singleton
public class TabStateArchivedRepository {

    private final TabStateArchivedDao mTabStateDao;

    private final Executor mDiskExecutor;

    @Inject
    public TabStateArchivedRepository(TabStateArchivedDao tabStateDao, @Qualifiers.DiskIO Executor diskExecutor) {
        this.mTabStateDao = tabStateDao;
        mDiskExecutor = diskExecutor;
    }

    /**
     * Returns a PagingSource for the UI to consume via a PagingData stream.
     */
    public PagingSource<Integer, TabStateArchivedEntity> getTabsArchive() {
        return mTabStateDao.getArchive();
    }

    /**
     * Maps a GeckoStateEntity to an Archive entity and saves it asynchronously.
     */
    public void addAsync(GeckoStateEntity geckoStateEntity) {
        if (shouldSkip(geckoStateEntity)) {
            return;
        }

        TabStateArchivedEntity archivedEntity = mapToArchivedEntity(geckoStateEntity);
        mTabStateDao.insert(archivedEntity);
    }

    /**
     * Maps a GeckoStateEntity to an Archive entity and saves it synchronously.
     * Useful for calls within background Tasks or Workers.
     */
    public void addSync(GeckoStateEntity geckoStateEntity) {
        if (shouldSkip(geckoStateEntity)) {
            return;
        }

        // Additional check for content URLs for sync operations
        if (URLUtil.isContentUrl(geckoStateEntity.getUri())) {
            return;
        }

        TabStateArchivedEntity archivedEntity = mapToArchivedEntity(geckoStateEntity);
        mTabStateDao.insertSync(archivedEntity);
    }

    /**
     * Inserts a raw archived entity asynchronously.
     */
    public void addAsync(TabStateArchivedEntity tabStateArchivedEntity) {
        mTabStateDao.insert(tabStateArchivedEntity);
    }

    /**
     * Inserts a raw archived entity synchronously.
     */
    public void addSync(TabStateArchivedEntity tabStateArchivedEntity) {
        mTabStateDao.insertSync(tabStateArchivedEntity);
    }

    /**
     * Deletes a specific archived tab.
     */
    public void delete(TabStateArchivedEntity tabStateArchivedEntity) {
        mDiskExecutor.execute(() -> mTabStateDao.delete(tabStateArchivedEntity));

    }

    /**
     * Deletes an archived tab by its unique ID.
     */
    public void delete(int id) {
        mDiskExecutor.execute(() -> mTabStateDao.deleteById(id));

    }

    /**
     * Clears the entire archive.
     */
    public void deleteAll() {
        mDiskExecutor.execute(mTabStateDao::deleteAll);

    }

    // --- Private Helpers ---

    private boolean shouldSkip(GeckoStateEntity entity) {
        return entity == null || entity.isHome() || URLUtil.isAboutUrl(entity.getUri());
    }

    private TabStateArchivedEntity mapToArchivedEntity(GeckoStateEntity geckoStateEntity) {
        TabStateArchivedEntity archived = new TabStateArchivedEntity();
        archived.setId(geckoStateEntity.getId()); // uid in database
        archived.setTitle(geckoStateEntity.getTitle());
        archived.setUri(geckoStateEntity.getUri());
        archived.setCreationDate(geckoStateEntity.getCreationDate());
        archived.setSessionState(geckoStateEntity.getSessionState());
        archived.setIcon(geckoStateEntity.getIcon());
        return archived;
    }
}