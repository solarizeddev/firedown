package com.solarized.firedown.ui.diffs;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;

import com.solarized.firedown.data.entity.WebHistoryEntity;
import com.solarized.firedown.data.entity.WebHistorySeparatorEntity;

import java.util.Objects;

public class WebHistoryDiffCallback extends DiffUtil.ItemCallback<Object> {

    @Override
    public boolean areItemsTheSame(@NonNull Object oldItem, @NonNull Object newItem) {
        // 1. Compare two History items (by DB Primary Key)
        if (oldItem instanceof WebHistoryEntity oldEntity && newItem instanceof WebHistoryEntity newEntity) {
            return oldEntity.getId() == newEntity.getId();
        }

        // 2. Compare two Separators (by Resource ID / Title)
        if (oldItem instanceof WebHistorySeparatorEntity oldSep && newItem instanceof WebHistorySeparatorEntity newSep) {
            return oldSep.getTitleResId() == newSep.getTitleResId();
        }

        return false;
    }

    @Override
    public boolean areContentsTheSame(@NonNull Object oldItem, @NonNull Object newItem) {
        // This works perfectly ONLY if WebHistoryEntity and WebHistorySeparatorEntity
        // have an overridden equals() method.
        return Objects.equals(oldItem, newItem);
    }
}