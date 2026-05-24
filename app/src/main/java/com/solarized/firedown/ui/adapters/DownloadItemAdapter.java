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
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.color.MaterialColors;
import com.solarized.firedown.GlideHelper;
import com.solarized.firedown.R;
import com.solarized.firedown.Sorting;
import com.solarized.firedown.data.Download;
import com.solarized.firedown.data.entity.DownloadEntity;
import com.solarized.firedown.data.entity.DownloadSeparatorEntity;
import com.solarized.firedown.ui.OnItemClickListener;
import com.solarized.firedown.ui.ProgressOverlayView;
import com.solarized.firedown.utils.DateUtils;
import com.solarized.firedown.utils.DownloadSortOrganizer;
import com.solarized.firedown.utils.FileUriHelper;
import com.solarized.firedown.utils.GroupAggregate;
import com.solarized.firedown.utils.MessageHelper;
import com.solarized.firedown.utils.Utils;
import com.solarized.firedown.utils.WebUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class DownloadItemAdapter extends PagingDataAdapter<Object, RecyclerView.ViewHolder> {

    private final Context mContext;
    private final OnItemClickListener mOnItemClickListener;
    private final HashSet<Integer> mSelected;
    private final int mColorNormal;
    private final int mColorSelected;
    private final Drawable mChecked;
    private final Drawable mUnChecked;
    private final RequestOptions mRequestOptions;
    /** Backgrounds for download rows. Active and finished now share
     *  the same surface — the live signal moved to a thicker, tinted
     *  LinearProgressIndicator under the filename. Stacked active
     *  rows used to read as one heavy warm block under the
     *  "Downloading" section; the per-row bar is per-row by definition
     *  and stacks cleanly. List items want plain surface (transparent
     *  against the page); grid items keep the surfaceContainerHigh
     *  placeholder the layout originally set. */
    private final int mDefaultListBg;
    private final int mDefaultGridBg;
    /** Brand accent for the list-mode mime label, also used as the
     *  progress bar indicator colour. */
    private final int mDefaultPrimary;
    private final int mDefaultPrimaryAlpha;
    private boolean mActionMode;
    private boolean mEnabled;
    private boolean mEnableGrid;

    /** Current sort mode, mirrored from the ViewModel so the adapter can map
     *  any DownloadEntity to its section category for the collapse check
     *  and the chevron rotation. Same category function as the separator
     *  insertion path uses, so headers and items always agree. */
    private int mSortType = Sorting.SORT_DATE;
    private DownloadSortOrganizer mOrganizer = new DownloadSortOrganizer(mSortType);

    /** Section-header collapse state. A category is collapsed iff it
     *  appears in this set — default empty so a fresh sort starts with
     *  everything expanded. */
    @NonNull private Set<Integer> mCollapsedCategories = Collections.emptySet();

    /** Per-category aggregates used to fill the header subtitle
     *  ("N files · X MB"). Empty until the ViewModel's aggregator emits. */
    @NonNull private Map<Integer, GroupAggregate> mAggregates = Collections.emptyMap();

    @Nullable private OnHeaderClickListener mOnHeaderClickListener;

    /** Header click contract — the fragment translates these to a
     *  ViewModel toggle so the expand set lives in one place. */
    public interface OnHeaderClickListener {
        void onHeaderClick(int category);
    }



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

        mDefaultListBg = MaterialColors.getColor(context,
                com.google.android.material.R.attr.colorSurface, Color.TRANSPARENT);
        mDefaultGridBg = MaterialColors.getColor(context,
                com.google.android.material.R.attr.colorSurfaceContainerHigh, Color.TRANSPARENT);
        mDefaultPrimary = MaterialColors.getColor(context,
                android.R.attr.colorPrimary, Color.BLACK);
        mDefaultPrimaryAlpha = androidx.core.graphics.ColorUtils
                .setAlphaComponent(mDefaultPrimary, 0x33);
    }


    @Override
    public void onViewRecycled(@NonNull RecyclerView.ViewHolder holder) {
        super.onViewRecycled(holder);
        if (holder instanceof DownloadViewHolder h) {
            GlideHelper.clearSafe(h.image);
            // Clear tag too — a bind after recycle calls setTag(null) and relies on it,
            // and any future caller reading the tag should not see a stale key.
            h.image.setTag(null);
            // Drop the FINISHED-row label cache. The id-mismatch check in
            // getFinishedLabel would already rebuild on the next bind, but
            // nulling here lets the String be GC'd while the holder sits
            // in the RecycledViewPool.
            h.cachedFinishedLabel = null;
        }
    }

    // ── Selection / state management ────────────────────────────────────
    // Selection is tracked by entity ID, NOT adapter position.
    // Positions shift when PagingData refreshes or separators are inserted/removed,
    // causing position-based selection to point at wrong items.

    /**
     * Sentinel passed in {@code payloads} so {@link #onBindViewHolder(
     * RecyclerView.ViewHolder, int, List)} can update selection chrome
     * (action mode + checkmark + stroke + action-button visibility)
     * without re-running the full bind path — the full bind allocates,
     * resolves mime text, kicks Glide loads, and rebuilds status views,
     * none of which change when the user enters action mode.
     */
    private static final Object PAYLOAD_SELECTION  = new Object();

    /** Section-header collapse toggled — items in the affected group flip
     *  between full-size and 0dp, headers rotate their chevron. No Glide
     *  load, no view rebuild. Without this targeted path, tapping any
     *  header would re-fire thumbnail decodes for every visible row,
     *  including audio files whose embedded-art decode is guaranteed to
     *  fail (FFmpegThumbnailer returns null bitmap), causing a visible
     *  blink on every collapse toggle. */
    private static final Object PAYLOAD_COLLAPSE   = new Object();

    /** Per-group aggregates changed — only header subtitles need to
     *  re-render. Items ignore this payload entirely. */
    private static final Object PAYLOAD_AGGREGATES = new Object();

    public void setActionMode(boolean value) {
        mActionMode = value;
        notifyItemRangeChanged(0, getItemCount(), PAYLOAD_SELECTION);
    }

    public void setSelected(int position) {
        Object item = peek(position);
        if (!(item instanceof DownloadEntity entity)) return;
        int id = entity.getId();
        if (mSelected.contains(id))
            mSelected.remove(id);
        else
            mSelected.add(id);
        notifyItemChanged(position, PAYLOAD_SELECTION);
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
        notifyItemRangeChanged(0, getItemCount(), PAYLOAD_SELECTION);
    }

    public void selectAll() {
        for (int i = 0; i < getItemCount(); i++) {
            if (peek(i) instanceof DownloadEntity entity) {
                mSelected.add(entity.getId());
            }
        }
        notifyItemRangeChanged(0, getItemCount(), PAYLOAD_SELECTION);
    }

    public void deselectAll() {
        mSelected.clear();
        notifyItemRangeChanged(0, getItemCount(), PAYLOAD_SELECTION);
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

    // ── Section header / collapse API ──────────────────────────────────

    /** Mirror the ViewModel's sort mode so item bind can resolve each
     *  entity's category for the collapse check. No-op when the mode
     *  hasn't actually changed — the organizer is stateless beyond the
     *  domain label cache, but rebuilding on every chip toggle would
     *  drop that cache mid-scroll for nothing. */
    public void setSortType(int sortType) {
        if (mSortType == sortType) return;
        mSortType = sortType;
        mOrganizer = new DownloadSortOrganizer(sortType);
        notifyItemRangeChanged(0, getItemCount());
    }

    public void setCollapsedCategories(@NonNull Set<Integer> collapsed) {
        if (mCollapsedCategories.equals(collapsed)) return;
        mCollapsedCategories = collapsed;
        // Targeted payload so the bind path can update chevron rotation
        // and item visibility without re-running the full bind (which
        // would re-fire Glide loads — fine for cached results, but audio
        // files whose embedded-art decode is guaranteed to fail get no
        // cache entry and re-decode on every retry, producing a visible
        // blink on each header tap).
        notifyItemRangeChanged(0, getItemCount(), PAYLOAD_COLLAPSE);
    }

    public void setAggregates(@NonNull Map<Integer, GroupAggregate> aggregates) {
        if (mAggregates == aggregates || mAggregates.equals(aggregates)) return;
        mAggregates = aggregates;
        // Only headers consume aggregates; the payload lets items short
        // out without rebinding (same blink-avoidance reasoning as
        // PAYLOAD_COLLAPSE).
        notifyItemRangeChanged(0, getItemCount(), PAYLOAD_AGGREGATES);
    }

    public void setOnHeaderClickListener(@Nullable OnHeaderClickListener listener) {
        mOnHeaderClickListener = listener;
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
            HeaderViewHolder header = new HeaderViewHolder(
                    inflater.inflate(R.layout.fragment_item_header, parent, false));
            header.itemView.setOnClickListener(v -> {
                // boundCategory is set during onBindViewHolder; a stale
                // sentinel means the holder hasn't been bound yet —
                // ignore rather than fire onHeaderClick(0) which would
                // collapse the "0" category by accident.
                if (mOnHeaderClickListener != null
                        && header.boundCategory != HeaderViewHolder.UNBOUND) {
                    mOnHeaderClickListener.onHeaderClick(header.boundCategory);
                }
            });
            return header;
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

    /**
     * Partial bind path. setActionMode / selectAll / deselectAll /
     * setEnabled all flip selection chrome on every visible row; the
     * full bind would re-resolve mime text, rebuild status views, and
     * fire Glide loads (which re-decode FFmpeg thumbnails on miss).
     * When the only payload is {@link #PAYLOAD_SELECTION}, just update
     * the selection-related views and skip the rest.
     */
    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder viewHolder,
                                 int position,
                                 @NonNull List<Object> payloads) {
        if (!payloads.isEmpty()
                && Collections.frequency(payloads, PAYLOAD_SELECTION) == payloads.size()
                && viewHolder instanceof DownloadViewHolder holder) {
            Object item = peek(position);
            if (!(item instanceof DownloadEntity entity)) return;
            boolean contains = mSelected.contains(entity.getId());
            int viewType = getItemViewType(position);
            int status = getStatus(viewType);
            boolean isGrid = isGridType(viewType);

            // Selection state factors into row visibility (an item in a
            // collapsed group stays visible while selected during action
            // mode), so re-evaluate height alongside the selection chrome.
            // Without this, selectAll would silently select rows inside
            // collapsed groups that the user can't see.
            applyRowVisibility(holder.itemView, isRowVisible(entity));

            holder.item.setEnabled(mEnabled);
            holder.item.setStrokeColor(mActionMode && contains ? mColorSelected : mColorNormal);
            holder.selected.setVisibility(mActionMode ? View.VISIBLE : View.GONE);
            holder.selected.setImageDrawable(mActionMode ? (contains ? mChecked : mUnChecked) : null);
            holder.actionButton.setVisibility(mActionMode ? View.INVISIBLE : View.VISIBLE);
            // Action icon depends on status (queued = clear, otherwise more)
            // — re-set so we don't end up showing the wrong glyph after a
            // status update raced with an action-mode toggle.
            setActionIcon(holder, isGrid,
                    status == Download.QUEUED
                            ? R.drawable.ic_clear_24
                            : R.drawable.ic_baseline_more_vert_24);
            return;
        }

        // Collapse-only payload: flip row visibility (items) or rotate the
        // chevron (headers). No Glide load, no view rebuild — this is the
        // path that prevents the "audio file blink on every header tap"
        // when FFmpegThumbnailer has no embedded art to extract.
        if (!payloads.isEmpty()
                && Collections.frequency(payloads, PAYLOAD_COLLAPSE) == payloads.size()) {
            applyCollapsePayload(viewHolder, position);
            return;
        }

        // Aggregates-only payload: header subtitle text. Items ignore.
        if (!payloads.isEmpty()
                && Collections.frequency(payloads, PAYLOAD_AGGREGATES) == payloads.size()) {
            applyAggregatesPayload(viewHolder, position);
            return;
        }

        // Anything else (or no payload, or mixed payloads) → full rebind.
        super.onBindViewHolder(viewHolder, position, payloads);
    }

    private void applyCollapsePayload(@NonNull RecyclerView.ViewHolder viewHolder, int position) {
        Object item = peek(position);
        if (item instanceof DownloadSeparatorEntity sep
                && viewHolder instanceof HeaderViewHolder header) {
            applyHeaderCollapseAffordance(header, sep.getCategory());
            return;
        }
        if (item instanceof DownloadEntity entity
                && viewHolder instanceof DownloadViewHolder holder) {
            applyRowVisibility(holder.itemView, isRowVisible(entity));
        }
    }

    private void applyAggregatesPayload(@NonNull RecyclerView.ViewHolder viewHolder, int position) {
        if (!(viewHolder instanceof HeaderViewHolder header)) return;
        Object item = peek(position);
        if (!(item instanceof DownloadSeparatorEntity sep)) return;
        GroupAggregate agg = mAggregates.get(sep.getCategory());
        if (agg != null) {
            header.subtitle.setVisibility(View.VISIBLE);
            header.subtitle.setText(formatGroupSubtitle(header.itemView.getContext(), agg));
        } else {
            header.subtitle.setVisibility(View.GONE);
        }
        // Group count may have shifted (sort change, downloads added /
        // removed) — refresh the chevron + tappability so a single-group
        // list doesn't expose a footgun toggle.
        applyHeaderCollapseAffordance(header, sep.getCategory());
    }

    /**
     * A header only earns its chevron and tap target when there's more
     * than one group to switch between. With a single group on screen
     * the only thing a tap can do is hide every download the user is
     * trying to look at — so we drop the chevron, kill the click and
     * the selectable-background foreground, and the row reads as a
     * plain section label.
     */
    private void applyHeaderCollapseAffordance(@NonNull HeaderViewHolder header, int category) {
        boolean canCollapse = mAggregates.size() > 1;
        header.chevron.setVisibility(canCollapse ? View.VISIBLE : View.GONE);
        header.itemView.setClickable(canCollapse);
        header.itemView.setFocusable(canCollapse);

        boolean expanded = !mCollapsedCategories.contains(category);
        header.chevron.setRotation(expanded ? 180f : 0f);
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder viewHolder, int position) {
        Object item = getItem(position);
        if (item == null) return;

        if (viewHolder instanceof HeaderViewHolder header && item instanceof DownloadSeparatorEntity sep) {
            int category = sep.getCategory();
            header.boundCategory = category;

            if (sep.getTitleResId() != 0) {
                header.text.setText(header.itemView.getContext().getString(sep.getTitleResId()));
            } else {
                header.text.setText(sep.getTitleText());
            }

            GroupAggregate agg = mAggregates.get(category);
            if (agg != null) {
                header.subtitle.setVisibility(View.VISIBLE);
                header.subtitle.setText(formatGroupSubtitle(header.itemView.getContext(), agg));
            } else {
                header.subtitle.setVisibility(View.GONE);
            }

            applyHeaderCollapseAffordance(header, category);
            return;
        }

        if (!(viewHolder instanceof DownloadViewHolder holder) || !(item instanceof DownloadEntity entity))
            return;

        // Collapse: items in a collapsed group render as a zero-height
        // row. They stay in the adapter (and in the PagingData) so
        // expanding doesn't re-trigger a fetch; they just take no
        // vertical space and skip the heavier bind work below.
        // isRowVisible centralises the search-mode / collapse /
        // action-mode-selected interaction.
        boolean rowVisible = isRowVisible(entity);
        applyRowVisibility(holder.itemView, rowVisible);
        if (!rowVisible) return;

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
        // List mode renders mime as a text label that prefixes the
        // domain ('VÍDEO · youtube.com'), so append the separator
        // here; grid keeps the chip styling, no separator.
        String mimeLabel = FileUriHelper.getLongMimeText(mContext, mimeType);
        if (TextUtils.isEmpty(mimeLabel)) {
            holder.mimeText.setVisibility(View.GONE);
        } else {
            holder.mimeText.setVisibility(View.VISIBLE);
            holder.mimeText.setText(isGrid ? mimeLabel : mimeLabel + " · ");
        }

        // ── Row surface ─────────────────────────────────────────────
        // Same background for active and finished rows. The active
        // signal lives in the thicker tinted LinearProgressIndicator
        // (list mode) or the existing ProgressOverlayView on the
        // thumbnail (grid mode), both of which are per-row and stack
        // cleanly under the "Downloading" section without forming
        // a warm-tinted block.
        holder.item.setCardBackgroundColor(isGrid ? mDefaultGridBg : mDefaultListBg);

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
        setVisible(holder.progressRow, false);
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
            setVisible(holder.progressRow, true);
            // Bar tints: indicator in brand coral (the 'live' signal)
            // and track in the same coral at ~20 % alpha so it reads
            // as a subtle channel beneath, not a saturated wash. M3
            // LinearProgressIndicator takes raw int colors; the
            // indicator color applies to both determinate and
            // indeterminate states.
            if (holder.progressBar != null) {
                holder.progressBar.setIndicatorColor(mDefaultPrimary);
                holder.progressBar.setTrackColor(mDefaultPrimaryAlpha);
            }
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
            holder.finishedText.setVisibility(isGrid ? View.GONE : View.VISIBLE);
            holder.finishedText.setText(getFinishedLabel(holder, entity));
        }

        // Finished items always load the real thumbnail
        GlideHelper.load(entity, mRequestOptions, holder.image);
    }


    /**
     * Returns the cached "<size> - <date>" label, rebuilding only when
     * the holder is bound to a different entity or the row's size /
     * date actually changed (rare for FINISHED — these are terminal
     * fields). Saves one Utils.getFileSize, one DateUtils.getFileDate,
     * and one String concatenation per scroll re-bind of the same row.
     */
    private static String getFinishedLabel(DownloadViewHolder holder, DownloadEntity entity) {
        long id = entity.getId();
        long size = entity.getFileSize();
        long date = entity.getFileDate();
        if (holder.cachedFinishedLabel != null
                && holder.cachedFinishedKeyId == id
                && holder.cachedFinishedKeySize == size
                && holder.cachedFinishedKeyDate == date) {
            return holder.cachedFinishedLabel;
        }
        String label = holder.itemView.getContext().getString(
                R.string.download_finished_meta,
                Utils.getFileSize(size),
                DateUtils.getFileDate(date));
        holder.cachedFinishedKeyId = id;
        holder.cachedFinishedKeySize = size;
        holder.cachedFinishedKeyDate = date;
        holder.cachedFinishedLabel = label;
        return label;
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
        final @Nullable View progressRow;
        final @Nullable TextView progressText;
        final @Nullable LinearProgressIndicator progressBar;
        final @Nullable TextView finishedText;
        final @Nullable TextView errorText;
        final @Nullable TextView queuedText;
        final @Nullable TextView mimeDuration;
        final @Nullable View topScrim;

        // Cache for the FINISHED row's "<size> - <date>" label. Built
        // from Utils.getFileSize + DateUtils.getFileDate, both of
        // which allocate; without the cache every bindFinished call
        // re-formats the same string. Keyed by id+size+date so a
        // size update (rare for FINISHED rows) invalidates cleanly,
        // and a recycled holder bound to a different entity rebuilds
        // on the first id mismatch. Cleared in onViewRecycled too.
        long cachedFinishedKeyId = Long.MIN_VALUE;
        long cachedFinishedKeySize = Long.MIN_VALUE;
        long cachedFinishedKeyDate = Long.MIN_VALUE;
        @Nullable String cachedFinishedLabel;

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
            progressRow = view.findViewById(R.id.progress_row);
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
            // Use binding (local) position so peek() into this PagingDataAdapter
            // stays correct when wrapped in a ConcatAdapter that prepends rows.
            int pos = getBindingAdapterPosition();
            if (listener != null) listener.onItemClick(pos, v.getId());
        }

        @Override
        public boolean onLongClick(View v) {
            int pos = getBindingAdapterPosition();
            if (listener != null) {
                listener.onLongClick(pos, v.getId());
                return true;
            }
            return false;
        }
    }

    static class HeaderViewHolder extends RecyclerView.ViewHolder {
        /** Distinguishes "never bound" from a legitimate category whose hash
         *  happens to be 0 — without it, a click on a fresh holder before the
         *  first bind would target category 0 (probably no group) and
         *  silently flip its state. */
        static final int UNBOUND = Integer.MIN_VALUE;

        final TextView text;
        final TextView subtitle;
        final View     chevron;
        int boundCategory = UNBOUND;

        HeaderViewHolder(View view) {
            super(view);
            text     = view.findViewById(R.id.item_header);
            subtitle = view.findViewById(R.id.item_header_subtitle);
            chevron  = view.findViewById(R.id.item_header_chevron);
        }
    }

    /**
     * Single source of truth for whether a download row should render at
     * full size or as a zero-height placeholder.
     *
     * <ul>
     *   <li>Flat-list (search) mode skips grouping entirely — the
     *       collapse set is meaningless without separators.</li>
     *   <li>Otherwise, a row is hidden iff its group sits in the
     *       collapsed set,</li>
     *   <li>…with one override: a selected row inside action mode stays
     *       visible regardless of its group's collapse state, so the
     *       user can always see what's in their selection. Without
     *       this, "select all" (or pre-collapse selections) would
     *       silently include rows the user can't see — surprise deletes.
     *       The header chevron stays at the user's explicit toggle
     *       state; only the selected rows pin through.</li>
     * </ul>
     */
    private boolean isRowVisible(@NonNull DownloadEntity entity) {
        boolean grouped = peek(0) instanceof DownloadSeparatorEntity;
        if (!grouped) return true;
        int cat = mOrganizer.getCategory(entity);
        if (!mCollapsedCategories.contains(cat)) return true;
        return mActionMode && mSelected.contains(entity.getId());
    }

    /**
     * Collapses or restores a download row by toggling its layoutParams
     * height. Visibility GONE alone wouldn't work — RecyclerView still
     * measures GONE children to their natural size, so the list would
     * keep gaps where collapsed items used to be. Setting height to 0
     * is what actually removes the row from the layout pass.
     */
    private static void applyRowVisibility(@NonNull View row, boolean visible) {
        ViewGroup.LayoutParams lp = row.getLayoutParams();
        if (lp == null) return;
        int target = visible ? ViewGroup.LayoutParams.WRAP_CONTENT : 0;
        if (lp.height != target) {
            lp.height = target;
            row.setLayoutParams(lp);
        }
        row.setVisibility(visible ? View.VISIBLE : View.GONE);
    }

    /** "{n} files · {size}". Pluralization is light — Java's
     *  {@code Resources.getQuantityString} is fine here but the
     *  English "1 file / N files" split is the only locale rule
     *  that matters for this header today. */
    private static String formatGroupSubtitle(@NonNull Context ctx, @NonNull GroupAggregate agg) {
        String files = ctx.getResources().getQuantityString(
                R.plurals.downloads_group_files, agg.count, agg.count);
        return files + " · " + Utils.readableFileSize(agg.totalSize);
    }

    static class EmptyViewHolder extends RecyclerView.ViewHolder {
        EmptyViewHolder(View view) { super(view); }
    }
}