package com.solarized.firedown.data.di;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.OptIn;
import androidx.room.ExperimentalRoomApi;
import androidx.room.Room;
import androidx.room.RoomDatabase;

import com.solarized.firedown.Preferences;
import com.solarized.firedown.data.DownloadDatabase;
import com.solarized.firedown.data.TabStateArchivedDatabase;
import com.solarized.firedown.data.TrackingPermissionDatabase;
import com.solarized.firedown.data.WasmAllowlistDatabase;
import com.solarized.firedown.data.dao.DownloadDao;
import com.solarized.firedown.data.dao.TabStateArchivedDao;
import com.solarized.firedown.data.dao.TrackingPermissionDao;
import com.solarized.firedown.data.dao.WasmAllowlistDao;
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

@Module
@InstallIn(SingletonComponent.class)
public class DatabaseModule {

    /*
     * setInMemoryTrackingMode(false) on EVERY Room database — do not remove.
     *
     * Room's InvalidationTracker (the thing that makes LiveData/Paging
     * re-query after a write) defaults to a per-connection TEMP table
     * (room_table_modification_log) plus TEMP triggers. TEMP objects live on
     * the native SQLite connection — and the framework can recycle that
     * connection underneath Room (Samsung OneUI enables SQLite's
     * idle-connection timeout system-wide; observed on an SM-A536B/SDK 36).
     * A recycled connection comes back with NO temp tables and NO triggers,
     * and Room 2.7+ never re-creates them: configureConnection runs once,
     * and the refresh path swallows the resulting SQLiteException
     * (notifyInvalidation catches it and returns "nothing invalidated").
     * Symptom: "(1) no such table: room_table_modification_log" in logcat
     * and a UI that loads fine but never refreshes after a write — deletes
     * that don't disappear, downloads that don't appear. Older Room (2.6)
     * crashed on the addObserver path instead; 2.7.2/2.8.4 contain NO fix
     * for this (verified against the release notes — an earlier bump here
     * claimed otherwise and was wrong).
     *
     * setInMemoryTrackingMode(false) (@ExperimentalRoomApi, added in
     * 2.7.0-alpha12 for exactly this, b/185414040) makes the tracking table
     * AND the triggers persistent schema objects in the database file, which
     * survive any connection recycling. Cost: trigger writes go through the
     * journal instead of memory — negligible at this app's write rates.
     */

    @Provides
    @Singleton
    @OptIn(markerClass = ExperimentalRoomApi.class)
    public WebHistoryDatabase provideDatabase(@ApplicationContext Context context) {
        return Room.databaseBuilder(context, WebHistoryDatabase.class, WebHistoryDatabase.DATABASE_NAME)
                .addMigrations(WebHistoryDatabase.MIGRATION_1_2, WebHistoryDatabase.MIGRATION_2_3,
                        WebHistoryDatabase.MIGRATION_3_4)
                .setInMemoryTrackingMode(false)
                .build();
    }

    @Provides
    @Singleton
    @OptIn(markerClass = ExperimentalRoomApi.class)
    public WebBookmarkDatabase provideWebBookmarkDatabase(@ApplicationContext Context context) {
        return Room.databaseBuilder(context, WebBookmarkDatabase.class, WebBookmarkDatabase.DATABASE_NAME)
                .addMigrations(WebBookmarkDatabase.MIGRATION_1_2)
                // The shortcuts feature briefly bumped this DB to v3 (pinned /
                // pin_order) on a dev branch; the feature + its migration were
                // reverted, so the schema is back at v2. A dev device that ran the
                // v3 build has a higher on-disk version than the code now requests
                // — allow that downgrade to rebuild the bookmark table rather than
                // crash (dev-only; main never saw v3). Normal upgrades unaffected.
                .fallbackToDestructiveMigrationOnDowngrade(true)
                .setJournalMode(RoomDatabase.JournalMode.AUTOMATIC)
                .fallbackToDestructiveMigration(false)
                .setInMemoryTrackingMode(false)
                .build();
    }


    @Provides
    @Singleton
    @OptIn(markerClass = ExperimentalRoomApi.class)
    public TabStateArchivedDatabase provideTabDatabase(@ApplicationContext Context context) {
        return Room.databaseBuilder(
                        context,
                        TabStateArchivedDatabase.class,
                        TabStateArchivedDatabase.DATABASE_NAME
                )
                .setJournalMode(RoomDatabase.JournalMode.AUTOMATIC)
                .addMigrations(TabStateArchivedDatabase.MIGRATION_1_2)
                .fallbackToDestructiveMigration(false)
                .setInMemoryTrackingMode(false)
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
    @OptIn(markerClass = ExperimentalRoomApi.class)
    public TrackingPermissionDatabase provideTrackingDatabase(@ApplicationContext Context context) {
        return Room.databaseBuilder(context,
                        TrackingPermissionDatabase.class, TrackingPermissionDatabase.DATABASE_NAME)
                .setJournalMode(RoomDatabase.JournalMode.AUTOMATIC)
                .fallbackToDestructiveMigration(false)
                .setInMemoryTrackingMode(false)
                .build();
    }


    @Provides
    @Singleton
    @OptIn(markerClass = ExperimentalRoomApi.class)
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
                        DownloadDatabase.MIGRATION_8_9,
                        DownloadDatabase.MIGRATION_9_10,
                        DownloadDatabase.MIGRATION_10_11,
                        DownloadDatabase.MIGRATION_11_12,
                        DownloadDatabase.MIGRATION_12_13
                )
                .setInMemoryTrackingMode(false)
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
    @Singleton
    @OptIn(markerClass = ExperimentalRoomApi.class)
    public WasmAllowlistDatabase provideWasmAllowlistDatabase(@ApplicationContext Context context) {
        return Room.databaseBuilder(context,
                        WasmAllowlistDatabase.class, WasmAllowlistDatabase.DATABASE_NAME)
                .setJournalMode(RoomDatabase.JournalMode.AUTOMATIC)
                .fallbackToDestructiveMigration(false)
                .setInMemoryTrackingMode(false)
                .build();
    }

    @Provides
    public WasmAllowlistDao provideWasmAllowlistDao(WasmAllowlistDatabase database) {
        return database.wasmAllowlistDao();
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
    public WebHistoryDao provideDao(WebHistoryDatabase db) {
        return db.webHistoryDao();
    }



}