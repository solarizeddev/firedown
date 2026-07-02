package com.solarized.firedown.sync;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.solarized.firedown.sync.model.VaultEntry;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The delete↔load ordering races of the backed-up-files list, tested against the
 * REAL guard ({@link PendingRemovals} — the class {@code CloudBackupListFragment}
 * delegates to), not a re-implementation. Each scenario is a race that shipped
 * (or nearly shipped) as a real bug:
 *
 * <ol>
 *   <li>a load() whose manifest pull pre-dates the delete's OCC commit must not
 *       RESURRECT the optimistically-removed row (the ghost);
 *   <li>the REVERSE ordering — delete success must NOT clear the guard eagerly,
 *       or a stale pull running after the clear re-adds the row;
 *   <li>a failed delete clears the guard directly so the restored row survives
 *       the next load.
 * </ol>
 */
public class PendingRemovalsTest {

    private static VaultEntry entry(String objectId) {
        return new VaultEntry(objectId, "dek", objectId + ".mp4", 100, "video/mp4", 0, 1, null);
    }

    private static List<String> ids(List<VaultEntry> entries) {
        List<String> out = new ArrayList<>();
        for (VaultEntry e : entries) {
            out.add(e.objectId);
        }
        return out;
    }

    @Test
    public void stalePullDoesNotResurrectDeletingRow() {
        PendingRemovals pr = new PendingRemovals();
        pr.add("a"); // optimistic remove of "a"; server delete in flight

        // A load() lands whose pull PRE-dates the delete commit: the manifest still
        // lists "a". The row must stay hidden — and stay guarded (the pull that
        // still lists it proves nothing about the delete).
        List<VaultEntry> visible = pr.filterAndReconcile(
                Arrays.asList(entry("a"), entry("b")));
        assertEquals("only the non-deleting row is visible", List.of("b"), ids(visible));
        assertTrue("a stale pull keeps the id guarded", pr.isPending("a"));
    }

    @Test
    public void freshPullConfirmsDeletionAndReconcilesGuard() {
        PendingRemovals pr = new PendingRemovals();
        pr.add("a");

        // A later pull no longer lists "a" → confirmed gone, guard reconciled.
        List<VaultEntry> visible = pr.filterAndReconcile(List.of(entry("b")));
        assertEquals(List.of("b"), ids(visible));
        assertFalse("a confirmed-gone id is dropped from the guard", pr.isPending("a"));
        assertEquals(0, pr.size());
    }

    /** The reverse-order race the eager success-clear reopened: delete SUCCEEDS,
     *  then a load() whose pull PRE-dated the delete completes. Because success
     *  never clears the guard (there is no success-clear API at all — only
     *  {@link PendingRemovals#clear} for failure), the stale pull can't re-add
     *  the row; the NEXT fresh pull reconciles the id away. */
    @Test
    public void deleteSuccessThenStalePullStillSuppressed() {
        PendingRemovals pr = new PendingRemovals();
        pr.add("a");
        // delete succeeded server-side — caller does nothing to the guard

        // Stale pull (issued before the delete) completes now, still listing "a".
        List<VaultEntry> visible = pr.filterAndReconcile(
                Arrays.asList(entry("a"), entry("b")));
        assertEquals("stale pull after success must not resurrect", List.of("b"), ids(visible));
        assertTrue(pr.isPending("a"));

        // Next fresh pull no longer lists it → guard reconciled.
        visible = pr.filterAndReconcile(List.of(entry("b")));
        assertEquals(List.of("b"), ids(visible));
        assertFalse(pr.isPending("a"));
    }

    @Test
    public void deleteFailureClearsGuardSoRestoredRowSurvivesLoad() {
        PendingRemovals pr = new PendingRemovals();
        pr.add("a");
        // Delete FAILED (offline) — the fragment re-inserts the row and clears the
        // guard, so subsequent loads show the still-on-server entry again.
        pr.clear("a");

        List<VaultEntry> visible = pr.filterAndReconcile(
                Arrays.asList(entry("a"), entry("b")));
        assertEquals("un-guarded row is visible again", Arrays.asList("a", "b"), ids(visible));
        assertFalse(pr.isPending("a"));
    }

    @Test
    public void batchDeleteGuardsAllAndReconcilesIndependently() {
        PendingRemovals pr = new PendingRemovals();
        pr.add("a");
        pr.add("b");
        pr.add("c");

        // A mid-flight pull where "a" already committed its delete, "b"/"c" not yet.
        List<VaultEntry> visible = pr.filterAndReconcile(
                Arrays.asList(entry("b"), entry("c"), entry("d")));
        assertEquals("only the untouched row shows", List.of("d"), ids(visible));
        assertFalse("a is confirmed gone", pr.isPending("a"));
        assertTrue(pr.isPending("b"));
        assertTrue(pr.isPending("c"));
    }

    @Test
    public void nullIdsAreIgnored() {
        PendingRemovals pr = new PendingRemovals();
        pr.add(null);
        pr.clear(null);
        assertFalse(pr.isPending(null));
        assertEquals(0, pr.size());
    }
}
