package com.solarized.firedown.data.repository;

import androidx.lifecycle.LiveData;
import androidx.paging.PagingSource;

import com.solarized.firedown.data.DataCallback;
import com.solarized.firedown.data.dao.WebBookmarkDao;
import com.solarized.firedown.data.di.Qualifiers;
import com.solarized.firedown.data.entity.WebBookmarkEntity;
import com.solarized.firedown.geckoview.GeckoState;
import com.solarized.firedown.utils.Utils;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executor;
import javax.inject.Inject;
import javax.inject.Singleton;

@Singleton
public class WebBookmarkDataRepository {

    private final WebBookmarkDao mWebBookmarkDao;
    // Read on the caller thread (getCount, contains) and written on a
    // mix of caller thread (add/delete/deleteAll) and disk executor
    // (initial population from DAO). HashSet is not thread-safe, and
    // an unsynchronized add() racing with the init addAll() leaks
    // bookmarks into "saved but absent from sync set" state. Wrap in
    // a synchronized view — every operation we use is single-call
    // (add/remove/contains/clear/size), so no need for compound
    // synchronized blocks at call sites.
    private final Set<Integer> mSyncEntities =
            Collections.synchronizedSet(new HashSet<>());

    private final Executor mDiskExecutor;

    private final Executor mMainExecutor;

    @Inject
    public WebBookmarkDataRepository(WebBookmarkDao webBookmarkDao, @Qualifiers.DiskIO Executor diskExecutor, @Qualifiers.MainThread Executor mainExecutor) {
        this.mWebBookmarkDao = webBookmarkDao;
        this.mDiskExecutor = diskExecutor;
        this.mMainExecutor = mainExecutor;
        // Initialize the sync set on a background thread
        mDiskExecutor.execute(() -> {
            List<Integer> ids = mWebBookmarkDao.getAllIds();
            if (ids != null) {
                mSyncEntities.addAll(ids);
            }
        });
    }

    public int getCount() { return mSyncEntities.size(); }

    public LiveData<List<WebBookmarkEntity>> getWebBookmark(int limit) {
        return mWebBookmarkDao.getBookmark(limit);
    }

    public PagingSource<Integer, WebBookmarkEntity> get() {
        return mWebBookmarkDao.getBookmarks();
    }

    public PagingSource<Integer, WebBookmarkEntity> getSearch(String search) {
        return mWebBookmarkDao.search(search);
    }

    public void add(GeckoState geckoState) {
        if (geckoState == null) return;
        WebBookmarkEntity entity = new WebBookmarkEntity();
        entity.setFileDate(System.currentTimeMillis());
        entity.setFileTitle(Utils.capitalize(geckoState.getEntityTitle()));
        entity.setFileUrl(geckoState.getEntityUri());
        entity.setId(geckoState.getEntityUri().hashCode());
        entity.setFileIcon(geckoState.getEntityIcon());
        add(entity);
    }

    public boolean contains(GeckoState geckoState) {
        if (geckoState == null)
            return false;
        return mSyncEntities.contains(geckoState.getEntityUri().hashCode());
    }

    public void add(WebBookmarkEntity web) {
        if (web != null) {
            mSyncEntities.add(web.getId());
            mDiskExecutor.execute(() -> mWebBookmarkDao.insert(web));

        }
    }

    public void delete(WebBookmarkEntity web) {
        if (web != null) {
            mSyncEntities.remove(web.getId());
            mDiskExecutor.execute(() -> mWebBookmarkDao.delete(web));
        }
    }

    public void delete(int id) {
        mSyncEntities.remove(id);
        mDiskExecutor.execute(() -> mWebBookmarkDao.deleteById(id));
    }

    public void deleteAll() {
        mSyncEntities.clear();
        mDiskExecutor.execute(mWebBookmarkDao::deleteAll);
    }

    public void getId(int id, DataCallback<WebBookmarkEntity> callback){
        mDiskExecutor.execute(() -> {
            try {
                WebBookmarkEntity result = mWebBookmarkDao.getId(id); // Synchronous DAO call
                // Switch back to Main Thread for the callback
                mMainExecutor.execute(() -> callback.onComplete(result));
            } catch (Exception e) {
                mMainExecutor.execute(() -> callback.onError(e));
            }

        });
    }
}