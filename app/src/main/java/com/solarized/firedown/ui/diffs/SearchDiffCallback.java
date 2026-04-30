package com.solarized.firedown.ui.diffs;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;

import com.solarized.firedown.data.entity.AutoCompleteEntity;
import java.util.Objects;

public class SearchDiffCallback extends DiffUtil.ItemCallback<AutoCompleteEntity> {

    @Override
    public boolean areItemsTheSame(@NonNull AutoCompleteEntity oldItem, @NonNull AutoCompleteEntity newItem) {
        return oldItem.getId() == newItem.getId();
    }

    @Override
    public boolean areContentsTheSame(@NonNull AutoCompleteEntity oldItem, @NonNull AutoCompleteEntity newItem) {
        return oldItem.getType() == newItem.getType()
                && Objects.equals(oldItem.getTitle(), newItem.getTitle())
                && Objects.equals(oldItem.getIcon(), newItem.getIcon())
                && Objects.equals(oldItem.getSubText(), newItem.getSubText());
    }
}