package com.solarized.firedown.ui.adapters;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.widget.AppCompatImageButton;
import androidx.appcompat.widget.AppCompatImageView;
import androidx.core.content.ContextCompat;
import androidx.paging.PagingDataAdapter;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.load.resource.bitmap.RoundedCorners;
import com.bumptech.glide.request.RequestOptions;
import com.google.android.material.card.MaterialCardView;
import com.solarized.firedown.GlideHelper;
import com.solarized.firedown.R;
import com.solarized.firedown.data.entity.WebHistoryEntity;
import com.solarized.firedown.data.entity.WebHistorySeparatorEntity;
import com.solarized.firedown.ui.OnItemClickListener;
import com.solarized.firedown.utils.Utils;
import com.solarized.firedown.utils.WebUtils;

import java.util.HashSet;


public class WebHistoryAdapter extends PagingDataAdapter<Object, RecyclerView.ViewHolder> {

    private static final String TAG = WebHistoryAdapter.class.getSimpleName();

    public static final int HEADER = 2;

    public static final int ITEM = 1;

    public static final int EMPTY = 0;

    private final OnItemClickListener mOnItemClickListener;

    private final HashSet<Integer> mSelected;

    private final int mColorNormal;

    private final int mColorSelected;

    private final Drawable mChecked;

    private final Drawable mUnChecked;

    private final RequestOptions mRequestOptions;

    private boolean mActionMode;


    public WebHistoryAdapter(Context context, @NonNull DiffUtil.ItemCallback<Object> diffCallback, OnItemClickListener onItemClickListener) {
        super(diffCallback);
        mOnItemClickListener = onItemClickListener;
        mSelected = new HashSet<>();
        int mRoundedPixels = context.getResources().getDimensionPixelOffset(R.dimen.icon_rounded);
        RoundedCorners mRoundedCorners = new RoundedCorners(mRoundedPixels);
        mColorNormal = ContextCompat.getColor(context, R.color.transparent);
        mColorSelected = ContextCompat.getColor(context, R.color.md_theme_primaryContainer);
        mChecked = ContextCompat.getDrawable(context, R.drawable.ic_baseline_check_circle_24);
        mUnChecked = Utils.tintDrawable(context, R.drawable.radio_button_unchecked_24, R.color.md_theme_primaryContainer);
        mRequestOptions = RequestOptions.bitmapTransform(mRoundedCorners);
    }

    @Override
    public void onViewRecycled(@NonNull RecyclerView.ViewHolder holder) {
        super.onViewRecycled(holder);
        if (holder instanceof WebHistoryViewHolder h) {
            GlideHelper.clearSafe(h.file_icon);
        }
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup viewGroup, int viewType) {
        if(viewType == HEADER){
            View view = LayoutInflater.from(viewGroup.getContext())
                    .inflate(R.layout.fragment_item_header, viewGroup, false);
            return new WebHistoryHeaderViewHolder(view);
        }else if(viewType == ITEM){
            View view = LayoutInflater.from(viewGroup.getContext())
                    .inflate(R.layout.fragment_web_history_item, viewGroup, false);
            return new WebHistoryViewHolder(view, mOnItemClickListener);
        }else{
            View view = LayoutInflater.from(viewGroup.getContext())
                    .inflate(R.layout.fragment_web_empty_item, viewGroup, false);
            return new EmptyViewHolder(view);

        }
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder viewHolder, int position) {
        // 1. Get the item as a generic Object
        Object item = getItem(position);

        // 2. Safety check for Paging placeholders
        if (item == null) return;

        // 3. Bind Header View
        if (viewHolder instanceof WebHistoryHeaderViewHolder headerHolder && item instanceof WebHistorySeparatorEntity separator) {
            // Use the Resource ID from the separator for localization
            headerHolder.header.setText(headerHolder.itemView.getContext().getString(separator.getTitleResId()));
        }

        // 4. Bind History Item View
        else if (viewHolder instanceof WebHistoryViewHolder historyHolder && item instanceof WebHistoryEntity entity) {
            boolean isSelected = mSelected.contains(position);

            // Basic Text
            historyHolder.file_name.setText(entity.getTitle());
            historyHolder.file_url.setText(WebUtils.getDomainName(entity.getUrl()));

            // Selection & Action Mode UI logic
            historyHolder.selected.setVisibility(mActionMode ? View.VISIBLE : View.GONE);
            historyHolder.file_spacer.setVisibility(mActionMode ? View.VISIBLE : View.GONE);
            historyHolder.file_more.setVisibility(mActionMode ? View.INVISIBLE : View.VISIBLE);

            if (mActionMode) {
                historyHolder.selected.setImageDrawable(isSelected ? mChecked : mUnChecked);
                historyHolder.item.setStrokeColor(isSelected ? mColorSelected : mColorNormal);
            } else {
                // Reset stroke if action mode is off
                historyHolder.item.setStrokeColor(mColorNormal);
            }

            // Icon Loading
            GlideHelper.load(entity.getIcon(),
                    entity.getUrl(),
                    historyHolder.file_icon,
                    mRequestOptions);
        }
    }


    @Override
    public int getItemViewType(int position){
        Object object = peek(position);
        if(object instanceof WebHistorySeparatorEntity){
            return HEADER;
        }else if(object != null){
            return ITEM;
        }else{
            return EMPTY;
        }
    }

    public WebHistoryEntity getWebHistoryEntity(int position) {
        Object item = peek(position);
        if (item instanceof WebHistoryEntity entity) {
            return entity;
        }
        return null; // Return null if it's a separator or empty
    }

    public Object getWebHistoryItem(int position) {
        return peek(position);
    }

    public void setActionMode(boolean value){
        mActionMode = value;
        notifyItemRangeChanged(0, getItemCount());
    }

    public void setSelected(int position){
        if(mSelected.contains(position))
            mSelected.remove(position);
        else {
            mSelected.add(position);
        }
        notifyItemChanged(position);
    }

    public int getSelectedSize(){
        return mSelected.size();
    }

    public HashSet<Integer> getSelected(){
        return mSelected;
    }

    public void resetSelected(){
        mSelected.clear();
        notifyItemRangeChanged(0, getItemCount());
    }


    static class WebHistoryViewHolder extends RecyclerView.ViewHolder implements View.OnClickListener, View.OnLongClickListener {
        OnItemClickListener mOnItemClickListener;
        MaterialCardView item;
        TextView file_name;
        TextView file_url;
        AppCompatImageView file_icon;
        AppCompatImageButton file_more;
        AppCompatImageView selected;

        View file_spacer;

        public WebHistoryViewHolder(View view, OnItemClickListener onItemClickListener) {
            super(view);
            mOnItemClickListener = onItemClickListener;
            item = view.findViewById(R.id.item_web_history);
            selected = view.findViewById(R.id.item_web_selected);
            file_name = view.findViewById(R.id.file_name);
            file_url = view.findViewById(R.id.file_url);
            file_more = view.findViewById(R.id.file_more);
            file_icon = view.findViewById(R.id.file_icon);
            file_spacer = view.findViewById(R.id.spacer);
            file_more.setOnClickListener(this);
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


    static class WebHistoryHeaderViewHolder extends RecyclerView.ViewHolder {

        TextView header;

        public WebHistoryHeaderViewHolder(@NonNull View itemView) {
            super(itemView);
            header = itemView.findViewById(R.id.item_header);
        }
    }


    static class EmptyViewHolder extends RecyclerView.ViewHolder {

        public EmptyViewHolder(@NonNull View itemView) {
            super(itemView);
        }
    }


}