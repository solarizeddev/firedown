package com.solarized.firedown.settings;

import android.content.Context;
import android.graphics.Bitmap;
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

import java.util.ArrayList;
import java.util.List;

/**
 * Backed-up files list. One row per manifest entry: the stored preview (decoded
 * from the entry's base64 thumb, or a mime-type fallback) + name + "size · date".
 * Tapping a row fires {@link OnItemClickListener} (the fragment opens the per-item
 * bottom sheet).
 */
public class CloudBackupFileAdapter extends RecyclerView.Adapter<CloudBackupFileAdapter.VH> {

    public interface OnItemClickListener {
        void onItemClick(VaultEntry entry);
    }

    private final List<VaultEntry> mItems = new ArrayList<>();
    private final OnItemClickListener mListener;

    public CloudBackupFileAdapter(OnItemClickListener listener) {
        this.mListener = listener;
    }

    public void submit(List<VaultEntry> items) {
        mItems.clear();
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
        holder.bind(mItems.get(position));
    }

    @Override
    public int getItemCount() {
        return mItems.size();
    }

    static class VH extends RecyclerView.ViewHolder {
        private final ImageView thumb;
        private final TextView name;
        private final TextView meta;
        private VaultEntry current;

        VH(@NonNull View itemView, OnItemClickListener listener) {
            super(itemView);
            thumb = itemView.findViewById(R.id.cb_thumb);
            name = itemView.findViewById(R.id.cb_name);
            meta = itemView.findViewById(R.id.cb_meta);
            itemView.setOnClickListener(v -> {
                if (listener != null && current != null) {
                    listener.onItemClick(current);
                }
            });
        }

        void bind(VaultEntry entry) {
            current = entry;
            Context ctx = itemView.getContext();
            name.setText(entry.name);
            meta.setText(metaFor(ctx, entry));

            Bitmap bmp = VaultThumbnail.decode(entry.thumb);
            if (bmp != null) {
                thumb.setImageBitmap(bmp);
            } else {
                // No stored preview (e.g. backed up before previews, or audio/doc
                // with no art) — the app's mime-type fallback fills the slot.
                String mime = entry.mime != null ? entry.mime : "application/octet-stream";
                thumb.setImageDrawable(MimeTypeThumbnail.generateDrawable(ctx, mime, true));
            }
        }

        private static String metaFor(Context ctx, VaultEntry entry) {
            String size = Formatter.formatShortFileSize(ctx, entry.size);
            if (entry.downloadedAt <= 0) {
                return size;
            }
            CharSequence date = DateUtils.getRelativeTimeSpanString(
                    entry.downloadedAt, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS);
            return ctx.getString(R.string.cloud_backup_item_summary, size, date);
        }
    }
}
