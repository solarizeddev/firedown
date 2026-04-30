package com.solarized.firedown.ui.diffs;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;

import com.solarized.firedown.data.entity.InfoEntity;
import java.util.Objects;

public class InfoDiffCallback extends DiffUtil.ItemCallback<InfoEntity> {

    @Override
    public boolean areItemsTheSame(@NonNull InfoEntity oldItem, @NonNull InfoEntity newItem) {
        return oldItem.getId() == newItem.getId();
    }

    @Override
    public boolean areContentsTheSame(@NonNull InfoEntity oldItem, @NonNull InfoEntity newItem) {
        return oldItem.getType() == newItem.getType()
                && Objects.equals(oldItem.getText(), newItem.getText());
    }
}
