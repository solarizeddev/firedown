package com.solarized.firedown.data;

import androidx.annotation.VisibleForTesting;
import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;

import com.solarized.firedown.App;
import com.solarized.firedown.data.dao.TrackingPermissionDao;
import com.solarized.firedown.data.entity.TrackingPermissionsEntity;

@Database(entities = {TrackingPermissionsEntity.class}, version = 1, exportSchema = false)
public abstract class TrackingPermissionDatabase extends RoomDatabase {

    @VisibleForTesting
    public static final String DATABASE_NAME = "tracking-db";

    private static class Loader {
        static volatile TrackingPermissionDatabase INSTANCE = Room.databaseBuilder(App.getAppContext(),
                        TrackingPermissionDatabase.class, DATABASE_NAME)
                .setJournalMode(JournalMode.AUTOMATIC)
                .fallbackToDestructiveMigration(false)
                .build();
    }


    public abstract TrackingPermissionDao trackingPermissionDao();

    public static TrackingPermissionDatabase getInstance() {
        return Loader.INSTANCE;
    }
}
