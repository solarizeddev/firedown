package com.solarized.firedown.data.di;

import android.content.SharedPreferences;

import com.solarized.firedown.addons.AddonManager;
import com.solarized.firedown.addons.AmoCatalogClient;
import com.solarized.firedown.data.repository.BrowserDownloadRepository;
import com.solarized.firedown.data.repository.DownloadDataRepository;
import com.solarized.firedown.data.repository.GeckoStateDataRepository;
import com.solarized.firedown.data.repository.IncognitoStateRepository;
import com.solarized.firedown.data.repository.TaskRepository;
import com.solarized.firedown.data.repository.WebBookmarkDataRepository;
import com.solarized.firedown.data.repository.WebHistoryDataRepository;
import com.solarized.firedown.geckoview.GeckoComponents;
import com.solarized.firedown.geckoview.GeckoRuntimeHelper;
import com.solarized.firedown.geckoview.GeckoUblockHelper;
import com.solarized.firedown.geckoview.media.GeckoMediaController;
import com.solarized.firedown.p2pshare.P2pShareController;

import dagger.hilt.EntryPoint;
import dagger.hilt.InstallIn;
import dagger.hilt.components.SingletonComponent;

@EntryPoint
@InstallIn(SingletonComponent.class)
public interface RepositoryEntryPoint {
    WebHistoryDataRepository getWebHistoryRepository();

    WebBookmarkDataRepository getWebBookmarkDataRepository();

    SharedPreferences getSharedPreferences();

    GeckoRuntimeHelper getGeckoRuntimeHelper();

    // Exposed for the device tests — the same singleton the runtime helper
    // feeds, so a test observes the real blocked-request figures.
    GeckoUblockHelper getGeckoUblockHelper();

    // Exposed for CaptureLiveTest (androidTest) — the same singleton the
    // capture pipeline (GeckoInspectTask) writes into, so the test observes
    // real end-to-end captures from real page loads.
    BrowserDownloadRepository getBrowserDownloadRepository();

    GeckoStateDataRepository getGeckoStateDataRepository();

    // The page-download stream strategy finds a private tab's response here.
    IncognitoStateRepository getIncognitoStateRepository();
    // Exposed for DownloadEngineStressTest (androidTest) — the same singleton
    // the download service writes into, so the test reads the real rows the
    // real RunnableManager/DownloadEngine produced — and for the player, whose
    // activity is not a Hilt entry point, to find a video's subtitles.
    DownloadDataRepository getDownloadDataRepository();

    // Exposed for MediaNotificationTest (androidTest) — the real session
    // delegates and the controller they feed, so a page's MediaSession
    // metadata is checked through the app's own plumbing.
    GeckoComponents getGeckoComponents();

    GeckoMediaController getGeckoMediaController();

    // Exposed for TaskFlowDeviceTest (androidTest) — the running-task state
    // the Downloads bar follows, driven through the same singleton
    // TaskManager writes into.
    TaskRepository getTaskRepository();

    // Exposed for AddonsDeviceTest (androidTest) — the singletons the
    // runtime helper attaches to the real WebExtensionController.
    AddonManager getAddonManager();

    AmoCatalogClient getAmoCatalogClient();

    // Exposed for P2pScreensDeviceTest (androidTest) — the share session the
    // Send and Receive screens render, so the test can wait on its stage.
    P2pShareController getP2pShareController();
}
