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
 * A settings row whose action takes a while (an add-on installing): while
 * busy it shows a spinner in its trailing widget slot, and its click
 * listener checks {@link #isBusy()} so a second tap can't start the same
 * thing twice (Preference's onClick runs BEFORE the listener, so it can't
 * stop it). The state changes in
 * place ({@link #notifyChanged()}), so the row keeps its icon and place
 * instead of being rebuilt.
 */
public class BusyPreference extends Preference {

    private boolean mBusy;

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

    @Override
    public void onBindViewHolder(@NonNull PreferenceViewHolder holder) {
        super.onBindViewHolder(holder);
        View spinner = holder.findViewById(R.id.preference_busy);
        if (spinner != null) {
            spinner.setVisibility(mBusy ? View.VISIBLE : View.GONE);
        }
    }
}
