package com.solarized.firedown.utils;

/**
 * Per-group totals for the downloads list section headers.
 * Built once per sort change by {@link DownloadAggregator} and
 * consumed by the adapter when binding a header row.
 */
public final class GroupAggregate {
    public final int  count;
    public final long totalSize;

    public GroupAggregate(int count, long totalSize) {
        this.count = count;
        this.totalSize = totalSize;
    }
}
