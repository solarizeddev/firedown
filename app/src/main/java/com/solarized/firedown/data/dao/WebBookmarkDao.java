package com.solarized.firedown.data.dao;

import androidx.lifecycle.LiveData;
import androidx.paging.PagingSource;
import androidx.room.Dao;
import androidx.room.Delete;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import com.solarized.firedown.data.entity.WebBookmarkEntity;

import java.util.List;

@Dao
public interface WebBookmarkDao {

    @Query("SELECT * FROM webbookmark")
    List<WebBookmarkEntity> getAllRaw();

    @Query("SELECT uid FROM webbookmark")
    List<Integer> getAllIds();

    @Query("SELECT * FROM webbookmark WHERE uid LIKE :id")
    WebBookmarkEntity getId(int id);


    // Pinned bookmarks render at the top of the list (replaces the
    // standalone shortcuts concept); secondary sort by date so
    // ordering is stable inside each tier.
    @Query("SELECT * FROM webbookmark ORDER BY is_pinned DESC, file_date DESC")
    PagingSource<Integer, WebBookmarkEntity> getBookmarks();

    @Query("SELECT * FROM webbookmark ORDER BY is_pinned DESC, file_date DESC LIMIT :limit")
    LiveData<List<WebBookmarkEntity>> getBookmark(int limit);

    /** Pinned-only feed for the home pinned favicons strip. ORDER BY
     *  file_date so the most recently pinned sits on the leading
     *  edge (read first in LTR). */
    @Query("SELECT * FROM webbookmark WHERE is_pinned = 1 ORDER BY file_date DESC LIMIT :limit")
    LiveData<List<WebBookmarkEntity>> getPinnedLive(int limit);

    @Query("SELECT * FROM webbookmark WHERE file_url LIKE :search or file_title LIKE :search ORDER BY is_pinned DESC, file_date DESC")
    PagingSource<Integer, WebBookmarkEntity> search(String search);

    @Query("UPDATE webbookmark SET is_pinned = :pinned WHERE uid = :id")
    Integer setPinned(int id, boolean pinned);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    Long insert(WebBookmarkEntity web);

    @Delete
    Integer delete(WebBookmarkEntity web);

    @Query("DELETE FROM webbookmark WHERE uid = :id")
    Integer deleteById(int id);

    @Query("DELETE FROM webbookmark")
    Integer deleteAll();

    @Query("SELECT COUNT(file_url) FROM webbookmark")
    Integer getRowCount();

}
