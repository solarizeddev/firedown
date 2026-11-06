package com.solarized.firedown.settings.ui;

import android.content.Context;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.preference.PreferenceViewHolder;

import com.solarized.firedown.R;

/**
 * An add-on in Mozilla's list: an "Add" button in the trailing slot, so
 * the row says what a tap does (a tap anywhere on it does the same). While
 * the add-on installs the {@link BusyPreference} spinner takes the button's
 * place; a row that can't be added (a built-in id) shows no button.
 */
public class CatalogPreference extends BusyPreference {

    @Nullable
    private CharSequence mActionDescription;

    public CatalogPreference(@NonNull Context context) {
        super(context);
        setWidgetLayoutResource(R.layout.preference_widget_catalog);
    }

    /** What a screen reader says for the button: "Add Dark Reader", not "Add". */
    public void setActionDescription(@Nullable CharSequence description) {
        mActionDescription = description;
    }

    @Override
    public void onBindViewHolder(@NonNull PreferenceViewHolder holder) {
        super.onBindViewHolder(holder);
        View action = holder.findViewById(R.id.preference_action);
        if (action == null) {
            return;
        }
        boolean shown = isSelectable() && !isBusy();
        action.setVisibility(shown ? View.VISIBLE : View.GONE);
        action.setContentDescription(mActionDescription);
        action.setOnClickListener(shown ? v -> {
            OnPreferenceClickListener listener = getOnPreferenceClickListener();
            if (listener != null) {
                listener.onPreferenceClick(this);
            }
        } : null);
    }
}
