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

    // v3: bookmarks sync (docs/BOOKMARKS_SYNC.md). Adds the per-URL
    // last-writer-wins + tombstone columns the merge engine reads. Additive only
    // (CLAUDE.md Room rules). The DEFAULT 0 here MUST match the entity's
    // @ColumnInfo(defaultValue = "0") or Room's schema-identity check fails on a
    // migrated DB. updated_at backfills from file_date so existing rows carry a
    // sane last-modified.
    //
    // NOTE on the earlier reverted v3: a DIFFERENT 2→3 (pinned / pin_order for
    // home-shortcuts) existed only on a dev branch and never shipped — all main
    // users are still at v2 and migrate cleanly here. The maintainer's own dev
    // devices that ran that orphaned v3 carry a different v3 schema; equal-version
    // schema drift is NOT auto-healed (no migration runs at equal version, and
    // fallbackToDestructiveMigrationOnDowngrade only fires on a downgrade), so
    // those dev devices must clear app data once.
    public static final Migration MIGRATION_2_3 = new Migration(2, 3) {
        @Override
        public void migrate(SupportSQLiteDatabase database) {
            database.execSQL("ALTER TABLE 'webbookmark' ADD COLUMN 'updated_at' INTEGER NOT NULL DEFAULT 0");
            database.execSQL("ALTER TABLE 'webbookmark' ADD COLUMN 'deleted' INTEGER NOT NULL DEFAULT 0");
            database.execSQL("ALTER TABLE 'webbookmark' ADD COLUMN 'deleted_at' INTEGER NOT NULL DEFAULT 0");
            // Backfill from file_date — UNQUOTED identifiers (single quotes would
            // make file_date a string literal, storing 0 instead of the column).
            database.execSQL("UPDATE webbookmark SET updated_at = file_date");
        }
    };
}
