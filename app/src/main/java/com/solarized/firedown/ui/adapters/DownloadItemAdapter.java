package com.solarized.firedown.ui.adapters;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.AppCompatImageView;
import androidx.core.content.ContextCompat;
import androidx.paging.PagingDataAdapter;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.request.RequestOptions;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.color.MaterialColors;
import com.solarized.firedown.GlideHelper;
import com.solarized.firedown.R;
import com.solarized.firedown.data.Download;
import com.solarized.firedown.data.entity.DownloadEntity;
import com.solarized.firedown.data.entity.DownloadSeparatorEntity;
import com.solarized.firedown.ui.OnItemClickListener;
import com.solarized.firedown.ui.ProgressOverlayView;
import com.solarized.firedown.utils.DateUtils;
import com.solarized.firedown.utils.FileUriHelper;
import com.solarized.firedown.utils.MessageHelper;
import com.solarized.firedown.utils.Utils;
import com.solarized.firedown.utils.WebUtils;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Locale;

public class DownloadItemAdapter extends PagingDataAdapter<Object, RecyclerView.ViewHolder> {

    private final Context mContext;
    private final OnItemClickListener mOnItemClickListener;
    private final HashSet<Integer> mSelected;
    private final int mColorNormal;
    private final int mColorSelected;
    private final Drawable mChecked;
    private final Drawable mUnChecked;
    private final RequestOptions mRequestOptions;
    private boolean mActionMode;
    private boolean mEnabled;
    private boolean mEnableGrid;



    public DownloadItemAdapter(Context context, @NonNull DiffUtil.ItemCallback<Object> diffCallback,
                               OnItemClickListener onItemClickListener, boolean enableGrid) {
        super(diffCallback);
        mContext = context;
        mEnabled = true;
        mEnableGrid = enableGrid;
        mOnItemClickListener = onItemClickListener;
        mSelected = new HashSet<>();
        mColorNormal = ContextCompat.getColor(mContext, R.color.transparent);
        mColorSelected = MaterialColors.getColor(context,
                com.google.android.material.R.attr.colorPrimaryContainer, Color.TRANSPARENT);
        mChecked = Utils.tintDrawableColor(context, R.drawable.ic_baseline_check_circle_24, MaterialColors.getColor(context,
                com.google.android.material.R.attr.colorPrimaryContainer, Color.TRANSPARENT));
        mUnChecked = Utils.tintDrawableColor(context, R.drawable.radio_button_unchecked_24,
                MaterialColors.getColor(context,
                        com.google.android.material.R.attr.colorPrimaryContainer, Color.TRANSPARENT));
        mRequestOptions = new RequestOptions();
    }


    @Override
    public void onViewRecycled(@NonNull RecyclerView.ViewHolder holder) {
        super.onViewRecycled(holder);
        if (holder instanceof DownloadViewHolder h) {
            GlideHelper.clearSafe(h.image);
            // Clear tag too — a bind after recycle calls setTag(null) and relies on it,
            // and any future caller reading the tag should not see a stale key.
            h.image.setTag(null);
        }
    }

    // ── Selection / state management ────────────────────────────────────
    // Selection is tracked by entity ID, NOT adapter position.
    // Positions shift when PagingData refreshes or separators are inserted/removed,
    // causing position-based selection to point at wrong items.

    public void setActionMode(boolean value) {
        mActionMode = value;
        notifyItemRangeChanged(0, getItemCount());
    }

    public void setSelected(int position) {
        Object item = peek(position);
        if (!(item instanceof DownloadEntity entity)) return;
        int id = entity.getId();
        if (mSelected.contains(id))
            mSelected.remove(id);
        else
            mSelected.add(id);
        notifyItemChanged(position);
    }

    public boolean isSelected(int entityId) {
        return mSelected.contains(entityId);
    }

    public int getSelectedSize() { return mSelected.size(); }
    public HashSet<Integer> getSelectedIds() { return mSelected; }
    /** @deprecated Use {@link #getSelectedIds()} — returns entity IDs, not positions. */
    @Deprecated
    public HashSet<Integer> getSelected() { return mSelected; }
    public boolean isSelectedEmpty() { return mSelected.isEmpty(); }
    public void clearSelected() { mSelected.clear(); }

    public void setEnabled(boolean enabled) {
        mEnabled = enabled;
        notifyItemRangeChanged(0, getItemCount());
    }

    public void selectAll() {
        for (int i = 0; i < getItemCount(); i++) {
            if (peek(i) instanceof DownloadEntity entity) {
                mSelected.add(entity.getId());
            }
        }
        notifyItemRangeChanged(0, getItemCount());
    }

    public void deselectAll() {
        mSelected.clear();
        notifyItemRangeChanged(0, getItemCount());
    }

    /**
     * Returns all currently-selected DownloadEntity objects by scanning the snapshot.
     * This is the safe way to collect entities — never resolve by position.
     */
    public ArrayList<DownloadEntity> getSelectedEntities() {
        ArrayList<DownloadEntity> result = new ArrayList<>(mSelected.size());
        for (int i = 0; i < getItemCount(); i++) {
            Object item = peek(i);
            if (item instanceof DownloadEntity entity && mSelected.contains(entity.getId())) {
                result.add(entity);
            }
        }
        return result;
    }

    /**
     * Returns selected entities filtered to only FINISHED status.
     */
    public ArrayList<DownloadEntity> getSelectedFinishedEntities() {
        ArrayList<DownloadEntity> result = new ArrayList<>(mSelected.size());
        for (int i = 0; i < getItemCount(); i++) {
            Object item = peek(i);
            if (item instanceof DownloadEntity entity
                    && mSelected.contains(entity.getId())
                    && entity.getFileStatus() == Download.FINISHED) {
                result.add(entity);
            }
        }
        return result;
    }

    @SuppressLint("NotifyDataSetChanged")
    public void enableGrid(boolean grid) {
        mEnableGrid = grid;
        notifyDataSetChanged();
    }

    @Nullable
    public DownloadEntity getDownloadEntity(int position) {
        Object item = peek(position);
        return item instanceof DownloadEntity entity ? entity : null;
    }

    // ── View types ──────────────────────────────────────────────────────

    @Override
    public int getItemViewType(int position) {
        Object item = peek(position);
        if (item instanceof DownloadSeparatorEntity) return Download.HEADER;
        if (item instanceof DownloadEntity entity) {
            int status = entity.getFileStatus();
            return switch (status) {
                case Download.FINISHED -> mEnableGrid ? Download.FINISHED_GRID : Download.FINISHED;
                case Download.PROGRESS -> mEnableGrid ? Download.PROGRESS_GRID : Download.PROGRESS;
                case Download.QUEUED   -> mEnableGrid ? Download.QUEUED_GRID : Download.QUEUED;
                case Download.ERROR    -> mEnableGrid ? Download.ERROR_GRID : Download.ERROR;
                default -> status;
            };
        }
        return Download.EMPTY;
    }

    private boolean isGridType(int viewType) {
        return viewType == Download.FINISHED_GRID
                || viewType == Download.PROGRESS_GRID
                || viewType == Download.QUEUED_GRID
                || viewType == Download.ERROR_GRID
                || viewType == Download.PAUSED_GRID;
    }

    private int getStatus(int viewType) {
        return switch (viewType) {
            case Download.PROGRESS, Download.PROGRESS_GRID -> Download.PROGRESS;
            case Download.FINISHED, Download.FINISHED_GRID -> Download.FINISHED;
            case Download.QUEUED, Download.QUEUED_GRID, Download.PAUSED_GRID -> Download.QUEUED;
            case Download.ERROR, Download.ERROR_GRID -> Download.ERROR;
            default -> -1;
        };
    }

    // ── Create ──────────────────────────────────────────────────────────

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inflater = LayoutInflater.from(parent.getContext());

        if (viewType == Download.HEADER) {
            return new HeaderViewHolder(inflater.inflate(R.layout.fragment_item_header, parent, false));
        }

        if (viewType == Download.EMPTY) {
            return new EmptyViewHolder(inflater.inflate(R.layout.fragment_download_empty_item, parent, false));
        }

        int layoutRes = isGridType(viewType)
                ? R.layout.fragment_download_item_grid
                : R.layout.fragment_download_item;

        return new DownloadViewHolder(inflater.inflate(layoutRes, parent, false), mOnItemClickListener);
    }

    // ── Bind ────────────────────────────────────────────────────────────

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder viewHolder, int position) {
        Object item = getItem(position);
        if (item == null) return;

        if (viewHolder instanceof HeaderViewHolder header && item instanceof DownloadSeparatorEntity sep) {
            if (sep.getTitleResId() != 0) {
                header.text.setText(header.itemView.getContext().getString(sep.getTitleResId()));
            } else {
                header.text.setText(sep.getTitleText());
            }
            return;
        }

        if (!(viewHolder instanceof DownloadViewHolder holder) || !(item instanceof DownloadEntity entity))
            return;

        int viewType = getItemViewType(position);
        int status = getStatus(viewType);
        boolean isGrid = isGridType(viewType);
        boolean contains = mSelected.contains(entity.getId());

        String mimeType = entity.getFileMimeType();
        String originUrl = entity.getOriginUrl();
        String fileUrl = entity.getFileUrl();
        String domain = TextUtils.isEmpty(originUrl)
                ? WebUtils.getDomainName(fileUrl) : WebUtils.getDomainName(originUrl);

        // ── Common fields ───────────────────────────────────────────
        holder.item.setEnabled(mEnabled);
        holder.item.setStrokeColor(mActionMode && contains ? mColorSelected : mColorNormal);
        holder.selected.setVisibility(mActionMode ? View.VISIBLE : View.GONE);
        holder.selected.setImageDrawable(mActionMode ? (contains ? mChecked : mUnChecked) : null);
        holder.mimeText.setText(FileUriHelper.getLongMimeText(mContext, mimeType));

        if (holder.fileName != null) holder.fileName.setText(entity.getFileName());
        if (holder.fileUrl != null) holder.fileUrl.setText(domain);


        // ── Action button icon ──────────────────────────────────────
        if (status == Download.QUEUED) {
            holder.actionButton.setVisibility(mActionMode ? View.INVISIBLE : View.VISIBLE);
            setActionIcon(holder, isGrid, R.drawable.ic_clear_24);
        } else {
            holder.actionButton.setVisibility(mActionMode ? View.INVISIBLE : View.VISIBLE);
            setActionIcon(holder, isGrid, R.drawable.ic_baseline_more_vert_24);
        }

        // ── Reset all status views ──────────────────────────────────
        setVisible(holder.progressText, false);
        setVisible(holder.progressBar, false);
        setVisible(holder.finishedText, false);
        setVisible(holder.errorText, false);
        setVisible(holder.queuedText, false);
        setVisible(holder.imageProgress, false);
        setVisible(holder.topScrim, false);
        setVisible(holder.mimeDuration, false);

        // ── Status-specific binding ─────────────────────────────────
        switch (status) {
            case Download.PROGRESS -> bindProgress(holder, entity, isGrid);
            case Download.FINISHED -> bindFinished(holder, entity, isGrid);
            case Download.ERROR -> bindError(holder, entity);
            case Download.QUEUED -> bindQueued(holder, entity, isGrid);
        }
    }

    private void bindProgress(DownloadViewHolder holder, DownloadEntity entity, boolean isGrid) {
        boolean retrieving = entity.getFileIsLive();

        if(isGrid){
            // No thumbnail — overlay is the entire visual
            if (holder.imageProgress != null) {
                holder.imageProgress.setVisibility(View.VISIBLE);
                holder.imageProgress.setIndeterminate(retrieving);
                if (!retrieving) {
                    holder.imageProgress.setProgress(entity.getFileProgress());
                }
            }
            // Cancel any in-flight load so a late completion can't paint over the null.
            GlideHelper.clearSafe(holder.image);
            holder.image.setImageDrawable(null);
            holder.image.setTag(null);
        }else {
            setVisible(holder.progressText, true);
            setVisible(holder.progressBar, true);
            if(holder.progressText != null){
                holder.progressText.setText(retrieving
                        ? Utils.readableFileSize(entity.getFileSize())
                        : String.format(Locale.US, "%d%%", entity.getFileProgress()));
            }
            if(holder.progressBar != null){
                holder.progressBar.setIndeterminate(retrieving);
                if (!retrieving) holder.progressBar.setProgress(entity.getFileProgress());
            }
            GlideHelper.loadFallback(entity, holder.image);
        }


    }

    private void bindFinished(DownloadViewHolder holder, DownloadEntity entity, boolean isGrid) {
        String size = Utils.getFileSize(entity.getFileSize());
        String date = DateUtils.getFileDate(entity.getFileDate());
        String mimeType = entity.getFileMimeType();
        String durationFormat = entity.getDurationFormatted();
        boolean hasDuration = !TextUtils.isEmpty(durationFormat)
                && (FileUriHelper.isVideo(mimeType) || FileUriHelper.isAudio(mimeType));

        if (isGrid) {
            setVisible(holder.topScrim, true);
            if (holder.mimeDuration != null && hasDuration) {
                holder.mimeDuration.setVisibility(View.VISIBLE);
                holder.mimeDuration.setText(durationFormat);
            }
        }

        if (holder.finishedText != null) {
            holder.finishedText.setVisibility(isGrid ? View.GONE: View.VISIBLE);
            holder.finishedText.setText(String.format("%s - %s", size, date));
        }

        // Finished items always load the real thumbnail
        GlideHelper.load(entity, mRequestOptions, holder.image);
    }

    private void bindError(DownloadViewHolder holder, DownloadEntity entity) {
        if (holder.errorText != null) {
            holder.errorText.setVisibility(View.VISIBLE);
            int errorId = MessageHelper.getResourceIdFromCode(entity.getFileErrorType());
            holder.errorText.setText(errorId);
        }
        GlideHelper.loadFallback(entity, holder.image);
    }

    private void bindQueued(DownloadViewHolder holder, DownloadEntity entity, boolean isGrid) {
        if (!isGrid && holder.queuedText != null) {
            holder.queuedText.setVisibility(View.VISIBLE);
        }
        GlideHelper.loadFallback(entity, holder.image);
    }


    // ── Helpers ──────────────────────────────────────────────────────────

    private static void setVisible(@Nullable View view, boolean visible) {
        if (view != null) view.setVisibility(visible ? View.VISIBLE : View.GONE);
    }

    private static void setActionIcon(DownloadViewHolder holder, boolean isGrid, int iconRes) {
        // Works for both AppCompatImageButton (list) and MaterialButton (grid)

        if (holder.actionButton instanceof MaterialButton btn) {
            btn.setIconResource(iconRes);
            btn.setIconTint(ColorStateList.valueOf(
                    isGrid ? Color.WHITE
                            : MaterialColors.getColor(btn, com.google.android.material.R.attr.colorOnSurfaceVariant)
            ));
        }
    }

    // ── ViewHolders ─────────────────────────────────────────────────────

    static class DownloadViewHolder extends RecyclerView.ViewHolder
            implements View.OnClickListener, View.OnLongClickListener {

        final OnItemClickListener listener;
        final MaterialCardView item;
        final AppCompatImageView selected;
        final AppCompatImageView image;
        final TextView mimeText;
        final @Nullable TextView fileName;
        final @Nullable TextView fileUrl;
        final View actionButton;

        // Status-specific (nullable — not all layouts have all views)
        final @Nullable ProgressOverlayView imageProgress;
        final @Nullable TextView progressText;
        final @Nullable ProgressBar progressBar;
        final @Nullable TextView finishedText;
        final @Nullable TextView errorText;
        final @Nullable TextView queuedText;
        final @Nullable TextView mimeDuration;
        final @Nullable View topScrim;

        DownloadViewHolder(View view, OnItemClickListener onItemClickListener) {
            super(view);
            listener = onItemClickListener;

            item = view.findViewById(R.id.item);
            selected = view.findViewById(R.id.item_download_selected);
            image = view.findViewById(R.id.image);
            mimeText = view.findViewById(R.id.mime_text);
            fileName = view.findViewById(R.id.file_name);
            fileUrl = view.findViewById(R.id.file_url);

            // Unified action button ID
            actionButton = view.findViewById(R.id.item_download_action);

            // Status views (null-safe across list/grid layouts)
            imageProgress = view.findViewById(R.id.image_progress);
            progressText = view.findViewById(R.id.progress_text);
            progressBar = view.findViewById(R.id.progress_bar);
            finishedText = view.findViewById(R.id.item_download_finished);
            errorText = view.findViewById(R.id.error_text);
            queuedText = view.findViewById(R.id.queued_text);
            mimeDuration = view.findViewById(R.id.mime_duration);
            topScrim = view.findViewById(R.id.top_scrim);

            image.setClipToOutline(true);

            item.setOnClickListener(this);
            item.setOnLongClickListener(this);
            selected.setOnClickListener(this);
            if (actionButton != null) {
                actionButton.setOnClickListener(this);
                Utils.expandTouchArea(actionButton);
            }
        }

        @Override
        public void onClick(View v) {
            int pos = getAbsoluteAdapterPosition();
            if (listener != null) listener.onItemClick(pos, v.getId());
        }

        @Override
        public boolean onLongClick(View v) {
            int pos = getAbsoluteAdapterPosition();
            if (listener != null) {
                listener.onLongClick(pos, v.getId());
                return true;
            }
            return false;
        }
    }

    static class HeaderViewHolder extends RecyclerView.ViewHolder {
        final TextView text;
        HeaderViewHolder(View view) {
            super(view);
            text = view.findViewById(R.id.item_header);
        }
    }

    static class EmptyViewHolder extends RecyclerView.ViewHolder {
        EmptyViewHolder(View view) { super(view); }
    }
}