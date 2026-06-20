package com.solarized.firedown.data.entity;

import androidx.annotation.NonNull;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

/**
 * One blocked URL for the empty-focus most-visited strip — Chromium/Brave's
 * "Top Sites blocklist" model: removing a tile HIDES it (history is untouched);
 * the tile is suppressed because its URL is in this table. Keyed by the URL
 * itself (one row per blocked site; an INSERT OR REPLACE can't duplicate).
 */
@Entity(tableName = "most_visited_block")
public class MostVisitedBlockEntity {

    @PrimaryKey
    @NonNull
    @ColumnInfo(name = "url")
    public String url = "";

    @ColumnInfo(name = "date")
    public long date;

    public MostVisitedBlockEntity() {
    }

    public MostVisitedBlockEntity(@NonNull String url, long date) {
        this.url = url;
        this.date = date;
    }
}
