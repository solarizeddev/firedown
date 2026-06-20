package com.solarized.firedown.data.repository;

import com.solarized.firedown.data.dao.MostVisitedBlockDao;
import com.solarized.firedown.data.di.Qualifiers;
import com.solarized.firedown.data.entity.MostVisitedBlockEntity;

import java.util.List;
import java.util.concurrent.Executor;

import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * The most-visited "Top Sites blocklist" (Chromium/Brave model): blocking a tile
 * HIDES the site from the strip without touching history. The sync methods are
 * for callers already on a background thread (AutoCompleteSearch.mostVisited /
 * the ViewModel's block-then-reload), so the reload sees the just-written block.
 */
@Singleton
public class MostVisitedBlockRepository {

    private final MostVisitedBlockDao mDao;
    private final Executor mDiskExecutor;

    @Inject
    public MostVisitedBlockRepository(MostVisitedBlockDao dao, @Qualifiers.DiskIO Executor diskExecutor) {
        mDao = dao;
        mDiskExecutor = diskExecutor;
    }

    /** Block (hide) a URL — synchronous; call off the main thread. */
    public void blockSync(String url) {
        if (url == null || url.isEmpty()) return;
        mDao.insert(new MostVisitedBlockEntity(url, System.currentTimeMillis()));
    }

    /** All blocked URLs — synchronous; call off the main thread. */
    public List<String> getBlockedUrlsSync() {
        return mDao.getAllUrls();
    }

    /** Un-hide a single URL (async). */
    public void unblock(String url) {
        mDiskExecutor.execute(() -> mDao.delete(url));
    }

    /** Clear the whole blocklist (async) — e.g. when history is cleared. */
    public void clear() {
        mDiskExecutor.execute(mDao::deleteAll);
    }
}
