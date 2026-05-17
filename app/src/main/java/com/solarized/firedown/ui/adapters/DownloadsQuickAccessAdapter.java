package com.solarized.firedown.ui.adapters;

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
 * Tiny ListAdapter backing the quick-access popup that opens when the
 * user long-presses the Downloads button in the bottom bar. Same
 * layout shape as the main downloads list — thumbnail tile with a
 * footer scrim filename overlay and a play glyph for video/audio —
 * just downscaled to a 96dp tile so a row of four fits in the popup.
 */
public class DownloadsQuickAccessAdapter
        extends ListAdapter<DownloadEntity, DownloadsQuickAccessAdapter.TileViewHolder> {

    public interface OnTileClickListener {
        void onTileClick(DownloadEntity entity);
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
    @Nullable private final OnTileClickListener mListener;

    public DownloadsQuickAccessAdapter(@Nullable OnTileClickListener listener) {
        super(DIFF);
        mListener = listener;
    }

    @NonNull
    @Override
    public TileViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_downloads_quick_access, parent, false);
        return new TileViewHolder(v, mListener);
    }

    @Override
    public void onBindViewHolder(@NonNull TileViewHolder holder, int position) {
        holder.bind(getItem(position), mRequestOptions);
    }

    static class TileViewHolder extends RecyclerView.ViewHolder implements View.OnClickListener {

        private final AppCompatImageView image;
        private final AppCompatImageView playOverlay;
        private final TextView fileName;
        @Nullable private final OnTileClickListener listener;
        @Nullable private DownloadEntity boundEntity;

        TileViewHolder(@NonNull View itemView, @Nullable OnTileClickListener listener) {
            super(itemView);
            this.image = itemView.findViewById(R.id.image);
            this.playOverlay = itemView.findViewById(R.id.play_overlay);
            this.fileName = itemView.findViewById(R.id.file_name);
            this.listener = listener;
            this.image.setClipToOutline(true);
            itemView.findViewById(R.id.item).setOnClickListener(this);
        }

        void bind(@NonNull DownloadEntity entity, @NonNull RequestOptions options) {
            boundEntity = entity;
            fileName.setText(entity.getFileName());
            GlideHelper.load(entity, options, image);

            String mime = entity.getFileMimeType();
            boolean playable = mime != null
                    && (FileUriHelper.isVideo(mime) || FileUriHelper.isAudio(mime));
            playOverlay.setVisibility(playable ? View.VISIBLE : View.GONE);
        }

        @Override
        public void onClick(View v) {
            if (listener != null && boundEntity != null) {
                listener.onTileClick(boundEntity);
            }
        }
    }
}
