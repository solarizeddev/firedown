package com.solarized.firedown.ui.adapters;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.RecyclerView;

import com.solarized.firedown.R;
import com.google.android.material.card.MaterialCardView;
import com.solarized.firedown.utils.SelectionStyling;

/**
 * Single-card header for DownloadFragment's RecyclerView: "Reinstalled?
 * Restore your previous downloads", shown only after a DETECTED reinstall
 * whose automatic backup restore came back empty (see
 * {@code DownloadBackupMirror} — sentinel-pair detection +
 * {@code isRestoreBannerPending}). Card tap launches the SAF restore flow;
 * the X retires the banner permanently.
 *
 * <p>Same self-hiding ConcatAdapter-header pattern as
 * {@link IncognitoInProgressHeaderAdapter}; the fragment keeps at most ONE
 * informational banner visible at a time (this one yields while vault
 * downloads are in flight).
 */
public class RestoreBannerAdapter extends RecyclerView.Adapter<RestoreBannerAdapter.BannerViewHolder> {

    public interface OnBannerListener {
        void onRestoreBannerClicked();

        void onRestoreBannerDismissed();
    }

    private boolean mVisible = false;
    // When true the banner is the "grant access to already-restored files"
    // variant (reinstall auto-restore brought the list back, but the
    // foreign-owned public files need a SAF grant to open) rather than the
    // "restore your previous downloads" variant (auto-restore empty). Same tap
    // target and flow — only the subtitle copy differs.
    private boolean mGrantMode = false;
    @Nullable
    private final OnBannerListener mListener;

    public RestoreBannerAdapter(@Nullable OnBannerListener listener) {
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

    /** Select the grant-access subtitle variant. Call before/while visible; a
     *  change while shown re-binds the card. */
    public void setGrantMode(boolean grantMode) {
        if (grantMode == mGrantMode) {
            return;
        }
        mGrantMode = grantMode;
        if (mVisible) {
            notifyItemChanged(0);
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
                .inflate(R.layout.item_restore_banner, parent, false);
        // Same brand wash as the incognito-in-progress header — composed in
        // code because the tone is derived, not a resource color.
        if (v instanceof MaterialCardView card) {
            card.setCardBackgroundColor(SelectionStyling.selectedCardWashOver(
                    parent.getContext(), com.google.android.material.R.attr.colorSurface));
        }
        return new BannerViewHolder(v);
    }

    @Override
    public void onBindViewHolder(@NonNull BannerViewHolder holder, int position) {
        holder.bind(mListener, mGrantMode);
    }

    public static class BannerViewHolder extends RecyclerView.ViewHolder {

        private final View mClose;
        private final TextView mSubtitle;

        BannerViewHolder(@NonNull View itemView) {
            super(itemView);
            mClose = itemView.findViewById(R.id.restore_banner_close);
            mSubtitle = itemView.findViewById(R.id.restore_banner_subtitle);
        }

        void bind(@Nullable OnBannerListener listener, boolean grantMode) {
            if (mSubtitle != null) {
                mSubtitle.setText(grantMode
                        ? R.string.restore_banner_grant_subtitle
                        : R.string.restore_banner_subtitle);
            }
            // Grant mode is non-dismissible: the restored files are unopenable
            // until a grant is taken, so hiding the X keeps the one-tap fix in
            // front of the user instead of letting a dismiss strand them with a
            // list of dead entries. The re-import variant keeps its dismiss.
            mClose.setVisibility(grantMode ? View.GONE : View.VISIBLE);
            if (listener == null) {
                itemView.setOnClickListener(null);
                mClose.setOnClickListener(null);
                return;
            }
            itemView.setOnClickListener(v -> listener.onRestoreBannerClicked());
            mClose.setOnClickListener(grantMode ? null
                    : v -> listener.onRestoreBannerDismissed());
        }
    }
}
