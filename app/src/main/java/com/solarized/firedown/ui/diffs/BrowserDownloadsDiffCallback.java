package com.solarized.firedown.ui.diffs;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;
import com.solarized.firedown.data.entity.BrowserDownloadEntity;

import org.apache.commons.collections4.CollectionUtils;
import java.util.Objects;

public class BrowserDownloadsDiffCallback extends DiffUtil.ItemCallback<BrowserDownloadEntity> {

    @Override
    public boolean areItemsTheSame(@NonNull BrowserDownloadEntity oldItem, @NonNull BrowserDownloadEntity newItem) {
        return oldItem.getUid() == newItem.getUid();
    }

    @Override
    public boolean areContentsTheSame(@NonNull BrowserDownloadEntity oldItem, @NonNull BrowserDownloadEntity newItem) {
        return Objects.equals(oldItem.getFileName(), newItem.getFileName())
                && Objects.equals(oldItem.getFileUrl(), newItem.getFileUrl())
                && Objects.equals(oldItem.getFileOrigin(), newItem.getFileOrigin())
                && Objects.equals(oldItem.getFileDuration(), newItem.getFileDuration())
                && oldItem.getDurationTime() == newItem.getDurationTime()
                && Objects.equals(oldItem.getMimeType(), newItem.getMimeType())
                && Objects.equals(oldItem.getFileHeaders(), newItem.getFileHeaders())
                // Use CollectionUtils for lists to ensure deep equality/order independence
                && CollectionUtils.isEqualCollection(oldItem.getStreams(), newItem.getStreams())
                && CollectionUtils.isEqualCollection(oldItem.getTags(), newItem.getTags());
    }
}
