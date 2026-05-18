package com.solarized.firedown.ui.diffs;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;

import com.solarized.firedown.data.entity.WebBookmarkEntity;
import java.util.Objects;

public class WebBookmarkDiffCallback extends DiffUtil.ItemCallback<WebBookmarkEntity> {

    @Override
    public boolean areItemsTheSame(@NonNull WebBookmarkEntity oldItem, @NonNull WebBookmarkEntity newItem) {
        return oldItem.getId() == newItem.getId();
    }

    @Override
    public boolean areContentsTheSame(@NonNull WebBookmarkEntity oldItem, @NonNull WebBookmarkEntity newItem) {
        return oldItem.getDate() == newItem.getDate()
                && Objects.equals(oldItem.getTitle(), newItem.getTitle())
                && Objects.equals(oldItem.getIcon(), newItem.getIcon())
                && Objects.equals(oldItem.getUrl(), newItem.getUrl());
    }
}