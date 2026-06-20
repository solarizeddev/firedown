package com.solarized.firedown.data.dao;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import com.solarized.firedown.data.entity.MostVisitedBlockEntity;

import java.util.List;

@Dao
public interface MostVisitedBlockDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insert(MostVisitedBlockEntity entity);

    @Query("SELECT host FROM most_visited_block")
    List<String> getAllHosts();

    @Query("DELETE FROM most_visited_block WHERE host = :host")
    void delete(String host);

    @Query("DELETE FROM most_visited_block")
    void deleteAll();
}
