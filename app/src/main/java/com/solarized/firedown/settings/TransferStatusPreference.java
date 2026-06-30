package com.solarized.firedown.settings;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.preference.Preference;
import androidx.preference.PreferenceViewHolder;

import com.solarized.firedown.R;

/**
 * The Cloud Backup "status" row. Identical to a plain {@link Preference} (icon +
 * title + live summary), but reveals a small indeterminate progress bar in its
 * widget slot while a transfer (upload/restore) is in flight — so an in-progress
 * status reads as active work, not just text. Transfers are indeterminate (the
 * workers post an indeterminate notification), hence an indeterminate bar.
 */
public class TransferStatusPreference extends Preference {

    private boolean mActive;

    public TransferStatusPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
        setWidgetLayoutResource(R.layout.preference_widget_progress);
    }

    /** Shows/hides the progress bar (called from the WorkManager observer). */
    public void setActive(boolean active) {
        if (mActive != active) {
            mActive = active;
            notifyChanged();
        }
    }

    @Override
    public void onBindViewHolder(@NonNull PreferenceViewHolder holder) {
        super.onBindViewHolder(holder);
        View bar = holder.findViewById(R.id.transfer_progress);
        if (bar != null) {
            bar.setVisibility(mActive ? View.VISIBLE : View.GONE);
        }
    }
}
