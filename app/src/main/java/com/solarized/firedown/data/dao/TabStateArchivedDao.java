package com.solarized.firedown.data.dao;

import androidx.lifecycle.LiveData;
import androidx.paging.PagingSource;
import androidx.room.Dao;
import androidx.room.Delete;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import androidx.room.Transaction;

import com.solarized.firedown.data.entity.TabStateArchivedEntity;

import java.util.List;

@Dao
public interface TabStateArchivedDao {

    // @Transaction: an unbounded read can span several CursorWindows and
    // must pin one snapshot — see DownloadDao "One-shot Queries".
    @Transaction
    @Query("SELECT * FROM tabstate")
    List<TabStateArchivedEntity> getAllRaw();

    /**
     * Live count of tabs archived at or after {@code sinceMs}. Powers the
     * "X tabs archived in the last [interval]" banner — TabsFragment
     * passes {@code now - interval} so the banner reflects only tabs
     * archived inside the user's chosen window. Rows from before the
     * v1 → v2 migration carry {@code archived_at = 0} and so never
     * count, even when the user's window extends to epoch-zero.
     */
    @Query("SELECT COUNT(*) FROM tabstate WHERE archived_at >= :sinceMs AND archived_at > 0")
    LiveData<Integer> getCountSinceLive(long sinceMs);

    /**
     * PagingSource for Paging 3. Ordered by when each tab was ARCHIVED
     * (archived_at DESC) so the most recently archived tab sits on top —
     * what the user expects. file_date (original creation) is the tiebreaker
     * and the fallback for legacy pre-v2 rows (archived_at = 0), which sort
     * to the bottom among themselves by creation date.
     *
     * <p><b>file_state is deliberately NOT selected</b> (NULL-aliased so the
     * row still maps onto the entity). It holds the full serialized
     * GeckoSession state, and a single heavy tab's blob can exceed the ~2 MB
     * SQLite CursorWindow — making that ROW unreadable
     * ({@code SQLiteBlobTooBigException}) and failing the whole paging load.
     * The symptom was exactly "banner says 17 tabs archived, archive screen
     * empty": the banner's COUNT(*) never materializes the blob, while the
     * old {@code SELECT *} here died on the first oversized row and the
     * fragment's LoadState.Error path showed the empty state. The list never
     * needs the blob anyway — it's fetched on tap, chunked (see
     * {@link #getSessionStateChunk}).</p>
     */
    @Query("SELECT uid, file_title, file_uri, file_icon, NULL AS file_state, "
            + "file_date, archived_at FROM tabstate "
            + "ORDER BY archived_at DESC, file_date DESC")
    PagingSource<Integer, TabStateArchivedEntity> getArchive();

    /**
     * Character length of the stored session state (SQLite {@code length()}
     * on TEXT counts characters, matching {@code substr} semantics). Null
     * when the row is missing or the state is NULL.
     */
    @Query("SELECT length(file_state) FROM tabstate WHERE uid = :id")
    Long getSessionStateLength(int id);

    /**
     * One chunk of the session state ({@code substr} is 1-based and
     * character-addressed). Chunked because reading the WHOLE blob in one
     * row is what hits the CursorWindow limit — each chunk stays far below
     * it, so even a multi-MB session state is readable. See
     * {@code TabStateArchivedRepository.getSessionStateSync}.
     */
    @Query("SELECT substr(file_state, :start, :len) FROM tabstate WHERE uid = :id")
    String getSessionStateChunk(int id, long start, long len);

    /**
     * Age purge: drop archived tabs whose archive time is older than
     * {@code cutoffMs}. Legacy pre-v2 rows (archived_at = 0) carry no archive
     * time and are intentionally NOT aged out here — the count cap
     * ({@code trimToNewest}) is what bounds them instead.
     */
    @Query("DELETE FROM tabstate WHERE archived_at > 0 AND archived_at < :cutoffMs")
    Integer purgeOlderThan(long cutoffMs);

    /**
     * Count cap: keep the newest {@code keep} archived tabs (by archive time,
     * creation date as tiebreaker — same order as {@link #getArchive()}) and
     * delete the rest. Legacy archived_at = 0 rows sort last, so they're the
     * first dropped when over the cap.
     */
    @Query("DELETE FROM tabstate WHERE uid NOT IN "
            + "(SELECT uid FROM tabstate ORDER BY archived_at DESC, file_date DESC LIMIT :keep)")
    Integer trimToNewest(int keep);

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