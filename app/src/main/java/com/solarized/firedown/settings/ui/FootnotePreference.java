package com.solarized.firedown.settings.ui;

import android.content.Context;
import android.util.AttributeSet;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.preference.Preference;

/**
 * A summary-only disclosure line under a screen's controls (the translations
 * Mozilla-download notice, the tab archive's retention note): not a control,
 * so it sits flat on the page below the card group rather than wearing a
 * card of its own — the Android Settings footer shape. Never selectable.
 * Its text keeps the stock row's 72dp start (16dp padding + the 56dp icon
 * frame), which is exactly where a card row's title starts (16dp gutter +
 * 16dp card padding + the 40dp card icon frame), so footnote and rows align.
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
}
