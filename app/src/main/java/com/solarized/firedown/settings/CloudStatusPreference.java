package com.solarized.firedown.settings;

import android.content.Context;
import android.text.format.Formatter;
import android.util.AttributeSet;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.preference.Preference;
import androidx.preference.PreferenceViewHolder;

import com.solarized.firedown.R;
import com.solarized.firedown.sync.StorageApiClient;

import java.text.DateFormatSymbols;
import java.util.Locale;

/**
 * The Cloud Backup status hero (custom-layout {@link Preference}, styled by
 * {@code preference_cloud_status.xml}). Shows, top to bottom: a big balance, a
 * one-time-credit note, a live status line, and a facts line — bound from the
 * {@link StorageApiClient.Quota}, the manifest usage, and the WorkManager
 * transfer state that {@link CloudBackupSettingsFragment} feeds in.
 *
 * <p>States: not-set-up → a single invitation line; a running transfer →
 * "Transfer in progress…"; metered + funded → balance + "Covered until ~Month
 * Year"; metered + grace → the read-only/top-up line; the current UNMETERED phase
 * → the backed-up facts + a "free during the beta" note (no balance/runout, since
 * there's nothing to meter yet).
 */
public class CloudStatusPreference extends Preference {

    private boolean mSetUp;
    private boolean mActive;
    private StorageApiClient.Quota mQuota; // null = unknown / offline / not set up
    private int mFileCount = -1;
    private long mTotalBytes = -1;

    public CloudStatusPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
        setLayoutResource(R.layout.preference_cloud_status);
        setSelectable(false);
    }

    public void setSetUp(boolean setUp) {
        if (mSetUp != setUp) {
            mSetUp = setUp;
            notifyChanged();
        }
    }

    /** A transfer (upload/restore) is running — overrides the status line. */
    public void setActive(boolean active) {
        if (mActive != active) {
            mActive = active;
            notifyChanged();
        }
    }

    public void setQuota(StorageApiClient.Quota quota) {
        mQuota = quota;
        notifyChanged();
    }

    /** Backed-up usage from the manifest ({@code -1} = unknown). */
    public void setUsage(int fileCount, long totalBytes) {
        mFileCount = fileCount;
        mTotalBytes = totalBytes;
        notifyChanged();
    }

    @Override
    public void onBindViewHolder(@NonNull PreferenceViewHolder holder) {
        super.onBindViewHolder(holder);
        Context ctx = getContext();
        TextView balance = (TextView) holder.findViewById(R.id.cb_balance);
        TextView label = (TextView) holder.findViewById(R.id.cb_balance_label);
        TextView note = (TextView) holder.findViewById(R.id.cb_note);
        TextView until = (TextView) holder.findViewById(R.id.cb_until);
        TextView facts = (TextView) holder.findViewById(R.id.cb_facts);

        boolean metered = mQuota != null && mQuota.metered;
        boolean showBalance = mSetUp && metered;

        balance.setVisibility(showBalance ? View.VISIBLE : View.GONE);
        label.setVisibility(showBalance ? View.VISIBLE : View.GONE);
        if (showBalance) {
            balance.setText(formatGbMonths(mQuota.balanceGbMonths));
            label.setText(R.string.cloud_status_gb_label);
        }

        // Note line: metered = "no auto-renew"; unmetered set up = "free during beta".
        String noteText = null;
        if (mSetUp) {
            noteText = metered
                    ? ctx.getString(R.string.cloud_status_oneoff)
                    : ctx.getString(R.string.cloud_status_free_beta);
        }
        note.setText(noteText);
        note.setVisibility(noteText != null ? View.VISIBLE : View.GONE);

        // Status line.
        until.setText(statusLine(ctx, metered));

        // Facts: "N files · X backed up" — only once set up and usage is known.
        if (mSetUp && mFileCount >= 0 && mTotalBytes >= 0) {
            String files = ctx.getResources().getQuantityString(
                    R.plurals.settings_cloud_backup_file_count, mFileCount, mFileCount);
            String size = Formatter.formatShortFileSize(ctx, mTotalBytes);
            facts.setText(ctx.getString(R.string.cloud_status_facts, files, size));
            facts.setVisibility(View.VISIBLE);
        } else {
            facts.setVisibility(View.GONE);
        }
    }

    private String statusLine(Context ctx, boolean metered) {
        if (!mSetUp) {
            return ctx.getString(R.string.settings_cloud_backup_not_set_up);
        }
        if (mActive) {
            return ctx.getString(R.string.settings_cloud_backup_active);
        }
        if (metered) {
            if (mQuota.readOnly) {
                String when = monthYear(mQuota.graceUntil);
                return when != null
                        ? ctx.getString(R.string.cloud_status_grace, when)
                        : ctx.getString(R.string.cloud_status_grace_nodate);
            }
            String when = monthYear(mQuota.projectedRunoutAt);
            if (when != null) {
                return ctx.getString(R.string.cloud_status_covered_until, when);
            }
            return ctx.getString(R.string.cloud_status_covered_ongoing);
        }
        // Unmetered / quota unavailable but set up.
        return ctx.getString(R.string.cloud_status_backups_on);
    }

    /** A GB-months balance, trimming a trailing ".0" (100.0 → "100"). */
    private static String formatGbMonths(double v) {
        if (v == Math.rint(v)) {
            return Long.toString(Math.round(v));
        }
        return String.format(Locale.getDefault(), "%.1f", v);
    }

    /** RFC3339 (e.g. "2027-03-15T…") → a localized "Mar 2027", or null on any
     *  parse failure (so the caller can fall back to a date-less line). */
    private static String monthYear(String rfc3339) {
        if (rfc3339 == null || rfc3339.length() < 7) {
            return null;
        }
        try {
            int year = Integer.parseInt(rfc3339.substring(0, 4));
            int month = Integer.parseInt(rfc3339.substring(5, 7));
            if (month < 1 || month > 12) {
                return null;
            }
            String[] months = new DateFormatSymbols().getShortMonths();
            return months[month - 1] + " " + year;
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
