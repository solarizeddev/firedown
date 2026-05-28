package com.solarized.firedown.ui.adapters;

import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.PluralsRes;
import androidx.appcompat.widget.AppCompatImageButton;
import androidx.appcompat.widget.AppCompatImageView;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.load.resource.bitmap.RoundedCorners;
import com.bumptech.glide.request.RequestOptions;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.color.MaterialColors;
import com.solarized.firedown.GlideHelper;
import com.solarized.firedown.R;
import com.solarized.firedown.data.entity.GeckoStateEntity;
import com.solarized.firedown.ui.IncognitoColors;
import com.solarized.firedown.ui.OnItemClickListener;
import com.solarized.firedown.ui.diffs.GeckoStateDiffCallback;
import com.solarized.firedown.utils.Utils;

import java.util.Collections;
import java.util.List;
import java.util.Set;


public class BrowserTabsAdapter extends GridListBaseAdapter<GeckoStateEntity, RecyclerView.ViewHolder> {

    private static final String TAG = BrowserTabsAdapter.class.getSimpleName();

    /**
     * Reserved view type for the archive banner. Picked far above the
     * grid/list values inherited from {@link GridListBaseAdapter} so
     * there's no chance of collision if more tab view types are added
     * later.
     */
    public static final int TYPE_BANNER = 1_000;

    /** Per-adapter banner state. There is at most one banner row, always at
     *  adapter position 0 when {@link #mBannerVisible} is true. */
    public interface OnBannerActionListener {
        void onViewArchive();
        void onDismiss();
    }

    private final OnItemClickListener mOnItemClickListener;
    private final Context mContext;
    private final int mColorNormal;
    private final int mColorIncognitoNormal;
    private final int mColorSelected;
    private final int mColorOnPrimaryContainer;
    private final RoundedCorners mRoundedCorners;
    private final RequestOptions mRequestOptions;
    /** Set of session IDs that currently have active media playback. */
    private Set<Integer> mMediaSessionIds = Collections.emptySet();

    private boolean mBannerVisible = false;
    private int mBannerCount = 0;
    @PluralsRes private int mBannerTitleResId = R.plurals.archive_banner_title;
    @Nullable private OnBannerActionListener mBannerListener;


    public BrowserTabsAdapter(Context context, @NonNull DiffUtil.ItemCallback<GeckoStateEntity> diffCallback, OnItemClickListener onItemClickListener, boolean enableGrid) {
        super(diffCallback);
        mContext = context;
        mList = enableGrid;
        mOnItemClickListener = onItemClickListener;
        mColorIncognitoNormal = IncognitoColors.getSurfaceContainerHigh(mContext, true);
        mColorNormal = MaterialColors.getColor(mContext,
                com.google.android.material.R.attr.colorSurfaceContainer, 0);
        mColorSelected = ContextCompat.getColor(mContext, R.color.md_theme_primaryContainer);
        mColorOnPrimaryContainer = IncognitoColors.getOnPrimaryContainer(mContext, true);
        int mRoundedPixels = mContext.getResources().getDimensionPixelOffset(R.dimen.icon_rounded);
        mRoundedCorners = new RoundedCorners(mRoundedPixels);
        mRequestOptions = RequestOptions.bitmapTransform(mRoundedCorners);


    }

    // ── Banner API ───────────────────────────────────────────────────

    /**
     * Show (or refresh) the archive banner at adapter position 0.
     * Listener stays attached across {@link #showBanner} re-calls; pass
     * null to keep the existing listener.
     */
    public void showBanner(int count, @PluralsRes int titleResId,
                           @Nullable OnBannerActionListener listener) {
        if (count <= 0) return;
        if (listener != null) mBannerListener = listener;
        boolean wasVisible = mBannerVisible;
        mBannerCount = count;
        mBannerTitleResId = titleResId;
        mBannerVisible = true;
        if (wasVisible) {
            notifyItemChanged(0);
        } else {
            // Inserting at 0 shifts every diffed row down by one; the
            // OffsetListUpdateCallback installed in GridListBaseAdapter
            // makes subsequent AsyncListDiffer events line up with the
            // shifted positions automatically.
            notifyItemInserted(0);
        }
    }

    public void dismissBanner() {
        if (!mBannerVisible) return;
        mBannerVisible = false;
        mBannerCount = 0;
        notifyItemRemoved(0);
    }

    /**
     * Set banner state without dispatching any notify event. Used by
     * the fragment's first-snapshot application path so the adapter
     * carries the banner row from the moment it's attached to the
     * RecyclerView — the first layout pass sees the banner via
     * {@link #getItemCount} / {@link #getItemViewType} and lays it out
     * together with the tabs in a single pass. After the adapter is
     * attached use {@link #showBanner} / {@link #dismissBanner} which
     * dispatch proper {@code notifyItem*} events.
     */
    public void setBannerSilently(boolean visible, int count,
                                  @PluralsRes int titleResId,
                                  @Nullable OnBannerActionListener listener) {
        mBannerVisible = visible && count > 0;
        mBannerCount = mBannerVisible ? count : 0;
        if (mBannerVisible) mBannerTitleResId = titleResId;
        if (listener != null) mBannerListener = listener;
    }

    public boolean isBannerVisible() {
        return mBannerVisible;
    }

    @Override
    public int getPositionOffset() {
        return mBannerVisible ? 1 : 0;
    }

    @Override
    public int getItemCount() {
        return super.getItemCount() + getPositionOffset();
    }

    @Override
    public int getItemViewType(int position) {
        if (mBannerVisible && position == 0) return TYPE_BANNER;
        return super.getItemViewType(position);
    }

    /** Translate an adapter position into the differ's tab-list index.
     *  Caller is responsible for not invoking this on the banner row. */
    private int toTabIndex(int adapterPosition) {
        return adapterPosition - getPositionOffset();
    }

    private GeckoStateEntity getTab(int adapterPosition) {
        return getItem(toTabIndex(adapterPosition));
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup viewGroup, int viewType) {

        if (viewType == TYPE_BANNER) {
            View view = LayoutInflater.from(viewGroup.getContext())
                    .inflate(R.layout.fragment_tabs_archive_banner, viewGroup, false);
            return new BannerViewHolder(view);
        }
        if (viewType == TYPE_LIST) {
            View view = LayoutInflater.from(viewGroup.getContext())
                    .inflate(R.layout.fragment_tabs_item_list, viewGroup, false);
            return new BrowserTabsAdapter.TabEntityViewHolderPhone(view, mOnItemClickListener);
        }else{
            View view = LayoutInflater.from(viewGroup.getContext())
                    .inflate(R.layout.fragment_tabs_item, viewGroup, false);
            return new BrowserTabsAdapter.TabEntityViewHolderPhone(view, mOnItemClickListener);
        }
    }



    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder viewHolder,
                                 int position,
                                 @NonNull List<Object> payloads) {

        if (viewHolder instanceof BannerViewHolder bannerHolder) {
            bannerHolder.bind(mBannerCount, mBannerTitleResId, mBannerListener);
            return;
        }

        if (payloads.isEmpty()) {
            Log.d("BrowserTabsAdapter", "FULL BIND position=" + position
                    + " id=" + getTab(position).getId()
                    + " active=" + getTab(position).isActive());
            onBindViewHolder(viewHolder, position);
            return;
        }

        Log.d("BrowserTabsAdapter", "PARTIAL BIND position=" + position
                + " id=" + getTab(position).getId()
                + " payloads=" + payloads.size());

        TabEntityViewHolderPhone holder = (TabEntityViewHolderPhone) viewHolder;
        GeckoStateEntity entity = getTab(position);
        boolean incognito = entity.isIncognito();

        for (Object raw : payloads) {
            if (!(raw instanceof Bundle bundle)) continue;

            if (bundle.containsKey(GeckoStateDiffCallback.PAYLOAD_THUMB)) {
                String thumb = entity.getThumb();
                bindThumb(holder, entity, thumb);
            }
            if (bundle.containsKey(GeckoStateDiffCallback.PAYLOAD_ACTIVE)) {
                boolean active = bundle.getBoolean(GeckoStateDiffCallback.PAYLOAD_ACTIVE);
                int bgColor;
                if (active) {
                    bgColor = mColorSelected;
                } else {
                    bgColor = entity.isIncognito() ? mColorIncognitoNormal : mColorNormal;
                }
                holder.item.setCardBackgroundColor(bgColor);
                int onSurfaceColor = IncognitoColors.getOnSurface(mContext, incognito);
                holder.file_name.setTextColor(active ? mColorOnPrimaryContainer : onSurfaceColor);
                holder.file_url.setTextColor(active ? mColorOnPrimaryContainer : onSurfaceColor);
                holder.close.setColorFilter(active ? mColorOnPrimaryContainer : onSurfaceColor);
            }
            if (bundle.containsKey(GeckoStateDiffCallback.PAYLOAD_TITLE)) {
                String title = bundle.getString(GeckoStateDiffCallback.PAYLOAD_TITLE);
                String url = entity.getUri();
                holder.file_name.setText(TextUtils.isEmpty(title) ? url : title);
            }
            if (bundle.containsKey(GeckoStateDiffCallback.PAYLOAD_ICON)) {
                String icon = bundle.getString(GeckoStateDiffCallback.PAYLOAD_ICON);
                GlideHelper.load(icon, icon, holder.file_icon, mRequestOptions);
            }
        }

        // Always rebind media indicator on partial bind
        bindMediaIndicator(holder, entity);
    }


    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder viewHolder, int position) {

        if (viewHolder instanceof BannerViewHolder bannerHolder) {
            bannerHolder.bind(mBannerCount, mBannerTitleResId, mBannerListener);
            return;
        }

        GeckoStateEntity geckoStateEntity = getTab(position);
        TabEntityViewHolderPhone holder = (TabEntityViewHolderPhone) viewHolder;

        String fileImage = geckoStateEntity.getThumb();
        String fileIcon = geckoStateEntity.getIcon();
        String title = geckoStateEntity.getTitle();
        String url = geckoStateEntity.getUri();
        boolean incognito = geckoStateEntity.isIncognito();

        boolean active = geckoStateEntity.isActive();

        Log.d(TAG, "setEntityIcon filename: " + fileImage + " fileIcon: " + fileIcon + " fileUrl: " + url + " isHome: " + geckoStateEntity.isHome() + " isActive: " + geckoStateEntity.isActive());

        if (geckoStateEntity.isHome()) {
            Glide.with(holder.itemView).clear(holder.file_image);
            Glide.with(holder.itemView)
                    .load(geckoStateEntity.isIncognito() ? R.drawable.new_incognito_tab : R.drawable.new_tab)
                    .dontAnimate()
                    .into(holder.file_image);
            holder.file_icon.setVisibility(View.GONE);
            holder.file_url.setText(mContext.getString(R.string.popup_tabs_new));
            holder.file_name.setText(mContext.getString(R.string.popup_tabs_new));
        } else {
            bindThumb(holder, geckoStateEntity, fileImage);

            holder.file_icon.setVisibility(View.VISIBLE);

            {
                holder.file_name.setText(TextUtils.isEmpty(title) ? url : title);
                holder.file_url.setText(url);
                GlideHelper.load(fileIcon, url, holder.file_icon, mRequestOptions);
            }
        }

        Log.d(TAG, "TabsFragment adapter: " + geckoStateEntity.getId() + " active: " + geckoStateEntity.isActive());

        int bgColor;
        if (active) {
            bgColor = mColorSelected;
        } else {
            bgColor = geckoStateEntity.isIncognito() ? mColorIncognitoNormal : mColorNormal;
        }

        int onSurfaceColor = IncognitoColors.getOnSurface(mContext, incognito);

        holder.item.setCardBackgroundColor(bgColor);

        holder.file_name.setTextColor(active
                ? mColorOnPrimaryContainer
                : onSurfaceColor);

        holder.file_url.setTextColor(active
                ? mColorOnPrimaryContainer
                : onSurfaceColor);

        holder.close.setColorFilter(active
                ? mColorOnPrimaryContainer
                : onSurfaceColor);

        // Media indicator
        bindMediaIndicator(holder, geckoStateEntity);
    }


    @Override
    public long getItemId(int position) {
        if (mBannerVisible && position == 0) return Long.MIN_VALUE;
        GeckoStateEntity geckoStateEntity = getTab(position);
        if (geckoStateEntity != null) {
            return geckoStateEntity.getId();
        }
        return 0;
    }

    @Override
    public void onViewRecycled(@NonNull RecyclerView.ViewHolder holder) {
        super.onViewRecycled(holder);
        if (holder instanceof TabEntityViewHolderPhone tabHolder) {
            GlideHelper.clearSafe(tabHolder.file_image, tabHolder.file_icon);
        }
    }


    // ── Media indicator ──────────────────────────────────────────────────

    /**
     * Updates the set of session IDs that are currently playing media.
     * Call this from your Fragment/Activity whenever GeckoMediaController
     * reports a change in active sessions.
     */
    public void setMediaSessionIds(@NonNull Set<Integer> sessionIds) {
        Set<Integer> old = mMediaSessionIds;
        mMediaSessionIds = sessionIds;

        // Iterate the differ's tab list directly so banner-shift bookkeeping
        // doesn't sneak into the per-item check; convert tab index back to
        // adapter position once via getPositionOffset() when we actually
        // dispatch the change notification.
        int offset = getPositionOffset();
        int tabCount = super.getItemCount();
        for (int i = 0; i < tabCount; i++) {
            int id = getItem(i).getId();
            boolean wasPaying = old.contains(id);
            boolean isPlaying = sessionIds.contains(id);
            if (wasPaying != isPlaying) {
                notifyItemChanged(i + offset);
            }
        }
    }

    private void bindMediaIndicator(TabEntityViewHolderPhone holder, GeckoStateEntity entity) {
        if (holder.mediaIndicator == null) return;
        boolean playing = mMediaSessionIds.contains(entity.getId());
        holder.mediaIndicator.setVisibility(playing ? View.VISIBLE : View.GONE);
    }


    // ── Thumbnail binding ────────────────────────────────────────────────

    private void bindThumb(TabEntityViewHolderPhone holder, GeckoStateEntity entity, String thumb) {
        Bitmap cached = entity.getCachedThumb();
        if (cached != null) {
            holder.file_image.setImageBitmap(cached);
            return;
        }
        if (TextUtils.isEmpty(thumb)) {
            Glide.with(holder.itemView).clear(holder.file_image);
            if (!entity.isActive()) {
                Glide.with(holder.itemView)
                        .load(R.color.md_theme_onSurfaceVariant)
                        .dontAnimate()
                        .into(holder.file_image);
            } else {
                holder.file_image.setImageDrawable(null);
            }
        } else {
            Glide.with(holder.itemView)
                    .load(thumb)
                    .dontAnimate()
                    .placeholder(null)
                    .into(holder.file_image);
        }
    }


    // ── ViewHolder ───────────────────────────────────────────────────────

    static class TabEntityViewHolderPhone extends RecyclerView.ViewHolder implements View.OnClickListener {
        OnItemClickListener mOnItemClickListener;
        MaterialCardView item;
        AppCompatImageButton close;
        TextView file_name;
        TextView file_url;
        AppCompatImageView file_image;
        AppCompatImageView file_icon;
        AppCompatImageView mediaIndicator;

        public TabEntityViewHolderPhone(View view, OnItemClickListener onItemClickListener) {
            super(view);
            mOnItemClickListener = onItemClickListener;
            item = view.findViewById(R.id.tab_item);
            file_name = view.findViewById(R.id.tab_title);
            close = view.findViewById(R.id.tab_close);
            file_image = view.findViewById(R.id.tab_thumbnail);
            file_url = view.findViewById(R.id.tab_url);
            file_icon = view.findViewById(R.id.tab_icon);
            mediaIndicator = view.findViewById(R.id.tab_media_indicator);
            file_icon.setClipToOutline(true);
            file_image.setClipToOutline(true);
            item.setOnClickListener(this);
            close.setOnClickListener(this);
            Utils.expandTouchArea(close);
        }

        @Override
        public void onClick(View v) {
            int position = getAbsoluteAdapterPosition();
            if (mOnItemClickListener != null) {
                mOnItemClickListener.onItemClick(position, v.getId());
            }
        }
    }


    // ── Banner ViewHolder ────────────────────────────────────────────────

    /** Renders the "X tabs archived in the last [interval]" card.
     *  Listeners are rebound on every bind so the adapter can be swapped
     *  between fragments / configurations without leaking the previous
     *  listener instance. */
    static class BannerViewHolder extends RecyclerView.ViewHolder {

        private final TextView mTitle;
        private final TextView mSubtitle;
        private final View mDismiss;

        BannerViewHolder(@NonNull View itemView) {
            super(itemView);
            mTitle = itemView.findViewById(R.id.archive_banner_title);
            mSubtitle = itemView.findViewById(R.id.archive_banner_subtitle);
            mDismiss = itemView.findViewById(R.id.archive_banner_dismiss);
        }

        void bind(int count, @PluralsRes int titleResId,
                  @Nullable OnBannerActionListener listener) {
            mTitle.setText(itemView.getResources().getQuantityString(titleResId, count, count));
            mSubtitle.setText(R.string.archive_banner_subtitle);
            if (listener == null) {
                itemView.setOnClickListener(null);
                mDismiss.setOnClickListener(null);
            } else {
                itemView.setOnClickListener(v -> listener.onViewArchive());
                mDismiss.setOnClickListener(v -> listener.onDismiss());
            }
        }
    }
}