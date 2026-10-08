package com.solarized.firedown.settings.ui;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.preference.Preference;
import androidx.preference.PreferenceViewHolder;

import com.solarized.firedown.R;

/**
 * A summary-only disclosure line under a screen's controls (the translations
 * Mozilla-download notice, the tab archive's retention note): not a control,
 * so it sits flat on the page below the card group rather than wearing a
 * card of its own — the Android Settings footer shape. Never selectable.
 * Its text starts 72dp from the screen edge (16dp padding + the stock 56dp
 * icon frame), exactly where a card row's title starts (16dp gutter + 16dp
 * card padding + the 40dp card icon frame), so footnote and rows align. The
 * padding is set here because the stock row's own start padding
 * (?android:listPreferredItemPaddingStart) measured ~12dp on-device.
 */
public class FootnotePreference extends Preference implements FlatPreference {

    public FootnotePreference(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        setSelectable(false);
    }

    public FootnotePreference(@NonNull Context context) {
        super(context);
        setSelectable(false);
    }

    @Override
    public void onBindViewHolder(@NonNull PreferenceViewHolder holder) {
        super.onBindViewHolder(holder);
        View item = holder.itemView;
        int padding = item.getResources().getDimensionPixelSize(R.dimen.settings_card_row_padding);
        item.setPaddingRelative(padding, item.getPaddingTop(), padding, item.getPaddingBottom());
    }
}
