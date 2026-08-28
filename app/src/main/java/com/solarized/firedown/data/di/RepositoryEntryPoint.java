package com.solarized.firedown.data.di;

import android.content.SharedPreferences;

import com.solarized.firedown.data.repository.BrowserDownloadRepository;
import com.solarized.firedown.data.repository.GeckoStateDataRepository;
import com.solarized.firedown.data.repository.WebBookmarkDataRepository;
import com.solarized.firedown.data.repository.WebHistoryDataRepository;
import com.solarized.firedown.geckoview.GeckoRuntimeHelper;
import com.solarized.firedown.geckoview.GeckoUblockHelper;

import dagger.hilt.InstallIn;
import dagger.hilt.EntryPoint;
import dagger.hilt.components.SingletonComponent;

@EntryPoint
@InstallIn(SingletonComponent.class)
public interface RepositoryEntryPoint {
    WebHistoryDataRepository getWebHistoryRepository();

    WebBookmarkDataRepository getWebBookmarkDataRepository();

    SharedPreferences getSharedPreferences();

    GeckoRuntimeHelper getGeckoRuntimeHelper();

    // Exposed for UblockBridgeLiveTest (androidTest) — same singleton the
    // runtime helper injects, so the test observes the real LiveData streams.
    GeckoUblockHelper getGeckoUblockHelper();

    // Exposed for CaptureLiveTest (androidTest) — the same singleton the
    // capture pipeline (GeckoInspectTask) writes into, so the test observes
    // real end-to-end captures from real page loads.
    BrowserDownloadRepository getBrowserDownloadRepository();

    GeckoStateDataRepository getGeckoStateDataRepository();
}
