package com.solarized.firedown.settings;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.preference.Preference;
import androidx.preference.PreferenceViewHolder;

import com.solarized.firedown.R;
import com.solarized.firedown.settings.ui.FlatPreference;
import com.solarized.firedown.utils.Utils;

import java.text.NumberFormat;
import java.util.Locale;

/**
 * The hero of the blocked-stats screen (Settings → Privacy → Trackers and
 * ads blocked): three stat cards — blocked since install, the estimated data
 * saved, blocked today — fed by {@code BlockedStatsFragment} from
 * {@code GeckoUblockHelper}'s uBlock tallies via {@link #setStats}. A flat,
 * read-only custom-layout {@link Preference} ({@code preference_blocked_stats
 * .xml}), the CloudStatusPreference / TrackingStatusPreference pattern:
 * fragment observes, setter stores + notifyChanged, bind renders.
 *
 * <p>At a count of 0 it shows the one "Protection active" line instead of
 * three zeros, which would read as "protection is broken" rather than "a
 * fresh install, nothing browsed yet".</p>
 */
public class BlockedStatsPreference extends Preference implements FlatPreference {

    /** The per-blocked-request estimate behind "Data saved": Brave's
     *  published methodology (about 50 KB per blocked request). An estimate,
     *  said so by the screen's footnote. */
    private static final long AVG_BYTES_PER_BLOCKED_REQUEST = 50_000L;

    private long mTotal;
    private long mToday;

    public BlockedStatsPreference(@NonNull Context context, AttributeSet attrs) {
        super(context, attrs);
        setLayoutResource(R.layout.preference_blocked_stats);
        setSelectable(false);
        setPersistent(false);
    }

    /** Pushes the latest totals; a value that did not change is a no-op. */
    public void setStats(long total, long today) {
        if (mTotal == total && mToday == today) {
            return;
        }
        mTotal = total;
        mToday = today;
        notifyChanged();
    }

    @Override
    public void onBindViewHolder(@NonNull PreferenceViewHolder holder) {
        super.onBindViewHolder(holder);
        View idle = holder.findViewById(R.id.bs_idle);
        View stats = holder.findViewById(R.id.bs_stats);
        if (idle == null || stats == null) {
            return;
        }
        if (mTotal <= 0L) {
            idle.setVisibility(View.VISIBLE);
            stats.setVisibility(View.GONE);
            return;
        }
        idle.setVisibility(View.GONE);
        stats.setVisibility(View.VISIBLE);
        NumberFormat fmt = NumberFormat.getInstance(Locale.getDefault());
        TextView count = (TextView) holder.findViewById(R.id.bs_count);
        TextView saved = (TextView) holder.findViewById(R.id.bs_saved);
        TextView today = (TextView) holder.findViewById(R.id.bs_today);
        count.setText(fmt.format(mTotal));
        // "~": an estimate, said where the number is (the capture tiles'
        // estimated-size mark), not only in the footnote.
        saved.setText(getContext().getString(R.string.capture_size_estimate,
                Utils.readableFileSize(mTotal * AVG_BYTES_PER_BLOCKED_REQUEST)));
        today.setText(fmt.format(mToday));
    }
}
