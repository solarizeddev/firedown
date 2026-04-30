package com.solarized.firedown.ui.diffs;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;

import com.solarized.firedown.data.entity.DownloadEntity;
import com.solarized.firedown.data.entity.DownloadSeparatorEntity;

import java.util.Objects;

public class DownloadDiffCallback extends DiffUtil.ItemCallback<Object> {

    @Override
    public boolean areItemsTheSame(@NonNull Object oldItem, @NonNull Object newItem) {
        // Compare two Download items by primary key
        if (oldItem instanceof DownloadEntity oldEntity && newItem instanceof DownloadEntity newEntity) {
            return oldEntity.getId() == newEntity.getId();
        }

        // Compare two Separators by category
        if (oldItem instanceof DownloadSeparatorEntity oldSep && newItem instanceof DownloadSeparatorEntity newSep) {
            return oldSep.getCategory() == newSep.getCategory();
        }

        return false;
    }

    @Override
    public boolean areContentsTheSame(@NonNull Object oldItem, @NonNull Object newItem) {
        if (oldItem instanceof DownloadEntity oldEntity && newItem instanceof DownloadEntity newEntity) {
            return oldEntity.getFileStatus() == newEntity.getFileStatus()
                    && Objects.equals(oldEntity.getFileImg(), newEntity.getFileImg())
                    && Objects.equals(oldEntity.getDurationFormatted(), newEntity.getDurationFormatted())
                    && Objects.equals(oldEntity.getFilePath(), newEntity.getFilePath())
                    && Objects.equals(oldEntity.getFileName(), newEntity.getFileName())
                    && oldEntity.getFileProgress() == newEntity.getFileProgress()
                    && oldEntity.getFileSize() == newEntity.getFileSize()
                    && oldEntity.getFileIsLive() == newEntity.getFileIsLive()
                    && Objects.equals(oldEntity.getFileMimeType(), newEntity.getFileMimeType())
                    && oldEntity.getFileErrorType() == newEntity.getFileErrorType()
                    && oldEntity.getDuration() == newEntity.getDuration()
                    && oldEntity.getThumbnailDuration() == newEntity.getThumbnailDuration();
        }

        return Objects.equals(oldItem, newItem);
    }
}