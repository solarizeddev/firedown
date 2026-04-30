package com.solarized.firedown.data.di;

import android.content.SharedPreferences;

import com.solarized.firedown.data.repository.GeckoStateDataRepository;
import com.solarized.firedown.data.repository.ShortCutsDataRepository;
import com.solarized.firedown.data.repository.WebBookmarkDataRepository;
import com.solarized.firedown.data.repository.WebHistoryDataRepository;
import com.solarized.firedown.geckoview.GeckoRuntimeHelper;

import dagger.hilt.InstallIn;
import dagger.hilt.EntryPoint;
import dagger.hilt.components.SingletonComponent;

@EntryPoint
@InstallIn(SingletonComponent.class)
public interface RepositoryEntryPoint {
    WebHistoryDataRepository getWebHistoryRepository();

    WebBookmarkDataRepository getWebBookmarkDataRepository();

    ShortCutsDataRepository getShortCutsDataRepository();

    SharedPreferences getSharedPreferences();

    GeckoRuntimeHelper getGeckoRuntimeHelper();

    GeckoStateDataRepository getGeckoStateDataRepository();
}
