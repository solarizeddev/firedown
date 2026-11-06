package com.solarized.firedown.settings.ui;

import android.content.Context;
import android.text.Layout;
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
    private int mSummaryLead;

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
        setSummaryMaxLines(lines, 0);
    }

    /**
     * The same, for a summary that starts with a lead of {@code leadLength}
     * characters (the catalog's "★ 4.6 · 1.2M users" line): the lead keeps
     * every line it takes on screen and {@code lines} more follow it. A
     * lead wraps where the trailing button is wide ("Добавить", "Toevoegen"
     * on a 360dp phone) or the font is large, and a fixed count would take
     * its second line out of the description's two.
     */
    public void setSummaryMaxLines(int lines, int leadLength) {
        mSummaryMaxLines = lines;
        mSummaryLead = leadLength;
    }

    @Override
    public void onBindViewHolder(@NonNull PreferenceViewHolder holder) {
        super.onBindViewHolder(holder);
        TextView summary = (TextView) holder.findViewById(android.R.id.summary);
        if (summary != null) {
            summary.setEllipsize(TextUtils.TruncateAt.END);
            LeadClamp clamp = (LeadClamp) summary.getTag(R.id.summary_lead_clamp);
            if (mSummaryLead > 0) {
                if (clamp == null) {
                    clamp = new LeadClamp(summary);
                    summary.setTag(R.id.summary_lead_clamp, clamp);
                    summary.addOnLayoutChangeListener(clamp);
                }
                clamp.set(mSummaryMaxLines, mSummaryLead);
                // One lead line is the usual case; the first layout corrects it.
                summary.setMaxLines(mSummaryMaxLines + 1);
            } else {
                if (clamp != null) {
                    clamp.set(mSummaryMaxLines, 0);
                }
                summary.setMaxLines(mSummaryMaxLines);
            }
        }
        View spinner = holder.findViewById(R.id.preference_busy);
        if (spinner != null) {
            spinner.setVisibility(mBusy ? View.VISIBLE : View.GONE);
        }
    }

    /**
     * Sets the summary's line limit from its laid-out lead: the lines the
     * lead's last character sits on, plus the lines after it. Runs after
     * every layout of the view (a rebind's new text lays it out again, the
     * same size or not), and changes the limit only when it differs, in a
     * post so the view isn't asked for a new layout inside this one.
     */
    private static final class LeadClamp implements View.OnLayoutChangeListener {
        private final TextView mView;
        private int mLines;
        private int mLead;

        LeadClamp(@NonNull TextView view) {
            mView = view;
        }

        void set(int lines, int lead) {
            mLines = lines;
            mLead = lead;
        }

        @Override
        public void onLayoutChange(View v, int left, int top, int right, int bottom,
                                   int oldLeft, int oldTop, int oldRight, int oldBottom) {
            if (mLead <= 0) {
                return;
            }
            Layout layout = mView.getLayout();
            CharSequence text = mView.getText();
            if (layout == null || text == null || mLead > text.length()) {
                return;
            }
            int wanted = layout.getLineForOffset(mLead - 1) + 1 + mLines;
            if (mView.getMaxLines() != wanted) {
                int lead = mLead;
                mView.post(() -> {
                    if (mLead == lead) {
                        mView.setMaxLines(wanted);
                    }
                });
            }
        }
    }
}
