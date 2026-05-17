package com.solarized.firedown.ui.adapters;


import android.content.Context;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.widget.AppCompatImageView;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.load.resource.bitmap.RoundedCorners;
import com.bumptech.glide.request.RequestOptions;
import com.google.android.material.card.MaterialCardView;
import com.solarized.firedown.GlideHelper;
import com.solarized.firedown.Preferences;
import com.solarized.firedown.R;
import com.solarized.firedown.data.entity.ShortCutsEntity;
import com.solarized.firedown.ui.OnItemClickListener;
import com.solarized.firedown.ui.diffs.ShortCutsDiffCallback;


public class ShortCutsAdapter extends ListAdapter<ShortCutsEntity, ShortCutsAdapter.WebVisitedViewHolder> {

    private static final String TAG = ShortCutsAdapter.class.getSimpleName();

    private static final int VIEW_TYPE_SHORTCUT = 0;
    private static final int VIEW_TYPE_ADD = 1;

    private final OnItemClickListener mOnItemClickListener;

    private final RequestOptions mRequestOptions;



    public ShortCutsAdapter(Context context, ShortCutsDiffCallback shortCutsDiffCallback, @NonNull OnItemClickListener onItemClickListener) {
        super(shortCutsDiffCallback);
        mOnItemClickListener = onItemClickListener;
        int mRoundedPixels = context.getResources().getDimensionPixelOffset(R.dimen.icon_rounded);
        RoundedCorners mRoundedCorners = new RoundedCorners(mRoundedPixels);
        mRequestOptions = RequestOptions.bitmapTransform(mRoundedCorners);
    }


    @Override
    public void onViewRecycled(@NonNull WebVisitedViewHolder holder) {
        super.onViewRecycled(holder);
        if (holder.file_icon != null) {
            GlideHelper.clearSafe(holder.file_icon);
        }
    }

    /**
     * Appends one synthetic 'Add' tile after the real shortcuts so the
     * user can pin a new site from the populated home state without
     * having to navigate to a page first. Hidden at the cap — a tile
     * that only opens a "delete one first" dialog is hostile chrome,
     * the affordance just disappears until the user makes room. The
     * browser-menu 'Add to shortcuts' path still surfaces the cap
     * dialog (the user is on a page they want to pin, so the
     * explanation is useful there).
     */
    private boolean hasAddTile() {
        return super.getItemCount() < Preferences.SHORTCUTS_LIST_LIMIT;
    }

    @Override
    public int getItemCount() {
        return super.getItemCount() + (hasAddTile() ? 1 : 0);
    }

    @Override
    public int getItemViewType(int position) {
        return (hasAddTile() && position == super.getItemCount())
                ? VIEW_TYPE_ADD
                : VIEW_TYPE_SHORTCUT;
    }

    @NonNull
    @Override
    public WebVisitedViewHolder onCreateViewHolder(@NonNull ViewGroup viewGroup, int viewType) {
        int layoutRes = (viewType == VIEW_TYPE_ADD)
                ? R.layout.fragment_web_visited_item_add
                : R.layout.fragment_web_visited_item;
        View view = LayoutInflater.from(viewGroup.getContext()).inflate(layoutRes, viewGroup, false);
        return new WebVisitedViewHolder(view, mOnItemClickListener, viewType);
    }

    @Override
    public void onBindViewHolder(@NonNull WebVisitedViewHolder holder, int position) {
        if (holder.viewType == VIEW_TYPE_ADD) {
            // Static — nothing to bind. Click is wired in the ViewHolder.
            return;
        }
        ShortCutsEntity shortcutsEntity = getItem(position);

        holder.file_name.setText(shortcutsEntity.getDomain());

        Log.d(TAG, "WebVisited adapter icon: " + shortcutsEntity.getIcon() + " url:" + shortcutsEntity.getUrl() + " domain: " + shortcutsEntity.getDomain());

        GlideHelper.load(shortcutsEntity.getIcon(), shortcutsEntity.getUrl(), holder.file_icon, mRequestOptions);
    }


    public static class WebVisitedViewHolder extends RecyclerView.ViewHolder implements View.OnClickListener, View.OnLongClickListener {
        final int viewType;
        OnItemClickListener mOnItemClickListener;
        MaterialCardView item;
        TextView file_name;
        AppCompatImageView file_icon;

        public WebVisitedViewHolder(View view, OnItemClickListener onItemClickListener, int viewType) {
            super(view);
            this.viewType = viewType;
            mOnItemClickListener = onItemClickListener;

            if (viewType == VIEW_TYPE_ADD) {
                item = view.findViewById(R.id.item_web_visited_add);
                item.setOnClickListener(this);
                // No long-press on the Add tile — there's nothing to remove or edit.
            } else {
                item = view.findViewById(R.id.item_web_visited);
                file_icon = view.findViewById(R.id.file_icon);
                file_name = view.findViewById(R.id.file_name);
                item.setOnClickListener(this);
                item.setOnLongClickListener(this);
            }
        }

        @Override
        public void onClick(View v) {
            int position = getAbsoluteAdapterPosition();
            if (mOnItemClickListener != null) {
                mOnItemClickListener.onItemClick(position, v.getId());
            }
        }

        @Override
        public boolean onLongClick(View v) {
            int position = getAbsoluteAdapterPosition();
            if (mOnItemClickListener != null) {
                mOnItemClickListener.onLongClick(position, v.getId());
                return true;
            }
            return false;
        }
    }


}
