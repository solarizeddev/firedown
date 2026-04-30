package com.solarized.firedown.data.repository;


import androidx.annotation.NonNull;

import com.solarized.firedown.data.ShortCutDatabase;
import com.solarized.firedown.data.WebHistoryDatabase;
import com.solarized.firedown.data.di.Qualifiers;
import com.solarized.firedown.utils.WebUtils;

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
    private final ShortCutDatabase mShortCutDb;
    private final OkHttpClient mOkHttpClient;
    private final Executor mDiskExecutor;

    @Inject
    public IconsRepository(
            WebHistoryDatabase historyDb,
            ShortCutDatabase shortCutDb,
            OkHttpClient okHttpClient,
            @Qualifiers.DiskIO Executor diskExecutor
    ) {
        this.mHistoryDb = historyDb;
        this.mShortCutDb = shortCutDb;
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
                int estimatedRes = 0;
                if (response.isSuccessful()) {
                    String length = response.header("Content-Length");
                    if (length != null) {
                        estimatedRes = estimateResolution(Long.parseLong(length));
                    }
                }
                syncToDatabases(url, iconUrl, estimatedRes);
                response.close();
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

            // Update Shortcuts
            int shortCutRes = mShortCutDb.shortCutsDao().getResolution(url);
//            Log.d(TAG, "syncToDatabases shortcut: " + shortCutRes + " url: " + url + " resolution: " +resolution + " icon: " + iconUrl);
//            List<ShortCutsEntity> shortCutsEntityList = mShortCutDb.shortCutsDao().getAllRaw();
//            for(ShortCutsEntity entity : shortCutsEntityList){
//                Log.d(TAG, "syncToDatabases raw shortcut: " + entity.getFileIconResolution() + " url: " + entity.getUrl() + " domain: " + entity.getDomain() + " icon: " + entity.getIcon());
//            }
            if (resolution >= shortCutRes || shortCutRes <= 0) {
                String domain = WebUtils.getDomainName(url);
                mShortCutDb.shortCutsDao().updateIconData(domain, iconUrl, resolution);
            }
        });
    }


    private int estimateResolution(long bytes) {
        if (bytes > 40000) return 512;
        if (bytes > 15000) return 192;
        if (bytes > 5000) return 96;
        return 32;
    }
}