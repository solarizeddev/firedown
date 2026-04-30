package com.solarized.firedown.data;

import androidx.room.Database;
import androidx.room.RoomDatabase;
import com.solarized.firedown.data.dao.TabStateArchivedDao;
import com.solarized.firedown.data.entity.TabStateArchivedEntity;

@Database(entities = {TabStateArchivedEntity.class}, version = 1, exportSchema = false)
public abstract class TabStateArchivedDatabase extends RoomDatabase {
    public static final String DATABASE_NAME = "tabstate-db";
    public abstract TabStateArchivedDao tabStateDao();
}
