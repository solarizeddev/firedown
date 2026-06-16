package com.solarized.firedown.data.dao;

import androidx.lifecycle.LiveData;
import androidx.paging.PagingSource;
import androidx.room.Dao;
import androidx.room.Delete;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;


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

    @Query("SELECT * FROM webhistory ORDER BY file_date DESC LIMIT 20")
    List<WebHistoryEntity> getAutoCompleteHistory();

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