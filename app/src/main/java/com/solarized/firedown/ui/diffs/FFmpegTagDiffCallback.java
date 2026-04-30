package com.solarized.firedown.ui.diffs;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;

import com.solarized.firedown.data.entity.FFmpegTagEntity;
import java.util.Objects;

public class FFmpegTagDiffCallback extends DiffUtil.ItemCallback<FFmpegTagEntity> {

    @Override
    public boolean areItemsTheSame(@NonNull FFmpegTagEntity oldItem, @NonNull FFmpegTagEntity newItem) {
        return oldItem.getUid() == newItem.getUid();
    }

    @Override
    public boolean areContentsTheSame(@NonNull FFmpegTagEntity oldItem, @NonNull FFmpegTagEntity newItem) {
        return Objects.equals(oldItem.getText(), newItem.getText());
    }
}
