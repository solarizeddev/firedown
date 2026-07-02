package com.solarized.firedown.sync;

import com.solarized.firedown.sync.model.VaultEntry;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The delete↔load ordering guard for the backed-up-files list: object ids whose
 * server delete is IN FLIGHT (optimistically removed from the UI, not yet
 * confirmed gone by a fresh manifest pull). Extracted from
 * {@code CloudBackupListFragment} so the race semantics are unit-tested as the
 * real code (a fragment-embedded Set can't be) — the fragment delegates to this.
 *
 * <p>The ordering rules, each of which closed a real bug:
 * <ul>
 *   <li><b>A racing load() must not resurrect a deleting row.</b> The optimistic
 *       remove drops the row before the delete's OCC push commits, so a manifest
 *       pull landing in that window STILL contains the entry;
 *       {@link #filterAndReconcile} drops it from the visible result.</li>
 *   <li><b>Delete-SUCCESS does NOT clear the guard</b> — only a fresh pull that no
 *       longer lists the id does (the {@code retainAll} inside
 *       {@link #filterAndReconcile}). Clearing eagerly in the success callback
 *       reopens the ghost by the REVERSE ordering: a load() whose pull PRE-dated
 *       the delete can run after that clear and re-add the stale row. Object ids
 *       are server-random per create, so a reconciled id can never wrongly match
 *       a future entry, and a lingering guarded id (no load since the delete) is
 *       a harmless no-op filter.</li>
 *   <li><b>Only delete-FAILURE clears an id directly</b> ({@link #clear}) — the
 *       entry is still on the server and the caller re-inserts the row.</li>
 * </ul>
 *
 * <p>Main-thread only in production (all manager callbacks post to main), so no
 * synchronization.
 */
public final class PendingRemovals {

    private final Set<String> mIds = new HashSet<>();

    /** Guards an id whose optimistic remove just started (server delete in flight). */
    public void add(String objectId) {
        if (objectId != null) {
            mIds.add(objectId);
        }
    }

    /** Un-guards an id whose delete FAILED (the entry is still on the server and
     *  the caller restores the row). Never called on success — see the class doc. */
    public void clear(String objectId) {
        if (objectId != null) {
            mIds.remove(objectId);
        }
    }

    /** True while {@code objectId}'s delete is in flight / unconfirmed. */
    public boolean isPending(String objectId) {
        return objectId != null && mIds.contains(objectId);
    }

    /** The number of guarded ids (test/diagnostic). */
    public int size() {
        return mIds.size();
    }

    /**
     * Applies a fresh manifest pull: returns the entries that should be VISIBLE
     * (every pulled entry whose delete is not in flight) and reconciles the guard
     * set against the pull — an id the server no longer lists is confirmed gone
     * and dropped; a still-listed id (a stale pull, or a failed delete that never
     * committed) stays guarded.
     */
    public List<VaultEntry> filterAndReconcile(List<VaultEntry> pulled) {
        List<VaultEntry> visible = new ArrayList<>(pulled.size());
        Set<String> pulledIds = new HashSet<>();
        for (VaultEntry e : pulled) {
            pulledIds.add(e.objectId);
            if (!mIds.contains(e.objectId)) {
                visible.add(e);
            }
        }
        mIds.retainAll(pulledIds);
        return visible;
    }
}
