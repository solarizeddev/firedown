package com.solarized.firedown.ui.adapters;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.drawable.Drawable;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.widget.AppCompatImageButton;
import androidx.appcompat.widget.AppCompatImageView;
import androidx.core.content.ContextCompat;
import androidx.core.widget.ImageViewCompat;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.resource.bitmap.RoundedCorners;
import com.bumptech.glide.request.RequestOptions;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.shape.ShapeAppearanceModel;
import com.solarized.firedown.App;
import com.solarized.firedown.GlideHelper;
import com.solarized.firedown.R;
import com.solarized.firedown.data.entity.AutoCompleteEntity;
import com.solarized.firedown.ui.IncognitoColors;
import com.solarized.firedown.ui.OnItemClickListener;


public class SearchAutocompleteAdapter extends ListAdapter<AutoCompleteEntity, RecyclerView.ViewHolder>{

    private static final int SEARCH = 0;

    private static final int LIST = 1;

    private static final String TAG = SearchAutocompleteAdapter.class.getSimpleName();

    private final OnItemClickListener mOnItemClickListener;

    private final Drawable searchDrawable;

    private final Drawable historyDrawable;

    private final Drawable tabDrawable;

    private final String mSearchFor;

    private final String mSwitchTab;

    private final RequestOptions mRequestOptions;

    private final ShapeAppearanceModel singleShapeAppearance;

    private final ShapeAppearanceModel topShapeAppearance;

    private final ShapeAppearanceModel middleShapeAppearance;

    private final ShapeAppearanceModel bottomShapeAppearance;

    private boolean mIncognito = false;

    public SearchAutocompleteAdapter(Context context, @NonNull DiffUtil.ItemCallback<AutoCompleteEntity> diffCallback, OnItemClickListener onItemClickListener) {
        super(diffCallback);
        mSearchFor = context.getString(R.string.search_for);
        mSwitchTab = context.getString(R.string.switch_to_tab_description);
        mOnItemClickListener = onItemClickListener;
        searchDrawable = ContextCompat.getDrawable(context, R.drawable.ic_search_24);
        if (searchDrawable != null) {
            int size = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 22, context.getResources().getDisplayMetrics());
            searchDrawable.setBounds(0, 0, size, size);
        }
        historyDrawable = ContextCompat.getDrawable(context, R.drawable.ic_baseline_history_24);
        if (historyDrawable != null) {
            int size = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 22, context.getResources().getDisplayMetrics());
            historyDrawable.setBounds(0, 0, size, size);
        }
        tabDrawable = ContextCompat.getDrawable(context, R.drawable.ic_tabs_24);
        if (tabDrawable != null) {
            int size = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 22, context.getResources().getDisplayMetrics());
            tabDrawable.setBounds(0, 0, size, size);
        }
        int mRoundedPixels = context.getResources().getDimensionPixelOffset(R.dimen.icon_rounded);
        RoundedCorners mRoundedCorners = new RoundedCorners(mRoundedPixels);
        mRequestOptions = RequestOptions.bitmapTransform(mRoundedCorners);
        int cardSmallRadius = context.getResources().getDimensionPixelOffset(R.dimen.list_autocomplete_card_small_radius);
        int cardBigRadius = context.getResources().getDimensionPixelOffset(R.dimen.list_autocomplete_card_big_radius);
        singleShapeAppearance = ShapeAppearanceModel.builder().setAllCornerSizes(cardBigRadius).build();
        middleShapeAppearance = ShapeAppearanceModel.builder().setAllCornerSizes(cardSmallRadius).build();
        topShapeAppearance = ShapeAppearanceModel.builder().setBottomLeftCornerSize(cardSmallRadius)
                .setBottomRightCornerSize(cardSmallRadius).setTopLeftCornerSize(cardBigRadius)
                .setTopRightCornerSize(cardBigRadius).build();
        bottomShapeAppearance = ShapeAppearanceModel.builder().setBottomLeftCornerSize(cardBigRadius)
                .setBottomRightCornerSize(cardBigRadius).setTopLeftCornerSize(cardSmallRadius)
                .setTopRightCornerSize(cardSmallRadius).build();
    }

    @Override
    public void onViewRecycled(@NonNull RecyclerView.ViewHolder holder) {
        super.onViewRecycled(holder);
        if (holder instanceof SearchViewHolderPhone h) {
            GlideHelper.clearSafe(h.buttonSearchView);
        }
    }

    @Override
    public long getItemId(int position) {
        AutoCompleteEntity searchEntity = getItem(position);
        return searchEntity.getId();
    }

    @Override
    public int getItemViewType(int position){
        if(position == 0)
            return SEARCH;
        else
            return LIST;
    }

    public boolean isBottom(int position){
        return (position == getItemCount() -1);
    }

    public void setIncognito(boolean incognito) {
        if (mIncognito == incognito) return;
        mIncognito = incognito;
        if (getItemCount() > 0) {
            notifyItemRangeChanged(0, getItemCount());
        }
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup viewGroup, int viewType) {
        if (viewType == SEARCH) {
            View view = LayoutInflater.from(viewGroup.getContext())
                    .inflate(R.layout.fragment_autocomplete_item_search, viewGroup, false);
            return new SearchViewHolderPhoneSearch(view, mOnItemClickListener);

        } else {
            View view = LayoutInflater.from(viewGroup.getContext())
                    .inflate(R.layout.fragment_autocomplete_item, viewGroup, false);
            return new SearchViewHolderPhone(view, mOnItemClickListener);
        }
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {

        AutoCompleteEntity searchEntity = getItem(position);
        int type = getItemViewType(position);

        if (mIncognito) {
            applyIncognitoColors(holder);
        }

        if(type == SEARCH){
            SearchViewHolderPhoneSearch searchViewHolderPhoneSearch = (SearchViewHolderPhoneSearch) holder;
            searchViewHolderPhoneSearch.item.setShapeAppearanceModel(getItemCount() == 1 ? singleShapeAppearance : topShapeAppearance);
            searchViewHolderPhoneSearch.textView.setText(searchEntity.getTitle());
        }else{
            int searchType = searchEntity.getType();
            SearchViewHolderPhone searchViewHolderPhone = (SearchViewHolderPhone) holder;
            searchViewHolderPhone.item.setShapeAppearanceModel(isBottom(position) ? bottomShapeAppearance : middleShapeAppearance);
            switch (searchType) {
                case AutoCompleteEntity.RESULTS:
                    Glide.with(searchViewHolderPhone.buttonSearchView).load(searchEntity.getDrawableId()).fallback(searchDrawable).into(searchViewHolderPhone.buttonSearchView);
                    searchViewHolderPhone.subTextView.setText(String.format(mSearchFor, searchEntity.getTitle()));
                    searchViewHolderPhone.textView.setText(searchEntity.getTitle());
                    searchViewHolderPhone.buttonTextView.setImageResource(R.drawable.ic_search_24);
                    break;
                case AutoCompleteEntity.HISTORY:
                    searchViewHolderPhone.subTextView.setText(searchEntity.getSubText());
                    searchViewHolderPhone.buttonTextView.setImageDrawable(historyDrawable);
                    searchViewHolderPhone.textView.setText(searchEntity.getTitle());
                    GlideHelper.load(searchEntity.getIcon(), searchEntity.getSubText(), searchViewHolderPhone.buttonSearchView, mRequestOptions);
                    break;
                case AutoCompleteEntity.TAB:
                    searchViewHolderPhone.subTextView.setText(mSwitchTab);
                    searchViewHolderPhone.buttonTextView.setImageDrawable(tabDrawable);
                    searchViewHolderPhone.textView.setText(searchEntity.getTitle());
                    GlideHelper.load(searchEntity.getIcon(), searchEntity.getSubText(), searchViewHolderPhone.buttonSearchView, mRequestOptions);
                    break;
            }
        }
    }

    private void applyIncognitoColors(RecyclerView.ViewHolder holder) {
        Context context = holder.itemView.getContext();
        int cardBg = IncognitoColors.getSurfaceContainerHigh(context, true);
        int onSurface = IncognitoColors.getOnSurface(context, true);
        int onSurfaceVariant = IncognitoColors.getOnSurfaceVariant(context, true);
        ColorStateList variantTint = ColorStateList.valueOf(onSurfaceVariant);
        if (holder instanceof SearchViewHolderPhone h) {
            h.item.setCardBackgroundColor(cardBg);
            h.textView.setTextColor(onSurface);
            h.subTextView.setTextColor(onSurfaceVariant);
            ImageViewCompat.setImageTintList(h.buttonTextView, variantTint);
        } else if (holder instanceof SearchViewHolderPhoneSearch h) {
            h.item.setCardBackgroundColor(cardBg);
            h.textView.setTextColor(onSurface);
            ImageViewCompat.setImageTintList(h.icon, variantTint);
        }
    }


    static class SearchViewHolderPhone extends RecyclerView.ViewHolder implements View.OnClickListener {

        MaterialCardView item;
        TextView textView;
        TextView subTextView;
        AppCompatImageView buttonSearchView;
        AppCompatImageButton buttonTextView;
        OnItemClickListener mOnItemClickListener;

        public SearchViewHolderPhone(View view, OnItemClickListener onItemClickListener){
            super(view);
            mOnItemClickListener = onItemClickListener;
            textView = view.findViewById(R.id.text);
            subTextView = view.findViewById(R.id.subtext);
            buttonTextView = view.findViewById(R.id.button);
            buttonSearchView = view.findViewById(R.id.search_badge);
            buttonSearchView.setClipToOutline(true);
            item = view.findViewById(R.id.item_search);
            item.setOnClickListener(this);
        }

        @Override
        public void onClick(View view) {
            int position = getAbsoluteAdapterPosition();
            if(mOnItemClickListener != null){
                mOnItemClickListener.onItemClick(position, view.getId());
            }
        }
    }

    static class SearchViewHolderPhoneSearch extends RecyclerView.ViewHolder implements View.OnClickListener {

        MaterialCardView item;
        TextView textView;
        AppCompatImageView icon;
        OnItemClickListener mOnItemClickListener;

        public SearchViewHolderPhoneSearch(View view, OnItemClickListener onItemClickListener){
            super(view);
            mOnItemClickListener = onItemClickListener;
            textView = view.findViewById(R.id.text);
            icon = view.findViewById(R.id.icon);
            item = view.findViewById(R.id.item_search);
            item.setOnClickListener(this);
        }

        @Override
        public void onClick(View view) {
            int position = getAbsoluteAdapterPosition();
            if(mOnItemClickListener != null){
                mOnItemClickListener.onItemClick(position, view.getId());
            }
        }
    }

}
