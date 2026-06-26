package com.solarized.firedown.sync.model;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The sync merge engine — a pure function (no Android), union by normalized URL,
 * last-writer-wins per URL with tombstones (docs/BOOKMARKS_SYNC.md §6). The
 * server stays dumb; all merge logic is here, and it is the unit-tested heart of
 * correctness.
 *
 * <p>Per URL: keep the entry with the greater {@link SyncItem#effectiveTs()}. On
 * an exact tie a tombstone wins (deterministic convergence — a delete is sticky).
 * A tombstone newer than a live item deletes it, and a live edit newer than a
 * tombstone resurrects it.</p>
 */
public final class Merge {

    private Merge() {}

    /** Merges two item sets into the converged set (live items + live tombstones). */
    public static List<SyncItem> merge(Collection<SyncItem> local, Collection<SyncItem> remote) {
        Map<String, SyncItem> winners = new HashMap<>();
        accumulate(winners, local);
        accumulate(winners, remote);
        return new ArrayList<>(winners.values());
    }

    private static void accumulate(Map<String, SyncItem> winners, Collection<SyncItem> items) {
        if (items == null) {
            return;
        }
        for (SyncItem candidate : items) {
            if (candidate == null || candidate.url == null || candidate.url.isEmpty()) {
                continue;
            }
            SyncItem current = winners.get(candidate.url);
            if (current == null || wins(candidate, current)) {
                winners.put(candidate.url, candidate);
            }
        }
    }

    /** Whether {@code a} beats {@code b} for the same URL. */
    private static boolean wins(SyncItem a, SyncItem b) {
        long ta = a.effectiveTs();
        long tb = b.effectiveTs();
        if (ta != tb) {
            return ta > tb;
        }
        // Tie: a tombstone is sticky (so both devices converge to the deletion).
        return a.deleted && !b.deleted;
    }

    /**
     * Drops tombstones whose {@code deletedAt} is older than {@code ttlMillis}
     * relative to {@code now} — they have had time to propagate, so the tombstone
     * can be forgotten (a GC pass also hard-deletes them locally). Live items are
     * always kept.
     */
    public static List<SyncItem> gcTombstones(Collection<SyncItem> items, long now, long ttlMillis) {
        List<SyncItem> out = new ArrayList<>(items.size());
        for (SyncItem it : items) {
            if (it.deleted && (now - it.deletedAt) > ttlMillis) {
                continue;
            }
            out.add(it);
        }
        return out;
    }
}
