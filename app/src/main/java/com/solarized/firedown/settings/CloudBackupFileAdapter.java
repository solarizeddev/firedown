package com.solarized.firedown.settings;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.text.TextUtils;
import android.text.format.DateUtils;
import android.text.format.Formatter;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.widget.AppCompatImageView;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.ColorUtils;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListUpdateCallback;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.request.RequestOptions;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.solarized.firedown.GlideHelper;
import com.solarized.firedown.R;
import com.solarized.firedown.data.entity.DownloadEntity;
import com.solarized.firedown.glide.MimeTypeThumbnail;
import com.solarized.firedown.glide.VaultThumbModel;
import com.solarized.firedown.sync.model.VaultEntry;
import com.solarized.firedown.utils.FileUriHelper;
import com.solarized.firedown.utils.SelectionStyling;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Backed-up files list, styled to match the Downloads list. Two row types:
 * <ul>
 *   <li><b>TYPE_TRANSFER</b> — an upload in progress (not in the manifest yet):
 *       its own row with a determinate per-item progress bar and a cancel button,
 *       shown at the top (like an in-flight download in the Downloads list).</li>
 *   <li><b>TYPE_FILE</b> — a committed manifest entry (the stored preview / mime
 *       fallback, a TitleSmall name, and a {@code MIME · size} / date.</li>
 * </ul>
 * Tapping a file row fires {@link OnItemClickListener#onItemClick}; the cancel
 * button fires {@link OnItemClickListener#onCancelTransfer}.
 */
public class CloudBackupFileAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    private static final int TYPE_TRANSFER = 0;
    private static final int TYPE_FILE = 1;
    private static final int TYPE_FILE_GRID = 2;
    private static final int TYPE_TRANSFER_GRID = 3;

    /** Grid caption text-shadow, applied over a real preview only — kept in
     *  lockstep with item_cloud_backup_file_grid.xml and with
     *  DownloadItemAdapter.GRID_TEXT_SHADOW. See FileGridVH#applyGridDim. */
    private static final int GRID_TEXT_SHADOW = 0x80000000;

    public interface OnItemClickListener {
        void onItemClick(VaultEntry entry);

        /** Long-press a committed file row — enters/extends multi-select. */
        void onItemLongClick(VaultEntry entry);

        /** Cancel the in-progress transfer with this WorkManager id. */
        void onCancelTransfer(String workId);
    }

    /** One in-progress upload row (not yet a manifest entry). */
    public static final class Transfer {
        public final String workId;
        public final String name;
        public final String mime;
        public final long done;
        public final long total;
        /** A terminally-FAILED backup (worker gave up) — rendered as an error row
         *  instead of a progress bar, so a background failure isn't silent. The ✕
         *  dismisses it (the fragment prunes the finished work record). */
        public final boolean failed;
        /** A COMPACT cause for a terminal failure — two or three words,
         *  localised by the fragment ({@code failureShort}). Null when the
         *  server gave no slug or gave one we cannot explain — the row then
         *  falls back to the generic "Backup failed" rather than guessing.
         *
         *  <p>Compact because the state line it lands on is singleLine +
         *  ellipsized and shares its row with the mime chip: the full sentences
         *  (which the fragment sends to a snackbar instead) truncated mid-remedy
         *  here, e.g. "Not enough credi…". Keep new values to a few words; if a
         *  cause needs explaining, explain it in the snackbar. */
        public final String errorText;

        public Transfer(String workId, String name, String mime, long done, long total) {
            this(workId, name, mime, done, total, false, null);
        }

        public Transfer(String workId, String name, String mime, long done, long total,
                        boolean failed, String errorText) {
            this.workId = workId;
            this.name = name;
            this.mime = mime;
            this.done = done;
            this.total = total;
            this.failed = failed;
            this.errorText = errorText;
        }
    }

    private final List<Transfer> mTransfers = new ArrayList<>();
    private final List<VaultEntry> mItems = new ArrayList<>();
    /**
     * objectId → a Glide MODEL for an entry with NO stored manifest preview,
     * resolved once per manifest load by the fragment
     * ({@code CloudBackupManager.resolveLocalThumb}): a {@code DownloadEntity}
     * when the local copy is still here, or a {@code VaultObjectModel} for a
     * cloud-only image.
     *
     * <p>This replaced TWO hand-rolled {@code LruCache}es of BITMAPS — one for
     * decoded manifest previews, one for these backfills. They were a second
     * memory budget beside Glide's own (which is device-sized); the manifest one
     * existed only to amortise a base64+JPEG decode the bind was doing on the
     * MAIN thread; and the backfill one held a mandatory COPY of a bitmap Glide
     * was already caching under the Downloads list's key ({@code
     * downloadThumbSync} must copy — the pooled original returns to the bitmap
     * pool). The same image lived twice, in two budgets.
     *
     * <p>Now the bind hands Glide a model and Glide owns every cached bitmap.
     * These entries are references, not images, so this map needs no byte budget
     * and no memory-trim hook.
     */
    private final Map<String, Object> mThumbModels = new HashMap<>();

    /**
     * Selected committed entries (by objectId). HANDED IN by the fragment from
     * the ViewModel rather than owned here — which is what makes this adapter a
     * pure renderer, lets a rotation keep the selection, and bounded the
     * setActionMode notify below.
     */
    private Set<String> mSelected = Collections.emptySet();
    private boolean mActionMode;
    /** Grid vs list for the committed FILE rows. In-progress TYPE_TRANSFER rows
     *  are unaffected — they render as full-width rows in both modes (the
     *  fragment's SpanSizeLookup spans them across the grid). */
    private boolean mEnableGrid;
    private final OnItemClickListener mListener;

    public CloudBackupFileAdapter(OnItemClickListener listener) {
        this.mListener = listener;
    }

    // ---- multi-select ----

    public void setActionMode(boolean on) {
        if (mActionMode == on) {
            return;
        }
        mActionMode = on;
        // Only committed FILE rows carry a check + wash — transfer rows show a
        // cancel button in either mode. So this is a bounded range, not the
        // blanket notifyDataSetChanged it used to be (which re-decoded every
        // thumbnail just to toggle a tick). Clearing the selection is the
        // ViewModel's job now.
        if (!mItems.isEmpty()) {
            notifyItemRangeChanged(mTransfers.size(), mItems.size());
        }
    }

    /** Replaces the selection and rebinds the committed rows. */
    public void setSelection(Set<String> selected) {
        mSelected = selected == null ? Collections.emptySet() : selected;
        if (!mItems.isEmpty()) {
            notifyItemRangeChanged(mTransfers.size(), mItems.size());
        }
    }

    public boolean isActionMode() {
        return mActionMode;
    }

    public boolean isGrid() {
        return mEnableGrid;
    }

    /** Switches the committed FILE rows between list and grid. Changes their view
     *  type, so a full rebind is required (the RecycledViewPool keys holders by
     *  view type). Transfer rows are untouched. */
    public void enableGrid(boolean on) {
        if (mEnableGrid == on) {
            return;
        }
        mEnableGrid = on;
        notifyDataSetChanged();
    }
    /**
     * Replaces the committed-file rows with a minimal DIFF — never a blanket
     * {@code notifyDataSetChanged}. The Backups screen re-pulls the manifest on
     * every {@code onResume} (app pause/resume, and the return from the item
     * sheet), so an IDENTICAL reload must produce ZERO rebinds — otherwise every
     * visible thumbnail visibly re-paints (the "images flicker on pause/resume /
     * after restore" reports). DiffUtil rebinds only genuinely changed rows.
     *
     * <p>{@link #mThumbModels} is deliberately NOT cleared here: a resolved
     * model is keyed by the server-random, immutable objectId, so a stale key
     * can never point at the wrong image, and keeping it stops a backfilled
     * thumbnail from flashing back to the mime glyph (then re-resolving) on
     * every reload.
     */
    public void submit(List<VaultEntry> items) {
        List<VaultEntry> newItems = items != null ? new ArrayList<>(items) : new ArrayList<>();
        DiffUtil.DiffResult diff = DiffUtil.calculateDiff(new DiffUtil.Callback() {
            @Override
            public int getOldListSize() {
                return mItems.size();
            }

            @Override
            public int getNewListSize() {
                return newItems.size();
            }

            @Override
            public boolean areItemsTheSame(int oldPos, int newPos) {
                String a = mItems.get(oldPos).objectId;
                String b = newItems.get(newPos).objectId;
                return a != null && a.equals(b);
            }

            @Override
            public boolean areContentsTheSame(int oldPos, int newPos) {
                return sameRow(mItems.get(oldPos), newItems.get(newPos));
            }
        });
        mItems.clear();
        mItems.addAll(newItems);
        // Offset every dispatched position past the in-progress transfer rows,
        // which occupy the top of the list and are untouched by submit().
        final int offset = mTransfers.size();
        diff.dispatchUpdatesTo(new ListUpdateCallback() {
            @Override
            public void onInserted(int position, int count) {
                notifyItemRangeInserted(offset + position, count);
            }

            @Override
            public void onRemoved(int position, int count) {
                notifyItemRangeRemoved(offset + position, count);
            }

            @Override
            public void onMoved(int fromPosition, int toPosition) {
                notifyItemMoved(offset + fromPosition, offset + toPosition);
            }

            @Override
            public void onChanged(int position, int count, Object payload) {
                notifyItemRangeChanged(offset + position, count, payload);
            }
        });
    }

    /** DiffUtil contents test: whether two entries render an identical row
     *  (same displayed facts + same stored preview). */
    private static boolean sameRow(VaultEntry a, VaultEntry b) {
        return a.size == b.size
                && a.downloadedAt == b.downloadedAt
                && Objects.equals(a.name, b.name)
                && Objects.equals(a.mime, b.mime)
                && Objects.equals(a.thumb, b.thumb);
    }

    /** Replaces the in-progress transfer rows (shown above the committed files). */
    public void setTransfers(List<Transfer> transfers) {
        int oldCount = mTransfers.size();
        mTransfers.clear();
        if (transfers != null) {
            mTransfers.addAll(transfers);
        }
        int newCount = mTransfers.size();
        // Progress ticks keep the count stable — rebind ONLY the transfer rows
        // (positions 0..newCount-1) so committed file rows below don't re-decode
        // their thumbnails on every byte update. A count change (a transfer
        // started/finished) reshuffles positions → full rebind.
        if (oldCount == newCount) {
            if (newCount > 0) {
                notifyItemRangeChanged(0, newCount);
            }
        } else {
            notifyDataSetChanged();
        }
    }

    public boolean hasTransfers() {
        return !mTransfers.isEmpty();
    }

    /** Removes one committed entry (by objectId) in place; returns its former
     *  index within the FILES (not counting transfer rows), or -1. */
    public int removeByObjectId(String objectId) {
        for (int i = 0; i < mItems.size(); i++) {
            if (mItems.get(i).objectId.equals(objectId)) {
                mItems.remove(i);
                notifyItemRemoved(mTransfers.size() + i);
                return i;
            }
        }
        return -1;
    }

    /** Re-inserts a committed entry at a files-index (undo of an optimistic remove). */
    public void insertAt(int position, VaultEntry entry) {
        int p = Math.max(0, Math.min(position, mItems.size()));
        mItems.add(p, entry);
        notifyItemInserted(mTransfers.size() + p);
    }

    /**
     * Records a preview backfilled from the local file (for an entry that had no
     * stored thumb) and refreshes its row so the thumbnail appears. Already a
     * decoded bitmap (the backfill decodes off the main thread — via Glide's
     * cache or MediaMetadataRetriever); the old base64-string contract made the
     * row bind re-decode on the main thread what the backfill had just encoded.
     */
    /** The resolved fallback model for an entry (null when none) — the item
     *  sheet reads it so a pre-preview entry whose ROW shows an image doesn't
     *  open a sheet with a bare mime glyph. */
    public Object thumbModel(String objectId) {
        return objectId != null ? mThumbModels.get(objectId) : null;
    }

    /** Slots in a resolved fallback model and rebinds just that row. */
    public void setThumbModel(String objectId, Object model) {
        if (objectId == null || model == null) {
            return;
        }
        mThumbModels.put(objectId, model);
        for (int i = 0; i < mItems.size(); i++) {
            if (objectId.equals(mItems.get(i).objectId)) {
                notifyItemChanged(mTransfers.size() + i);
                return;
            }
        }
    }

    /** Number of committed file rows (excludes in-progress transfers). */
    public int size() {
        return mItems.size();
    }

    /** Whether the row at this adapter position is an in-progress TRANSFER row —
     *  those span the full grid width (the fragment's SpanSizeLookup), so an
     *  uploading file still reads as a row even in grid mode. */
    public boolean isTransferPosition(int position) {
        return position < mTransfers.size();
    }

    @Override
    public int getItemViewType(int position) {
        if (position < mTransfers.size()) {
            // In grid mode an in-progress upload renders as a TILE (like the
            // Downloads grid), not a full-width row.
            return mEnableGrid ? TYPE_TRANSFER_GRID : TYPE_TRANSFER;
        }
        return mEnableGrid ? TYPE_FILE_GRID : TYPE_FILE;
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inflater = LayoutInflater.from(parent.getContext());
        if (viewType == TYPE_TRANSFER) {
            View v = inflater.inflate(R.layout.item_cloud_backup_transfer, parent, false);
            return new TransferVH(v, false, mListener);
        }
        if (viewType == TYPE_TRANSFER_GRID) {
            // Same TransferVH — the grid tile reuses every field id. The `grid`
            // flag is NOT cosmetic: the two layouts put the state line on
            // opposite grounds, so the holder must not impose one theme ink on
            // both (see stateNormalColor/stateErrorColor).
            View v = inflater.inflate(R.layout.item_cloud_backup_transfer_grid, parent, false);
            return new TransferVH(v, true, mListener);
        }
        if (viewType == TYPE_FILE_GRID) {
            View v = inflater.inflate(R.layout.item_cloud_backup_file_grid, parent, false);
            return new FileGridVH(v, mListener);
        }
        View v = inflater.inflate(R.layout.item_cloud_backup_file, parent, false);
        return new FileVH(v, mListener);
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        if (holder instanceof TransferVH) {
            ((TransferVH) holder).bind(mTransfers.get(position));
        } else {
            VaultEntry entry = mItems.get(position - mTransfers.size());
            Object thumb = thumbModelFor(entry);
            boolean selected = mSelected.contains(entry.objectId);
            if (holder instanceof FileGridVH) {
                ((FileGridVH) holder).bind(entry, thumb, mActionMode, selected);
            } else {
                ((FileVH) holder).bind(entry, thumb, mActionMode, selected);
            }
        }
    }

    /**
     * Resolves what to hand Glide for this row: the STORED manifest preview when
     * the entry has one, else the fallback model the fragment resolved (local
     * file / cloud object), else null for the mime glyph.
     *
     * <p>No decoding and no caching happen here any more — both are Glide's, so
     * there is no memory-trim hook to keep in step either (this used to be two
     * LruCaches plus a trimThumbCache the host fragment called on
     * ComponentCallbacks2 signals).
     */
    private Object thumbModelFor(VaultEntry entry) {
        VaultThumbModel stored = VaultThumbModel.of(entry);
        if (stored != null) {
            return stored;
        }
        return entry.objectId != null ? mThumbModels.get(entry.objectId) : null;
    }

    @Override
    public int getItemCount() {
        return mTransfers.size() + mItems.size();
    }

    /** Mime chip ("VÍDEO · ") shared by both row types. */
    private static void bindMimeChip(TextView mime, Context ctx, String mimeType) {
        String label = mimeType != null ? FileUriHelper.getLongMimeText(ctx, mimeType) : null;
        if (TextUtils.isEmpty(label)) {
            mime.setVisibility(View.GONE);
        } else {
            mime.setVisibility(View.VISIBLE);
            mime.setText(label + " · ");
        }
    }

    /** Decoded preview bitmap when present, else the mime-type fallback card.
    /**
     * Paints a row's thumbnail from a Glide MODEL (or the mime glyph for null).
     *
     * <p>The mime glyph is both placeholder and error drawable, which is what
     * preserves the documented no-flicker property: a cold bind shows exactly
     * the state a thumbnail-less entry shows anyway — never a blank, never the
     * previous row's image — and a memory-cache hit resolves inside {@code
     * into()} with no placeholder frame at all. {@code dontAnimate()} suppresses
     * the cross-fade for the same reason.
     *
     * <p>{@code into()} on the SAME ImageView cancels any request still in
     * flight for it, so a recycled holder can never be painted by the previous
     * row's load; the null branch clears explicitly for that reason.
     *
     * <p>A {@code DownloadEntity} goes through {@link GlideHelper} rather than a
     * bare load: that path builds the request exactly as the Downloads list
     * does, so signature + options + override match and the row is served from
     * the cache entry that list already populated, instead of re-extracting a
     * video frame into a second copy.
     */
    private static void bindThumb(ImageView thumb, Context ctx, Object model, String mimeType) {
        String mt = mimeType != null ? mimeType : "application/octet-stream";
        Drawable glyph = MimeTypeThumbnail.generateDrawable(ctx, mt, true);
        if (model == null) {
            Glide.with(thumb).clear(thumb);
            thumb.setImageDrawable(glyph);
            return;
        }
        if (model instanceof DownloadEntity && thumb instanceof AppCompatImageView) {
            GlideHelper.load((DownloadEntity) model, new RequestOptions(),
                    (AppCompatImageView) thumb);
            return;
        }
        Glide.with(thumb)
                .load(model)
                // Never persist a decrypted preview of an E2E-backed-up file:
                // the stored-thumb bytes come from the manifest (already in
                // memory) and the vault-object bytes are decrypt-on-read, so a
                // disk cache would buy nothing and cost the guarantee.
                .diskCacheStrategy(DiskCacheStrategy.NONE)
                .placeholder(glyph)
                .error(glyph)
                .dontAnimate()
                .into(thumb);
    }

    static class FileVH extends RecyclerView.ViewHolder {
        private final MaterialCardView card;
        private final ImageView thumb;
        private final ImageView check;
        private final View action;
        private final TextView name;
        private final TextView mime;
        private final TextView size;
        private final TextView date;
        private VaultEntry current;

        FileVH(@NonNull View itemView, OnItemClickListener listener) {
            super(itemView);
            card = (MaterialCardView) itemView;
            thumb = itemView.findViewById(R.id.cb_thumb);
            check = itemView.findViewById(R.id.cb_selected);
            action = itemView.findViewById(R.id.cb_action);
            name = itemView.findViewById(R.id.cb_name);
            mime = itemView.findViewById(R.id.cb_mime);
            size = itemView.findViewById(R.id.cb_size);
            date = itemView.findViewById(R.id.cb_date);
            thumb.setClipToOutline(true);
            View.OnClickListener open = v -> {
                if (listener != null && current != null) {
                    listener.onItemClick(current);
                }
            };
            itemView.setOnClickListener(open);
            action.setOnClickListener(open); // the ⋮ opens the same sheet
            itemView.setOnLongClickListener(v -> {
                if (listener != null && current != null) {
                    listener.onItemLongClick(current);
                    return true;
                }
                return false;
            });
        }

        void bind(VaultEntry entry, Object thumbModel, boolean actionMode, boolean selected) {
            current = entry;
            Context ctx = itemView.getContext();
            name.setText(entry.name);
            bindMimeChip(mime, ctx, entry.mime);
            size.setText(Formatter.formatShortFileSize(ctx, entry.size));
            // Date only. The cloud-only state used to append "· Not on this
            // device" here and that was REMOVED: on an established install
            // almost every backed-up file has since been cleared from Downloads,
            // so the marker rendered on nearly every row (8 of 9 on the reporter's
            // screen) — and a marker present on ~90% of rows carries no
            // information, which is the very argument that keeps the OPPOSITE
            // ("also on this device") badge off this list. It also read as an
            // asymmetry: silence for one state, words for the other.
            //
            // The fact still matters, but only at the moment of deciding to
            // remove — so it moved to the item sheet and the batch-delete
            // confirmation, where it is stated ONCE and always relevant. Don't
            // reinstate a per-row marker in either direction; if a row ever needs
            // a glyph, the earned one is failed/stale backup state, which this
            // surface still has no indicator for.
            if (entry.downloadedAt > 0) {
                date.setVisibility(View.VISIBLE);
                date.setText(DateUtils.getRelativeTimeSpanString(entry.downloadedAt,
                        System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS));
            } else {
                date.setVisibility(View.GONE);
            }
            bindThumb(thumb, ctx, thumbModel, entry.mime);

            // Selection chrome (Downloads parity): the check replaces the ⋮ action
            // button IN THE SAME SLOT (button INVISIBLE so the slot width holds and
            // the row never reflows) + the SelectionStyling primaryContainer@20%
            // wash on the card.
            if (actionMode) {
                action.setVisibility(View.INVISIBLE);
                check.setVisibility(View.VISIBLE);
                check.setImageResource(selected
                        ? R.drawable.ic_baseline_check_circle_24
                        : R.drawable.radio_button_unchecked_24);
                card.setCardBackgroundColor(selected
                        ? SelectionStyling.selectedCardWashOver(
                                ctx, com.google.android.material.R.attr.colorSurface)
                        : Color.TRANSPARENT);
            } else {
                action.setVisibility(View.VISIBLE);
                check.setVisibility(View.GONE);
                card.setCardBackgroundColor(Color.TRANSPARENT);
            }
        }
    }

    /** Grid tile for a committed file. Same facts as the list row (thumbnail +
     *  title + mime · size) on a 16:10 card; selection is a corner check + a
     *  primary card stroke (the tile convention), not the list row's wash. */
    static class FileGridVH extends RecyclerView.ViewHolder {
        private final MaterialCardView card;
        private final ImageView thumb;
        private final ImageView check;
        private final View action;
        private final TextView name;
        private final TextView mime;
        private final TextView size;
        private final View bottomBlock;
        private VaultEntry current;

        FileGridVH(@NonNull View itemView, OnItemClickListener listener) {
            super(itemView);
            card = itemView.findViewById(R.id.cb_item);
            thumb = itemView.findViewById(R.id.cb_thumb);
            check = itemView.findViewById(R.id.cb_selected);
            action = itemView.findViewById(R.id.cb_action);
            name = itemView.findViewById(R.id.cb_name);
            mime = itemView.findViewById(R.id.cb_mime);
            size = itemView.findViewById(R.id.cb_size);
            bottomBlock = itemView.findViewById(R.id.cb_bottom_block);
            thumb.setClipToOutline(true);
            // Clicks live on the CARD (it fills the whole tile column), so the
            // hit area + ripple cover the tile; the ⋮ has its own handler.
            View.OnClickListener open = v -> {
                if (listener != null && current != null) {
                    listener.onItemClick(current);
                }
            };
            card.setOnClickListener(open);
            action.setOnClickListener(open); // the ⋮ opens the same sheet
            card.setOnLongClickListener(v -> {
                if (listener != null && current != null) {
                    listener.onItemLongClick(current);
                    return true;
                }
                return false;
            });
        }

        void bind(VaultEntry entry, Object thumbModel, boolean actionMode, boolean selected) {
            current = entry;
            Context ctx = card.getContext();
            name.setText(entry.name);
            // MimePrimary label (Downloads/Captured grid parity): weighted TEXT,
            // no pill — see the MimePrimary style header. The label stays BARE and
            // the " · " is prepended to the SIZE instead, so a mime we couldn't
            // resolve leaves no dangling separator; the row reads 'VÍDEO · 56 MB'.
            String mimeLabel = entry.mime != null
                    ? FileUriHelper.getLongMimeText(ctx, entry.mime) : null;
            boolean mimeShown = !TextUtils.isEmpty(mimeLabel);
            if (!mimeShown) {
                mime.setVisibility(View.GONE);
            } else {
                mime.setVisibility(View.VISIBLE);
                mime.setText(mimeLabel);
            }
            String sizeText = Formatter.formatShortFileSize(ctx, entry.size);
            size.setText(mimeShown ? " · " + sizeText : sizeText);
            bindThumb(thumb, ctx, thumbModel, entry.mime);
            applyGridDim(thumbModel != null);

            // Grid selection: the check replaces the ⋮ in the top-end corner and
            // the card takes a primary stroke (vs the transparent resting stroke);
            // the list-row wash would fight the thumbnail.
            if (actionMode) {
                action.setVisibility(View.GONE);
                check.setVisibility(View.VISIBLE);
                check.setImageResource(selected
                        ? R.drawable.ic_baseline_check_circle_24
                        : R.drawable.radio_button_unchecked_24);
                card.setStrokeColor(selected
                        ? MaterialColors.getColor(card,
                                android.R.attr.colorPrimary, Color.TRANSPARENT)
                        : Color.TRANSPARENT);
            } else {
                action.setVisibility(View.VISIBLE);
                check.setVisibility(View.GONE);
                card.setStrokeColor(Color.TRANSPARENT);
            }
        }

        /**
         * Dim-only, mirroring {@code DownloadItemAdapter.applyGridTileGround}.
         * The {@code bottom_scrim} exists to float white text over an unknown,
         * arbitrary-brightness photo; a tile with no stored preview falls back
         * to {@code MimeTypeThumbnail}'s single flat ground, which already
         * carries white at 13.7:1. There the gradient buys nothing and costs
         * something — over a solid colour it is visible AS a gradient, a
         * vignette smudged across the bottom of an otherwise clean tile. Same
         * reasoning retires the text shadows on those tiles. The ink never
         * changes; it is white on both. Must be applied on EVERY bind, in both
         * directions, because the holder is recycled between preview and
         * no-preview entries.
         */
        private void applyGridDim(boolean hasPreview) {
            if (bottomBlock == null) return;
            if (hasPreview) {
                bottomBlock.setBackgroundResource(R.drawable.bottom_scrim);
            } else {
                bottomBlock.setBackground(null);
            }
            float radius = hasPreview ? 2f : 0f;
            int shadow = hasPreview ? GRID_TEXT_SHADOW : Color.TRANSPARENT;
            name.setShadowLayer(radius, 0f, 1f, shadow);
            mime.setShadowLayer(radius, 0f, 1f, shadow);
            size.setShadowLayer(radius, 0f, 1f, shadow);
        }
    }

    static class TransferVH extends RecyclerView.ViewHolder {
        private final ImageView thumb;
        private final TextView name;
        private final TextView mime;
        private final TextView state;
        private final TextView percent;
        private final LinearProgressIndicator bar;
        /**
         * The state line's inks, resolved ONCE per holder — never a bare theme
         * attr at bind time. This holder serves BOTH layouts, and they sit on
         * opposite grounds: the list row's text is on the theme surface, while
         * the grid tile's is over the fixed dark {@code #4A2120} fallback ground
         * (or a thumbnail). A theme-surface ink is unreadable there in LIGHT
         * theme — onSurfaceVariant measures 1.47:1 and colorError 1.95:1 on that
         * ground, against a 4.5:1 floor, which is why "Backing up…" was legible
         * in dark and invisible in light. So NORMAL keeps whatever the layout
         * declared (the grid XML's #E0FFFFFF, 10.3:1; the list XML's
         * onSurfaceVariant) and ERROR picks per surface.
         */
        private final int stateNormalColor;
        private final int stateErrorColor;
        private String currentWorkId;

        TransferVH(@NonNull View itemView, boolean grid, OnItemClickListener listener) {
            super(itemView);
            thumb = itemView.findViewById(R.id.cb_thumb);
            name = itemView.findViewById(R.id.cb_name);
            mime = itemView.findViewById(R.id.cb_mime);
            state = itemView.findViewById(R.id.cb_transfer_state);
            percent = itemView.findViewById(R.id.cb_progress_text);
            bar = itemView.findViewById(R.id.cb_progress_bar);
            // Capture the layout's own ink before anything can overwrite it —
            // each layout already declares the right one for its ground.
            stateNormalColor = state.getCurrentTextColor();
            // On the grid tile the error ink must also survive the dark ground,
            // so it takes colorPrimaryContainer — the same on-dark-ground ink
            // the Downloads grid tile's status_text uses for ERROR/QUEUED
            // (5.83:1 light / 4.69:1 dark on #4A2120). The list row keeps the
            // real colorError, which is what that token is for on a surface.
            stateErrorColor = MaterialColors.getColor(itemView,
                    grid ? com.google.android.material.R.attr.colorPrimaryContainer
                            : androidx.appcompat.R.attr.colorError,
                    Color.RED);
            thumb.setClipToOutline(true);
            // Same indicator/track colours as the Downloads in-flight row:
            // primary indicator over a primary@20% track.
            // Same indicator/track pairing as the Downloads in-flight row: the
            // indicator is progress_indicator (a deeper coral in light theme, so
            // it separates from its own track — colorPrimary cannot, see the
            // resource comment), over a colorPrimary@20% track.
            int primary = MaterialColors.getColor(itemView, android.R.attr.colorPrimary, Color.BLACK);
            bar.setIndicatorColor(ContextCompat.getColor(itemView.getContext(),
                    R.color.progress_indicator));
            bar.setTrackColor(ColorUtils.setAlphaComponent(primary, 0x33));
            itemView.findViewById(R.id.cb_transfer_cancel).setOnClickListener(v -> {
                if (listener != null && currentWorkId != null) {
                    listener.onCancelTransfer(currentWorkId);
                }
            });
        }

        void bind(Transfer t) {
            currentWorkId = t.workId;
            Context ctx = itemView.getContext();
            name.setText(t.name);
            bindMimeChip(mime, ctx, t.mime);
            if (t.failed) {
                // Terminal failure — say so instead of a forever-0% bar (a
                // background failure used to be completely silent here). The ✕
                // dismisses the row. Error colour on the state line only; both
                // branches set the colour because the holder is recycled.
                state.setText(t.errorText != null
                        ? t.errorText
                        : ctx.getString(R.string.cloud_backup_transfer_failed));
                state.setTextColor(stateErrorColor);
                bar.setVisibility(View.GONE);
                percent.setVisibility(View.GONE);
                bindThumb(thumb, ctx, null, t.mime);
                return;
            }
            state.setText(R.string.cloud_backup_transfer_uploading);
            state.setTextColor(stateNormalColor);
            bar.setVisibility(View.VISIBLE);
            // ALWAYS determinate with a percent shown (0% before the first byte
            // report) — exactly like the Downloads list row. Hiding the percent
            // when done==0 shifted the bar's left margin; the percent slot is now
            // always present so the bar starts at the same place every frame. Only
            // a (degenerate) zero-byte total falls back to indeterminate.
            if (t.total > 0) {
                int pct = (int) Math.min(100, t.done * 100 / t.total);
                bar.setIndeterminate(false);
                bar.setProgress(pct);
                percent.setVisibility(View.VISIBLE);
                percent.setText(String.format(Locale.US, "%d%%", pct));
            } else {
                bar.setIndeterminate(true);
                percent.setVisibility(View.GONE);
            }
            bindThumb(thumb, ctx, null, t.mime);
        }
    }
}
