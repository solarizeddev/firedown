package com.solarized.firedown.utils;

import java.util.Objects;

/**
 * Per-group totals for the downloads list section headers.
 * Built once per sort change by {@link DownloadAggregator} and
 * consumed by the adapter when binding a header row.
 *
 * <p>{@code equals}/{@code hashCode} are deliberate: the adapter
 * short-circuits {@code setAggregates} when the new map is
 * structurally identical to the old one, and the map's own equality
 * delegates to ours per entry. Without these overrides a progress
 * tick on any download would re-emit a fresh map (new
 * GroupAggregate instances, identical fields), the short-circuit
 * would miss, and every visible row would rebind — re-firing
 * thumbnail decodes for audio files with no embedded art and
 * causing a visible blink on every progress update.
 */
public final class GroupAggregate {
    public final int  count;
    public final long totalSize;

    public GroupAggregate(int count, long totalSize) {
        this.count = count;
        this.totalSize = totalSize;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof GroupAggregate other)) return false;
        return count == other.count && totalSize == other.totalSize;
    }

    @Override
    public int hashCode() {
        return Objects.hash(count, totalSize);
    }
}
