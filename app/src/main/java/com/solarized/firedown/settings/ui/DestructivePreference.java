package com.solarized.firedown.settings.ui;

import android.content.Context;
import android.content.res.ColorStateList;
import android.util.AttributeSet;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.preference.Preference;
import androidx.preference.PreferenceViewHolder;

import com.solarized.firedown.R;

/**
 * A row that destroys something (an add-on's Remove): its title and glyph
 * in {@code @color/destructive_ink}, so it reads as the one action on the
 * page that cannot be undone rather than another plain row. Deep red in
 * light theme (the brand coral is 2.3:1 as text there), the coral in dark.
 */
public class DestructivePreference extends Preference {

    public DestructivePreference(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
    }

    public DestructivePreference(@NonNull Context context) {
        super(context);
    }

    @Override
    public void onBindViewHolder(@NonNull PreferenceViewHolder holder) {
        super.onBindViewHolder(holder);
        int ink = ContextCompat.getColor(getContext(), R.color.destructive_ink);
        View title = holder.findViewById(android.R.id.title);
        if (title instanceof TextView) {
            ((TextView) title).setTextColor(ink);
        }
        View icon = holder.findViewById(android.R.id.icon);
        if (icon instanceof ImageView) {
            ((ImageView) icon).setImageTintList(ColorStateList.valueOf(ink));
        }
    }
}
