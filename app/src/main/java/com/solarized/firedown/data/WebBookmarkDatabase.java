package com.solarized.firedown.data;

import androidx.room.Database;
import androidx.room.RoomDatabase;
import androidx.room.migration.Migration;
import androidx.sqlite.db.SupportSQLiteDatabase;

import com.solarized.firedown.data.dao.WebBookmarkDao;
import com.solarized.firedown.data.entity.WebBookmarkEntity;

@Database(entities = {WebBookmarkEntity.class}, version = 2, exportSchema = false)
public abstract class WebBookmarkDatabase extends RoomDatabase {

    public static final String DATABASE_NAME = "webbookmark-db";

    public abstract WebBookmarkDao webBookmarkDao();

    public static final Migration MIGRATION_1_2 = new Migration(1, 2) {
        @Override
        public void migrate(SupportSQLiteDatabase database) {
            database.execSQL("ALTER TABLE 'webbookmark' ADD COLUMN 'file_icon' TEXT DEFAULT NULL");
            database.execSQL("ALTER TABLE 'webbookmark' ADD COLUMN 'file_preview' TEXT DEFAULT NULL");
        }
    };

    // NOTE: a 2→3 migration (pinned / pin_order columns for the home-shortcuts
    // feature) existed only on a dev branch and was reverted with the feature —
    // it never shipped to main, so the DB stays at version 2. Dev devices that
    // ran the v3 build downgrade gracefully via
    // fallbackToDestructiveMigrationOnDowngrade in DatabaseModule (rebuilds the
    // bookmark table on those devices only).
}
