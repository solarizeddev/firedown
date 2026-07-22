package com.solarized.firedown.settings;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.preference.Preference;
import androidx.preference.PreferenceViewHolder;

import com.solarized.firedown.R;

import java.text.DateFormat;
import java.text.NumberFormat;
import java.util.Date;
import java.util.Locale;

/**
 * One quiet status row at the top of the Enhanced Tracking Protection
 * screen — the count from Gecko's persisted ETP database ("N trackers
 * blocked" + the honest "recording since" date), above the
 * Standard / Strict / Custom control. A read-only custom-layout
 * {@link Preference} ({@code preference_tracking_status.xml}), fed by
 * {@code TrackingFragment} via {@link #setStatus} (the CloudStatusPreference
 * pattern: fragment queries, setter stores + notifyChanged, bind renders).
 *
 * <p>Deliberately a single line, not a stats dashboard: the ETP count is
 * shown in exactly ONE place, on the screen that's about tracking
 * protection, so it never sits next to (and lose to) uBlock's larger
 * filter-list number on the trackers sheet. Hidden until the DB has
 * recorded at least one event.</p>
 */
public class TrackingStatusPreference extends Preference {

    private boolean mHasData;
    private long mCount;
    private long mSinceMs;

    public TrackingStatusPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
        setLayoutResource(R.layout.preference_tracking_status);
        setSelectable(false);
        setVisible(false);
    }

    /** Push the queried count + earliest-recorded date. Reveals the row
     *  only when the count is &gt; 0 (an empty DB shows nothing). */
    public void setStatus(long count, long sinceMs) {
        mCount = count;
        mSinceMs = sinceMs;
        mHasData = count > 0L;
        setVisible(mHasData);
        notifyChanged();
    }

    @Override
    public void onBindViewHolder(@NonNull PreferenceViewHolder holder) {
        super.onBindViewHolder(holder);
        if (!mHasData) return;

        TextView count = (TextView) holder.findViewById(R.id.tps_count);
        count.setText(getContext().getString(R.string.tracking_status_blocked,
                NumberFormat.getInstance(Locale.getDefault()).format(mCount)));

        TextView since = (TextView) holder.findViewById(R.id.tps_since);
        if (mSinceMs > 0L) {
            String date = DateFormat.getDateInstance(DateFormat.MEDIUM, Locale.getDefault())
                    .format(new Date(mSinceMs));
            since.setText(getContext().getString(R.string.tracking_status_since, date));
            since.setVisibility(View.VISIBLE);
        } else {
            since.setVisibility(View.GONE);
        }
    }
}
