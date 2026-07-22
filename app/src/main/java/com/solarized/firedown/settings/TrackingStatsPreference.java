package com.solarized.firedown.settings;

import android.content.Context;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.LinearLayout;
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
 * Stats header for the Enhanced Tracking Protection settings screen —
 * what Gecko's persisted ETP database actually recorded (all-time / this
 * week / today + a per-category breakdown, plus the honest "recording
 * since" date), shown ABOVE the Standard / Strict / Custom controls that
 * decide what the protection does. A read-only, custom-layout
 * {@link Preference} ({@code preference_tracking_stats.xml}).
 *
 * <p>Follows the {@code CloudStatusPreference} pattern: the fragment
 * ({@code TrackingFragment}) queries {@code ContentBlockingController},
 * pushes the aggregates in via {@link #setStats}, and this stores them +
 * {@code notifyChanged()}s so {@link #onBindViewHolder} re-renders. This
 * is a DIFFERENT data source from the Home sheet's uBlock counts — Gecko
 * tracking-protection events (tracking cookies, fingerprinters,
 * cryptominers, social / bounce), never uBlock's filter-list requests —
 * and the two are never summed. The whole preference stays hidden until
 * the database has recorded at least one event, so a freshly enabled,
 * still-empty DB shows nothing rather than a bank of zeroes.</p>
 */
public class TrackingStatsPreference extends Preference {

    private boolean mHasData;
    private long mAllTime;
    private long mWeek;
    private long mToday;
    private long mSinceMs;
    private long mTrackers;
    private long mCookies;
    private long mFingerprinters;
    private long mCryptominers;
    private long mSocial;
    private long mBounce;

    public TrackingStatsPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
        setLayoutResource(R.layout.preference_tracking_stats);
        setSelectable(false);
        // Hidden until data arrives (and only if the DB is non-empty).
        setVisible(false);
    }

    /**
     * Push the queried ETP-database aggregates (category counts already
     * folded into display buckets). Reveals the header only when the
     * all-time total is &gt; 0.
     */
    public void setStats(long allTime, long week, long today, long sinceMs,
                         long trackers, long cookies, long fingerprinters,
                         long cryptominers, long social, long bounce) {
        mAllTime = allTime;
        mWeek = week;
        mToday = today;
        mSinceMs = sinceMs;
        mTrackers = trackers;
        mCookies = cookies;
        mFingerprinters = fingerprinters;
        mCryptominers = cryptominers;
        mSocial = social;
        mBounce = bounce;
        mHasData = allTime > 0L;
        setVisible(mHasData);
        notifyChanged();
    }

    @Override
    public void onBindViewHolder(@NonNull PreferenceViewHolder holder) {
        super.onBindViewHolder(holder);
        if (!mHasData) return;

        ((TextView) holder.findViewById(R.id.ts_alltime)).setText(formatCount(mAllTime));
        ((TextView) holder.findViewById(R.id.ts_week)).setText(formatCount(mWeek));
        ((TextView) holder.findViewById(R.id.ts_today)).setText(formatCount(mToday));

        TextView since = (TextView) holder.findViewById(R.id.ts_since);
        if (mSinceMs > 0L) {
            String date = DateFormat.getDateInstance(DateFormat.MEDIUM, Locale.getDefault())
                    .format(new Date(mSinceMs));
            since.setText(getContext().getString(R.string.tracking_stats_since, date));
            since.setVisibility(View.VISIBLE);
        } else {
            since.setVisibility(View.GONE);
        }

        View catHeader = holder.findViewById(R.id.ts_cat_header);
        LinearLayout breakdown = (LinearLayout) holder.findViewById(R.id.ts_breakdown);
        breakdown.removeAllViews();

        // Show the breakdown only when two or more categories have a count:
        // a lone category (e.g. only "Trackers") would just repeat the hero
        // total, adding a redundant row rather than information.
        long[] cats = {mTrackers, mCookies, mFingerprinters, mCryptominers, mSocial, mBounce};
        int nonZero = 0;
        for (long c : cats) {
            if (c > 0L) nonZero++;
        }
        if (nonZero < 2) {
            catHeader.setVisibility(View.GONE);
            breakdown.setVisibility(View.GONE);
            return;
        }

        catHeader.setVisibility(View.VISIBLE);
        breakdown.setVisibility(View.VISIBLE);
        LayoutInflater inflater = LayoutInflater.from(getContext());
        addRow(breakdown, inflater, R.string.trackers_info_etp_cat_trackers, mTrackers);
        addRow(breakdown, inflater, R.string.trackers_info_etp_cat_cookies, mCookies);
        addRow(breakdown, inflater, R.string.trackers_info_etp_cat_fingerprinters, mFingerprinters);
        addRow(breakdown, inflater, R.string.trackers_info_etp_cat_cryptominers, mCryptominers);
        addRow(breakdown, inflater, R.string.trackers_info_etp_cat_social, mSocial);
        addRow(breakdown, inflater, R.string.trackers_info_etp_cat_bounce, mBounce);
    }

    private void addRow(@NonNull LinearLayout container, @NonNull LayoutInflater inflater,
                        int labelRes, long count) {
        if (count <= 0L) return;
        View row = inflater.inflate(R.layout.item_tracking_stat_row, container, false);
        ((TextView) row.findViewById(R.id.tsr_label)).setText(labelRes);
        ((TextView) row.findViewById(R.id.tsr_count)).setText(formatCount(count));
        container.addView(row);
    }

    private static String formatCount(long n) {
        return NumberFormat.getInstance(Locale.getDefault()).format(n);
    }
}
