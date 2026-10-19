package com.solarized.firedown.ui.adapters;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.card.MaterialCardView;
import com.solarized.firedown.R;
import com.solarized.firedown.ui.HostLabel;
import com.solarized.firedown.utils.SheetGroups;

import java.text.NumberFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Per-host rows (host + per-host block count) for the "Blocked on this page"
 * sheet (BlockedAdsDetailDialogFragment, uBlock's per-page list). It used
 * to be a mixed-type adapter with per-category header rows for the ETP
 * trackers sheet; that sheet and the tracker-count pipeline behind it were
 * removed with the per-site tracking switch, and the header row type went
 * with them — the host row is all that remains.
 *
 * <p>Each row is a grouped sheet card in the anatomy of the Settings
 * "Most blocked domains today" rows (item_blocked_tracker_host): the host
 * through {@link HostLabel}, the count always shown and locale-formatted
 * like {@code CountPreference}'s. The corners come from the row's place in
 * the list ({@link SheetGroups#shapeFor}), and since the list re-sorts by
 * count as blocks land, a row's place changes without its content
 * changing — so every list change re-shapes the bound rows with
 * {@link #PAYLOAD_SHAPE} (corners only, no full rebind, no change
 * animation).
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

    private static final Object PAYLOAD_SHAPE = new Object();

    /** Common base so the adapter's generic parameter is concrete. */
    public abstract static class Item {
        /** Stable key that survives content changes — used by DiffUtil's
         *  areItemsTheSame so a host whose count went from 2 to 3 stays
         *  the same row instead of being treated as a remove + add. */
        abstract String key();
    }

    /** Per-host row — host, its registrable domain, and the count. */
    public static final class HostRow extends Item {
        public final String host;
        public final String domain;
        public final long count;

        public HostRow(String host, String domain, long count) {
            this.host = host;
            this.domain = domain;
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
                return oh.count == nh.count && Objects.equals(oh.host, nh.host)
                        && Objects.equals(oh.domain, nh.domain);
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
            vh.host.setText(HostLabel.format(vh.itemView.getContext(), r.host, r.domain));
            // Allocated per bind like CountPreference's, so the column follows
            // a locale change without the sheet being reopened.
            vh.count.setText(NumberFormat.getInstance(Locale.getDefault()).format(r.count));
            applyShape(vh, position);
        }
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position,
                                 @NonNull List<Object> payloads) {
        if (!payloads.isEmpty() && payloads.stream().allMatch(p -> p == PAYLOAD_SHAPE)) {
            if (holder instanceof HostViewHolder vh) {
                applyShape(vh, position);
            }
            return;
        }
        super.onBindViewHolder(holder, position, payloads);
    }

    @Override
    public void onCurrentListChanged(@NonNull List<Item> previousList,
                                     @NonNull List<Item> currentList) {
        super.onCurrentListChanged(previousList, currentList);
        if (!currentList.isEmpty()) {
            notifyItemRangeChanged(0, currentList.size(), PAYLOAD_SHAPE);
        }
    }

    private void applyShape(@NonNull HostViewHolder vh, int position) {
        if (vh.itemView instanceof MaterialCardView card) {
            SheetGroups.apply(card, position == 0, position >= getItemCount() - 1);
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
