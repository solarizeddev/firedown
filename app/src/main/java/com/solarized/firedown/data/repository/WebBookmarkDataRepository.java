package com.solarized.firedown.data.repository;

import android.text.TextUtils;

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
import java.util.Locale;
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
            // One-shot migration: rows persisted before URL normalization
            // landed have uids hashed from the raw user-typed string
            // (case-sensitive, trailing slash sensitive), which mismatch
            // the post-redirect URIs GeckoSession reports. Re-key any
            // row whose stored URL doesn't already match the normalized
            // hash so contains() / getId() resolve consistently.
            List<WebBookmarkEntity> all = mWebBookmarkDao.getAllRaw();
            if (all != null) {
                for (WebBookmarkEntity entity : all) {
                    int normalizedId = bookmarkIdFor(entity.getUrl());
                    if (entity.getId() != normalizedId) {
                        mWebBookmarkDao.deleteById(entity.getId());
                        entity.setId(normalizedId);
                        mWebBookmarkDao.insert(entity);
                    }
                }
            }
            List<Integer> ids = mWebBookmarkDao.getAllIds();
            if (ids != null) {
                mSyncEntities.addAll(ids);
            }
        });
    }

    /**
     * Canonical bookmark id for a URL.
     *
     * <p>Identity is the hash of a normalized form so that user-typed
     * variants ("Https://Example.com", "https://example.com/") and the
     * post-redirect URL the GeckoSession ends up reporting all resolve
     * to the same bookmark. Normalization:</p>
     * <ul>
     *   <li>strip a single trailing "/" so root-path URLs don't double-up
     *   <li>lowercase the scheme + host portion (everything before the
     *       first "/" after "://"), leaving the path / query case-sensitive
     *       since servers can — and some do — treat path segments as
     *       case-sensitive
     * </ul>
     *
     * <p>Null/empty input maps to 0 so the call site doesn't have to
     * branch.</p>
     */
    public static int bookmarkIdFor(String url) {
        return normalize(url).hashCode();
    }

    private static String normalize(String url) {
        if (TextUtils.isEmpty(url)) return "";
        String trimmed = url.endsWith("/") && url.length() > 1
                ? url.substring(0, url.length() - 1)
                : url;
        int schemeEnd = trimmed.indexOf("://");
        if (schemeEnd == -1) {
            return trimmed.toLowerCase(Locale.ROOT);
        }
        int pathStart = trimmed.indexOf('/', schemeEnd + 3);
        if (pathStart == -1) {
            return trimmed.toLowerCase(Locale.ROOT);
        }
        return trimmed.substring(0, pathStart).toLowerCase(Locale.ROOT)
                + trimmed.substring(pathStart);
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
        String uri = geckoState.getEntityUri();
        WebBookmarkEntity entity = new WebBookmarkEntity();
        entity.setFileDate(System.currentTimeMillis());
        entity.setFileTitle(Utils.capitalize(geckoState.getEntityTitle()));
        entity.setFileUrl(uri);
        entity.setId(bookmarkIdFor(uri));
        entity.setFileIcon(geckoState.getEntityIcon());
        add(entity);
    }

    public boolean contains(GeckoState geckoState) {
        if (geckoState == null)
            return false;
        return mSyncEntities.contains(bookmarkIdFor(geckoState.getEntityUri()));
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

    /**
     * Refreshes the stored favicon for whichever bookmark matches the
     * canonical id of this URL. Called by IconsRepository when
     * GeckoRuntimeHelper signals a new icon: the persisted history row
     * always gets updated, the bookmark row only if the URL is
     * actually bookmarked (no-op otherwise). The sync-set check skips
     * the disk hop for the common "icon arrived for a URL we don't
     * track" case.
     */
    public void updateIcon(String url, String iconUrl) {
        if (TextUtils.isEmpty(url) || TextUtils.isEmpty(iconUrl)) return;
        int id = bookmarkIdFor(url);
        if (!mSyncEntities.contains(id)) return;
        mDiskExecutor.execute(() -> mWebBookmarkDao.updateIcon(id, iconUrl));
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