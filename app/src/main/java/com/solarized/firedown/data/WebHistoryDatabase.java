package com.solarized.firedown.data;

import androidx.room.Database;
import androidx.room.RoomDatabase;
import androidx.room.migration.Migration;
import androidx.sqlite.db.SupportSQLiteDatabase;

import com.solarized.firedown.data.dao.WebHistoryDao;
import com.solarized.firedown.data.entity.WebHistoryEntity;

@Database(entities = {WebHistoryEntity.class}, version = 4, exportSchema = false)
public abstract class WebHistoryDatabase extends RoomDatabase {

    public static final String DATABASE_NAME = "webhistory-db";

    public abstract WebHistoryDao webHistoryDao();

    public static final Migration MIGRATION_1_2 = new Migration(1, 2) {
        @Override
        public void migrate(SupportSQLiteDatabase database) {
            database.execSQL("ALTER TABLE 'webhistory' ADD COLUMN 'file_icon' TEXT DEFAULT NULL");
        }
    };

    public static final Migration MIGRATION_2_3 = new Migration(2, 3) {
        @Override
        public void migrate(SupportSQLiteDatabase database) {
            database.execSQL("ALTER TABLE 'webhistory' ADD COLUMN 'file_icon_resolution' INTEGER NOT NULL DEFAULT 0");
        }
    };

    // Index file_url so the url-keyed favicon/title updates (updateIconData /
    // updateTitleByUrl) seek instead of scanning the table. The DDL matches the
    // exact form Room generates for @Index(value={"file_url"}) — same proven
    // pattern as DownloadDatabase.MIGRATION_10_11 — so the post-migration schema
    // validates against the entity (exportSchema=false still checks the identity
    // hash at open).
    public static final Migration MIGRATION_3_4 = new Migration(3, 4) {
        @Override
        public void migrate(SupportSQLiteDatabase database) {
            database.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_webhistory_file_url` "
                            + "ON `webhistory` (`file_url`)");
        }
    };
}
