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
import com.solarized.firedown.R;
import com.solarized.firedown.data.entity.ShortCutsEntity;
import com.solarized.firedown.ui.OnItemClickListener;
import com.solarized.firedown.ui.diffs.ShortCutsDiffCallback;


public class ShortCutsAdapter extends ListAdapter<ShortCutsEntity, ShortCutsAdapter.WebVisitedViewHolder> {

    private static final String TAG = ShortCutsAdapter.class.getSimpleName();

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
        GlideHelper.clearSafe(holder.file_icon);
    }

    @NonNull
    @Override
    public WebVisitedViewHolder onCreateViewHolder(@NonNull ViewGroup viewGroup, int viewType) {

        View view = LayoutInflater.from(viewGroup.getContext())
                .inflate(R.layout.fragment_web_visited_item, viewGroup, false);
        return new WebVisitedViewHolder(view, mOnItemClickListener);
    }

    @Override
    public void onBindViewHolder(@NonNull WebVisitedViewHolder holder, int position) {
        ShortCutsEntity shortcutsEntity = getItem(position);

        holder.file_name.setText(shortcutsEntity.getDomain());

        Log.d(TAG, "WebVisited adapter icon: " + shortcutsEntity.getIcon() + " url:" + shortcutsEntity.getUrl() + " domain: " + shortcutsEntity.getDomain());

        GlideHelper.load(shortcutsEntity.getIcon(), shortcutsEntity.getUrl(), holder.file_icon, mRequestOptions);
    }


    public static class WebVisitedViewHolder extends RecyclerView.ViewHolder implements View.OnClickListener, View.OnLongClickListener {
        OnItemClickListener mOnItemClickListener;
        MaterialCardView item;
        TextView file_name;
        AppCompatImageView file_icon;

        public WebVisitedViewHolder(View view, OnItemClickListener onItemClickListener) {
            super(view);
            mOnItemClickListener = onItemClickListener;
            item = view.findViewById(R.id.item_web_visited);
            file_icon = view.findViewById(R.id.file_icon);
            file_name = view.findViewById(R.id.file_name);
            item.setOnClickListener(this);
            item.setOnClickListener(this);
            item.setOnLongClickListener(this);
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