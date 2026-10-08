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
import okhttp3.Request;
import okhttp3.Response;

/**
 * What happens when a page reports its favicon ({@code GeckoRuntimeHelper}'s
 * icons message): the icon URL is written to the page's history and bookmark
 * rows, and the icon BYTES are refreshed in the {@link FaviconStore} when the
 * stored copy is missing or stale — the Firefox/Chrome model, where a visit
 * is what refreshes an icon.
 *
 * <p>The resolution the history row keeps (its "keep the higher-res icon"
 * gate) is the page's declared size when it declared one, else an estimate
 * from the stored icon's byte length. That estimate used to come from a
 * separate HEAD request per icon; the store's own fetch replaces it, so an
 * icon costs one request, and an icon that is not stored (a private tab's)
 * costs none.
 */
@Singleton
public class IconsRepository {

    private static final String TAG = IconsRepository.class.getSimpleName();
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
     * icon. {@code declaredResolution} is the page's declared pixel area, or
     * {@code 0} when it declared none. {@code storeIcon} is true only when the
     * reporting session is a REGULAR tab — the favicon store must never hold a
     * private visit's icon, and a private tab's icon is never fetched here.
     */
    public void updateIcon(String pageUrl, String iconUrl, int declaredResolution, boolean storeIcon) {
        if (!storeIcon || !FaviconFetch.isFetchable(iconUrl)) {
            syncToDatabases(pageUrl, iconUrl, Math.max(declaredResolution, 0));
            return;
        }
        // The staleness check stats a file, so it runs on the disk executor;
        // the fetch itself is OkHttp's async call.
        mDiskExecutor.execute(() -> refreshStoredIcon(pageUrl, iconUrl, declaredResolution));
    }

    /**
     * Visit-driven refresh of the favicon store: refetch when the stored copy
     * is missing or older than {@link FaviconStore#REFRESH_MS}. A failed fetch
     * or a non-image answer keeps the stored bytes (FaviconStore.store refuses
     * them), so an icon the site stopped serving keeps showing. Single-flight
     * per icon URL. Whatever the outcome, the history/bookmark rows get the
     * icon URL with the best resolution known at that point.
     */
    private void refreshStoredIcon(String pageUrl, String iconUrl, int declaredResolution) {
        long now = System.currentTimeMillis();
        if (!mFaviconStore.needsRefresh(iconUrl, now) || !mFaviconStore.beginRefresh(iconUrl)) {
            syncToDatabases(pageUrl, iconUrl, resolutionFor(declaredResolution, storedLength(iconUrl)));
            return;
        }
        Call call;
        try {
            call = mOkHttpClient.newCall(FaviconFetch.request(iconUrl, pageUrl));
        } catch (IllegalArgumentException e) {
            mFaviconStore.endRefresh(iconUrl);
            syncToDatabases(pageUrl, iconUrl, resolutionFor(declaredResolution, storedLength(iconUrl)));
            return;
        }
        call.enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call c, @NonNull IOException e) {
                mFaviconStore.endRefresh(iconUrl);
                syncToDatabases(pageUrl, iconUrl, resolutionFor(declaredResolution, storedLength(iconUrl)));
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
                syncToDatabases(pageUrl, iconUrl, resolutionFor(declaredResolution, storedLength(iconUrl)));
            }
        });
    }

    /** The stored icon's byte length, 0 when none. */
    private long storedLength(String iconUrl) {
        return mFaviconStore.fileFor(iconUrl).length();
    }

    private static int resolutionFor(int declaredResolution, long byteLength) {
        if (declaredResolution > 0) {
            return declaredResolution;
        }
        return estimateResolution(byteLength);
    }

    private void syncToDatabases(String url, String iconUrl, int resolution) {
        // History: ONE conditional UPDATE. The old read-then-write
        // (getResolution + unconditional updateIconData) did two table scans per
        // icon signal AND rewrote an unchanged icon on every revisit, firing
        // Room invalidation that requeried the history list for nothing. The
        // resolution gate (keep the higher-res icon) and the no-op guard now live
        // in the WHERE clause, so a redundant signal does no write at all.
        mDiskExecutor.execute(() -> mHistoryDb.webHistoryDao().updateIconData(url, iconUrl, resolution));
        // Update Bookmark if one exists for this URL. The repository
        // short-circuits when the URL isn't bookmarked, so this is
        // cheap on the common path. Stored without a resolution column
        // — bookmarks always take the newest icon since there's no
        // higher-res preference to defend.
        mBookmarkRepository.updateIcon(url, iconUrl);
    }

    /** A pixel-area proxy from an icon's byte length, for an icon whose page
     *  declared no size: the history row's higher-res gate needs SOME order. */
    private static int estimateResolution(long bytes) {
        if (bytes <= 0) return 0;
        if (bytes > 40000) return 512;
        if (bytes > 15000) return 192;
        if (bytes > 5000) return 96;
        return 32;
    }
}
