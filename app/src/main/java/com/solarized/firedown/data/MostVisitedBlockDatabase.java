package com.solarized.firedown.data;

import androidx.annotation.VisibleForTesting;
import androidx.room.Database;
import androidx.room.RoomDatabase;

import com.solarized.firedown.data.dao.MostVisitedBlockDao;
import com.solarized.firedown.data.entity.MostVisitedBlockEntity;

/**
 * Standalone DB for the most-visited "Top Sites blocklist" (Chromium/Brave
 * model). Deliberately its OWN database, not a table added to
 * {@code WebHistoryDatabase} — that DB's tracking-mode history makes migrations
 * there a known minefield, and the blocklist is filtered in Java by
 * {@code AutoCompleteSearch.mostVisited()} (no JOIN with history needed).
 */
@Database(entities = {MostVisitedBlockEntity.class}, version = 1, exportSchema = false)
public abstract class MostVisitedBlockDatabase extends RoomDatabase {

    @VisibleForTesting
    public static final String DATABASE_NAME = "most-visited-block-db";

    public abstract MostVisitedBlockDao mostVisitedBlockDao();
}
