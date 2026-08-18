package com.solarized.firedown.ui.adapters;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.DrawableRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.appcompat.widget.AppCompatImageView;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.card.MaterialCardView;
import com.solarized.firedown.R;
import com.solarized.firedown.utils.SelectionStyling;

/**
 * Single-card announce header for a list's RecyclerView: an icon, a title, a
 * subtitle and an X. Card tap runs the CTA; the X retires it permanently.
 * Shown only while the feature it announces is OFF and the banner hasn't been
 * retired (dismissed, or the feature later turned on).
 *
 * <p>Two instances ship, differing ONLY in copy + glyph, which is why this is
 * PARAMETERIZED rather than copied into a second near-identical adapter:
 * <ul>
 *   <li>{@code WebBookmarkFragment} — "Sync your bookmarks across devices"
 *       (the original; the no-copy constructor keeps its defaults).</li>
 *   <li>{@code DownloadFragment} — "Back up your downloads to the cloud".</li>
 * </ul>
 * The class keeps its historical {@code Sync*} name the way the Cloud Backup
 * feature keeps its internal {@code Vault*} names: user-facing copy is the
 * thing that says "Cloud Backup", not the type.
 *
 * <p>Same self-hiding ConcatAdapter-header pattern as
 * {@link IncognitoInProgressHeaderAdapter}.
 */
public class SyncBannerAdapter extends RecyclerView.Adapter<SyncBannerAdapter.BannerViewHolder> {

    public interface OnBannerListener {
        void onSyncBannerClicked();

        void onSyncBannerDismissed();
    }

    private boolean mVisible = false;
    @Nullable
    private final OnBannerListener mListener;
    @StringRes
    private int mTitleRes;
    @StringRes
    private int mSubtitleRes;
    @DrawableRes
    private final int mIconRes;

    /** The bookmarks-sync banner (the original copy + glyph). */
    public SyncBannerAdapter(@Nullable OnBannerListener listener) {
        this(listener, R.string.sync_banner_title, R.string.sync_banner_subtitle,
                R.drawable.cloud_outline_24);
    }

    public SyncBannerAdapter(@Nullable OnBannerListener listener,
                             @StringRes int titleRes,
                             @StringRes int subtitleRes,
                             @DrawableRes int iconRes) {
        mListener = listener;
        mTitleRes = titleRes;
        mSubtitleRes = subtitleRes;
        mIconRes = iconRes;
    }

    /**
     * Swap the banner's copy in place — the STAGE mechanism: one banner
     * instance carries state-dependent text (the Downloads cloud banner's
     * discovery vs activation stages) instead of a second adapter, the same
     * one-affordance rule as the Cloud screen's morphing CTA. Idempotent; a
     * visible card rebinds so the new copy paints immediately.
     */
    public void setCopy(@StringRes int titleRes, @StringRes int subtitleRes) {
        if (titleRes == mTitleRes && subtitleRes == mSubtitleRes) {
            return;
        }
        mTitleRes = titleRes;
        mSubtitleRes = subtitleRes;
        if (mVisible) {
            notifyItemChanged(0);
        }
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
        // Same soft brand wash as the other headers (composed in code — the
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
        holder.bind(mListener, mTitleRes, mSubtitleRes, mIconRes);
    }

    public static class BannerViewHolder extends RecyclerView.ViewHolder {

        private final View mClose;
        private final TextView mTitle;
        private final TextView mSubtitle;
        private final AppCompatImageView mIcon;

        BannerViewHolder(@NonNull View itemView) {
            super(itemView);
            mClose = itemView.findViewById(R.id.sync_banner_close);
            mTitle = itemView.findViewById(R.id.sync_banner_title);
            mSubtitle = itemView.findViewById(R.id.sync_banner_subtitle);
            mIcon = itemView.findViewById(R.id.sync_banner_icon);
        }

        void bind(@Nullable OnBannerListener listener, @StringRes int titleRes,
                  @StringRes int subtitleRes, @DrawableRes int iconRes) {
            mTitle.setText(titleRes);
            mSubtitle.setText(subtitleRes);
            mIcon.setImageResource(iconRes);
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
