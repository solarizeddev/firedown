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
     */
    @Query("SELECT * FROM tabstate ORDER BY archived_at DESC, file_date DESC")
    PagingSource<Integer, TabStateArchivedEntity> getArchive();

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