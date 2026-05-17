package com.solarized.firedown.ui.adapters;

import android.text.format.DateUtils;
import android.text.format.Formatter;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.AppCompatImageView;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.request.RequestOptions;
import com.solarized.firedown.GlideHelper;
import com.solarized.firedown.R;
import com.solarized.firedown.data.entity.DownloadEntity;
import com.solarized.firedown.utils.FileUriHelper;

/**
 * Backs the vertical list inside the Downloads quick-access bottom
 * sheet. Each row shows a 48dp thumbnail, the filename, and a meta
 * line of 'relative time · size'. Reuses {@link GlideHelper#load} so
 * thumbnails get the same mime-fallback / cover-art-extraction path
 * as the main downloads list.
 */
public class DownloadsQuickAccessAdapter
        extends ListAdapter<DownloadEntity, DownloadsQuickAccessAdapter.RowViewHolder> {

    public interface OnRowClickListener {
        void onRowClick(DownloadEntity entity);
    }

    private static final DiffUtil.ItemCallback<DownloadEntity> DIFF =
            new DiffUtil.ItemCallback<>() {
                @Override
                public boolean areItemsTheSame(@NonNull DownloadEntity a, @NonNull DownloadEntity b) {
                    return a.getId() == b.getId();
                }

                @Override
                public boolean areContentsTheSame(@NonNull DownloadEntity a, @NonNull DownloadEntity b) {
                    return a.getFileDate() == b.getFileDate()
                            && a.getFileStatus() == b.getFileStatus()
                            && a.getFileSize() == b.getFileSize()
                            && safeEq(a.getFileName(), b.getFileName())
                            && safeEq(a.getFilePath(), b.getFilePath());
                }

                private boolean safeEq(@Nullable String a, @Nullable String b) {
                    return a == null ? b == null : a.equals(b);
                }
            };

    private final RequestOptions mRequestOptions = new RequestOptions();
    @Nullable private final OnRowClickListener mListener;

    public DownloadsQuickAccessAdapter(@Nullable OnRowClickListener listener) {
        super(DIFF);
        mListener = listener;
    }

    @NonNull
    @Override
    public RowViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_downloads_quick_access_row, parent, false);
        return new RowViewHolder(v, mListener);
    }

    @Override
    public void onBindViewHolder(@NonNull RowViewHolder holder, int position) {
        holder.bind(getItem(position), mRequestOptions);
    }

    static class RowViewHolder extends RecyclerView.ViewHolder implements View.OnClickListener {

        private final AppCompatImageView image;
        private final AppCompatImageView playOverlay;
        private final TextView fileName;
        private final TextView fileMeta;
        @Nullable private final OnRowClickListener listener;
        @Nullable private DownloadEntity boundEntity;

        RowViewHolder(@NonNull View itemView, @Nullable OnRowClickListener listener) {
            super(itemView);
            this.image = itemView.findViewById(R.id.image);
            this.playOverlay = itemView.findViewById(R.id.play_overlay);
            this.fileName = itemView.findViewById(R.id.file_name);
            this.fileMeta = itemView.findViewById(R.id.file_meta);
            this.listener = listener;
            this.image.setClipToOutline(true);
            itemView.setOnClickListener(this);
        }

        void bind(@NonNull DownloadEntity entity, @NonNull RequestOptions options) {
            boundEntity = entity;
            fileName.setText(entity.getFileName());
            fileMeta.setText(buildMeta(entity));
            GlideHelper.load(entity, options, image);

            String mime = entity.getFileMimeType();
            boolean playable = mime != null
                    && (FileUriHelper.isVideo(mime) || FileUriHelper.isAudio(mime));
            playOverlay.setVisibility(playable ? View.VISIBLE : View.GONE);
        }

        private CharSequence buildMeta(@NonNull DownloadEntity entity) {
            long when = entity.getFileDate();
            CharSequence relative = when > 0
                    ? DateUtils.getRelativeTimeSpanString(when, System.currentTimeMillis(),
                            DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE)
                    : null;

            long size = entity.getFileSize();
            String sizeStr = size > 0
                    ? Formatter.formatShortFileSize(itemView.getContext(), size)
                    : null;

            if (relative != null && sizeStr != null) {
                return relative + " · " + sizeStr;
            }
            return relative != null ? relative : (sizeStr != null ? sizeStr : "");
        }

        @Override
        public void onClick(View v) {
            if (listener != null && boundEntity != null) {
                listener.onRowClick(boundEntity);
            }
        }
    }
}
