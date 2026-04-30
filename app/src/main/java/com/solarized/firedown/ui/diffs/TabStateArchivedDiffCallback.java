package com.solarized.firedown.ui.diffs;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;

import com.solarized.firedown.data.entity.TabStateArchivedEntity;
import com.solarized.firedown.data.entity.TabStateHeaderArchivedEntity;

import java.util.Objects;

public class TabStateArchivedDiffCallback extends DiffUtil.ItemCallback<Object> {

    @Override
    public boolean areItemsTheSame(@NonNull Object oldItem, @NonNull Object newItem) {
        // Check Entity Equality
        if (oldItem instanceof TabStateArchivedEntity oldTab && newItem instanceof TabStateArchivedEntity newTab) {
            return oldTab.getId() == newTab.getId();
        }
        // Check Header Equality
        if (oldItem instanceof TabStateHeaderArchivedEntity oldHeader && newItem instanceof TabStateHeaderArchivedEntity newHeader) {
            return oldHeader.getId() == newHeader.getId();
        }
        return false;
    }

    @Override
    public boolean areContentsTheSame(@NonNull Object oldItem, @NonNull Object newItem) {
        // Compare Tab Contents
        if (oldItem instanceof TabStateArchivedEntity oldTab && newItem instanceof TabStateArchivedEntity newTab) {
            return oldTab.getCreationDate() == newTab.getCreationDate()
                    && Objects.equals(oldTab.getTitle(), newTab.getTitle())
                    && Objects.equals(oldTab.getUri(), newTab.getUri())
                    && Objects.equals(oldTab.getSessionState(), newTab.getSessionState())
                    && Objects.equals(oldTab.getIcon(), newTab.getIcon());
        }

        // Compare Header Contents
        if (oldItem instanceof TabStateHeaderArchivedEntity oldHeader && newItem instanceof TabStateHeaderArchivedEntity newHeader) {
            return Objects.equals(oldHeader.getTitle(), newHeader.getTitle());
        }

        return false;
    }
}