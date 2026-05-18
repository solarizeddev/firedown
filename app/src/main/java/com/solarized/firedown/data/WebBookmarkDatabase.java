package com.solarized.firedown.data;

import androidx.room.Database;
import androidx.room.RoomDatabase;
import androidx.room.migration.Migration;
import androidx.sqlite.db.SupportSQLiteDatabase;

import com.solarized.firedown.data.dao.WebBookmarkDao;
import com.solarized.firedown.data.entity.WebBookmarkEntity;

@Database(entities = {WebBookmarkEntity.class}, version = 3, exportSchema = false)
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

    /**
     * v3 introduces the {@code is_pinned} column. Pinned bookmarks
     * render at the top of the bookmarks list with a pin badge —
     * this is the destination of the now-deprecated 'shortcuts'
     * concept (see {@code LegacyShortcutsMigrator}, which runs once
     * on app startup to copy rows out of {@code shortcuts-db} into
     * this table with {@code is_pinned = 1}).
     */
    public static final Migration MIGRATION_2_3 = new Migration(2, 3) {
        @Override
        public void migrate(SupportSQLiteDatabase database) {
            database.execSQL(
                    "ALTER TABLE 'webbookmark' ADD COLUMN 'is_pinned' INTEGER NOT NULL DEFAULT 0");
        }
    };
}
