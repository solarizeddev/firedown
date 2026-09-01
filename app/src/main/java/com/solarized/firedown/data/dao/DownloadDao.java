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
    //
    // EVERY unbounded list read in this DAO is @Transaction — the 1.1.93
    // crash class. A `SELECT *` over the whole download table no longer
    // fits one ~2 MB CursorWindow on a long-running install (~290 rows of
    // signed URLs + header JSON + descriptions), so the Cursor pages: the
    // FIRST fill counts every row and keeps rows 0..N-1, and moving past N
    // RE-EXECUTES the statement to fill the next window. Outside a
    // transaction those two executions see different snapshots — the batch
    // delete removes rows one autocommit statement at a time on the DiskIO
    // lane while each delete's invalidation re-runs the aggregates LiveData
    // on Room's query thread — so the refill sees FEWER rows than the
    // cached count, the cursor walks to a row the window no longer holds,
    // and reading it throws
    //   IllegalStateException: Couldn't read row 292, col 0 from CursorWindow
    // inside RoomTrackingLiveData ("Exception while computing database live
    // data"), which is fatal. Row 292 = the first row past the initial
    // window, col 0 = the first column read; no oversized blob involved.
    // Room's own @Transaction javadoc names exactly this ("if the query
    // result does not fit into a single CursorWindow, the query result may
    // be corrupted due to changes in the database in between cursor window
    // swaps"), and the Paging sources never hit it because
    // LimitOffsetPagingSource already loads each page inside a transaction.
    // The transaction pins one snapshot across the count and every refill;
    // cost is a deferred read transaction per query. Small literal LIMITs
    // (autocomplete) need none — they fit one window.

    @Transaction
    @Query("SELECT * FROM download ORDER BY file_date DESC")
    List<DownloadEntity> getAllRaw();

    @Transaction
    @Query("SELECT * FROM download ORDER BY file_date DESC")
    List<DownloadEntity> getAllRawList();

    @Transaction
    @Query("SELECT * FROM download WHERE file_encrypted = 1 ORDER BY file_date DESC")
    List<DownloadEntity> getAllRawEnc();

    // --- Finder Queries ---

    @Query("SELECT * FROM download WHERE uid = :id LIMIT 1")
    DownloadEntity findByIdSync(int id);

    @Query("SELECT * FROM download WHERE file_path = :path LIMIT 1")
    DownloadEntity findByFilePath(String path);

    /**
     * First download whose name and byte-size both match — the "is this file
     * already in Downloads?" probe used by Cloud Backup (skip a restore that
     * would duplicate an existing file, and locate a local copy to backfill a
     * missing preview thumbnail from).
     */
    @Query("SELECT * FROM download WHERE file_name = :name AND file_size = :size LIMIT 1")
    DownloadEntity findByNameSize(String name, long size);

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

    /**
     * Flip the negative-cache flag for a single row. Called from the
     * Glide load listener after every decoder in the chain has failed
     * for a completed file, so subsequent paging accesses can short-
     * circuit to the static mime icon instead of re-running the
     * MediaMetadataRetriever / FFmpegThumbnailer chain.
     */
    @Query("UPDATE download SET file_thumbnail_unavailable = :unavailable WHERE uid = :id")
    void setThumbnailUnavailableSync(int id, boolean unavailable);

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

    /** Live sum of bytes for finished regular (non-vault) downloads — drives the
     *  home subtitle's "N saved" figure. */
    @Query("SELECT IFNULL(SUM(file_size), 0) FROM download WHERE file_safe = 0 AND file_status = 1")
    LiveData<Long> getRegularFinishedSizeLive();

    /** Live full list of regular (non-vault) downloads, used purely for
     *  per-group aggregation on the downloads list section headers
     *  (count + total bytes by sort category). Separate from the paging
     *  source so the adapter can render aggregates without consuming
     *  the entire paged stream. */
    @Transaction
    @Query("SELECT * FROM download WHERE file_safe = 0")
    LiveData<List<DownloadEntity>> getAllRegularLive();

    /** Vault equivalent of {@link #getAllRegularLive}. */
    @Transaction
    @Query("SELECT * FROM download WHERE file_safe = 1")
    LiveData<List<DownloadEntity>> getAllSafeLive();
}