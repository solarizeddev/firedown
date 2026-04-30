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
    @Query("SELECT * FROM webhistory WHERE file_url LIKE :search OR file_title LIKE :search ORDER BY file_date DESC LIMIT 6")
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

    @Query("SELECT file_icon_resolution FROM webhistory WHERE file_url = :url LIMIT 1")
    int getResolution(String url);

    @Query("UPDATE webhistory SET file_icon = :icon, file_icon_resolution = :res WHERE file_url = :url")
    void updateIconData(String url, String icon, int res);

    @Query("UPDATE webhistory SET file_title = :title WHERE uid = :id")
    Integer updateTitle(int id, String title);

    @Query("SELECT COUNT(file_url) FROM webhistory")
    Integer getRowCount();
}