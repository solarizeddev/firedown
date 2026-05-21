package com.solarized.firedown.ui.adapters;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;

import com.solarized.firedown.R;
import com.solarized.firedown.geckoview.GeckoUblockHelper.HostCount;
import com.solarized.firedown.ui.diffs.TopTrackerDiffCallback;

import java.text.NumberFormat;
import java.util.Locale;

/**
 * Adapter for the 'Top trackers' list inside TrackersInfoSheet. Rows
 * are pre-sorted by the JS side (descending count) so binding is
 * direct — no sorting in {@link #onBindViewHolder(ViewHolder, int)}.
 *
 * <p>Capped at 10 entries upstream by firedown.js' TOP_TRACKERS_PUSH;
 * the adapter has no row-count limit of its own.</p>
 */
public class TopTrackersAdapter extends ListAdapter<HostCount, TopTrackersAdapter.ViewHolder> {

    public TopTrackersAdapter() {
        super(new TopTrackerDiffCallback());
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        final TextView count;
        final TextView host;

        ViewHolder(@NonNull View itemView) {
            super(itemView);
            count = itemView.findViewById(R.id.top_tracker_count);
            host = itemView.findViewById(R.id.top_tracker_host);
        }
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View row = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_top_tracker, parent, false);
        return new ViewHolder(row);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        HostCount hc = getItem(position);
        // NumberFormat is allocated per-bind rather than cached on the
        // holder so the adapter follows the current locale if the user
        // changes it without recreating the sheet. Top-10 list, n binds
        // per push — negligible allocation cost.
        NumberFormat fmt = NumberFormat.getInstance(Locale.getDefault());
        holder.count.setText(fmt.format(hc.count));
        holder.host.setText(hc.host);
    }
}
