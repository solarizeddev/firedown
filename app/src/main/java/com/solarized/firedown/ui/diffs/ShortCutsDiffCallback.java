package com.solarized.firedown.ui.diffs;


import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;

import com.solarized.firedown.data.entity.ShortCutsEntity;

import java.util.Objects;

public class ShortCutsDiffCallback extends DiffUtil.ItemCallback<ShortCutsEntity> {

    @Override
    public boolean areItemsTheSame(@NonNull ShortCutsEntity oldItem, @NonNull ShortCutsEntity newItem) {
        return oldItem.getId() == newItem.getId();
    }

    @Override
    public boolean areContentsTheSame(@NonNull ShortCutsEntity oldItem, @NonNull ShortCutsEntity newItem) {
        return Objects.equals(oldItem.getTitle(), newItem.getTitle()) &&
                Objects.equals(oldItem.getUrl(), newItem.getUrl()) &&
                Objects.equals(oldItem.getIcon(), newItem.getIcon()) &&
                Objects.equals(oldItem.getDomain(), newItem.getDomain()) &&
                oldItem.getDate() == newItem.getDate();
    }
}
