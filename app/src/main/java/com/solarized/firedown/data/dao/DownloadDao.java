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
    //
    // (file_status IN (0, 2)) returns 1 for active (PROGRESS/QUEUED)
    // rows, 0 otherwise; sorting that DESC hoists every active download
    // to the top of the list regardless of the secondary sort field, so
    // a user opening the downloads screen on any sort sees their
    // in-flight downloads without scrolling. The secondary clause then
    // orders both blocks (active + finished) by the user's chosen field.

    @Query("SELECT * FROM download WHERE file_safe = 0 ORDER BY (file_status IN (0, 2)) DESC, file_date DESC")
    PagingSource<Integer, DownloadEntity> getDownloads();

    @Query("SELECT * FROM download WHERE file_safe = 0 ORDER BY (file_status IN (0, 2)) DESC, file_size DESC")
    PagingSource<Integer, DownloadEntity> getDownloadsSize();

    @Query("SELECT * FROM download WHERE file_safe = 0 ORDER BY (file_status IN (0, 2)) DESC, file_name ASC")
    PagingSource<Integer, DownloadEntity> getDownloadsName();

    @Query("SELECT * FROM download WHERE file_safe = 0 ORDER BY (file_status IN (0, 2)) DESC, file_origin_url ASC, file_date DESC")
    PagingSource<Integer, DownloadEntity> getDownloadsDomain();

    // --- Paging Source Queries (Safe/Encrypted) ---
    //
    // Vault items are typically finished+moved so the hoist is a no-op
    // in practice — kept here for symmetry so any future code path that
    // lands an in-flight vault download still gets the pin-to-top
    // behavior without a second migration.

    @Query("SELECT * FROM download WHERE file_safe = 1 ORDER BY (file_status IN (0, 2)) DESC, file_date DESC")
    PagingSource<Integer, DownloadEntity> getSafe();

    @Query("SELECT * FROM download WHERE file_safe = 1 ORDER BY (file_status IN (0, 2)) DESC, file_size DESC")
    PagingSource<Integer, DownloadEntity> getSafeSize();

    @Query("SELECT * FROM download WHERE file_safe = 1 ORDER BY (file_status IN (0, 2)) DESC, file_name ASC")
    PagingSource<Integer, DownloadEntity> getSafeName();

    @Query("SELECT * FROM download WHERE file_safe = 1 ORDER BY (file_status IN (0, 2)) DESC, file_origin_url ASC, file_date DESC")
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

    /**
     * Live count of vault-stored downloads (file_safe = 1). Surfaced
     * on the home empty hero so the vault entry can carry an item
     * count badge that updates as the user saves / deletes private
     * files. LiveData (not a one-shot Integer) so the badge stays in
     * sync without manual refresh hooks.
     */
    @Query("SELECT COUNT(*) FROM download WHERE file_safe = 1")
    LiveData<Integer> getSafeCountLive();

    /** Live count of finished regular (non-vault) downloads. Drives
     *  the home 'Downloads' card subtitle (N files saved). */
    @Query("SELECT COUNT(*) FROM download WHERE file_safe = 0 AND file_status = 1")
    LiveData<Integer> getRegularFinishedCountLive();

    /** Live sum of bytes for finished regular (non-vault) downloads.
     *  Drives the home 'Downloads' card subtitle's size suffix. */
    @Query("SELECT IFNULL(SUM(file_size), 0) FROM download WHERE file_safe = 0 AND file_status = 1")
    LiveData<Long> getRegularFinishedSizeLive();

    /** Live list of in-flight regular downloads (PROGRESS=0, QUEUED=2,
     *  non-vault), newest first. Drives the home active-download strip,
     *  which renders only the head row and surfaces the rest as a
     *  '+N more' suffix — so the full set is needed for an accurate count. */
    @Query("SELECT * FROM download WHERE file_safe = 0 AND file_status IN (0, 2) ORDER BY file_date DESC")
    LiveData<List<DownloadEntity>> getActiveRegularLive();

    /** Live full list of regular (non-vault) downloads, used purely for
     *  per-group aggregation on the downloads list section headers
     *  (count + total bytes by sort category). Separate from the paging
     *  source so the adapter can render aggregates without consuming
     *  the entire paged stream. */
    @Query("SELECT * FROM download WHERE file_safe = 0")
    LiveData<List<DownloadEntity>> getAllRegularLive();

    /** Vault equivalent of {@link #getAllRegularLive}. */
    @Query("SELECT * FROM download WHERE file_safe = 1")
    LiveData<List<DownloadEntity>> getAllSafeLive();
}