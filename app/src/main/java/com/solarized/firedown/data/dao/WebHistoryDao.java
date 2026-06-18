package com.solarized.firedown.data.dao;

import androidx.lifecycle.LiveData;
import androidx.paging.PagingSource;
import androidx.room.Dao;
import androidx.room.Delete;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import androidx.room.RawQuery;
import androidx.sqlite.db.SupportSQLiteQuery;


import com.solarized.firedown.data.entity.WebHistoryEntity;

import java.util.List;

@Dao
public interface WebHistoryDao {

    /**
     * Standard Paging 3 source for the full history list.
     */
    @Query("SELECT * FROM webhistory ORDER BY file_date DESC")
    PagingSource<Integer, WebHistoryEntity> getHistory();

    /**
     * Search query used by Paging 3. Note the use of LIKE for partial matches.
     */
    @Query("SELECT * FROM webhistory WHERE file_url LIKE :search OR file_title LIKE :search ORDER BY file_date DESC")
    PagingSource<Integer, WebHistoryEntity> getSearch(String search);

    /**
     * Returns a specific history entry based on URL or Title.
     */
    @Query("SELECT * FROM webhistory WHERE file_url LIKE :url OR file_title LIKE :title")
    WebHistoryEntity getHistorySync(String url, String title);

    /**
     * Limit-based history for quick access UI components.
     */
    @Query("SELECT * FROM webhistory ORDER BY file_date DESC LIMIT :limit")
    LiveData<List<WebHistoryEntity>> getHistory(int limit);

    /**
     * Optimized Auto-complete queries.
     */
    @Query("SELECT * FROM webhistory WHERE file_url LIKE :search OR file_title LIKE :search ORDER BY file_date DESC LIMIT 3")
    List<WebHistoryEntity> getAutoCompleteSearch(String search);

    // FTS-backed autocomplete lookup. @RawQuery because the query references the
    // webhistory_fts virtual table, which is NOT a modeled Room entity (see
    // WebHistoryDatabase) — a @Query would fail Room's compile-time SQL
    // verification ("no such table"). The SQL + bound args are built in
    // WebHistoryDataRepository.getAutoCompleteSearch.
    @RawQuery
    List<WebHistoryEntity> getAutoCompleteFts(SupportSQLiteQuery query);

    @Query("SELECT * FROM webhistory ORDER BY file_date DESC LIMIT 20")
    List<WebHistoryEntity> getAutoCompleteHistory();

    /**
     * "Most visited" frecency for the empty-focus suggestion list (the existing
     * list, filled instead of left blank, when the address bar is focused and
     * empty). There is NO visit-count column: the uid is {@code hash(url)+day},
     * so there is exactly one row per (url, day), and {@code COUNT(*)} grouped by
     * url is therefore the number of DISTINCT DAYS a url was visited — a
     * lightweight frecency proxy. Ordered by that count, then recency. about:
     * URLs (home/blank) are excluded at the source. The bare columns
     * (title/icon) come from an arbitrary row of each group, which is fine —
     * a site's title/icon is stable per url in practice. COUNT/MAX live only in
     * ORDER BY so the projection is exactly the entity's columns (no Room
     * cursor-mismatch warning).
     */
    @Query("SELECT * FROM webhistory WHERE file_url NOT LIKE 'about:%' "
            + "GROUP BY file_url ORDER BY COUNT(*) DESC, MAX(file_date) DESC LIMIT :limit")
    List<WebHistoryEntity> getMostVisited(int limit);

    /**
     * Persistence operations.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    Long insert(WebHistoryEntity web);

    @Delete
    Integer delete(WebHistoryEntity web);

    @Query("DELETE FROM webhistory WHERE uid = :id")
    Integer deleteById(int id);

    @Query("DELETE FROM webhistory")
    void deleteAll(); // Changed to void for simple background execution via Repository

    /**
     * Maintenance queries.
     */
    @Query("DELETE FROM webhistory WHERE file_date >= :range")
    void deleteRange(long range);

    @Query("DELETE FROM webhistory WHERE file_date <= :date")
    Integer purgeDatabase(long date);

    // Set a history row's favicon in ONE statement (no read-modify-write): the
    // resolution gate (keep a higher-res icon — only overwrite when the new res
    // is >= the stored one, or the stored one is unknown) and a no-op guard
    // (don't rewrite an identical icon+res) both live in the WHERE clause. The
    // no-op guard matters because an unconditional UPDATE fires Room's
    // invalidation on every revisit, needlessly requerying the history Paging
    // list even when the favicon hasn't changed. IS NOT is the null-safe
    // inequality (covers a NULL stored icon on first set). Returns rows changed.
    @Query("UPDATE webhistory SET file_icon = :icon, file_icon_resolution = :res "
            + "WHERE file_url = :url "
            + "AND (file_icon_resolution <= 0 OR :res >= file_icon_resolution) "
            + "AND (file_icon IS NOT :icon OR file_icon_resolution IS NOT :res)")
    int updateIconData(String url, String icon, int res);

    // Repair the title for a url after onTitleChange (which arrives separately
    // from, and usually later than, the row insert in onHistoryStateChange).
    // Keyed by file_url, NOT uid: the row's uid is generateId(url), so the old
    // tab-id-keyed update never matched the row it was meant to fix. Mirrors the
    // url-keyed updateIconData above.
    @Query("UPDATE webhistory SET file_title = :title WHERE file_url = :url")
    Integer updateTitleByUrl(String url, String title);

    @Query("SELECT COUNT(file_url) FROM webhistory")
    Integer getRowCount();
}