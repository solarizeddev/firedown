package com.solarized.firedown.ui.adapters;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.card.MaterialCardView;
import com.google.android.material.color.MaterialColors;
import com.solarized.firedown.R;
import com.solarized.firedown.utils.SelectionStyling;

/**
 * The Safe Folder ROW at the top of {@code DownloadFragment}'s list — the
 * vault's door, in the content rather than the toolbar (Files by Google's
 * "Safe folder" row, Samsung My Files' Secure Folder row, Google Photos'
 * Locked Folder tile: none of them put the vault in the chrome, and a fourth
 * toolbar glyph was too crowded at 360dp on-device).
 *
 * <p>It ABSORBS the former incognito-in-progress card, which was the same
 * lock + chevron into the same activity: one row, two states. At rest it is
 * the bare title; while vault (incognito-tab) downloads are in flight
 * ({@code TaskViewModel#getSafeCount}) a "N incognito downloads in progress"
 * subtitle appears and the card takes the brand wash so the state reads as
 * live. Two lock rows into one vault would have been
 * the stacking problem this exists to avoid.
 *
 * <p>Visibility is the fragment's call ({@link #setAllowed}): shown only
 * once the vault HOLDS something (a stored row or an in-flight vault
 * download — a user who never used the vault gets no row at all), and only
 * on the UNFILTERED, non-searching, non-selecting list — a filtered list is
 * a question about the downloads, and the door is not an answer to it. Rides
 * the list's ConcatAdapter at position 0 (above the cloud banner: furniture
 * first, promo second, so the permanent row never shifts).
 */
public class SafeFolderHeaderAdapter
        extends RecyclerView.Adapter<SafeFolderHeaderAdapter.HeaderViewHolder> {

    public interface OnClickListener {
        void onSafeFolderClicked();
    }

    private int mCount = 0;
    private boolean mAllowed = false;
    @Nullable private final OnClickListener mListener;

    public SafeFolderHeaderAdapter(@Nullable OnClickListener listener) {
        mListener = listener;
    }

    /** Show/hide the row. Idempotent; animates via insert/remove at 0. */
    public void setAllowed(boolean allowed) {
        if (allowed == mAllowed) {
            return;
        }
        mAllowed = allowed;
        if (allowed) {
            notifyItemInserted(0);
        } else {
            notifyItemRemoved(0);
        }
    }

    /**
     * Update the in-flight vault download count. Never hides the row — it
     * only switches the subtitle (and the wash) between rest and live.
     */
    public void setCount(int count) {
        int next = Math.max(0, count);
        if (next == mCount) {
            return;
        }
        boolean stateFlip = (mCount == 0) != (next == 0);
        mCount = next;
        if (mAllowed && (stateFlip || next > 0)) {
            notifyItemChanged(0);
        }
    }

    @Override
    public int getItemCount() {
        return mAllowed ? 1 : 0;
    }

    @NonNull
    @Override
    public HeaderViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_safe_folder_header, parent, false);
        return new HeaderViewHolder(v);
    }

    @Override
    public void onBindViewHolder(@NonNull HeaderViewHolder holder, int position) {
        holder.bind(mCount, mListener);
    }

    public static class HeaderViewHolder extends RecyclerView.ViewHolder {

        private final TextView mSubtitle;
        private final int mRestGround;
        private final int mLiveGround;

        HeaderViewHolder(@NonNull View itemView) {
            super(itemView);
            mSubtitle = itemView.findViewById(R.id.safe_folder_subtitle);
            // Rest = quiet furniture on the list's own container tone; live =
            // the brand wash the incognito card always wore (primaryContainer
            // @ 20% over surface, composed in code — not a flat resource).
            mRestGround = MaterialColors.getColor(itemView,
                    com.google.android.material.R.attr.colorSurfaceContainerHigh);
            mLiveGround = SelectionStyling.selectedCardWashOver(itemView.getContext(),
                    com.google.android.material.R.attr.colorSurface);
        }

        void bind(int count, @Nullable OnClickListener listener) {
            if (count > 0) {
                mSubtitle.setText(itemView.getResources().getQuantityString(
                        R.plurals.incognito_downloads_in_progress_title, count, count));
                mSubtitle.setVisibility(View.VISIBLE);
            } else {
                // Bare title at rest (maintainer call: a reassurance line
                // under a door read as nagging; Files by Google shows none).
                mSubtitle.setVisibility(View.GONE);
            }
            if (itemView instanceof MaterialCardView card) {
                card.setCardBackgroundColor(count > 0 ? mLiveGround : mRestGround);
            }
            if (listener != null) {
                itemView.setOnClickListener(v -> listener.onSafeFolderClicked());
            } else {
                itemView.setOnClickListener(null);
            }
        }
    }
}
