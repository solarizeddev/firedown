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

    /** Block (hide) a host — synchronous; call off the main thread. */
    public void blockSync(String host) {
        if (host == null || host.isEmpty()) return;
        mDao.insert(new MostVisitedBlockEntity(host, System.currentTimeMillis()));
    }

    /** All blocked hosts — synchronous; call off the main thread. */
    public List<String> getBlockedHostsSync() {
        return mDao.getAllHosts();
    }

    /** Un-hide a single host (async). */
    public void unblock(String host) {
        mDiskExecutor.execute(() -> mDao.delete(host));
    }

    /** Clear the whole blocklist (async) — e.g. when history is cleared. */
    public void clear() {
        mDiskExecutor.execute(mDao::deleteAll);
    }
}
