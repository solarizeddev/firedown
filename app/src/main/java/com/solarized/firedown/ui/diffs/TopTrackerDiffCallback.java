package com.solarized.firedown.ui.diffs;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;

import com.solarized.firedown.geckoview.GeckoUblockHelper.HostCount;

/**
 * DiffUtil callback for the TrackersInfoSheet top-trackers list.
 *
 * <p>Identity is the hostname — the same host can appear with a
 * different count across pushes, in which case it's the same row
 * with updated content, not a different row. Counts being equal
 * means the row's binding doesn't need to refresh.</p>
 */
public class TopTrackerDiffCallback extends DiffUtil.ItemCallback<HostCount> {

    @Override
    public boolean areItemsTheSame(@NonNull HostCount oldItem, @NonNull HostCount newItem) {
        return oldItem.host.equals(newItem.host);
    }

    @Override
    public boolean areContentsTheSame(@NonNull HostCount oldItem, @NonNull HostCount newItem) {
        return oldItem.host.equals(newItem.host) && oldItem.count == newItem.count;
    }
}
