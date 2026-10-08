package com.solarized.firedown.ui.adapters;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;

import com.solarized.firedown.R;

import java.util.Objects;

/**
 * Per-host rows (host + per-host block-count) for the blocked-hosts detail
 * sheet (BlockedAdsDetailDialogFragment, uBlock's per-page list). It used
 * to be a mixed-type adapter with per-category header rows for the ETP
 * trackers sheet; that sheet and the tracker-count pipeline behind it were
 * removed with the per-site tracking switch, and the header row type went
 * with them — the host row is all that remains.
 *
 * <p>Backed by {@link ListAdapter} + {@link DiffUtil} so each
 * counts-LiveData emission only re-binds the rows whose content
 * actually changed. New hosts animate in; existing rows whose count
 * went up rebind cleanly. The detail sheet refires often on
 * tracker-heavy pages (~10/sec while a YouTube ad loads) — without
 * DiffUtil every emission was a notifyDataSetChanged that dropped
 * scroll position transitions and rebuilt every visible row.
 */
public class BlockedTrackerDetailAdapter
        extends ListAdapter<BlockedTrackerDetailAdapter.Item, RecyclerView.ViewHolder> {

    /** Common base so the adapter's generic parameter is concrete. */
    public abstract static class Item {
        /** Stable key that survives content changes — used by DiffUtil's
         *  areItemsTheSame so a host whose count went from 2 to 3 stays
         *  the same row instead of being treated as a remove + add. */
        abstract String key();
    }

    /** Per-host row — host string + per-host count. */
    public static final class HostRow extends Item {
        public final String host;
        public final int count;

        public HostRow(String host, int count) {
            this.host = host;
            this.count = count;
        }

        @Override
        String key() {
            return "r:" + host;
        }
    }

    private static final DiffUtil.ItemCallback<Item> DIFF = new DiffUtil.ItemCallback<>() {
        @Override
        public boolean areItemsTheSame(@NonNull Item oldItem, @NonNull Item newItem) {
            return oldItem.key().equals(newItem.key());
        }

        @Override
        public boolean areContentsTheSame(@NonNull Item oldItem, @NonNull Item newItem) {
            if (oldItem instanceof HostRow oh && newItem instanceof HostRow nh) {
                return oh.count == nh.count && Objects.equals(oh.host, nh.host);
            }
            return false;
        }
    };

    public BlockedTrackerDetailAdapter() {
        super(DIFF);
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inflater = LayoutInflater.from(parent.getContext());
        return new HostViewHolder(inflater.inflate(
                R.layout.item_blocked_tracker_host, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        Item item = getItem(position);
        if (holder instanceof HostViewHolder vh && item instanceof HostRow r) {
            vh.host.setText(r.host);
            // ×N suffix only when the same host fired more than once —
            // a single hit reads cleaner without the count.
            if (r.count > 1) {
                vh.count.setVisibility(View.VISIBLE);
                vh.count.setText(vh.itemView.getResources()
                        .getString(R.string.blocked_trackers_host_count_multiplier, r.count));
            } else {
                vh.count.setVisibility(View.GONE);
            }
        }
    }


    static final class HostViewHolder extends RecyclerView.ViewHolder {
        final TextView host;
        final TextView count;

        HostViewHolder(@NonNull View itemView) {
            super(itemView);
            host = itemView.findViewById(R.id.host_label);
            count = itemView.findViewById(R.id.host_count);
        }
    }
}
