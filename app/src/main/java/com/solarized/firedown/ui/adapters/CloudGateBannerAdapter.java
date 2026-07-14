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
 * Single-card header for DownloadFragment's RecyclerView: the cloud-backup
 * interest gate announce ("Encrypted cloud backup — not built yet"). Card tap
 * opens the gate screen (CloudGateFragment); the X retires it permanently, and
 * counting interest on the gate screen retires it too.
 *
 * <p>Same self-hiding ConcatAdapter-header pattern as {@link SyncBannerAdapter}
 * / {@link IncognitoInProgressHeaderAdapter}.
 */
public class CloudGateBannerAdapter extends RecyclerView.Adapter<CloudGateBannerAdapter.BannerViewHolder> {

    public interface OnBannerListener {
        void onCloudGateBannerClicked();

        void onCloudGateBannerDismissed();
    }

    private boolean mVisible = false;
    @Nullable
    private final OnBannerListener mListener;

    public CloudGateBannerAdapter(@Nullable OnBannerListener listener) {
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
                .inflate(R.layout.item_cloud_gate_banner, parent, false);
        // Same soft brand wash as the sync/incognito banners (composed in code —
        // derived from colorPrimaryContainer over the surface), so the announce
        // cards read identically across lists.
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
            mClose = itemView.findViewById(R.id.cloud_gate_banner_close);
        }

        void bind(@Nullable OnBannerListener listener) {
            if (listener == null) {
                itemView.setOnClickListener(null);
                mClose.setOnClickListener(null);
                return;
            }
            itemView.setOnClickListener(v -> listener.onCloudGateBannerClicked());
            mClose.setOnClickListener(v -> listener.onCloudGateBannerDismissed());
        }
    }
}
