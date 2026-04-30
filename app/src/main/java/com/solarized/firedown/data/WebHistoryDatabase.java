package com.solarized.firedown.data;

import androidx.room.Database;
import androidx.room.RoomDatabase;
import androidx.room.migration.Migration;
import androidx.sqlite.db.SupportSQLiteDatabase;

import com.solarized.firedown.data.dao.WebHistoryDao;
import com.solarized.firedown.data.entity.WebHistoryEntity;

@Database(entities = {WebHistoryEntity.class}, version = 3, exportSchema = false)
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
}
