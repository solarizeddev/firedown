package com.solarized.firedown.data.repository;


import androidx.annotation.NonNull;

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

@Singleton
public class IconsRepository {

    private static final String TAG = IconsRepository.class.getSimpleName();
    private final WebHistoryDatabase mHistoryDb;
    private final WebBookmarkDataRepository mBookmarkRepository;
    private final OkHttpClient mOkHttpClient;
    private final Executor mDiskExecutor;

    @Inject
    public IconsRepository(
            WebHistoryDatabase historyDb,
            WebBookmarkDataRepository bookmarkRepository,
            OkHttpClient okHttpClient,
            @Qualifiers.DiskIO Executor diskExecutor
    ) {
        this.mHistoryDb = historyDb;
        this.mBookmarkRepository = bookmarkRepository;
        this.mOkHttpClient = okHttpClient;
        this.mDiskExecutor = diskExecutor;
    }

    /**
     * Primary entry point called by GeckoRuntimeHelper or Parsers
     */
    public void updateIcon(String url, String iconUrl, int resolution) {
        if (resolution <= 0) {
            fetchMetadataAndSync(url, iconUrl);
        } else {
            syncToDatabases(url, iconUrl, resolution);
        }
    }

    private void fetchMetadataAndSync(String url, String iconUrl) {
        Request request = new Request.Builder().url(iconUrl).head().build();

        mOkHttpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                syncToDatabases(url, iconUrl, 0); // Fallback to 0 on error
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) {
                // try-with-resources so the response body is always closed,
                // even if estimateResolution / parseLong throws (a malformed
                // Content-Length is a NumberFormatException → previously
                // OkHttp's dispatcher swallowed it, the syncToDatabases call
                // below was skipped, AND response.close() was never reached,
                // leaking the connection back into the pool until eviction).
                try (Response r = response) {
                    int estimatedRes = 0;
                    if (r.isSuccessful()) {
                        String length = r.header("Content-Length");
                        if (length != null) {
                            try {
                                estimatedRes = estimateResolution(Long.parseLong(length));
                            } catch (NumberFormatException ignored) {
                                // Fall through with estimatedRes = 0 — the
                                // syncToDatabases logic treats 0 as "unknown"
                                // and stores the icon without a resolution hint.
                            }
                        }
                    }
                    syncToDatabases(url, iconUrl, estimatedRes);
                }
            }
        });
    }

    private void syncToDatabases(String url, String iconUrl, int resolution) {
        mDiskExecutor.execute(() -> {
            // Update History
            int historyRes = mHistoryDb.webHistoryDao().getResolution(url);
            if (resolution >= historyRes || historyRes <= 0) {
                mHistoryDb.webHistoryDao().updateIconData(url, iconUrl, resolution);
            }
        });
        // Update Bookmark if one exists for this URL. The repository
        // short-circuits when the URL isn't bookmarked, so this is
        // cheap on the common path. Stored without a resolution column
        // — bookmarks always take the newest icon since there's no
        // higher-res preference to defend.
        mBookmarkRepository.updateIcon(url, iconUrl);
    }


    private int estimateResolution(long bytes) {
        if (bytes > 40000) return 512;
        if (bytes > 15000) return 192;
        if (bytes > 5000) return 96;
        return 32;
    }
}