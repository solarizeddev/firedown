package com.solarized.firedown.sync.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Unit tests for the pure merge engine + blob (de)serialization. */
public class MergeTest {

    private static SyncItem live(String url, long updatedAt) {
        return new SyncItem(url, "t", 1, "i", "p", updatedAt, false, 0);
    }

    private static SyncItem tomb(String url, long deletedAt) {
        return new SyncItem(url, null, 0, null, null, 0, true, deletedAt);
    }

    private static Map<String, SyncItem> byUrl(List<SyncItem> items) {
        Map<String, SyncItem> m = new HashMap<>();
        for (SyncItem it : items) {
            m.put(it.url, it);
        }
        return m;
    }

    @Test
    public void unionOfDisjointAdds() {
        List<SyncItem> merged = Merge.merge(
                Arrays.asList(live("a", 1)),
                Arrays.asList(live("b", 1)));
        assertEquals(2, merged.size());
    }

    @Test
    public void newerEditWins() {
        List<SyncItem> merged = Merge.merge(
                Arrays.asList(live("a", 10)),
                Arrays.asList(live("a", 20)));
        assertEquals(1, merged.size());
        assertEquals(20, byUrl(merged).get("a").updatedAt);
    }

    @Test
    public void tombstoneNewerThanEditDeletes() {
        List<SyncItem> merged = Merge.merge(
                Arrays.asList(live("a", 10)),
                Arrays.asList(tomb("a", 20)));
        assertTrue(byUrl(merged).get("a").deleted);
    }

    @Test
    public void editNewerThanTombstoneResurrects() {
        List<SyncItem> merged = Merge.merge(
                Arrays.asList(tomb("a", 10)),
                Arrays.asList(live("a", 20)));
        assertFalse(byUrl(merged).get("a").deleted);
    }

    @Test
    public void tieGoesToTombstone() {
        List<SyncItem> merged = Merge.merge(
                Arrays.asList(live("a", 50)),
                Arrays.asList(tomb("a", 50)));
        assertTrue(byUrl(merged).get("a").deleted);
    }

    @Test
    public void mergeIsIdempotent() {
        List<SyncItem> first = Merge.merge(
                Arrays.asList(live("a", 10), tomb("b", 5)),
                Arrays.asList(live("a", 20), live("c", 1)));
        List<SyncItem> second = Merge.merge(first, new ArrayList<>(first));
        assertEquals(byUrl(first).keySet(), byUrl(second).keySet());
        assertEquals(20, byUrl(second).get("a").updatedAt);
    }

    @Test
    public void gcDropsOldTombstonesKeepsLiveAndRecent() {
        long now = 1_000_000_000L;
        long ttl = 100;
        List<SyncItem> kept = Merge.gcTombstones(
                Arrays.asList(live("a", now), tomb("b", now - 50), tomb("c", now - 500)),
                now, ttl);
        Map<String, SyncItem> m = byUrl(kept);
        assertEquals(2, kept.size());
        assertTrue(m.containsKey("a"));   // live kept
        assertTrue(m.containsKey("b"));   // recent tombstone kept
        assertNull(m.get("c"));           // old tombstone dropped
    }

    @Test
    public void blobJsonRoundTrip() {
        List<SyncItem> items = Arrays.asList(live("https://a/", 10), tomb("https://b/", 20));
        String json = BookmarkDoc.toJson(items, 99);
        List<SyncItem> back = BookmarkDoc.fromJson(json);
        Map<String, SyncItem> m = byUrl(back);
        assertEquals(2, back.size());
        assertEquals(10, m.get("https://a/").updatedAt);
        assertTrue(m.get("https://b/").deleted);
        assertEquals(20, m.get("https://b/").deletedAt);
    }
}
