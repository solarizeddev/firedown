package com.solarized.firedown.data.di;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.room.Room;
import androidx.room.RoomDatabase;

import com.solarized.firedown.Preferences;
import com.solarized.firedown.data.DownloadDatabase;
import com.solarized.firedown.data.TabStateArchivedDatabase;
import com.solarized.firedown.data.TrackingPermissionDatabase;
import com.solarized.firedown.data.dao.DownloadDao;
import com.solarized.firedown.data.dao.ShortCutDao;
import com.solarized.firedown.data.ShortCutDatabase;
import com.solarized.firedown.data.ShortCutDatabaseCallback;
import com.solarized.firedown.data.dao.TabStateArchivedDao;
import com.solarized.firedown.data.dao.TrackingPermissionDao;
import com.solarized.firedown.data.dao.WebBookmarkDao;
import com.solarized.firedown.data.WebBookmarkDatabase;
import com.solarized.firedown.data.dao.WebHistoryDao;
import com.solarized.firedown.data.WebHistoryDatabase;
import com.solarized.firedown.data.repository.GeckoStateDataRepository;
import com.solarized.firedown.data.repository.TabStateArchivedRepository;
import com.solarized.firedown.geckoview.IncognitoNotificationHelper;
import com.solarized.firedown.geckoview.media.GeckoMediaController;

import java.util.concurrent.Executor;

import javax.inject.Singleton;

import dagger.Module;
import dagger.Provides;
import dagger.hilt.InstallIn;
import dagger.hilt.android.qualifiers.ApplicationContext;
import dagger.hilt.components.SingletonComponent;
import okhttp3.OkHttpClient;

@Module
@InstallIn(SingletonComponent.class)
public class DatabaseModule {

    @Provides
    @Singleton
    public WebHistoryDatabase provideDatabase(@ApplicationContext Context context) {
        return Room.databaseBuilder(context, WebHistoryDatabase.class, WebHistoryDatabase.DATABASE_NAME)
                .addMigrations(WebHistoryDatabase.MIGRATION_1_2, WebHistoryDatabase.MIGRATION_2_3)
                .build();
    }

    @Provides
    @Singleton
    public ShortCutDatabase provideShortcutDatabase(
            @ApplicationContext Context context,
            ShortCutDatabaseCallback callback) { // Callback is injected here
        return Room.databaseBuilder(context, ShortCutDatabase.class, ShortCutDatabase.DATABASE_NAME)
                .setJournalMode(RoomDatabase.JournalMode.AUTOMATIC)
                .addCallback(callback)
                .fallbackToDestructiveMigration(false)
                .build();
    }

    @Provides
    @Singleton
    public WebBookmarkDatabase provideWebBookmarkDatabase(@ApplicationContext Context context) {
        return Room.databaseBuilder(context, WebBookmarkDatabase.class, WebBookmarkDatabase.DATABASE_NAME)
                .addMigrations(WebBookmarkDatabase.MIGRATION_1_2)
                .setJournalMode(RoomDatabase.JournalMode.AUTOMATIC)
                .fallbackToDestructiveMigration(false)
                .build();
    }


    @Provides
    @Singleton
    public TabStateArchivedDatabase provideTabDatabase(@ApplicationContext Context context) {
        return Room.databaseBuilder(
                        context,
                        TabStateArchivedDatabase.class,
                        TabStateArchivedDatabase.DATABASE_NAME
                )
                .setJournalMode(RoomDatabase.JournalMode.AUTOMATIC)
                .fallbackToDestructiveMigration(false)
                .build();
    }


    @Provides
    @Singleton
    public GeckoStateDataRepository provideGeckoStateDataRepository(
            @ApplicationContext Context context,
            @Qualifiers.DiskIO Executor diskExecutor,
            TabStateArchivedRepository archivedRepository,
            GeckoMediaController geckoMediaController,
            SharedPreferences sharedPreferences
    ) {
        GeckoStateDataRepository repo = new GeckoStateDataRepository(context, diskExecutor, archivedRepository, geckoMediaController);
        // Optional: Trigger initial load from file immediately
        boolean enabled = sharedPreferences.getBoolean(
                Preferences.SETTINGS_TABS_ARCHIVE, true);
        long threshold = sharedPreferences.getLong(
                Preferences.SETTINGS_TABS_ARCHIVE_INTERVAL,
                Preferences.ONE_WEEK_INTERVAL);

        diskExecutor.execute(() -> repo.initializeGeckoStates(enabled ? threshold : -1));

        return repo;
    }

    @Provides
    @Singleton
    public IncognitoNotificationHelper provideIncognitoNotificationHelper(@ApplicationContext Context context

    ) {

        return new IncognitoNotificationHelper(context);
    }


    @Provides
    @Singleton
    public TrackingPermissionDatabase provideTrackingDatabase(@ApplicationContext Context context) {
        return Room.databaseBuilder(context,
                        TrackingPermissionDatabase.class, TrackingPermissionDatabase.DATABASE_NAME)
                .setJournalMode(RoomDatabase.JournalMode.AUTOMATIC)
                .fallbackToDestructiveMigration(false)
                .build();
    }


    @Provides
    @Singleton
    public DownloadDatabase provideDownloadDatabase(@ApplicationContext Context context) {
        return Room.databaseBuilder(context, DownloadDatabase.class, DownloadDatabase.DATABASE_NAME)
                .setJournalMode(RoomDatabase.JournalMode.AUTOMATIC)
                .addMigrations(
                        DownloadDatabase.MIGRATION_1_2,
                        DownloadDatabase.MIGRATION_2_3,
                        DownloadDatabase.MIGRATION_3_4,
                        DownloadDatabase.MIGRATION_4_5,
                        DownloadDatabase.MIGRATION_5_6,
                        DownloadDatabase.MIGRATION_6_7,
                        DownloadDatabase.MIGRATION_7_8,
                        DownloadDatabase.MIGRATION_8_9
                )
                .build();
    }

    @Provides
    public DownloadDao provideDownloadDao(DownloadDatabase database) {
        return database.downloadDao();
    }

    @Provides
    public TrackingPermissionDao provideTrackingDao(TrackingPermissionDatabase database) {
        return database.trackingPermissionDao();
    }


    @Provides
    public TabStateArchivedDao provideTabDao(TabStateArchivedDatabase database) {
        return database.tabStateDao();
    }

    @Provides
    public WebBookmarkDao provideWebBookmarkDao(WebBookmarkDatabase db) {
        return db.webBookmarkDao();
    }

    @Provides
    public ShortCutDao provideShortCutDao(ShortCutDatabase db) {
        return db.shortCutsDao();
    }

    @Provides
    public WebHistoryDao provideDao(WebHistoryDatabase db) {
        return db.webHistoryDao();
    }



}