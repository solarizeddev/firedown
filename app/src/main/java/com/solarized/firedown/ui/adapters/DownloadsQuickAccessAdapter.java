package com.solarized.firedown.ui.adapters;

import android.text.TextUtils;
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
import com.google.android.material.card.MaterialCardView;
import com.solarized.firedown.GlideHelper;
import com.solarized.firedown.R;
import com.solarized.firedown.data.entity.DownloadEntity;
import com.solarized.firedown.utils.DateUtils;
import com.solarized.firedown.utils.FileUriHelper;
import com.solarized.firedown.utils.Utils;
import com.solarized.firedown.utils.WebUtils;

/**
 * Backs the vertical list inside the Downloads quick-access bottom
 * sheet. Inflates {@link R.layout#fragment_download_item} so each
 * row looks identical to a list-mode row in the main DownloadFragment
 * — same thumbnail card, same mime badge + filename + domain layout,
 * same '&lt;size&gt; - &lt;date&gt;' meta line. Everything that only
 * applies to in-flight / errored / queued / selectable rows is
 * hidden, since this surface only ever shows FINISHED items.
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
                .inflate(R.layout.fragment_download_item, parent, false);
        return new RowViewHolder(v, mListener);
    }

    @Override
    public void onBindViewHolder(@NonNull RowViewHolder holder, int position) {
        holder.bind(getItem(position), mRequestOptions);
    }

    static class RowViewHolder extends RecyclerView.ViewHolder implements View.OnClickListener {

        private final MaterialCardView item;
        private final AppCompatImageView image;
        private final TextView mimeText;
        private final TextView fileName;
        private final TextView fileUrl;
        private final TextView finishedText;
        @Nullable private final OnRowClickListener listener;
        @Nullable private DownloadEntity boundEntity;

        RowViewHolder(@NonNull View itemView, @Nullable OnRowClickListener listener) {
            super(itemView);
            this.item = itemView.findViewById(R.id.item);
            this.image = itemView.findViewById(R.id.image);
            this.mimeText = itemView.findViewById(R.id.mime_text);
            this.fileName = itemView.findViewById(R.id.file_name);
            this.fileUrl = itemView.findViewById(R.id.file_url);
            this.finishedText = itemView.findViewById(R.id.item_download_finished);
            this.listener = listener;

            this.image.setClipToOutline(true);
            this.item.setOnClickListener(this);

            // Hide every part of the list-mode item layout that only
            // makes sense for non-finished / selectable rows — we
            // never bind those states here.
            hideIfPresent(itemView, R.id.item_download_selected);
            hideIfPresent(itemView, R.id.item_download_action);
            hideIfPresent(itemView, R.id.progress_text);
            hideIfPresent(itemView, R.id.progress_bar);
            hideIfPresent(itemView, R.id.queued_text);
            hideIfPresent(itemView, R.id.error_text);
            hideIfPresent(itemView, R.id.image_progress);
        }

        void bind(@NonNull DownloadEntity entity, @NonNull RequestOptions options) {
            boundEntity = entity;

            String mimeType = entity.getFileMimeType();
            String originUrl = entity.getOriginUrl();
            String fileUrlStr = entity.getFileUrl();
            String domain = TextUtils.isEmpty(originUrl)
                    ? WebUtils.getDomainName(fileUrlStr)
                    : WebUtils.getDomainName(originUrl);

            mimeText.setText(FileUriHelper.getLongMimeText(itemView.getContext(), mimeType));
            fileName.setText(entity.getFileName());
            fileUrl.setText(domain);

            finishedText.setVisibility(View.VISIBLE);
            finishedText.setText(Utils.getFileSize(entity.getFileSize())
                    + " - " + DateUtils.getFileDate(entity.getFileDate()));

            GlideHelper.load(entity, options, image);
        }

        @Override
        public void onClick(View v) {
            if (listener != null && boundEntity != null) {
                listener.onRowClick(boundEntity);
            }
        }

        private static void hideIfPresent(@NonNull View root, int id) {
            View v = root.findViewById(id);
            if (v != null) v.setVisibility(View.GONE);
        }
    }
}
