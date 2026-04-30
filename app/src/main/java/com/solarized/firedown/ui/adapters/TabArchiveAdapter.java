package com.solarized.firedown.ui.adapters;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.text.TextUtils;
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
import com.solarized.firedown.data.entity.TabStateArchivedEntity;
import com.solarized.firedown.data.entity.TabStateHeaderArchivedEntity;
import com.solarized.firedown.ui.OnItemClickListener;
import com.solarized.firedown.utils.Utils;

import java.util.HashSet;

// Changed generic type to Object to support both Tabs and Headers
public class TabArchiveAdapter extends PagingDataAdapter<Object, RecyclerView.ViewHolder> {

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

    public TabArchiveAdapter(Context context, @NonNull DiffUtil.ItemCallback<Object> diffCallback, OnItemClickListener onItemClickListener) {
        super(diffCallback);
        this.mOnItemClickListener = onItemClickListener;
        this.mSelected = new HashSet<>();
        int mRoundedPixels = context.getResources().getDimensionPixelOffset(R.dimen.icon_rounded);
        mColorNormal = ContextCompat.getColor(context, android.R.color.transparent);
        mColorSelected = ContextCompat.getColor(context, R.color.md_theme_primary);
        mChecked = Utils.tintDrawable(context, R.drawable.ic_baseline_check_circle_24, R.color.md_theme_primary);
        mUnChecked = Utils.tintDrawable(context, R.drawable.radio_button_unchecked_24, R.color.md_theme_onSurfaceVariant);
        mRequestOptions = RequestOptions.bitmapTransform(new RoundedCorners(mRoundedPixels));
    }

    @Override
    public void onViewRecycled(@NonNull RecyclerView.ViewHolder holder) {
        super.onViewRecycled(holder);
        if (holder instanceof TabArchiveViewHolderPhone h) {
            GlideHelper.clearSafe(h.file_icon);
        }
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup viewGroup, int viewType) {
        LayoutInflater inflater = LayoutInflater.from(viewGroup.getContext());
        if (viewType == HEADER) {
            return new HeaderViewHolder(inflater.inflate(R.layout.fragment_item_header, viewGroup, false));
        } else if (viewType == ITEM) {
            return new TabArchiveViewHolderPhone(inflater.inflate(R.layout.fragment_tab_archive_item, viewGroup, false), mOnItemClickListener);
        } else {
            return new EmptyViewHolder(inflater.inflate(R.layout.fragment_web_empty_item, viewGroup, false));
        }
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder viewHolder, int position) {
        Object item = getItem(position);
        if (item == null) return;

        if (viewHolder instanceof HeaderViewHolder headerHolder && item instanceof TabStateHeaderArchivedEntity header) {
            headerHolder.header.setText(header.getTitle());

        } else if (viewHolder instanceof TabArchiveViewHolderPhone tabHolder && item instanceof TabStateArchivedEntity tab) {
            boolean isSelected = mSelected.contains(position);

            String title = TextUtils.isEmpty(tab.getTitle()) ? "about:blank" : tab.getTitle();

            tabHolder.file_name.setText(title);
            tabHolder.file_url.setText(tab.getUri());

            if (mActionMode) {
                tabHolder.selected.setVisibility(View.VISIBLE);
                tabHolder.selected.setImageDrawable(isSelected ? mChecked : mUnChecked);
            } else {
                tabHolder.selected.setVisibility(View.GONE);
            }

            tabHolder.item.setStrokeColor(mActionMode && isSelected ? mColorSelected : mColorNormal);
            tabHolder.file_more.setVisibility(mActionMode ? View.INVISIBLE : View.VISIBLE);

            GlideHelper.load(tab.getIcon(), tab.getUri(), tabHolder.file_icon, mRequestOptions);
        }
    }

    @Override
    public int getItemViewType(int position) {
        Object object = peek(position); // Use peek to check type without triggering page fetch
        if (object instanceof TabStateHeaderArchivedEntity) {
            return HEADER;
        } else if (object instanceof TabStateArchivedEntity) {
            return ITEM;
        } else {
            return EMPTY;
        }
    }

    // --- Action Mode Helpers ---

    public void setActionMode(boolean value) {
        mActionMode = value;
        notifyItemRangeChanged(0, getItemCount());
    }

    public void setSelected(int position) {
        if (mSelected.contains(position)) mSelected.remove(position);
        else mSelected.add(position);
        notifyItemChanged(position);
    }

    public void resetSelected() {
        mSelected.clear();
        notifyItemRangeChanged(0, getItemCount());
    }

    public int getSelectedSize() { return mSelected.size(); }
    public HashSet<Integer> getSelected() { return mSelected; }

    // --- ViewHolders ---

    static class HeaderViewHolder extends RecyclerView.ViewHolder {
        TextView header;
        public HeaderViewHolder(@NonNull View itemView) {
            super(itemView);
            header = itemView.findViewById(R.id.item_header);
        }
    }

    static class TabArchiveViewHolderPhone extends RecyclerView.ViewHolder implements View.OnClickListener, View.OnLongClickListener {
        OnItemClickListener mOnItemClickListener;
        MaterialCardView item;
        TextView file_name, file_url;
        AppCompatImageView file_icon, selected;
        AppCompatImageButton file_more;

        public TabArchiveViewHolderPhone(View view, OnItemClickListener onItemClickListener) {
            super(view);
            mOnItemClickListener = onItemClickListener;
            item = view.findViewById(R.id.item_tab);
            selected = view.findViewById(R.id.item_tab_selected);
            file_name = view.findViewById(R.id.file_name);
            file_url = view.findViewById(R.id.file_url);
            file_more = view.findViewById(R.id.file_more);
            file_icon = view.findViewById(R.id.file_icon);

            file_more.setOnClickListener(this);
            item.setOnClickListener(this);
            item.setOnLongClickListener(this);
        }

        @Override
        public void onClick(View v) {
            if (mOnItemClickListener != null) mOnItemClickListener.onItemClick(getAbsoluteAdapterPosition(), v.getId());
        }

        @Override
        public boolean onLongClick(View v) {
            if (mOnItemClickListener != null) {
                mOnItemClickListener.onLongClick(getAbsoluteAdapterPosition(), v.getId());
                return true;
            }
            return false;
        }
    }

    static class EmptyViewHolder extends RecyclerView.ViewHolder {
        public EmptyViewHolder(@NonNull View itemView) { super(itemView); }
    }
}