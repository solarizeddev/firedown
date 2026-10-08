package com.solarized.firedown.ui.diffs;

import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.DiffUtil;

import com.solarized.firedown.data.entity.GeckoStateEntity;
import java.util.Objects;

public class GeckoStateDiffCallback extends DiffUtil.ItemCallback<GeckoStateEntity> {

    public static final String PAYLOAD_THUMB    = "thumb";
    public static final String PAYLOAD_ACTIVE   = "active";
    public static final String PAYLOAD_TITLE    = "title";
    public static final String PAYLOAD_ICON     = "icon";

    @Override
    public boolean areItemsTheSame(@NonNull GeckoStateEntity oldItem, @NonNull GeckoStateEntity newItem) {
        return oldItem.getId() == newItem.getId();
    }

    @Override
    public boolean areContentsTheSame(@NonNull GeckoStateEntity oldItem, @NonNull GeckoStateEntity newItem) {
        return oldItem.isActive() == newItem.isActive()
                && oldItem.getThumbVersion() == newItem.getThumbVersion() // the store's version of the screenshot, stamped per snapshot
                && Objects.equals(oldItem.getTitle(), newItem.getTitle())
                && Objects.equals(oldItem.getUri(), newItem.getUri())
                && Objects.equals(oldItem.getIcon(), newItem.getIcon());
    }

    @Nullable
    @Override
    public Object getChangePayload(@NonNull GeckoStateEntity oldItem, @NonNull GeckoStateEntity newItem) {
        Bundle diff = new Bundle();
        if (oldItem.getThumbVersion() != newItem.getThumbVersion()) {
            // A boolean flag just to signal "rebind the thumb": the adapter
            // reads the screenshot from the TabThumbnailStore by tab id.
            diff.putBoolean(PAYLOAD_THUMB, true);
        }
        if (oldItem.isActive() != newItem.isActive()) {
            diff.putBoolean(PAYLOAD_ACTIVE, newItem.isActive());
        }
        if (!Objects.equals(oldItem.getTitle(), newItem.getTitle())) {
            diff.putString(PAYLOAD_TITLE, newItem.getTitle());
        }
        if (!Objects.equals(oldItem.getIcon(), newItem.getIcon())) {
            diff.putString(PAYLOAD_ICON, newItem.getIcon());
        }
        return diff.isEmpty() ? null : diff;
    }
}
