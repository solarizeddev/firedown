package com.solarized.firedown.data;

import androidx.room.Database;
import androidx.room.RoomDatabase;
import com.solarized.firedown.data.dao.ShortCutDao;
import com.solarized.firedown.data.entity.ShortCutsEntity;

@Database(entities = {ShortCutsEntity.class}, version = 1, exportSchema = false)
public abstract class ShortCutDatabase extends RoomDatabase {
    public static final String DATABASE_NAME = "shortcuts-db";
    public abstract ShortCutDao shortCutsDao();
}
