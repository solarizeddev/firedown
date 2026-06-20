package com.solarized.firedown.data.entity;

import androidx.annotation.NonNull;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

/**
 * One blocked HOST for the empty-focus most-visited strip — Chromium/Brave's
 * "Top Sites blocklist" model: removing a tile HIDES that site (history is
 * untouched). Keyed by HOST (the same {@code hostOf} the strip dedups tiles by —
 * lowercased, leading {@code www.} stripped), NOT the exact URL, so hiding one
 * canonical variant ({@code firedown.app}) also hides the others
 * ({@code www.firedown.app}, other paths/schemes) instead of letting them
 * resurface as a new tile. (A urlless edge stores the raw key as a fallback.)
 */
@Entity(tableName = "most_visited_block")
public class MostVisitedBlockEntity {

    @PrimaryKey
    @NonNull
    @ColumnInfo(name = "host")
    public String host = "";

    @ColumnInfo(name = "date")
    public long date;

    public MostVisitedBlockEntity() {
    }

    public MostVisitedBlockEntity(@NonNull String host, long date) {
        this.host = host;
        this.date = date;
    }
}
