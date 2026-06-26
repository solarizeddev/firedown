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

    // All read/list/count queries filter `deleted = 0` so tombstones (sync
    // deletions pending propagation, webbookmark v3) never surface in the UI,
    // export, autocomplete, or the present-set. The sync engine gets its own
    // deleted-inclusive queries when it lands; the hard-delete mutators below
    // stay for the repository re-key path and the tombstone GC.

    @Query("SELECT * FROM webbookmark WHERE deleted = 0")
    List<WebBookmarkEntity> getAllRaw();

    @Query("SELECT uid FROM webbookmark WHERE deleted = 0")
    List<Integer> getAllIds();

    @Query("SELECT * FROM webbookmark WHERE uid LIKE :id AND deleted = 0")
    WebBookmarkEntity getId(int id);

    @Query("SELECT * FROM webbookmark WHERE deleted = 0 ORDER BY file_date DESC")
    PagingSource<Integer, WebBookmarkEntity> getBookmarks();

    /**
     * A–Z variant of {@link #getBookmarks()} for the bookmarks-list
     * sort toggle. COLLATE NOCASE so "amazon" and "Amazon" interleave
     * instead of all-uppercase sorting first. Recency stays the
     * default; {@link #search(String)} deliberately stays
     * recency-ordered — the toggle governs only the unfiltered list.
     */
    @Query("SELECT * FROM webbookmark WHERE deleted = 0 ORDER BY file_title COLLATE NOCASE ASC")
    PagingSource<Integer, WebBookmarkEntity> getBookmarksAlphabetical();

    @Query("SELECT * FROM webbookmark WHERE deleted = 0 ORDER BY file_date DESC LIMIT :limit")
    LiveData<List<WebBookmarkEntity>> getBookmark(int limit);

    @Query("SELECT * FROM webbookmark WHERE deleted = 0 AND (file_url LIKE :search or file_title LIKE :search) ORDER BY file_date DESC")
    PagingSource<Integer, WebBookmarkEntity> search(String search);

    @Query("SELECT * FROM webbookmark WHERE deleted = 0 AND (file_url LIKE :search OR file_title LIKE :search) ORDER BY file_date DESC LIMIT 3")
    List<WebBookmarkEntity> getAutoCompleteSearch(String search);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    Long insert(WebBookmarkEntity web);

    /** Bulk insert for "Import bookmarks" — one transaction, one Room
     *  invalidation (the Paging list refreshes once for the whole import,
     *  not per row). REPLACE so a re-imported URL merges by uid. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insertAll(List<WebBookmarkEntity> bookmarks);

    /**
     * In-place icon refresh for an existing bookmark. Used by
     * IconsRepository when GeckoRuntimeHelper signals a new icon for
     * a URL the user has bookmarked, so the list re-renders with the
     * latest favicon without going through a full insert/replace.
     * Returns the number of rows affected — 0 when no bookmark
     * matches, which is the no-op case and not an error.
     */
    // file_icon IS NOT :icon (null-safe) skips the write — and Room's
    // invalidation — when the favicon is unchanged, so revisiting a bookmarked
    // page doesn't needlessly requery the bookmark list.
    @Query("UPDATE webbookmark SET file_icon = :icon WHERE uid = :id AND file_icon IS NOT :icon")
    int updateIcon(int id, String icon);

    /**
     * Backfills the title for a bookmark created before its page's real title
     * arrived. PLACEHOLDER-ONLY: the WHERE clause restricts the update to a row
     * whose title is still empty or the "About:blank" sentinel (capitalize() of
     * the about:blank fallback getEntityTitle() returns when unset), so a user's
     * renamed bookmark — any other title — is never touched. Returns rows
     * affected (0 = not bookmarked, or already has a real title; both no-ops).
     */
    @Query("UPDATE webbookmark SET file_title = :title WHERE uid = :id "
            + "AND (file_title IS NULL OR file_title = '' OR LOWER(file_title) = 'about:blank')")
    int updateTitleIfPlaceholder(int id, String title);

    @Delete
    Integer delete(WebBookmarkEntity web);

    @Query("DELETE FROM webbookmark WHERE uid = :id")
    Integer deleteById(int id);

    @Query("DELETE FROM webbookmark")
    Integer deleteAll();

    @Query("SELECT COUNT(file_url) FROM webbookmark WHERE deleted = 0")
    Integer getRowCount();

    // ---- bookmarks sync (webbookmark v3) ----

    /** ALL rows including tombstones — the sync engine reads the full set to
     *  merge (the only query that does NOT filter deleted = 0). */
    @Query("SELECT * FROM webbookmark")
    List<WebBookmarkEntity> getAllForSync();

    /** Tombstones a bookmark in place (the sync-enabled delete path), so the
     *  deletion propagates to other devices before the GC drops it. */
    @Query("UPDATE webbookmark SET deleted = 1, deleted_at = :deletedAt, updated_at = :updatedAt WHERE uid = :id")
    int softDelete(int id, long deletedAt, long updatedAt);

    /** Tombstones every live bookmark (sync-enabled "delete all"). */
    @Query("UPDATE webbookmark SET deleted = 1, deleted_at = :t, updated_at = :t WHERE deleted = 0")
    int softDeleteAll(long t);

    /** Hard-deletes tombstones older than the cutoff (GC after the propagation
     *  TTL). Live rows are never touched. */
    @Query("DELETE FROM webbookmark WHERE deleted = 1 AND deleted_at < :cutoff")
    int gcTombstones(long cutoff);

}
