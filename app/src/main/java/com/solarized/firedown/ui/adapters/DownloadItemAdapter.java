package com.solarized.firedown.ui.adapters;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.text.TextUtils;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.AppCompatImageView;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.ColorUtils;
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
import com.solarized.firedown.data.Download;
import com.solarized.firedown.data.entity.DownloadEntity;
import com.solarized.firedown.data.entity.DownloadSeparatorEntity;
import com.solarized.firedown.sync.CloudBackupManager;
import com.solarized.firedown.ui.OnItemClickListener;
import com.solarized.firedown.ui.ProgressOverlayView;
import com.solarized.firedown.utils.DateUtils;
import com.solarized.firedown.utils.FileUriHelper;
import com.solarized.firedown.utils.GroupAggregate;
import com.solarized.firedown.utils.MessageHelper;
import com.solarized.firedown.utils.SelectionStyling;
import com.solarized.firedown.utils.Tracing;
import com.solarized.firedown.utils.Utils;
import com.solarized.firedown.utils.WebUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class DownloadItemAdapter extends PagingDataAdapter<Object, RecyclerView.ViewHolder> {

    private static final String TAG = "DownloadItemAdapter";

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
    /** Selected-state tonal wash for each surface — primaryContainer
     *  layered at 20% over the respective default. Stroke alone
     *  wasn't loud enough to confirm "did I really pick these?" at
     *  scroll speed; the wash makes the selected set readable from
     *  across the screen without going as loud as a full
     *  primaryContainer fill. */
    private final int mSelectedListBg;
    private final int mSelectedGridBg;
    /** Brand accent for the list-mode mime label, also used as the
     *  progress bar indicator colour. */
    private final int mDefaultPrimary;
    private final int mDefaultPrimaryAlpha;
    /** colorOnSurfaceVariant resolved once at construction; the list-mode
     *  action button's icon tint. setActionIcon was previously calling
     *  MaterialColors.getColor inline on every bind, which is a theme
     *  attribute resolution per row — caching the int once removes that
     *  lookup from the hot scroll path. */
    private final int mActionIconTintList;
    /** ColorStateList wrappers cached per surface — setIconTint takes a
     *  ColorStateList, and wrapping a plain int with valueOf allocates
     *  on every bind. Two surfaces (grid = white, list =
     *  colorOnSurfaceVariant), so two cached lists cover every call. */
    private final ColorStateList mActionIconTintListCsl;
    private final ColorStateList mActionIconTintGridCsl;
    private boolean mActionMode;
    private boolean mEnabled;
    private boolean mEnableGrid;
    /** Images-only filtered grid → square bare tiles (no scrim/title/chip/
     *  action button) at a denser span. Set by configureRecyclerView; the
     *  setter doesn't notify because configureRecyclerView always follows
     *  with {@link #enableGrid}, whose notifyDataSetChanged re-resolves
     *  every view type. */
    private boolean mDenseImages;
    /** Hide the mime label/chip — active while a single-type filter chip
     *  is checked (the chip rail already states the type). See
     *  {@link #setMimeSuppressed}. */
    private boolean mSuppressMime;

    /** Content keys (name + size, see {@link CloudBackupManager#contentKey}) of
     *  files backed up to the cloud. A FINISHED row whose key is present shows a
     *  quiet "backed up" badge on its thumbnail. Empty = none loaded / not a
     *  cloud-backup user / offline — so a badge is shown ONLY for a positively
     *  known key, never a wrong "not backed up". Set by the fragment on resume
     *  ({@link #setBackedUpKeys}). */
    @NonNull private Set<String> mBackedUpKeys = Collections.emptySet();

    /** Per-category aggregates used to fill the header subtitle
     *  ("N files · X MB"). Empty until the ViewModel's aggregator emits. */
    @NonNull private Map<Integer, GroupAggregate> mAggregates = Collections.emptyMap();

    /** Localized "VÍDEO" / "IMAGEN" / etc. label per mime type, computed
     *  the first time we see a given mime then reused for every subsequent
     *  bind. The label is a resource string lookup, which goes through
     *  the theme + LocaleList; doing it on every bind for every visible
     *  row added up during cold-start scroll. Per-adapter (not static)
     *  so a configuration change that rebuilds the adapter under a new
     *  locale rebuilds the cache too. */
    private final HashMap<String, String> mMimeLabelCache = new HashMap<>(16);
    /** Same string with the list-mode trailing " · " separator already
     *  appended — saves a String concat per list-mode bind in addition
     *  to the resource lookup. */
    private final HashMap<String, String> mMimeLabelListCache = new HashMap<>(16);




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

        // Default list-row card background is transparent — the
        // RecyclerView's parent already paints colorSurface, so
        // resolving the attr and re-painting the same colour on every
        // card was a no-op. The selected wash still blends primaryContainer
        // over the resolved colorSurface (selectedCardWashOver does
        // need a concrete base to layer onto; without it the 20% alpha
        // would read as a faint hint instead of a clear wash). Grid
        // tiles do live on a different elevation (colorSurfaceContainerHigh)
        // than the page, so their default still resolves the attr.
        mDefaultListBg = Color.TRANSPARENT;
        mDefaultGridBg = MaterialColors.getColor(context,
                com.google.android.material.R.attr.colorSurfaceContainerHigh, Color.TRANSPARENT);
        mSelectedListBg = SelectionStyling.selectedCardWashOver(context,
                com.google.android.material.R.attr.colorSurface);
        mSelectedGridBg = SelectionStyling.selectedCardWashOver(context,
                com.google.android.material.R.attr.colorSurfaceContainerHigh);
        mDefaultPrimary = MaterialColors.getColor(context,
                android.R.attr.colorPrimary, Color.BLACK);
        mDefaultPrimaryAlpha = ColorUtils
                .setAlphaComponent(mDefaultPrimary, 0x33);
        mActionIconTintList = MaterialColors.getColor(context,
                com.google.android.material.R.attr.colorOnSurfaceVariant, Color.BLACK);
        mActionIconTintListCsl = ColorStateList.valueOf(mActionIconTintList);
        mActionIconTintGridCsl = ColorStateList.valueOf(Color.WHITE);
    }


    @Override
    public void onViewRecycled(@NonNull RecyclerView.ViewHolder holder) {
        super.onViewRecycled(holder);
        if (holder instanceof DownloadViewHolder h) {
            GlideHelper.clearSafe(h.image);
            // Clear tag too — a bind after recycle calls setTag(null) and relies on it,
            // and any future caller reading the tag should not see a stale key.
            h.image.setTag(null);
            // Keep the cached label / domain strings on recycle. The
            // id-keyed equality checks in getFinishedLabel and the
            // domain-cache block already invalidate on entity change,
            // so leaving the values pinned across the pool round-trip
            // turns scroll-back into a cache hit when the pool hands
            // the holder back to the same entity. Trace data with
            // these nulled showed 100% miss rate
            // (finishedLabel:miss == bind:finished). The pinned
            // strings are ~20-50 bytes each; the alloc + format cost
            // they save on every recycled-pool rebind is the bigger
            // number on cold-start scroll.
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

    /** See {@link #mDenseImages}. Field-only — call before enableGrid. */
    public void setDenseImages(boolean dense) {
        mDenseImages = dense;
    }

    /**
     * Hide the mime label/chip on every row — set while a single-type
     * filter chip is active, where stamping "VIDEO" on every tile merely
     * repeats what the checked chip already states. Must notify itself:
     * a filter change re-submits the paged list, but DiffUtil won't
     * rebind the items that survived the filter unchanged, so the flag
     * flip has to rebind them (no-op when the value didn't change, so
     * video → audio chip taps don't pay the rebind).
     */
    @SuppressLint("NotifyDataSetChanged")
    public void setMimeSuppressed(boolean suppressed) {
        if (mSuppressMime == suppressed) {
            return;
        }
        mSuppressMime = suppressed;
        notifyDataSetChanged();
    }

    /**
     * Sets the backed-up content keys (the cloud-backup badge source). A full
     * rebind is needed — the paged list didn't change, so DiffUtil won't rebind
     * the rows whose badge should now appear/disappear. No-op when unchanged
     * (the common resume where nothing was backed up meanwhile).
     */
    @SuppressLint("NotifyDataSetChanged")
    public void setBackedUpKeys(@NonNull Set<String> keys) {
        if (mBackedUpKeys.equals(keys)) {
            return;
        }
        mBackedUpKeys = keys;
        notifyDataSetChanged();
    }

    /** Whether this FINISHED, non-safe file is backed up to the cloud (its
     *  content key is in {@link #mBackedUpKeys}). Safe-folder files never leave
     *  the device, so they're never badged. */
    private boolean isBackedUp(DownloadEntity entity) {
        if (mBackedUpKeys.isEmpty() || entity.isFileSafe()) {
            return false;
        }
        return mBackedUpKeys.contains(
                CloudBackupManager.contentKey(entity.getFileName(), entity.getFileSize()));
    }

    @Nullable
    public DownloadEntity getDownloadEntity(int position) {
        Object item = peek(position);
        return item instanceof DownloadEntity entity ? entity : null;
    }

    // ── Section header aggregates ──────────────────────────────────────

    public void setAggregates(@NonNull Map<Integer, GroupAggregate> aggregates) {
        if (mAggregates == aggregates || mAggregates.equals(aggregates)) return;
        mAggregates = aggregates;
        // Only headers consume aggregates; the payload lets items short
        // out without rebinding — important because a full rebind would
        // re-fire Glide loads, and audio files whose embedded-art decode
        // is guaranteed to fail (FFmpegThumbnailer returns null bitmap)
        // get no cache entry and re-decode on every retry, producing a
        // visible blink on each aggregate emit.
        notifyItemRangeChanged(0, getItemCount(), PAYLOAD_AGGREGATES);
    }

    // ── View types ──────────────────────────────────────────────────────

    @Override
    public int getItemViewType(int position) {
        Object item = peek(position);
        if (item instanceof DownloadSeparatorEntity) return Download.HEADER;
        if (item instanceof DownloadEntity entity) {
            int status = entity.getFileStatus();
            if (mEnableGrid && mDenseImages) {
                return switch (status) {
                    case Download.FINISHED -> Download.FINISHED_GRID_DENSE;
                    case Download.PROGRESS -> Download.PROGRESS_GRID_DENSE;
                    case Download.QUEUED   -> Download.QUEUED_GRID_DENSE;
                    case Download.ERROR    -> Download.ERROR_GRID_DENSE;
                    default -> status;
                };
            }
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
                || viewType == Download.PAUSED_GRID
                || isDenseType(viewType);
    }

    private boolean isDenseType(int viewType) {
        return viewType == Download.FINISHED_GRID_DENSE
                || viewType == Download.PROGRESS_GRID_DENSE
                || viewType == Download.QUEUED_GRID_DENSE
                || viewType == Download.ERROR_GRID_DENSE
                || viewType == Download.PAUSED_GRID_DENSE;
    }

    private int getStatus(int viewType) {
        return switch (viewType) {
            case Download.PROGRESS, Download.PROGRESS_GRID, Download.PROGRESS_GRID_DENSE -> Download.PROGRESS;
            case Download.FINISHED, Download.FINISHED_GRID, Download.FINISHED_GRID_DENSE -> Download.FINISHED;
            case Download.QUEUED, Download.QUEUED_GRID, Download.PAUSED_GRID,
                    Download.QUEUED_GRID_DENSE, Download.PAUSED_GRID_DENSE -> Download.QUEUED;
            case Download.ERROR, Download.ERROR_GRID, Download.ERROR_GRID_DENSE -> Download.ERROR;
            default -> -1;
        };
    }

    // ── Create ──────────────────────────────────────────────────────────

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        // Trace markers — visible in a Perfetto trace under the main
        // thread track so we can spot which step (inflate, bind, mime
        // resolution, etc) is actually blocking frames during cold-
        // start scroll. android.os.Trace.isEnabled is a cheap volatile
        // read when tracing isn't active, so the cost in non-trace
        // builds is negligible.
        Tracing.begin("DLA.onCreateViewHolder");
        try {
            LayoutInflater inflater = LayoutInflater.from(parent.getContext());

            if (viewType == Download.HEADER) {
                Tracing.begin("inflate:header");
                try {
                    return new HeaderViewHolder(
                            inflater.inflate(R.layout.fragment_item_header, parent, false));
                } finally { Tracing.end(); }
            }

            if (viewType == Download.EMPTY) {
                Tracing.begin("inflate:empty");
                try {
                    return new EmptyViewHolder(inflater.inflate(R.layout.fragment_download_empty_item, parent, false));
                } finally { Tracing.end(); }
            }

            boolean isGrid = isGridType(viewType);
            boolean isDense = isDenseType(viewType);
            int layoutRes = isDense
                    ? R.layout.fragment_download_item_grid_dense
                    : isGrid
                            ? R.layout.fragment_download_item_grid
                            : R.layout.fragment_download_item;

            Tracing.begin(isDense ? "inflate:row(dense)" : isGrid ? "inflate:row(grid)" : "inflate:row(list)");
            try {
                return new DownloadViewHolder(inflater.inflate(layoutRes, parent, false), mOnItemClickListener, isDense);
            } finally { Tracing.end(); }
        } finally { Tracing.end(); }
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
        Tracing.begin("DLA.onBindVH(payload)");
        try {
            if (!payloads.isEmpty()
                    && Collections.frequency(payloads, PAYLOAD_SELECTION) == payloads.size()
                    && viewHolder instanceof DownloadViewHolder holder) {
                Tracing.begin("bind:selectionOnly");
                try {
                    Object item = peek(position);
                    if (!(item instanceof DownloadEntity entity)) return;
                    boolean contains = mSelected.contains(entity.getId());
                    int viewType = getItemViewType(position);
                    int status = getStatus(viewType);
                    boolean isGrid = isGridType(viewType);

                    boolean washSelected = mActionMode && contains;
                    holder.item.setEnabled(mEnabled);
                    holder.item.setCardBackgroundColor(washSelected
                            ? (isGrid ? mSelectedGridBg : mSelectedListBg)
                            : (isGrid ? mDefaultGridBg  : mDefaultListBg));
                    holder.item.setStrokeColor(washSelected ? mColorSelected : mColorNormal);
                    holder.selected.setVisibility(mActionMode ? View.VISIBLE : View.GONE);
                    holder.selected.setImageDrawable(mActionMode ? (contains ? mChecked : mUnChecked) : null);
                    // The dense tile carries no action button (null-guarded).
                    if (holder.actionButton != null) {
                        holder.actionButton.setVisibility(mActionMode ? View.INVISIBLE : View.VISIBLE);
                        setActionIcon(holder, isGrid,
                                status == Download.QUEUED
                                        ? R.drawable.ic_clear_24
                                        : R.drawable.ic_baseline_more_vert_24);
                    }
                    return;
                } finally { Tracing.end(); }
            }

            // Aggregates-only payload: header subtitle text. Items ignore.
            if (!payloads.isEmpty()
                    && Collections.frequency(payloads, PAYLOAD_AGGREGATES) == payloads.size()) {
                Tracing.begin("bind:aggregatesOnly");
                try {
                    applyAggregatesPayload(viewHolder, position);
                } finally { Tracing.end(); }
                return;
            }

            // Anything else (or no payload, or mixed payloads) → full rebind.
            super.onBindViewHolder(viewHolder, position, payloads);
        } finally { Tracing.end(); }
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
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder viewHolder, int position) {
        Tracing.begin("DLA.onBindVH(full)");
        try {
            bindFull(viewHolder, position);
        } finally { Tracing.end(); }
    }

    private void bindFull(@NonNull RecyclerView.ViewHolder viewHolder, int position) {
        Object item = getItem(position);
        if (item == null) return;

        if (viewHolder instanceof HeaderViewHolder header && item instanceof DownloadSeparatorEntity sep) {
            Tracing.begin("bind:header");
            try {
                int category = sep.getCategory();

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
                return;
            } finally { Tracing.end(); }
        }

        if (!(viewHolder instanceof DownloadViewHolder holder) || !(item instanceof DownloadEntity entity))
            return;

        int viewType = getItemViewType(position);
        int status = getStatus(viewType);
        boolean isGrid = isGridType(viewType);
        boolean contains = mSelected.contains(entity.getId());

        String mimeType = entity.getFileMimeType();
        Log.d(TAG, "bind name=" + entity.getFileName() + " mime=" + mimeType);
        // Domain parse is URI + getHost + regex per call. Cache per
        // holder so repeated re-binds of the same row (selection
        // payload, action-mode toggles that miss the partial-bind path,
        // aggregate refresh) skip the parse. Keyed on entity id +
        // (originUrl|fileUrl) string identity, which is stable for any
        // row that hasn't actually changed source.
        long entityId = entity.getId();
        String domain;
        if (holder.cachedDomain != null
                && holder.cachedDomainEntityId == entityId) {
            // Same entity id → URL is stable (URL changes go through
            // delete+reinsert, so the id changes too). Skip the parse.
            // Was previously also gated on a string-identity check of
            // the URL ref, which broke on Room re-hydration: aggregate
            // / paging refresh rebuilds the DownloadEntity with a new
            // String for the same logical URL, missing the identity
            // check and dropping the cache. Trace showed 100% miss rate.
            domain = holder.cachedDomain;
        } else {
            String originUrl = entity.getOriginUrl();
            String fileUrl = entity.getFileUrl();
            String urlSource = TextUtils.isEmpty(originUrl) ? fileUrl : originUrl;
            Tracing.begin("bind:domainParse");
            try {
                domain = WebUtils.getDomainName(urlSource);
            } finally { Tracing.end(); }
            holder.cachedDomainEntityId = entityId;
            holder.cachedDomain = domain;
        }

        // ── Common fields ───────────────────────────────────────────
        holder.item.setEnabled(mEnabled);
        holder.item.setStrokeColor(mActionMode && contains ? mColorSelected : mColorNormal);
        holder.selected.setVisibility(mActionMode ? View.VISIBLE : View.GONE);
        holder.selected.setImageDrawable(mActionMode ? (contains ? mChecked : mUnChecked) : null);
        // List mode renders mime as a text label that prefixes the
        // domain ('VÍDEO · youtube.com'); grid keeps the chip styling
        // (no separator). Both forms are cached per mime type — see
        // mMimeLabelCache / mMimeLabelListCache. Without the cache,
        // every bind paid for a resource lookup (theme + LocaleList
        // resolution) plus a String concat on the list-mode path.
        // The dense tile has no mime view at all (the active filter chip
        // already states the type) — null-guarded. The normal tiles hide
        // it while a single-type filter is active (mSuppressMime), same
        // redundancy rule.
        if (holder.mimeText != null) {
            String mimeLabel = mSuppressMime ? null : mimeLabelFor(mimeType, isGrid);
            if (TextUtils.isEmpty(mimeLabel)) {
                holder.mimeText.setVisibility(View.GONE);
            } else {
                holder.mimeText.setVisibility(View.VISIBLE);
                holder.mimeText.setText(mimeLabel);
            }
        }

        // ── Row surface ─────────────────────────────────────────────
        // Same default for active and finished rows. The active signal
        // lives in the thicker tinted LinearProgressIndicator (list) or
        // the ProgressOverlayView on the thumbnail (grid). During
        // action mode, selected rows take the tonal wash so the
        // selection set reads from across the screen — see the field
        // comment on mSelectedListBg for the why.
        boolean washSelected = mActionMode && contains;
        holder.item.setCardBackgroundColor(washSelected
                ? (isGrid ? mSelectedGridBg : mSelectedListBg)
                : (isGrid ? mDefaultGridBg  : mDefaultListBg));

        if (holder.fileName != null) {
            // Downloads is a file-manager view. In the GRID, the thumbnail
            // already identifies an image, so its (usually slug) filename is
            // noise — hide it for image types. Keep it for audio/video/docs/
            // subtitles, where the name is how you find the file (audio has no
            // real thumbnail). The list always shows the name. (Captured is a
            // preview/decision surface and keeps the title always — handled in
            // BrowserOptionAdapter, unchanged.)
            if (isGrid && FileUriHelper.isImage(mimeType)) {
                setVisible(holder.fileName, false);
            } else {
                String name = entity.getFileName();
                holder.fileName.setText(name);
                setVisible(holder.fileName, !TextUtils.isEmpty(name));
            }
        }
        if (holder.fileUrl != null) holder.fileUrl.setText(domain);


        // ── Action button icon ──────────────────────────────────────
        // The dense tile carries no per-item button — tap opens the
        // viewer, long-press enters selection; per-item actions live there.
        if (holder.actionButton != null) {
            holder.actionButton.setVisibility(mActionMode ? View.INVISIBLE : View.VISIBLE);
            setActionIcon(holder, isGrid,
                    status == Download.QUEUED ? R.drawable.ic_clear_24 : R.drawable.ic_baseline_more_vert_24);
        }

        // ── Reset all status views ──────────────────────────────────
        // The grid has no progress_row/progress_text (the download ring is the
        // readout); the list keeps them inside progress_row, whose visibility
        // drives its children — so resetting the row is enough.
        setVisible(holder.progressRow, false);
        setVisible(holder.statusText, false);
        setVisible(holder.imageProgress, false);
        setVisible(holder.mimeDuration, false);
        // Grid info block is shown by default; PROGRESS hides it so the
        // progress overlay owns the whole tile. The dense tile is a pure
        // thumbnail — its block stays hidden (bindErrorInner re-shows it
        // so an ERROR row still reads its message).
        setVisible(holder.bottomBlock, !holder.denseTile);

        // ── Status-specific binding ─────────────────────────────────
        switch (status) {
            case Download.PROGRESS -> bindProgress(holder, entity, isGrid);
            case Download.FINISHED -> bindFinished(holder, entity, isGrid);
            case Download.ERROR -> bindError(holder, entity, isGrid);
            case Download.QUEUED -> bindQueued(holder, entity, isGrid);
        }

        // ── Cloud-backup badge ──────────────────────────────────────
        // A quiet mark on the thumbnail for a FINISHED file that's backed up to
        // the cloud. Only-when-true — absence is the signal, so the list stays
        // quiet (and non-users see none). Not on progress/error/queued rows (an
        // in-flight or failed download isn't backed up).
        if (holder.cloudBadge != null) {
            boolean backed = status == Download.FINISHED && isBackedUp(entity);
            int vis = backed ? View.VISIBLE : View.GONE;
            holder.cloudBadge.setVisibility(vis);
            // The list layout pairs the glyph with a leading middot (cloud · MIME ·
            // domain); toggle it in lockstep so a non-backed row shows no stray dot.
            // Grid/dense have no separator (null-skipped).
            if (holder.cloudSep != null) holder.cloudSep.setVisibility(vis);
        }
    }

    private void bindProgress(DownloadViewHolder holder, DownloadEntity entity, boolean isGrid) {
        Tracing.begin(isGrid ? "bind:progress(grid)" : "bind:progress(list)");
        try {
            bindProgressInner(holder, entity, isGrid);
        } finally { Tracing.end(); }
    }

    private void bindProgressInner(DownloadViewHolder holder, DownloadEntity entity, boolean isGrid) {
        // "Finishing…": the bytes are fully downloaded and a post-download mux
        // pass is running (SABR). Rendered like the indeterminate 'retrieving'
        // state — a spinner, never a frozen percent — plus an explicit
        // "Finishing…" label so a muxing row reads as more than a generic
        // spinner (the list shows it in the progress row; the grid surfaces it
        // in the otherwise-hidden bottom caption).
        boolean processing = entity.getFileProgress() == Download.PROCESSING_PROGRESS;
        boolean retrieving = entity.getFileIsLive() || processing;

        if(isGrid){
            // Progress IS the artwork. During a download there's no thumbnail,
            // so a placeholder mime glyph carries nothing the progress + title
            // don't — drop it and let the ring own the picture zone. The ring is
            // constrained in the layout to the area above the title caption
            // (dense tiles, a bare square, fill the whole tile), so it never
            // overlaps the title. No bottom bar / inline percent — the ring's
            // own percent is the readout. (progress_row/progress_text don't
            // exist in the grid layout any more; null-safe either way.)
            if (holder.imageProgress != null) {
                holder.imageProgress.setVisibility(View.VISIBLE);
                holder.imageProgress.setIndeterminate(retrieving);
                if (!retrieving) {
                    holder.imageProgress.setProgress(entity.getFileProgress());
                }
            }
            // No placeholder glyph: clear the image so the ring sits on the bare
            // card. clearSafe cancels any in-flight load that could paint over it.
            GlideHelper.clearSafe(holder.image);
            holder.image.setImageDrawable(null);
            holder.image.setTag(null);

            if (!holder.denseTile) {
                // Normal tile keeps the title in its caption below the ring
                // (dense is a bare square with no caption). With no thumbnail to
                // identify it, the title is the only identifier — so show it for
                // EVERY type, overriding the image-hide rule (which assumes a
                // real thumbnail exists).
                setVisible(holder.bottomBlock, true);
                setVisible(holder.mimeText, false);
                setVisible(holder.mimeDuration, false);
                if (holder.fileName != null) {
                    String name = entity.getFileName();
                    holder.fileName.setText(name);
                    setVisible(holder.fileName, !TextUtils.isEmpty(name));
                }
                // "Finishing…" (post-download mux) gets an explicit caption line
                // under the title; an ordinary download leans on the ring alone.
                if (processing && holder.statusText != null) {
                    holder.statusText.setText(R.string.download_finishing);
                    holder.statusText.setTextColor(mDefaultPrimary);
                    setVisible(holder.statusText, true);
                } else {
                    setVisible(holder.statusText, false);
                }
            }
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
                holder.progressText.setText(
                        processing ? holder.itemView.getContext().getString(R.string.download_finishing)
                        : retrieving ? Utils.readableFileSize(entity.getFileSize())
                        : String.format(Locale.US, "%d%%", entity.getFileProgress()));
            }
            if(holder.progressBar != null){
                holder.progressBar.setIndeterminate(retrieving);
                if (!retrieving) holder.progressBar.setProgress(entity.getFileProgress());
            }
            Tracing.begin("Glide.loadFallback");
            try { GlideHelper.loadFallback(entity, holder.image); }
            finally { Tracing.end(); }
        }
    }

    private void bindFinished(DownloadViewHolder holder, DownloadEntity entity, boolean isGrid) {
        Tracing.begin(isGrid ? "bind:finished(grid)" : "bind:finished(list)");
        try {
            bindFinishedInner(holder, entity, isGrid);
        } finally { Tracing.end(); }
    }

    private void bindFinishedInner(DownloadViewHolder holder, DownloadEntity entity, boolean isGrid) {
        String mimeType = entity.getFileMimeType();
        // The type's secondary metadatum, shown as a distinct element (the badge
        // overlay in grid, appended to the finished meta line in list) — never
        // folded into the mime-type label.
        String secondary = secondaryMetaLabel(entity, mimeType);

        if (isGrid) {
            // Grid meta row reads "[chip] duration · size" (or
            // "resolution · size" for images): size is the one list-line
            // fact with no other home in the grid — date is carried by the
            // section headers, so it stays out. (mimeDuration is absent in
            // the dense tile, which shows no text at all.)
            if (holder.mimeDuration != null) {
                String label = joinWithSize(secondary, entity.getFileSize());
                if (!TextUtils.isEmpty(label)) {
                    holder.mimeDuration.setVisibility(View.VISIBLE);
                    holder.mimeDuration.setText(label);
                }
            }
        }

        if (!isGrid && holder.statusText != null) {
            holder.statusText.setTextColor(MaterialColors.getColor(
                    holder.statusText,
                    com.google.android.material.R.attr.colorOnSurfaceVariant));
            holder.statusText.setText(getFinishedLabel(holder, entity));
            holder.statusText.setVisibility(View.VISIBLE);
        }

        Tracing.begin("Glide.load(finished)");
        try { GlideHelper.load(entity, mRequestOptions, holder.image); }
        finally { Tracing.end(); }
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
        Tracing.begin("finishedLabel:miss");
        try {
            String label = holder.itemView.getContext().getString(
                    R.string.download_finished_meta,
                    Utils.getFileSize(size),
                    DateUtils.getFileDate(date));
            // Append the type's secondary metadatum (duration / resolution /
            // language) to the "size · date" line — the list equivalent of the
            // grid badge. Immutable per entity, so the id/size/date cache key
            // still holds.
            String secondary = secondaryMetaLabel(entity, entity.getFileMimeType());
            if (!TextUtils.isEmpty(secondary)) {
                label = label + " · " + secondary;
            }
            holder.cachedFinishedKeyId = id;
            holder.cachedFinishedKeySize = size;
            holder.cachedFinishedKeyDate = date;
            holder.cachedFinishedLabel = label;
            return label;
        } finally { Tracing.end(); }
    }

    private void bindError(DownloadViewHolder holder, DownloadEntity entity, boolean isGrid) {
        Tracing.begin(isGrid ? "bind:error(grid)" : "bind:error(list)");
        try {
            bindErrorInner(holder, entity, isGrid);
        } finally { Tracing.end(); }
    }

    private void bindErrorInner(DownloadViewHolder holder, DownloadEntity entity, boolean isGrid) {
        // The dense tile hides its scrim block by default (pure thumbnail);
        // an ERROR row is the one state that must surface text, so re-show
        // it. No-op for the other layouts (list has no block; grid shows it).
        setVisible(holder.bottomBlock, true);
        if (holder.statusText != null) {
            // Grid scrim is darker so the error reads better on
            // colorPrimaryContainer; the list row is on plain surface
            // and uses colorPrimary for the same legibility against a
            // lighter ground. mDefaultPrimary is the same
            // android.R.attr.colorPrimary already cached in the
            // constructor — reuse it instead of running a MaterialColors
            // lookup every bind. (com.google.android.material.R.attr
            // does not export colorPrimary; it lives in the platform /
            // appcompat namespace.)
            int color = isGrid
                    ? MaterialColors.getColor(holder.statusText,
                            com.google.android.material.R.attr.colorPrimaryContainer)
                    : mDefaultPrimary;
            holder.statusText.setTextColor(color);
            int errorId = MessageHelper.getResourceIdFromCode(entity.getFileErrorType());
            holder.statusText.setText(errorId);
            holder.statusText.setVisibility(View.VISIBLE);
        }
        Tracing.begin("Glide.loadFallback");
        try { GlideHelper.loadFallback(entity, holder.image); }
        finally { Tracing.end(); }
    }

    private void bindQueued(DownloadViewHolder holder, DownloadEntity entity, boolean isGrid) {
        Tracing.begin(isGrid ? "bind:queued(grid)" : "bind:queued(list)");
        try {
            bindQueuedInner(holder, entity, isGrid);
        } finally { Tracing.end(); }
    }

    private void bindQueuedInner(DownloadViewHolder holder, DownloadEntity entity, boolean isGrid) {
        // Grid QUEUED leans on the title + mime chip in the bottom block; no
        // dedicated grid status label yet.
        if (!isGrid && holder.statusText != null) {
            holder.statusText.setTextColor(MaterialColors.getColor(
                    holder.statusText,
                    com.google.android.material.R.attr.colorOnSurfaceVariant));
            holder.statusText.setText(R.string.download_queued);
            holder.statusText.setVisibility(View.VISIBLE);
        }
        Tracing.begin("Glide.loadFallback");
        try { GlideHelper.loadFallback(entity, holder.image); }
        finally { Tracing.end(); }
    }


    // ── Helpers ──────────────────────────────────────────────────────────

    private static void setVisible(@Nullable View view, boolean visible) {
        if (view != null) view.setVisibility(visible ? View.VISIBLE : View.GONE);
    }

    /**
     * Returns the cached mime label for the given mime type, lazily
     * populating both the bare and list-mode (with " · " suffix)
     * variants on first miss. Null-safe — a null mime type returns
     * null (the caller hides the label).
     */
    private @Nullable String mimeLabelFor(@Nullable String mimeType, boolean isGrid) {
        if (mimeType == null) return null;
        HashMap<String, String> cache = isGrid ? mMimeLabelCache : mMimeLabelListCache;
        String cached = cache.get(mimeType);
        if (cached != null) return cached;
        Tracing.begin("mimeLabel:miss");
        try {
            String base = FileUriHelper.getLongMimeText(mContext, mimeType);
            if (base == null) return null;
            String label = isGrid ? base : base + " · ";
            cache.put(mimeType, label);
            return label;
        } finally { Tracing.end(); }
    }

    /**
     * "{secondary} · {size}" — the grid tile's meta-row facts after the mime
     * chip. Either part may be absent (HLS captures can finish with no
     * duration until the post-download refresh runs; size can be 0 for a
     * live/chunked source) — the join degrades to whichever part exists,
     * or null when neither does (caller keeps the view GONE).
     */
    private static @Nullable String joinWithSize(@Nullable String secondary, long size) {
        String sizeText = size > 0 ? Utils.getFileSize(size) : null;
        if (TextUtils.isEmpty(secondary)) {
            return sizeText;
        }
        if (sizeText == null) {
            return secondary;
        }
        return secondary + " · " + sizeText;
    }

    /**
     * The single secondary metadatum for an item, chosen by type: duration
     * (video/audio), resolution (image), or language (subtitle). Rendered as a
     * distinct element — the badge overlay in grid, appended to the finished
     * meta line in list — never folded into the mime-type label.
     */
    private static @Nullable String secondaryMetaLabel(DownloadEntity entity, @Nullable String mimeType) {
        if (FileUriHelper.isVideo(mimeType) || FileUriHelper.isAudio(mimeType)) {
            return entity.getDurationFormatted();
        }
        if (FileUriHelper.isImage(mimeType) || FileUriHelper.isSVG(mimeType)) {
            return entity.getFileResolution();
        }
        return entity.getFileLanguage();
    }

    private void setActionIcon(DownloadViewHolder holder, boolean isGrid, int iconRes) {
        // Works for both AppCompatImageButton (list) and MaterialButton (grid)

        if (holder.actionButton instanceof MaterialButton btn) {
            btn.setIconResource(iconRes);
            // Cached tint CSL — see mActionIconTintListCsl. Was a
            // MaterialColors.getColor + ColorStateList.valueOf on every
            // bind; both are tiny on their own, but the bind path runs
            // for every visible row on every scroll, and the int never
            // changes after the theme is resolved.
            btn.setIconTint(isGrid ? mActionIconTintGridCsl : mActionIconTintListCsl);
        }
    }

    // ── ViewHolders ─────────────────────────────────────────────────────

    static class DownloadViewHolder extends RecyclerView.ViewHolder
            implements View.OnClickListener, View.OnLongClickListener {

        final OnItemClickListener listener;
        final MaterialCardView item;
        final AppCompatImageView selected;
        final AppCompatImageView image;
        /** Absent (null) in the dense tile, which renders no text/chrome. */
        final @Nullable TextView mimeText;
        final @Nullable TextView fileName;
        final @Nullable TextView fileUrl;
        final @Nullable View actionButton;
        /** True for the dense (images-filter) square tile — pure thumbnail;
         *  bindFull keeps its scrim block hidden except for ERROR. */
        final boolean denseTile;

        // Status-specific (nullable — not all layouts have all views)
        final @Nullable ProgressOverlayView imageProgress;
        final @Nullable View progressRow;
        final @Nullable TextView progressText;
        final @Nullable LinearProgressIndicator progressBar;
        // Unified FINISHED / QUEUED / ERROR slot. Replaces the three
        // separate TextViews — finishedText / queuedText / errorText —
        // that the layout used to carry as mutually-exclusive
        // children. The recycler builds one view per row instead of
        // three, and bind* methods set text + color directly.
        final @Nullable TextView statusText;
        final @Nullable TextView mimeDuration;
        final @Nullable View bottomBlock;
        /** Quiet "backed up to cloud" marker, shown only for a FINISHED backed-up
         *  file. Placement differs per layout (the adapter only toggles visibility):
         *  LIST — a muted grey cloud LEADING the meta line (cloud · VIDEO · domain),
         *  anchored to text so it never floats; GRID + dense mosaic — a bare white
         *  cloud in the top-START corner (the grid meta line has no spare width, and
         *  the ⋮/selection-check own top-end). Not the old disc — no filled circle. */
        final @Nullable AppCompatImageView cloudBadge;
        /** List-only middot after {@link #cloudBadge} (cloud · MIME · domain), toggled
         *  in lockstep with it. Null on grid/dense (no separator there). */
        final @Nullable TextView cloudSep;

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

        // Cache for the parsed domain string. WebUtils.getDomainName
        // does URI construction + getHost + regex strip per call;
        // keyed by entity id only — URL field changes go through a
        // delete+reinsert (different id), so a matching id implies
        // the same logical URL. Earlier version also checked
        // string-identity of the URL reference and broke when Room
        // re-hydrated the entity (new String for same logical URL,
        // identity mismatch, cache always missed).
        long cachedDomainEntityId = Long.MIN_VALUE;
        @Nullable String cachedDomain;

        DownloadViewHolder(View view, OnItemClickListener onItemClickListener, boolean denseTile) {
            super(view);
            listener = onItemClickListener;
            this.denseTile = denseTile;

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
            statusText = view.findViewById(R.id.status_text);
            mimeDuration = view.findViewById(R.id.mime_duration);
            bottomBlock = view.findViewById(R.id.bottom_block);
            cloudBadge = view.findViewById(R.id.cloud_badge);
            cloudSep = view.findViewById(R.id.cloud_sep);

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
        final TextView text;
        final TextView subtitle;

        HeaderViewHolder(View view) {
            super(view);
            text     = view.findViewById(R.id.item_header);
            subtitle = view.findViewById(R.id.item_header_subtitle);
        }
    }

    /** "{n} files · {size}", or just "{n} files" when the total is
     *  unknown. fileSize comes from Content-Length at request creation
     *  time; HLS / live streams and any source without a length
     *  header land as 0, which would otherwise render the active
     *  "Downloading" section header as "2 files · 0 B" — accurate to
     *  the data but useless to read. Same gate handles the rare
     *  finished-but-size-unset edge case for free.
     *  <p>Pluralization is light — Java's
     *  {@code Resources.getQuantityString} is fine here but the
     *  English "1 file / N files" split is the only locale rule
     *  that matters for this header today. */
    private static String formatGroupSubtitle(@NonNull Context ctx, @NonNull GroupAggregate agg) {
        String files = ctx.getResources().getQuantityString(
                R.plurals.downloads_group_files, agg.count, agg.count);
        if (agg.totalSize <= 0) return files;
        return files + " · " + Utils.readableFileSize(agg.totalSize);
    }

    static class EmptyViewHolder extends RecyclerView.ViewHolder {
        EmptyViewHolder(View view) { super(view); }
    }
}