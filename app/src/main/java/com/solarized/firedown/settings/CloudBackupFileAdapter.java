package com.solarized.firedown.settings;

import android.content.Context;
import android.graphics.Bitmap;
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

import com.solarized.firedown.R;
import com.solarized.firedown.glide.MimeTypeThumbnail;
import com.solarized.firedown.sync.VaultThumbnail;
import com.solarized.firedown.sync.model.VaultEntry;
import com.solarized.firedown.utils.FileUriHelper;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Backed-up files list, styled to match the Downloads list row
 * (item_cloud_backup_file.xml ≡ fragment_download_item.xml): the stored preview
 * (decoded from the entry's base64 thumb) or the app's mime-type fallback, a
 * TitleSmall name, and a 'MIME · size · date' meta line. Tapping a row fires
 * {@link OnItemClickListener} (the fragment opens the per-item bottom sheet).
 *
 * <p>Entries backed up before previews existed carry no {@code thumb}. The
 * fragment backfills those for DISPLAY from the local file (when still present)
 * via {@link #setResolvedThumb}; until then the mime glyph fills the slot.
 */
public class CloudBackupFileAdapter extends RecyclerView.Adapter<CloudBackupFileAdapter.VH> {

    public interface OnItemClickListener {
        void onItemClick(VaultEntry entry);
    }

    private final List<VaultEntry> mItems = new ArrayList<>();
    /** objectId → base64 preview backfilled from the local file (display only). */
    private final Map<String, String> mResolvedThumbs = new HashMap<>();
    private final OnItemClickListener mListener;

    public CloudBackupFileAdapter(OnItemClickListener listener) {
        this.mListener = listener;
    }

    public void submit(List<VaultEntry> items) {
        mItems.clear();
        mResolvedThumbs.clear();
        if (items != null) {
            mItems.addAll(items);
        }
        notifyDataSetChanged();
    }

    /** Removes one entry (by objectId) in place; returns its former position or -1. */
    public int removeByObjectId(String objectId) {
        for (int i = 0; i < mItems.size(); i++) {
            if (mItems.get(i).objectId.equals(objectId)) {
                mItems.remove(i);
                notifyItemRemoved(i);
                return i;
            }
        }
        return -1;
    }

    /** Re-inserts an entry at a position (undo of an optimistic remove). */
    public void insertAt(int position, VaultEntry entry) {
        int p = Math.max(0, Math.min(position, mItems.size()));
        mItems.add(p, entry);
        notifyItemInserted(p);
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
                notifyItemChanged(i);
                return;
            }
        }
    }

    public int size() {
        return mItems.size();
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_cloud_backup_file, parent, false);
        return new VH(v, mListener);
    }

    @Override
    public void onBindViewHolder(@NonNull VH holder, int position) {
        holder.bind(mItems.get(position), mResolvedThumbs.get(mItems.get(position).objectId));
    }

    @Override
    public int getItemCount() {
        return mItems.size();
    }

    static class VH extends RecyclerView.ViewHolder {
        private final ImageView thumb;
        private final TextView name;
        private final TextView mime;
        private final TextView size;
        private final TextView date;
        private VaultEntry current;

        VH(@NonNull View itemView, OnItemClickListener listener) {
            super(itemView);
            thumb = itemView.findViewById(R.id.cb_thumb);
            name = itemView.findViewById(R.id.cb_name);
            mime = itemView.findViewById(R.id.cb_mime);
            size = itemView.findViewById(R.id.cb_size);
            date = itemView.findViewById(R.id.cb_date);
            // Clip the thumbnail to the rounded mask, same as the Downloads row.
            thumb.setClipToOutline(true);
            itemView.setOnClickListener(v -> {
                if (listener != null && current != null) {
                    listener.onItemClick(current);
                }
            });
        }

        void bind(VaultEntry entry, String resolvedThumb) {
            current = entry;
            Context ctx = itemView.getContext();
            name.setText(entry.name);

            // Line 2: mime chip ("VÍDEO · ") + size — same label + trailing
            // separator convention as the Downloads row's mime · domain.
            String mimeLabel = entry.mime != null
                    ? FileUriHelper.getLongMimeText(ctx, entry.mime) : null;
            if (TextUtils.isEmpty(mimeLabel)) {
                mime.setVisibility(View.GONE);
            } else {
                mime.setVisibility(View.VISIBLE);
                mime.setText(mimeLabel + " · ");
            }
            size.setText(Formatter.formatShortFileSize(ctx, entry.size));

            // Line 3: the backed-up date (relative), or hidden when unknown.
            if (entry.downloadedAt > 0) {
                date.setVisibility(View.VISIBLE);
                date.setText(DateUtils.getRelativeTimeSpanString(entry.downloadedAt,
                        System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS));
            } else {
                date.setVisibility(View.GONE);
            }

            // Stored preview first, then a display-only backfill from the local
            // file, then the app's mime-type fallback card.
            String thumbData = entry.thumb != null ? entry.thumb : resolvedThumb;
            Bitmap bmp = VaultThumbnail.decode(thumbData);
            if (bmp != null) {
                thumb.setImageBitmap(bmp);
            } else {
                String mimeType = entry.mime != null ? entry.mime : "application/octet-stream";
                thumb.setImageDrawable(MimeTypeThumbnail.generateDrawable(ctx, mimeType, true));
            }
        }
    }
}
