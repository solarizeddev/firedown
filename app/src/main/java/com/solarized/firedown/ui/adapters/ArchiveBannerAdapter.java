package com.solarized.firedown.ui.adapters;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.solarized.firedown.R;

/**
 * A single-item adapter that shows an "X tabs archived" banner
 * at the top of the tabs RecyclerView via ConcatAdapter.
 *
 * <p>Call {@link #show(int)} to display the banner with a count,
 * and {@link #dismiss()} to hide it.  The adapter reports 0 or 1
 * items, so the RecyclerView handles insertion/removal animations
 * automatically.</p>
 */
public class ArchiveBannerAdapter extends RecyclerView.Adapter<ArchiveBannerAdapter.BannerViewHolder> {

    public interface OnBannerActionListener {
        void onViewArchive();
        void onDismiss();
    }

    private int mArchivedCount = 0;
    private boolean mVisible = false;
    private final OnBannerActionListener mListener;

    public ArchiveBannerAdapter(@NonNull OnBannerActionListener listener) {
        mListener = listener;
        // Stable IDs so ConcatAdapter doesn't confuse it with tab items
        setHasStableIds(true);
    }

    /** Show the banner with the given archived tab count. */
    public void show(int count) {
        if (count <= 0) return;
        boolean wasVisible = mVisible;
        mArchivedCount = count;
        mVisible = true;
        if (wasVisible) {
            notifyItemChanged(0);
        } else {
            notifyItemInserted(0);
        }
    }

    /** Dismiss the banner. */
    public void dismiss() {
        if (!mVisible) return;
        mVisible = false;
        mArchivedCount = 0;
        notifyItemRemoved(0);
    }

    @Override
    public int getItemCount() {
        return mVisible ? 1 : 0;
    }

    @Override
    public long getItemId(int position) {
        return Long.MIN_VALUE; // unique stable ID that won't collide with tab IDs
    }

    @NonNull
    @Override
    public BannerViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.fragment_tabs_archive_banner, parent, false);
        return new BannerViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull BannerViewHolder holder, int position) {
        holder.bind(mArchivedCount, mListener);
    }

    public static class BannerViewHolder extends RecyclerView.ViewHolder {

        private final TextView mTitle;
        private final TextView mSubtitle;
        private final View mDismiss;

        BannerViewHolder(@NonNull View itemView) {
            super(itemView);
            mTitle = itemView.findViewById(R.id.archive_banner_title);
            mSubtitle = itemView.findViewById(R.id.archive_banner_subtitle);
            mDismiss = itemView.findViewById(R.id.archive_banner_dismiss);
        }

        void bind(int count, OnBannerActionListener listener) {
            String title = itemView.getResources().getQuantityString(
                    R.plurals.archive_banner_title, count, count);
            mTitle.setText(title);
            mSubtitle.setText(R.string.archive_banner_subtitle);

            itemView.setOnClickListener(v -> listener.onViewArchive());
            mDismiss.setOnClickListener(v -> listener.onDismiss());
        }
    }
}