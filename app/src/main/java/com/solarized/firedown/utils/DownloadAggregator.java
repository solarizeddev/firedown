package com.solarized.firedown.utils;

import com.solarized.firedown.data.entity.DownloadEntity;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Pure function: walks a full list of downloads under a given sort
 * mode and returns a per-category {@link GroupAggregate} map (count
 * + total bytes). Used by the downloads list to populate the
 * count/size on section headers without paying per-bind cost.
 *
 * <p>Works off {@link DownloadSortOrganizer#getCategory(DownloadEntity)}
 * — the same category function that the separator-insertion path
 * uses — so the map keys line up with the categories the adapter
 * sees when it renders header rows.
 */
public final class DownloadAggregator {
    private DownloadAggregator() {}

    public static Map<Integer, GroupAggregate> aggregate(List<DownloadEntity> items, int sortType) {
        DownloadSortOrganizer organizer = new DownloadSortOrganizer(sortType);
        // Two parallel maps so we can accumulate count and sum in a
        // single pass without boxing per increment.
        HashMap<Integer, int[]>  counts = new HashMap<>();
        HashMap<Integer, long[]> sizes  = new HashMap<>();
        for (DownloadEntity entity : items) {
            int cat = organizer.getCategory(entity);
            counts.computeIfAbsent(cat, k -> new int[1])[0]++;
            sizes .computeIfAbsent(cat, k -> new long[1])[0] += entity.getFileSize();
        }
        HashMap<Integer, GroupAggregate> out = new HashMap<>(counts.size());
        for (Map.Entry<Integer, int[]> e : counts.entrySet()) {
            int  c = e.getValue()[0];
            long s = sizes.getOrDefault(e.getKey(), new long[1])[0];
            out.put(e.getKey(), new GroupAggregate(c, s));
        }
        return out;
    }
}
