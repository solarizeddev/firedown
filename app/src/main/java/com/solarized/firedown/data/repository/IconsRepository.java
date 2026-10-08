package com.solarized.firedown.data.repository;


import androidx.annotation.NonNull;

import com.solarized.firedown.data.FaviconFetch;
import com.solarized.firedown.data.FaviconStore;
import com.solarized.firedown.data.WebHistoryDatabase;
import com.solarized.firedown.data.di.Qualifiers;

import java.io.IOException;
import java.util.concurrent.Executor;

import javax.inject.Inject;
import javax.inject.Singleton;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Response;

/**
 * What happens when a page reports its favicon ({@code GeckoRuntimeHelper}'s
 * icons message): the icon URL is written to the page's history and bookmark
 * rows (newest wins, on both), and the icon BYTES are refreshed in the
 * {@link FaviconStore} when the stored copy is missing or stale — the
 * Firefox/Chrome model, where a visit is what refreshes an icon.
 */
@Singleton
public class IconsRepository {

    private final WebHistoryDatabase mHistoryDb;
    private final WebBookmarkDataRepository mBookmarkRepository;
    private final OkHttpClient mOkHttpClient;
    private final Executor mDiskExecutor;
    private final FaviconStore mFaviconStore;

    @Inject
    public IconsRepository(
            WebHistoryDatabase historyDb,
            WebBookmarkDataRepository bookmarkRepository,
            OkHttpClient okHttpClient,
            @Qualifiers.DiskIO Executor diskExecutor,
            FaviconStore faviconStore
    ) {
        this.mHistoryDb = historyDb;
        this.mBookmarkRepository = bookmarkRepository;
        this.mOkHttpClient = okHttpClient;
        this.mDiskExecutor = diskExecutor;
        this.mFaviconStore = faviconStore;
    }

    /**
     * Primary entry point, called by GeckoRuntimeHelper when a page reports its
     * icon. {@code storeIcon} is true only when the reporting session is a
     * REGULAR tab — the favicon store must never hold a private visit's icon,
     * and a private tab's icon is never fetched here.
     */
    public void updateIcon(String pageUrl, String iconUrl, boolean storeIcon) {
        syncToDatabases(pageUrl, iconUrl);
        if (storeIcon && FaviconFetch.isFetchable(iconUrl)) {
            // The staleness check stats a file, so it runs on the disk
            // executor; the fetch itself is OkHttp's async call.
            mDiskExecutor.execute(() -> refreshStoredIcon(pageUrl, iconUrl));
        }
    }

    /**
     * Visit-driven refresh of the favicon store: refetch when the stored copy
     * is missing or older than {@link FaviconStore#REFRESH_MS}. A failed fetch
     * or a non-image answer keeps the stored bytes (FaviconStore.store refuses
     * them), so an icon the site stopped serving keeps showing. Single-flight
     * per icon URL.
     */
    private void refreshStoredIcon(String pageUrl, String iconUrl) {
        long now = System.currentTimeMillis();
        if (!mFaviconStore.needsRefresh(iconUrl, now) || !mFaviconStore.beginRefresh(iconUrl)) {
            return;
        }
        Call call;
        try {
            call = mOkHttpClient.newCall(FaviconFetch.request(iconUrl, pageUrl));
        } catch (IllegalArgumentException e) {
            mFaviconStore.endRefresh(iconUrl);
            return;
        }
        call.enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call c, @NonNull IOException e) {
                mFaviconStore.endRefresh(iconUrl);
            }

            @Override
            public void onResponse(@NonNull Call c, @NonNull Response response) {
                try (Response r = response) {
                    byte[] bytes = FaviconFetch.readBounded(r);
                    if (bytes != null) {
                        // store() refuses a non-image or oversized body itself.
                        mFaviconStore.store(iconUrl, bytes, System.currentTimeMillis());
                    }
                } catch (IOException ignored) {
                    // A body cut off mid-read: keep the stored copy.
                } finally {
                    mFaviconStore.endRefresh(iconUrl);
                }
            }
        });
    }

    private void syncToDatabases(String url, String iconUrl) {
        // History: ONE conditional UPDATE, newest icon wins, with a no-op guard
        // in the WHERE clause — an unconditional UPDATE would fire Room's
        // invalidation on every revisit and requery the history list for
        // nothing.
        mDiskExecutor.execute(() -> mHistoryDb.webHistoryDao().updateIcon(url, iconUrl));
        // Update Bookmark if one exists for this URL. The repository
        // short-circuits when the URL isn't bookmarked, so this is
        // cheap on the common path.
        mBookmarkRepository.updateIcon(url, iconUrl);
    }
}
