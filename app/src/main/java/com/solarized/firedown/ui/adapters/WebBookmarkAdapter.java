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
import com.solarized.firedown.data.entity.WebBookmarkEntity;
import com.solarized.firedown.ui.OnItemClickListener;
import com.solarized.firedown.utils.Utils;
import com.solarized.firedown.utils.WebUtils;

import java.util.HashSet;


public class WebBookmarkAdapter extends PagingDataAdapter<WebBookmarkEntity, RecyclerView.ViewHolder> {

    public static final int ITEM = 1;

    public static final int EMPTY = 0;

    private static final String TAG = WebBookmarkAdapter.class.getSimpleName();

    private final OnItemClickListener mOnItemClickListener;

    private final HashSet<Integer> mSelected;

    private final int mColorNormal;

    private final int mColorSelected;

    private final Drawable mChecked;

    private final Drawable mUnChecked;

    private boolean mActionMode;

    private final RequestOptions mRequestOptions;

    public WebBookmarkAdapter(Context context, @NonNull DiffUtil.ItemCallback<WebBookmarkEntity> diffCallback, OnItemClickListener onItemClickListener) {
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
        if (holder instanceof WebBookmarkViewHolder h) {
            GlideHelper.clearSafe(h.file_icon);
        }
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup viewGroup, int viewType) {
        if(viewType == ITEM){
            View view = LayoutInflater.from(viewGroup.getContext())
                    .inflate(R.layout.fragment_web_bookmark_item, viewGroup, false);
            return new WebBookmarkViewHolder(view, mOnItemClickListener);
        }else{
            View view = LayoutInflater.from(viewGroup.getContext())
                    .inflate(R.layout.fragment_web_empty_item, viewGroup, false);
            return new EmptyViewHolder(view);
        }

    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder viewHolder, int position) {

        WebBookmarkEntity webBookmarkEntity = getItem(position);

        if(webBookmarkEntity == null)
            return;

        boolean contains = mSelected.contains(position);

        String url = webBookmarkEntity.getUrl();
        String icon = webBookmarkEntity.getIcon();

        WebBookmarkViewHolder webHistoryViewHolder = (WebBookmarkViewHolder) viewHolder;
        webHistoryViewHolder.file_name.setText(webBookmarkEntity.getTitle());
        webHistoryViewHolder.file_url.setText(WebUtils.getDomainName(webBookmarkEntity.getUrl()));
        webHistoryViewHolder.selected.setVisibility(mActionMode ? View.VISIBLE : View.GONE);
        webHistoryViewHolder.selected.setImageDrawable(mActionMode ? (contains ? mChecked : mUnChecked) : null);
        webHistoryViewHolder.item.setStrokeColor(mActionMode && contains ? mColorSelected : mColorNormal);
        webHistoryViewHolder.file_more.setVisibility(mActionMode ? View.INVISIBLE : View.VISIBLE);
        webHistoryViewHolder.spacer.setVisibility(mActionMode ? View.INVISIBLE : View.VISIBLE);
        GlideHelper.load(icon, url, webHistoryViewHolder.file_icon, mRequestOptions);

    }


    @Override
    public int getItemViewType(int position){
        Object object = peek(position);
        if(object != null){
            return ITEM;
        }else{
            return EMPTY;
        }
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


    public void selectAll(){
        for(int i = 0; i < getItemCount(); i++){
            mSelected.add(i);
        }
        notifyItemRangeChanged(0, getItemCount());
    }

    public void deselectAll(){
        mSelected.clear();
        notifyItemRangeChanged(0, getItemCount());
    }

    public boolean isSelectedEmpty() {
        return mSelected.isEmpty();
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

    public WebBookmarkEntity getWebBookmarkItem(int position){
        return peek(position);
    }


    static class WebBookmarkViewHolder extends RecyclerView.ViewHolder implements View.OnClickListener, View.OnLongClickListener {
        OnItemClickListener mOnItemClickListener;
        MaterialCardView item;
        TextView file_name;
        TextView file_url;
        AppCompatImageView file_icon;
        AppCompatImageButton file_more;
        AppCompatImageView selected;

        View spacer;

        public WebBookmarkViewHolder(View view, OnItemClickListener onItemClickListener) {
            super(view);
            mOnItemClickListener = onItemClickListener;
            item = view.findViewById(R.id.item_web_bookmark);
            selected = view.findViewById(R.id.item_web_selected);
            file_name = view.findViewById(R.id.file_name);
            file_url = view.findViewById(R.id.file_url);
            file_icon = view.findViewById(R.id.file_icon);
            file_more = view.findViewById(R.id.file_more);
            spacer = view.findViewById(R.id.spacer);
            file_more.setOnClickListener(this);
            selected.setOnClickListener(this);
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

    static class EmptyViewHolder extends RecyclerView.ViewHolder {

        public EmptyViewHolder(@NonNull View itemView) {
            super(itemView);
        }
    }


}