package com.solarized.firedown.settings;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.text.TextUtils;
import android.text.format.DateUtils;
import android.text.format.Formatter;
import android.util.LruCache;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.graphics.ColorUtils;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListUpdateCallback;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.card.MaterialCardView;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.solarized.firedown.R;
import com.solarized.firedown.glide.MimeTypeThumbnail;
import com.solarized.firedown.sync.VaultThumbnail;
import com.solarized.firedown.sync.model.VaultEntry;
import com.solarized.firedown.utils.FileUriHelper;
import com.solarized.firedown.utils.SelectionStyling;

import java.util.ArrayList;
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

        public Transfer(String workId, String name, String mime, long done, long total) {
            this(workId, name, mime, done, total, false);
        }

        public Transfer(String workId, String name, String mime, long done, long total,
                        boolean failed) {
            this.workId = workId;
            this.name = name;
            this.mime = mime;
            this.done = done;
            this.total = total;
            this.failed = failed;
        }
    }

    private final List<Transfer> mTransfers = new ArrayList<>();
    private final List<VaultEntry> mItems = new ArrayList<>();
    /** objectId → preview bitmap backfilled from the local file (display only). */
    private final Map<String, Bitmap> mResolvedThumbs = new HashMap<>();
    /**
     * objectId → decoded STORED preview ({@code entry.thumb}). The bind used to
     * base64+JPEG-decode the manifest thumb on EVERY bind — every scroll-back,
     * every selection tick, every {@code notifyItemChanged} re-paid it on the
     * main thread. Stored thumbs are ≤160px JPEGs (≤~100 KB decoded), so a small
     * byte-bounded cache covers far more than the visible list; an evicted entry
     * just re-decodes on its next bind. Kept across {@link #submit} on purpose —
     * objectIds are server-random per object and a stored thumb is immutable, so
     * a stale key can never show the wrong image, only idle until evicted.
     */
    private static final int THUMB_CACHE_BYTES = 2 * 1024 * 1024;
    private final LruCache<String, Bitmap> mDecodedThumbs = new LruCache<>(THUMB_CACHE_BYTES) {
        @Override
        protected int sizeOf(@NonNull String key, @NonNull Bitmap value) {
            return value.getByteCount();
        }
    };
    /** Selected committed entries (by objectId) while in multi-select. */
    private final Set<String> mSelected = new HashSet<>();
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
        if (!on) {
            mSelected.clear();
        }
        notifyDataSetChanged(); // show/hide the check + wash on every row
    }

    public boolean isActionMode() {
        return mActionMode;
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

    public boolean isGrid() {
        return mEnableGrid;
    }

    /** Toggles selection of a committed entry and refreshes only its row. */
    public void toggleSelected(String objectId) {
        if (objectId == null) {
            return;
        }
        if (!mSelected.remove(objectId)) {
            mSelected.add(objectId);
        }
        for (int i = 0; i < mItems.size(); i++) {
            if (objectId.equals(mItems.get(i).objectId)) {
                notifyItemChanged(mTransfers.size() + i);
                return;
            }
        }
    }

    public int getSelectedCount() {
        return mSelected.size();
    }

    public List<String> getSelectedIds() {
        return new ArrayList<>(mSelected);
    }

    /**
     * Replaces the committed-file rows with a minimal DIFF — never a blanket
     * {@code notifyDataSetChanged}. The Backups screen re-pulls the manifest on
     * every {@code onResume} (app pause/resume, and the return from the item
     * sheet), so an IDENTICAL reload must produce ZERO rebinds — otherwise every
     * visible thumbnail visibly re-paints (the "images flicker on pause/resume /
     * after restore" reports). DiffUtil rebinds only genuinely changed rows.
     *
     * <p>{@link #mResolvedThumbs} is deliberately NOT cleared here (it used to
     * be): a display-backfilled preview is keyed by the server-random, immutable
     * objectId — exactly like {@link #mDecodedThumbs} — so a stale key can never
     * show the wrong image, and keeping it stops a backfilled thumbnail from
     * flashing back to the mime glyph (then re-resolving) on every reload.
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
    /** The display-backfilled preview for an entry (null when none resolved) —
     *  the item sheet reads it so a pre-preview entry whose ROW shows a
     *  regenerated thumbnail doesn't open a sheet with a bare mime glyph
     *  ({@code entry.thumb} is null for those; only this cache has the image). */
    public Bitmap resolvedThumb(String objectId) {
        return objectId != null ? mResolvedThumbs.get(objectId) : null;
    }

    public void setResolvedThumb(String objectId, Bitmap thumb) {
        if (objectId == null || thumb == null) {
            return;
        }
        mResolvedThumbs.put(objectId, thumb);
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
            return new TransferVH(v, mListener);
        }
        if (viewType == TYPE_TRANSFER_GRID) {
            // Same TransferVH — the grid tile reuses every field id.
            View v = inflater.inflate(R.layout.item_cloud_backup_transfer_grid, parent, false);
            return new TransferVH(v, mListener);
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
            Bitmap thumb = thumbBitmapFor(entry);
            boolean selected = mSelected.contains(entry.objectId);
            if (holder instanceof FileGridVH) {
                ((FileGridVH) holder).bind(entry, thumb, mActionMode, selected);
            } else {
                ((FileVH) holder).bind(entry, thumb, mActionMode, selected);
            }
        }
    }

    /**
     * Drops the stored-thumb decode cache. Called on memory-trim signals (the
     * host fragment's registered {@code ComponentCallbacks2}) — safe because the
     * cache is purely re-derivable: it refills lazily per bind from
     * {@code entry.thumb}. Backfilled previews ({@link #mResolvedThumbs}) are
     * deliberately KEPT — recreating those needs a full resolve pass (DB lookup
     * + decode), and losing them would leave permanent mime glyphs until the
     * next manifest load.
     */
    public void trimThumbCache() {
        mDecodedThumbs.evictAll();
    }

    /** Stored preview (decoded once, then served from {@link #mDecodedThumbs})
     *  → display-only backfill → null (the bind renders the mime glyph). */
    private Bitmap thumbBitmapFor(VaultEntry entry) {
        if (entry.thumb == null) {
            return mResolvedThumbs.get(entry.objectId);
        }
        Bitmap cached = entry.objectId != null ? mDecodedThumbs.get(entry.objectId) : null;
        if (cached != null) {
            return cached;
        }
        Bitmap decoded = VaultThumbnail.decode(entry.thumb);
        if (decoded != null && entry.objectId != null) {
            mDecodedThumbs.put(entry.objectId, decoded);
        }
        return decoded;
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
     *  Decoding/caching lives in {@link #thumbBitmapFor} — this only paints. */
    private static void bindThumb(ImageView thumb, Context ctx, Bitmap bmp, String mimeType) {
        if (bmp != null) {
            thumb.setImageBitmap(bmp);
        } else {
            String mt = mimeType != null ? mimeType : "application/octet-stream";
            thumb.setImageDrawable(MimeTypeThumbnail.generateDrawable(ctx, mt, true));
        }
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

        void bind(VaultEntry entry, Bitmap thumbBitmap, boolean actionMode, boolean selected) {
            current = entry;
            Context ctx = itemView.getContext();
            name.setText(entry.name);
            bindMimeChip(mime, ctx, entry.mime);
            size.setText(Formatter.formatShortFileSize(ctx, entry.size));
            if (entry.downloadedAt > 0) {
                date.setVisibility(View.VISIBLE);
                date.setText(DateUtils.getRelativeTimeSpanString(entry.downloadedAt,
                        System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS));
            } else {
                date.setVisibility(View.GONE);
            }
            bindThumb(thumb, ctx, thumbBitmap, entry.mime);

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

        void bind(VaultEntry entry, Bitmap thumbBitmap, boolean actionMode, boolean selected) {
            current = entry;
            Context ctx = card.getContext();
            name.setText(entry.name);
            // Decorated MimePrimary chip (Downloads/Captured grid parity): the BARE
            // label, no ' · ' separator — the pill is self-contained and the size
            // follows as plain scrim text, so the row reads '[VÍDEO] 56 MB'. (The
            // shared bindMimeChip appends ' · ' for the LIST's plain-text meta.)
            String mimeLabel = entry.mime != null
                    ? FileUriHelper.getLongMimeText(ctx, entry.mime) : null;
            if (TextUtils.isEmpty(mimeLabel)) {
                mime.setVisibility(View.GONE);
            } else {
                mime.setVisibility(View.VISIBLE);
                mime.setText(mimeLabel);
            }
            size.setText(Formatter.formatShortFileSize(ctx, entry.size));
            bindThumb(thumb, ctx, thumbBitmap, entry.mime);

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
    }

    static class TransferVH extends RecyclerView.ViewHolder {
        private final ImageView thumb;
        private final TextView name;
        private final TextView mime;
        private final TextView state;
        private final TextView percent;
        private final LinearProgressIndicator bar;
        private String currentWorkId;

        TransferVH(@NonNull View itemView, OnItemClickListener listener) {
            super(itemView);
            thumb = itemView.findViewById(R.id.cb_thumb);
            name = itemView.findViewById(R.id.cb_name);
            mime = itemView.findViewById(R.id.cb_mime);
            state = itemView.findViewById(R.id.cb_transfer_state);
            percent = itemView.findViewById(R.id.cb_progress_text);
            bar = itemView.findViewById(R.id.cb_progress_bar);
            thumb.setClipToOutline(true);
            // Same indicator/track colours as the Downloads in-flight row:
            // primary indicator over a primary@20% track.
            int primary = MaterialColors.getColor(itemView, android.R.attr.colorPrimary, Color.BLACK);
            bar.setIndicatorColor(primary);
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
                state.setText(R.string.cloud_backup_transfer_failed);
                state.setTextColor(MaterialColors.getColor(itemView,
                        androidx.appcompat.R.attr.colorError, Color.RED));
                bar.setVisibility(View.GONE);
                percent.setVisibility(View.GONE);
                bindThumb(thumb, ctx, null, t.mime);
                return;
            }
            state.setText(R.string.cloud_backup_transfer_uploading);
            state.setTextColor(MaterialColors.getColor(itemView,
                    com.google.android.material.R.attr.colorOnSurfaceVariant, Color.GRAY));
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
