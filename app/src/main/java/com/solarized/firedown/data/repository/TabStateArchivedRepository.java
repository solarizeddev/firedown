package com.solarized.firedown.data.repository;

import android.webkit.URLUtil;
import androidx.lifecycle.LiveData;
import androidx.paging.PagingSource;

import com.solarized.firedown.data.SessionStateStore;
import com.solarized.firedown.data.dao.TabStateArchivedDao;
import com.solarized.firedown.data.di.Qualifiers;
import com.solarized.firedown.data.entity.GeckoStateEntity;
import com.solarized.firedown.data.entity.TabStateArchivedEntity;

import java.util.concurrent.Executor;
import java.util.function.Consumer;

import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Repository for managing archived tabs.
 * Refactored for Hilt with direct DAO injection.
 */
@Singleton
public class TabStateArchivedRepository {

    /**
     * Session-state read chunk, in SQLite characters. Worst case (4-byte
     * UTF-8) that's 1 MiB of window per chunk — comfortably under the ~2 MB
     * CursorWindow that a whole-blob single-row read can blow through.
     */
    private static final long SESSION_STATE_CHUNK_CHARS = 256 * 1024;

    private final TabStateArchivedDao mTabStateDao;

    private final Executor mDiskExecutor;
    private final Executor mMainExecutor;

    @Inject
    public TabStateArchivedRepository(TabStateArchivedDao tabStateDao,
                                      @Qualifiers.DiskIO Executor diskExecutor,
                                      @Qualifiers.MainThread Executor mainExecutor) {
        this.mTabStateDao = tabStateDao;
        mDiskExecutor = diskExecutor;
        mMainExecutor = mainExecutor;
    }

    /**
     * Returns a PagingSource for the UI to consume via a PagingData stream.
     * The rows carry a NULL session state (the blob is excluded from the
     * list query so an oversized one can't kill the paging load — see the
     * DAO); fetch it on demand with {@link #getSessionState}.
     */
    public PagingSource<Integer, TabStateArchivedEntity> getTabsArchive() {
        return mTabStateDao.getArchive();
    }

    /**
     * Fetches an archived tab's session state off-main and delivers it on
     * the main thread. Null when the row is gone or holds no state — the
     * caller should then fall back to a plain URI load.
     */
    public void getSessionState(int id, Consumer<String> mainThreadCallback) {
        mDiskExecutor.execute(() -> {
            String state = getSessionStateSync(id);
            mMainExecutor.execute(() -> mainThreadCallback.accept(state));
        });
    }

    /**
     * Reads the session state in bounded chunks ({@code substr}) instead of
     * one row read, so a blob past the CursorWindow limit is still fully
     * readable. SQLite {@code length()}/{@code substr} are both
     * character-addressed on TEXT, so the chunk boundaries line up exactly.
     */
    public String getSessionStateSync(int id) {
        Long length = mTabStateDao.getSessionStateLength(id);
        if (length == null || length <= 0) {
            return null;
        }
        StringBuilder builder = new StringBuilder();
        for (long start = 1; start <= length; start += SESSION_STATE_CHUNK_CHARS) {
            String chunk = mTabStateDao.getSessionStateChunk(id, start, SESSION_STATE_CHUNK_CHARS);
            if (chunk == null) {
                // Row deleted between the length read and this chunk.
                return null;
            }
            builder.append(chunk);
        }
        return builder.toString();
    }

    /**
     * Live count of tabs archived since {@code sinceMs}. Used to drive the
     * "X tabs archived in the last [interval]" banner — TabsFragment
     * computes sinceMs as {@code now - interval} where interval is the
     * user's auto-archive setting.
     */
    public LiveData<Integer> getArchivedSinceCountLive(long sinceMs) {
        return mTabStateDao.getCountSinceLive(sinceMs);
    }

    /**
     * Maps a GeckoStateEntity to an Archive entity and saves it synchronously.
     * Useful for calls within background Tasks or Workers.
     */
    public void addSync(GeckoStateEntity geckoStateEntity) {
        addSync(geckoStateEntity, System.currentTimeMillis());
    }

    /**
     * Maps a GeckoStateEntity to an Archive entity and saves it
     * synchronously, stamping the archive time as {@code archivedAtMs}.
     * Callers archiving multiple tabs in a batch should pass the same
     * timestamp so they all show up in the banner's recent-window
     * count together.
     */
    public void addSync(GeckoStateEntity geckoStateEntity, long archivedAtMs) {
        if (shouldSkip(geckoStateEntity)) {
            return;
        }

        // Additional check for content URLs for sync operations
        if (URLUtil.isContentUrl(geckoStateEntity.getUri())) {
            return;
        }

        TabStateArchivedEntity archivedEntity = mapToArchivedEntity(geckoStateEntity);
        archivedEntity.setArchivedAt(archivedAtMs);
        mTabStateDao.insertSync(archivedEntity);
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

    /**
     * Enforces the archive bounds SYNCHRONOUSLY (caller must already be
     * off-main — this runs from the auto-archive sweep, which is on the disk
     * executor / a background thread). Age purge first, then the count cap.
     * {@code maxCount <= 0} or {@code maxAgeMillis <= 0} skips that bound.
     * Returns the number of archived rows removed.
     */
    public int purgeSync(int maxCount, long maxAgeMillis) {
        int removed = 0;
        if (maxAgeMillis > 0) {
            long cutoff = System.currentTimeMillis() - maxAgeMillis;
            Integer aged = mTabStateDao.purgeOlderThan(cutoff);
            if (aged != null) {
                removed += aged;
            }
        }
        if (maxCount > 0) {
            Integer capped = mTabStateDao.trimToNewest(maxCount);
            if (capped != null) {
                removed += capped;
            }
        }
        return removed;
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
        // The archive must be SELF-CONTAINED: a restored, never-opened tab
        // carries only a SessionStateStore file ref (v3 sessions file), and
        // once the tab leaves the live list its state file becomes
        // unreferenced and is grace-pruned — so the string is INLINED into the
        // Room row here, at archive time (runs on the disk executor). This is
        // the invariant that keeps SessionStateStore.prune's referenced set
        // complete (the Chromium multi-reference-domain lesson,
        // crbug.com/40486025 class): the archive never references store files.
        // A missing/unreadable file degrades to "" — the archived tab restores
        // by URL, same per-file containment as everywhere else.
        String state = geckoStateEntity.getSessionState();
        if (state.isEmpty()) {
            state = SessionStateStore.read(geckoStateEntity.getSessionStateRef());
        }
        archived.setSessionState(state);
        archived.setIcon(geckoStateEntity.getIcon());
        return archived;
    }
}