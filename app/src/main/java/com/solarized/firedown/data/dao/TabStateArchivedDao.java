package com.solarized.firedown.data.dao;

import androidx.lifecycle.LiveData;
import androidx.paging.PagingSource;
import androidx.room.Dao;
import androidx.room.Delete;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import com.solarized.firedown.data.entity.TabStateArchivedEntity;

import java.util.List;

@Dao
public interface TabStateArchivedDao {

    @Query("SELECT * FROM tabstate")
    List<TabStateArchivedEntity> getAllRaw();

    /**
     * PagingSource for Paging 3.
     * Uses file_date DESC to show the most recent tabs first.
     */
    @Query("SELECT * FROM tabstate ORDER BY file_date DESC")
    PagingSource<Integer, TabStateArchivedEntity> getArchive();

    @Query("SELECT * FROM tabstate WHERE uid = :id")
    LiveData<TabStateArchivedEntity> getTabState(int id);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    Long insert(TabStateArchivedEntity tabStateArchivedEntity);

    /**
     * Used for background tasks (like GeckoInspectTask)
     * where we are already on a background thread.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    long insertSync(TabStateArchivedEntity tabStateArchivedEntity);

    @Delete
    Integer delete(TabStateArchivedEntity tabStateArchivedEntity);

    @Query("DELETE FROM tabstate WHERE uid = :id")
    Integer deleteById(int id);

    @Query("DELETE FROM tabstate")
    Integer deleteAll();
}