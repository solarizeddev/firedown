package com.solarized.firedown.ui.adapters;

import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.AppCompatImageView;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.request.RequestOptions;
import com.google.android.material.card.MaterialCardView;
import com.solarized.firedown.GlideHelper;
import com.solarized.firedown.R;
import com.solarized.firedown.data.Download;
import com.solarized.firedown.data.entity.DownloadEntity;
import com.solarized.firedown.ui.ProgressOverlayView;
import com.solarized.firedown.utils.DateUtils;
import com.solarized.firedown.utils.FileUriHelper;
import com.solarized.firedown.utils.MessageHelper;
import com.solarized.firedown.utils.Utils;
import com.solarized.firedown.utils.WebUtils;

import java.util.Locale;

/**
 * Backs the vertical list inside the Downloads quick-access bottom
 * sheet. Inflates {@link R.layout#fragment_download_item} so each
 * row looks identical to a list-mode row in the main DownloadFragment
 * — same thumbnail card, same mime badge + filename + domain layout,
 * same status-specific bottom row (progress bar, finished
 * '&lt;size&gt; - &lt;date&gt;', error message, or 'queued' label).
 *
 * <p>Mirrors {@code DownloadItemAdapter}'s binding for the four
 * status types so PROGRESS / QUEUED / ERROR / FINISHED items all
 * render in their right state. Selection / action-menu paths are
 * skipped — this sheet is read-only.</p>
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
                            && a.getFileProgress() == b.getFileProgress()
                            && a.getFileErrorType() == b.getFileErrorType()
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
        private final ProgressOverlayView imageProgress;
        private final TextView mimeText;
        private final TextView fileName;
        private final TextView fileUrl;
        private final TextView finishedText;
        private final TextView progressText;
        private final ProgressBar progressBar;
        private final TextView queuedText;
        private final TextView errorText;
        @Nullable private final OnRowClickListener listener;
        @Nullable private DownloadEntity boundEntity;

        RowViewHolder(@NonNull View itemView, @Nullable OnRowClickListener listener) {
            super(itemView);
            this.item = itemView.findViewById(R.id.item);
            this.image = itemView.findViewById(R.id.image);
            this.imageProgress = itemView.findViewById(R.id.image_progress);
            this.mimeText = itemView.findViewById(R.id.mime_text);
            this.fileName = itemView.findViewById(R.id.file_name);
            this.fileUrl = itemView.findViewById(R.id.file_url);
            this.finishedText = itemView.findViewById(R.id.item_download_finished);
            this.progressText = itemView.findViewById(R.id.progress_text);
            this.progressBar = itemView.findViewById(R.id.progress_bar);
            this.queuedText = itemView.findViewById(R.id.queued_text);
            this.errorText = itemView.findViewById(R.id.error_text);
            this.listener = listener;

            this.image.setClipToOutline(true);
            this.item.setOnClickListener(this);

            // The list-mode card paints a 2dp stroke; DownloadItemAdapter
            // sets it to transparent in normal state (only the selection
            // path uses a non-transparent colour) and we don't have a
            // selection mode here, so always transparent. Without this
            // the stroke renders in the theme accent (orange) and the
            // row looks 'highlighted'.
            this.item.setStrokeColor(
                    ContextCompat.getColor(itemView.getContext(), R.color.transparent));

            // Selection check and action menu never apply on this surface.
            View selected = itemView.findViewById(R.id.item_download_selected);
            if (selected != null) selected.setVisibility(View.GONE);
            View action = itemView.findViewById(R.id.item_download_action);
            if (action != null) action.setVisibility(View.GONE);
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

            // Reset all status views; each branch enables only its own.
            setVisible(progressText, false);
            setVisible(progressBar, false);
            setVisible(finishedText, false);
            setVisible(errorText, false);
            setVisible(queuedText, false);
            setVisible(imageProgress, false);

            switch (entity.getFileStatus()) {
                case Download.PROGRESS -> bindProgress(entity);
                case Download.FINISHED -> bindFinished(entity, options);
                case Download.ERROR -> bindError(entity);
                case Download.QUEUED -> bindQueued(entity);
                default -> bindFinished(entity, options);
            }
        }

        private void bindProgress(@NonNull DownloadEntity entity) {
            boolean retrieving = entity.getFileIsLive();
            setVisible(progressText, true);
            setVisible(progressBar, true);
            progressText.setText(retrieving
                    ? Utils.readableFileSize(entity.getFileSize())
                    : String.format(Locale.US, "%d%%", entity.getFileProgress()));
            progressBar.setIndeterminate(retrieving);
            if (!retrieving) progressBar.setProgress(entity.getFileProgress());
            GlideHelper.loadFallback(entity, image);
        }

        private void bindFinished(@NonNull DownloadEntity entity, @NonNull RequestOptions options) {
            setVisible(finishedText, true);
            finishedText.setText(Utils.getFileSize(entity.getFileSize())
                    + " - " + DateUtils.getFileDate(entity.getFileDate()));
            GlideHelper.load(entity, options, image);
        }

        private void bindError(@NonNull DownloadEntity entity) {
            setVisible(errorText, true);
            int errorId = MessageHelper.getResourceIdFromCode(entity.getFileErrorType());
            errorText.setText(errorId);
            GlideHelper.loadFallback(entity, image);
        }

        private void bindQueued(@NonNull DownloadEntity entity) {
            setVisible(queuedText, true);
            GlideHelper.loadFallback(entity, image);
        }

        @Override
        public void onClick(View v) {
            if (listener != null && boundEntity != null) {
                listener.onRowClick(boundEntity);
            }
        }

        private static void setVisible(@Nullable View view, boolean visible) {
            if (view != null) view.setVisibility(visible ? View.VISIBLE : View.GONE);
        }
    }
}
