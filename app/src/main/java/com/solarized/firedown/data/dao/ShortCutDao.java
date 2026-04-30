package com.solarized.firedown.data.dao;

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Delete;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import androidx.room.Update;

import com.solarized.firedown.data.entity.ShortCutsEntity;

import java.util.List;

@Dao
public interface ShortCutDao {

    @Query("SELECT * FROM shortcuts")
    List<ShortCutsEntity> getAllRaw();

    @Query("SELECT * FROM shortcuts ORDER BY file_date DESC")
    LiveData<List<ShortCutsEntity>> getShortcuts();

    @Query("SELECT uid FROM shortcuts")
    List<Integer> getAllIds();

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    Long insert(ShortCutsEntity shortCuts);

    @Update
    void updateShortcut(ShortCutsEntity shortcut);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insertAll(List<ShortCutsEntity> shortCuts);

    @Delete
    Integer delete(ShortCutsEntity shortCuts);

    @Query("DELETE FROM shortcuts WHERE uid = :id")
    Integer deleteById(int id);


    @Query("DELETE FROM shortcuts")
    Integer deleteAll();

    @Query("SELECT file_icon_resolution FROM shortcuts WHERE file_url = :url LIMIT 1")
    int getResolution(String url);

    @Query("UPDATE shortcuts SET file_icon = :icon, file_icon_resolution = :res WHERE file_domain = :domain")
    void updateIconData(String domain, String icon, int res);


    @Query("DELETE FROM shortcuts WHERE file_date <= :date")
    Integer purgeDatabase(long date);
}
