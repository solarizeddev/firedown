package com.solarized.firedown.data.dao;

import androidx.lifecycle.LiveData;
import androidx.paging.PagingSource;
import androidx.room.Dao;
import androidx.room.Delete;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import androidx.room.Transaction;

import com.solarized.firedown.data.entity.DownloadEntity;
import java.util.List;

@Dao
public interface DownloadDao {

    // --- Paging Source Queries (Standard) ---

    @Query("SELECT * FROM download WHERE file_safe = 0 ORDER BY file_date DESC")
    PagingSource<Integer, DownloadEntity> getDownloads();

    @Query("SELECT * FROM download WHERE file_safe = 0 ORDER BY file_size DESC")
    PagingSource<Integer, DownloadEntity> getDownloadsSize();

    @Query("SELECT * FROM download WHERE file_safe = 0 ORDER BY file_name ASC")
    PagingSource<Integer, DownloadEntity> getDownloadsName();

    @Query("SELECT * FROM download WHERE file_safe = 0 ORDER BY file_origin_url ASC, file_date DESC")
    PagingSource<Integer, DownloadEntity> getDownloadsDomain();

    // --- Paging Source Queries (Safe/Encrypted) ---

    @Query("SELECT * FROM download WHERE file_safe = 1 ORDER BY file_date DESC")
    PagingSource<Integer, DownloadEntity> getSafe();

    @Query("SELECT * FROM download WHERE file_safe = 1 ORDER BY file_size DESC")
    PagingSource<Integer, DownloadEntity> getSafeSize();

    @Query("SELECT * FROM download WHERE file_safe = 1 ORDER BY file_name ASC")
    PagingSource<Integer, DownloadEntity> getSafeName();

    @Query("SELECT * FROM download WHERE file_safe = 1 ORDER BY file_origin_url ASC, file_date DESC")
    PagingSource<Integer, DownloadEntity> getSafeDomain();

    /**
     * Optimized Search Query.
     * Order: 0 = Date, 1 = Size, 2 = Name, 3 = Domain.
     */
    @Query("SELECT * FROM download WHERE file_safe = :safe AND file_name LIKE :search " +
            "ORDER BY " +
            "CASE WHEN :order = 0 THEN file_date END DESC, " +
            "CASE WHEN :order = 1 THEN file_size END DESC, " +
            "CASE WHEN :order = 2 THEN file_name END ASC, " +
            "CASE WHEN :order = 3 THEN file_origin_url END ASC")
    PagingSource<Integer, DownloadEntity> search(int order, boolean safe, String search);

    // --- One-shot Queries ---

    @Query("SELECT * FROM download WHERE file_safe = 0 ORDER BY file_date DESC LIMIT :limit")
    LiveData<List<DownloadEntity>> getDownloadsLimit(int limit);

    @Transaction
    @Query("SELECT * FROM download ORDER BY file_date DESC")
    List<DownloadEntity> getAllRaw();

    @Query("SELECT * FROM download ORDER BY file_date DESC")
    List<DownloadEntity> getAllRawList();

    @Query("SELECT * FROM download WHERE file_encrypted = 1 ORDER BY file_date DESC")
    List<DownloadEntity> getAllRawEnc();

    // --- Finder Queries ---

    @Query("SELECT * FROM download WHERE uid = :id LIMIT 1")
    DownloadEntity findByIdSync(int id);

    @Query("SELECT * FROM download WHERE file_path = :path LIMIT 1")
    DownloadEntity findByFilePath(String path);

    @Query("SELECT * FROM download WHERE uid = :id LIMIT 1")
    DownloadEntity findById(int id);

    @Query("SELECT uid FROM download")
    List<Integer> getAllIds();

    // --- Write Operations ---

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    Long insert(DownloadEntity download);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    long[] insertAll(List<DownloadEntity> downloadEntityList);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    long insertSync(DownloadEntity download);

    // --- Delete Operations ---

    @Delete
    Integer deleteSyncEntity(DownloadEntity download);

    @Query("DELETE FROM download WHERE uid = :downloadId")
    Integer deleteSync(int downloadId);

    @Query("DELETE FROM download")
    Integer deleteAll();

    // --- Utility ---

    @Query("SELECT COUNT(*) FROM download")
    Integer getRowCount();
}