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

    @Delete
    Integer delete(WebBookmarkEntity web);

    @Query("DELETE FROM webbookmark WHERE uid = :id")
    Integer deleteById(int id);

    @Query("DELETE FROM webbookmark")
    Integer deleteAll();

    @Query("SELECT COUNT(file_url) FROM webbookmark")
    Integer getRowCount();

}
