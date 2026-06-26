package com.solarized.firedown.ui.adapters;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.card.MaterialCardView;
import com.solarized.firedown.R;
import com.solarized.firedown.utils.SelectionStyling;

/**
 * Single-card header for WebBookmarkFragment's RecyclerView: "Sync your
 * bookmarks across devices", shown only when sync is OFF and the announce
 * banner hasn't been retired (dismissed, or sync later enabled). Card tap opens
 * the sync screen; the X retires it permanently.
 *
 * <p>Same self-hiding ConcatAdapter-header pattern as {@link RestoreBannerAdapter}.
 */
public class SyncBannerAdapter extends RecyclerView.Adapter<SyncBannerAdapter.BannerViewHolder> {

    public interface OnBannerListener {
        void onSyncBannerClicked();

        void onSyncBannerDismissed();
    }

    private boolean mVisible = false;
    @Nullable
    private final OnBannerListener mListener;

    public SyncBannerAdapter(@Nullable OnBannerListener listener) {
        mListener = listener;
    }

    /** Show/hide the card. Idempotent; animates via insert/remove at 0. */
    public void setVisible(boolean visible) {
        if (visible == mVisible) {
            return;
        }
        mVisible = visible;
        if (visible) {
            notifyItemInserted(0);
        } else {
            notifyItemRemoved(0);
        }
    }

    @Override
    public int getItemCount() {
        return mVisible ? 1 : 0;
    }

    @NonNull
    @Override
    public BannerViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_sync_banner, parent, false);
        // Same soft brand wash as RestoreBannerAdapter (composed in code — the
        // tone is derived from colorPrimaryContainer over the surface, not a
        // flat resource color), so the two banners read identically.
        if (v instanceof MaterialCardView card) {
            card.setCardBackgroundColor(SelectionStyling.selectedCardWashOver(
                    parent.getContext(), com.google.android.material.R.attr.colorSurface));
        }
        return new BannerViewHolder(v);
    }

    @Override
    public void onBindViewHolder(@NonNull BannerViewHolder holder, int position) {
        holder.bind(mListener);
    }

    public static class BannerViewHolder extends RecyclerView.ViewHolder {

        private final View mClose;

        BannerViewHolder(@NonNull View itemView) {
            super(itemView);
            mClose = itemView.findViewById(R.id.sync_banner_close);
        }

        void bind(@Nullable OnBannerListener listener) {
            if (listener == null) {
                itemView.setOnClickListener(null);
                mClose.setOnClickListener(null);
                return;
            }
            itemView.setOnClickListener(v -> listener.onSyncBannerClicked());
            mClose.setOnClickListener(v -> listener.onSyncBannerDismissed());
        }
    }
}
