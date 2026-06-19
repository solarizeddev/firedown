package com.solarized.firedown.ui.diffs;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;

import com.solarized.firedown.data.entity.AutoCompleteEntity;
import java.util.Objects;

public class SearchDiffCallback extends DiffUtil.ItemCallback<AutoCompleteEntity> {

    @Override
    public boolean areItemsTheSame(@NonNull AutoCompleteEntity oldItem, @NonNull AutoCompleteEntity newItem) {
        // Type is part of identity: a history row and a search/tab/bookmark row
        // can share a uid (both hash a string), and treating them as the same
        // item let a recycled holder keep the wrong glyph across a list change.
        return oldItem.getId() == newItem.getId() && oldItem.getType() == newItem.getType();
    }

    @Override
    public boolean areContentsTheSame(@NonNull AutoCompleteEntity oldItem, @NonNull AutoCompleteEntity newItem) {
        // Must compare EVERYTHING that changes the bound row, or DiffUtil skips
        // the rebind and the holder shows a stale icon — the "mangled icons"
        // when flipping between typed search (search/results/tab/bookmark/history)
        // and the empty-focus most-visited list. mostVisited drives the trailing
        // glyph (flame vs clock) and drawableId the RESULTS engine icon; both
        // were missing here, so a same-title/url row never rebound.
        return oldItem.getType() == newItem.getType()
                && oldItem.isMostVisited() == newItem.isMostVisited()
                && oldItem.getDrawableId() == newItem.getDrawableId()
                && Objects.equals(oldItem.getTitle(), newItem.getTitle())
                && Objects.equals(oldItem.getIcon(), newItem.getIcon())
                && Objects.equals(oldItem.getSubText(), newItem.getSubText());
    }
}