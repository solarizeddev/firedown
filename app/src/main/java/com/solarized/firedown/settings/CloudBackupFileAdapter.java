package com.solarized.firedown.settings;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.text.TextUtils;
import android.text.format.DateUtils;
import android.text.format.Formatter;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.card.MaterialCardView;
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

        public Transfer(String workId, String name, String mime, long done, long total) {
            this.workId = workId;
            this.name = name;
            this.mime = mime;
            this.done = done;
            this.total = total;
        }
    }

    private final List<Transfer> mTransfers = new ArrayList<>();
    private final List<VaultEntry> mItems = new ArrayList<>();
    /** objectId → base64 preview backfilled from the local file (display only). */
    private final Map<String, String> mResolvedThumbs = new HashMap<>();
    /** Selected committed entries (by objectId) while in multi-select. */
    private final Set<String> mSelected = new HashSet<>();
    private boolean mActionMode;
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

    public void submit(List<VaultEntry> items) {
        mItems.clear();
        mResolvedThumbs.clear();
        if (items != null) {
            mItems.addAll(items);
        }
        notifyDataSetChanged();
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
     * stored thumb) and refreshes its row so the thumbnail appears.
     */
    public void setResolvedThumb(String objectId, String thumb) {
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

    @Override
    public int getItemViewType(int position) {
        return position < mTransfers.size() ? TYPE_TRANSFER : TYPE_FILE;
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inflater = LayoutInflater.from(parent.getContext());
        if (viewType == TYPE_TRANSFER) {
            View v = inflater.inflate(R.layout.item_cloud_backup_transfer, parent, false);
            return new TransferVH(v, mListener);
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
            ((FileVH) holder).bind(entry, mResolvedThumbs.get(entry.objectId),
                    mActionMode, mSelected.contains(entry.objectId));
        }
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

    /** Stored preview → display-only backfill → mime-type fallback card. */
    private static void bindThumb(ImageView thumb, Context ctx, String thumbData, String mimeType) {
        Bitmap bmp = VaultThumbnail.decode(thumbData);
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

        void bind(VaultEntry entry, String resolvedThumb, boolean actionMode, boolean selected) {
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
            bindThumb(thumb, ctx, entry.thumb != null ? entry.thumb : resolvedThumb, entry.mime);

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
            state.setText(R.string.cloud_backup_transfer_uploading);
            // Determinate once the total is known and the first chunk lands;
            // indeterminate while we're still waiting on the first byte report.
            if (t.total > 0 && t.done > 0) {
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
