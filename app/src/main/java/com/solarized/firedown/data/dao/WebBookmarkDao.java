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

    @Query("SELECT * FROM webbookmark ORDER BY file_date DESC")
    PagingSource<Integer, WebBookmarkEntity> getBookmarks();

    @Query("SELECT * FROM webbookmark ORDER BY file_date DESC LIMIT :limit")
    LiveData<List<WebBookmarkEntity>> getBookmark(int limit);

    @Query("SELECT * FROM webbookmark WHERE file_url LIKE :search or file_title LIKE :search ORDER BY file_date DESC")
    PagingSource<Integer, WebBookmarkEntity> search(String search);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    Long insert(WebBookmarkEntity web);

    /**
     * In-place icon refresh for an existing bookmark. Used by
     * IconsRepository when GeckoRuntimeHelper signals a new icon for
     * a URL the user has bookmarked, so the list re-renders with the
     * latest favicon without going through a full insert/replace.
     * Returns the number of rows affected — 0 when no bookmark
     * matches, which is the no-op case and not an error.
     */
    @Query("UPDATE webbookmark SET file_icon = :icon WHERE uid = :id")
    int updateIcon(int id, String icon);

    @Delete
    Integer delete(WebBookmarkEntity web);

    @Query("DELETE FROM webbookmark WHERE uid = :id")
    Integer deleteById(int id);

    @Query("DELETE FROM webbookmark")
    Integer deleteAll();

    @Query("SELECT COUNT(file_url) FROM webbookmark")
    Integer getRowCount();

}
