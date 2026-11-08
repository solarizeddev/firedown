package com.solarized.firedown.settings.ui;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.view.ViewCompat;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat.AccessibilityActionCompat;
import androidx.preference.PreferenceViewHolder;

import com.solarized.firedown.R;

/**
 * An add-on in Mozilla's list: a "+" in the trailing slot, so the row
 * says what a tap does (a tap anywhere on it does the same). While
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

    /**
     * What a screen reader says the row does: "double-tap to add Dark
     * Reader". The button is hidden from it (same action, one node).
     */
    public void setActionDescription(@Nullable CharSequence description) {
        mActionDescription = description;
    }

    @Override
    public void onBindViewHolder(@NonNull PreferenceViewHolder holder) {
        super.onBindViewHolder(holder);
        boolean shown = isSelectable() && !isBusy();
        ViewCompat.replaceAccessibilityAction(holder.itemView, AccessibilityActionCompat.ACTION_CLICK,
                shown ? mActionDescription : null, null);
        View action = holder.findViewById(R.id.preference_action);
        if (action == null) {
            return;
        }
        // The slot's -8dp end margin draws the button into the row's end
        // padding; its ripple must not be cut at the frame or the padding.
        View parent = (View) action.getParent();
        while (parent instanceof ViewGroup) {
            ((ViewGroup) parent).setClipChildren(false);
            if (parent == holder.itemView) {
                ((ViewGroup) parent).setClipToPadding(false);
                break;
            }
            parent = (View) parent.getParent();
        }
        action.setVisibility(shown ? View.VISIBLE : View.GONE);
        action.setOnClickListener(shown ? v -> {
            OnPreferenceClickListener listener = getOnPreferenceClickListener();
            if (listener != null) {
                listener.onPreferenceClick(this);
            }
        } : null);
    }
}
