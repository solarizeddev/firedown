package com.solarized.firedown.settings;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.preference.Preference;
import androidx.preference.PreferenceViewHolder;

import com.solarized.firedown.R;

/**
 * Non-selectable info preference whose TITLE is painted the brand orange — used
 * for the sync FAQ questions (settings_sync_help). The answer (summary) keeps
 * its default muted colour.
 *
 * <p>The colour is forced in {@link #onBindViewHolder} <b>after</b> {@code super}
 * binds the row, because nothing earlier stuck: a {@code title} has no
 * colour attribute, a {@code ForegroundColorSpan} set on the title didn't render,
 * and a custom row layout's {@code textColor} was overridden during bind.
 * Setting it on the actual bound TextView post-super is the one place the
 * framework can't override afterwards.</p>
 */
public class FaqPreference extends Preference {

    public FaqPreference(@NonNull Context context, @NonNull AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    public void onBindViewHolder(@NonNull PreferenceViewHolder holder) {
        super.onBindViewHolder(holder);
        View titleView = holder.findViewById(android.R.id.title);
        if (titleView instanceof TextView) {
            ((TextView) titleView).setTextColor(
                    ContextCompat.getColor(getContext(), R.color.brand_orange));
        }
    }
}
