package com.solarized.firedown.data.dao;

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Delete;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import com.solarized.firedown.data.entity.TrackingPermissionsEntity;

import java.util.List;


@Dao
public interface TrackingPermissionDao {

    @Query("SELECT * FROM tracking")
    List<TrackingPermissionsEntity> getAllRaw();

    @Query("SELECT uid FROM tracking")
    List<Integer> getAllIds();

    @Query("SELECT * FROM tracking ORDER BY file_date")
    LiveData<List<TrackingPermissionsEntity>> getTracking();

    @Query("SELECT * FROM tracking WHERE uid = :id")
    TrackingPermissionsEntity getTracking(int id);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    Long insert(TrackingPermissionsEntity trackingPermissionsEntity);

    @Delete
    Integer delete(TrackingPermissionsEntity trackingPermissionsEntity);

    @Query("DELETE FROM tracking WHERE uid = :id")
    Integer deleteById(int id);

    @Query("DELETE FROM tracking")
    Integer deleteAll();
}
