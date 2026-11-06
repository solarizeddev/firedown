package com.solarized.firedown.settings.ui;

import android.content.Context;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.preference.Preference;
import androidx.preference.PreferenceViewHolder;

import com.solarized.firedown.R;

/**
 * A settings row whose action takes a while (an add-on installing): while
 * busy it shows a spinner in its trailing widget slot, and its click
 * listener checks {@link #isBusy()} so a second tap can't start the same
 * thing twice (Preference's onClick runs BEFORE the listener, so it can't
 * stop it). The state changes in
 * place ({@link #notifyChanged()}), so the row keeps its icon and place
 * instead of being rebuilt.
 */
public class BusyPreference extends Preference {

    /** The stock row's own summary limit (androidx preference.xml). */
    private static final int DEFAULT_SUMMARY_LINES = 10;

    private boolean mBusy;
    private int mSummaryMaxLines = DEFAULT_SUMMARY_LINES;

    public BusyPreference(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        setWidgetLayoutResource(R.layout.preference_widget_busy);
    }

    public BusyPreference(@NonNull Context context) {
        super(context);
        setWidgetLayoutResource(R.layout.preference_widget_busy);
    }

    public boolean isBusy() {
        return mBusy;
    }

    public void setBusy(boolean busy) {
        if (mBusy == busy) {
            return;
        }
        mBusy = busy;
        notifyChanged();
    }

    /**
     * Clamps the summary, ended with "…": a catalog row shows the start of
     * the add-on's description, not all of it. Set before the row is shown;
     * rows are recycled across BusyPreferences, so every bind sets it.
     */
    public void setSummaryMaxLines(int lines) {
        mSummaryMaxLines = lines;
    }

    @Override
    public void onBindViewHolder(@NonNull PreferenceViewHolder holder) {
        super.onBindViewHolder(holder);
        TextView summary = (TextView) holder.findViewById(android.R.id.summary);
        if (summary != null) {
            summary.setMaxLines(mSummaryMaxLines);
            summary.setEllipsize(TextUtils.TruncateAt.END);
        }
        View spinner = holder.findViewById(R.id.preference_busy);
        if (spinner != null) {
            spinner.setVisibility(mBusy ? View.VISIBLE : View.GONE);
        }
    }
}
