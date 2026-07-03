package com.solarized.firedown.settings;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.text.format.Formatter;
import android.util.AttributeSet;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.ColorUtils;
import androidx.preference.Preference;
import androidx.preference.PreferenceViewHolder;

import com.google.android.material.color.MaterialColors;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.solarized.firedown.R;
import com.solarized.firedown.sync.StorageApiClient;

import java.text.DateFormatSymbols;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.Locale;

/**
 * The Cloud Backup status hero (custom-layout {@link Preference}, styled by
 * {@code preference_cloud_status.xml}). One card, four states:
 *
 * <ul>
 *   <li><b>Unmetered beta</b> — big backed-up size, usage bar vs the included
 *       cap ({@code Quota.bytesLimit}), "Free during the beta" chip.</li>
 *   <li><b>Metered, funded</b> — usage bar vs the purchased plan's size cap +
 *       a month-tick time runway to the projected runout. The plan shape
 *       ("Up to 50 GB · 1 year") comes from {@link #setPlan} — stored
 *       CLIENT-side at purchase, because the server deliberately only knows the
 *       anonymous GB-month balance; unknown (legacy purchase / recovery-code
 *       restore on a new device) degrades to the covered-until line + a raw
 *       balance chip, no ticks.</li>
 *   <li><b>Grace / read-only</b> — amber data ink + the top-up-by deadline
 *       (amber, never colorError red — the app's attention convention).</li>
 *   <li><b>Not set up</b> — a dashed empty state pointing at the download
 *       sheet's ⋮ backup action.</li>
 * </ul>
 *
 * The bars are the only accent ink; a running transfer swaps the caption for
 * the live "Transfer in progress…" line.
 */
public class CloudStatusPreference extends Preference {

    /** Never let a non-empty usage render a 0-width bar — a sliver of ink keeps
     *  the meter reading as alive rather than broken. */
    private static final int MIN_BAR_PERCENT = 2;
    private static final long GB = 1_000_000_000L;
    /** Mean Gregorian month, for the ≈ months-left runway arithmetic. */
    private static final double DAYS_PER_MONTH = 30.44;

    private boolean mSetUp;
    private boolean mActive;
    /** Whether a recovery code exists on this device — drives the onboarding
     *  steps' check-offs in the not-set-up empty state. */
    private boolean mHasKey;
    private StorageApiClient.Quota mQuota; // null = unknown / offline / not set up
    private int mFileCount = -1;
    private long mTotalBytes = -1;
    private int mPlanSizeGb;
    private int mPlanMonths;

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

    /** A recovery code exists — checks off onboarding step ① in the empty state. */
    public void setHasKey(boolean hasKey) {
        if (mHasKey != hasKey) {
            mHasKey = hasKey;
            notifyChanged();
        }
    }

    /** A transfer (upload/restore) is running — overrides the caption line. */
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

    /** The last purchased plan shape (0/0 = unknown) — see the class doc. */
    public void setPlan(int sizeGb, int durationMonths) {
        if (mPlanSizeGb != sizeGb || mPlanMonths != durationMonths) {
            mPlanSizeGb = sizeGb;
            mPlanMonths = durationMonths;
            notifyChanged();
        }
    }

    @Override
    public void onBindViewHolder(@NonNull PreferenceViewHolder holder) {
        super.onBindViewHolder(holder);
        Context ctx = getContext();
        View hero = holder.findViewById(R.id.cb_hero);
        View empty = holder.findViewById(R.id.cb_empty);
        if (hero == null || empty == null) {
            return;
        }
        if (!mSetUp) {
            hero.setVisibility(View.GONE);
            empty.setVisibility(View.VISIBLE);
            bindOnboardingSteps(ctx, holder);
            return;
        }
        hero.setVisibility(View.VISIBLE);
        empty.setVisibility(View.GONE);

        TextView big = (TextView) holder.findViewById(R.id.cb_big);
        TextView bigUnit = (TextView) holder.findViewById(R.id.cb_big_unit);
        TextView chip = (TextView) holder.findViewById(R.id.cb_chip);
        LinearProgressIndicator bar = (LinearProgressIndicator) holder.findViewById(R.id.cb_bar);
        TextView caption = (TextView) holder.findViewById(R.id.cb_caption);
        TextView alert = (TextView) holder.findViewById(R.id.cb_alert);
        View runway = holder.findViewById(R.id.cb_runway);
        View runwayLabels = holder.findViewById(R.id.cb_runway_labels);
        TextView runwayCovered = (TextView) holder.findViewById(R.id.cb_runway_covered);
        TextView runwayLeft = (TextView) holder.findViewById(R.id.cb_runway_left);
        CloudRunwayView ticks = (CloudRunwayView) holder.findViewById(R.id.cb_ticks);

        boolean metered = mQuota != null && mQuota.metered;
        boolean grace = metered && mQuota.readOnly;
        boolean planKnown = mPlanSizeGb > 0 && mPlanMonths > 0;
        int ink = ContextCompat.getColor(ctx, grace ? R.color.backup_warning : R.color.brand_orange);

        // Headline: how much of the user's stuff is safe.
        big.setText(mTotalBytes >= 0 ? Formatter.formatShortFileSize(ctx, mTotalBytes) : "—");
        bigUnit.setText(R.string.cloud_status_backed_up);

        bindChip(ctx, chip, metered, grace, planKnown);

        // Usage bar — needs a denominator: the plan's size cap (metered) or the
        // included cap (unmetered). Preference rows recycle, so every state is
        // set on every bind.
        long capBytes = -1;
        if (metered) {
            if (planKnown) {
                capBytes = mPlanSizeGb * GB;
            }
        } else if (mQuota != null && mQuota.bytesLimit > 0) {
            capBytes = mQuota.bytesLimit;
        }
        int percent = -1;
        if (capBytes > 0 && mTotalBytes >= 0) {
            percent = (int) Math.min(100, Math.round(mTotalBytes * 100.0 / capBytes));
        }
        if (percent >= 0) {
            bar.setVisibility(View.VISIBLE);
            bar.setIndicatorColor(ink);
            bar.setTrackColor(MaterialColors.getColor(bar,
                    com.google.android.material.R.attr.colorSurfaceVariant));
            bar.setProgress(Math.max(percent, MIN_BAR_PERCENT));
        } else {
            bar.setVisibility(View.GONE);
        }

        caption.setText(captionText(ctx, metered, planKnown, percent, capBytes));

        bindRunway(ctx, alert, runway, runwayLabels, runwayCovered, runwayLeft, ticks,
                metered, grace, planKnown, ink);
    }

    /**
     * Coverage months behind the chip/runway: the SERVER's remaining balance ÷
     * the plan's size cap when both are known, else the locally-stored plan
     * months. Purchases ACCUMULATE server-side (every redeem adds GB-months to
     * one balance), so after a top-up the stored "size × months" shape
     * understates what the account actually holds — on-device the chip read
     * "Up to 100 GB · 5 months" against a 5410 GB-month balance (~54 months at
     * that cap). The balance is ground truth; the stored months only cover the
     * offline/quota-unknown render.
     */
    private int coverageMonths(boolean metered) {
        if (metered && mPlanSizeGb > 0 && mQuota.balanceGbMonths > 0) {
            return (int) Math.max(1, Math.round(mQuota.balanceGbMonths / mPlanSizeGb));
        }
        return mPlanMonths;
    }

    /**
     * The not-set-up empty state is the onboarding ROADMAP: ① recovery code →
     * ② storage credit → ③ first backup. A DONE step gets a "✓" prefix and
     * muted ink; the next pending step keeps full-contrast ink so it reads as
     * "you are here". Step ② counts as done once a purchase is known (local
     * plan shape or a metered balance); ③ is by definition pending while the
     * empty state shows (a committed file flips the whole card to the hero).
     */
    private void bindOnboardingSteps(Context ctx, PreferenceViewHolder holder) {
        TextView stepCode = (TextView) holder.findViewById(R.id.cb_step_code);
        TextView stepCredit = (TextView) holder.findViewById(R.id.cb_step_credit);
        TextView stepBackup = (TextView) holder.findViewById(R.id.cb_step_backup);
        if (stepCode == null || stepCredit == null || stepBackup == null) {
            return;
        }
        boolean codeDone = mHasKey;
        // Step ② — SERVER truth wins when the quota is loaded: a metered
        // account whose balance ran out (and was reaped back to this empty
        // state) must show "Add storage credit" as the CURRENT step again, but
        // the stale local plan prefs from the old purchase would keep it
        // wrongly checked. The prefs only back the offline/quota-unknown render.
        boolean creditDone;
        if (mQuota != null && mQuota.metered) {
            creditDone = mQuota.balanceGbMonths > 0;
        } else {
            creditDone = mPlanSizeGb > 0;
        }
        bindStep(ctx, stepCode, 1, ctx.getString(R.string.settings_sync_create_title),
                codeDone, !codeDone);
        bindStep(ctx, stepCredit, 2, ctx.getString(R.string.buy_credit_title),
                creditDone, codeDone && !creditDone);
        bindStep(ctx, stepBackup, 3, ctx.getString(R.string.cloud_status_empty_body),
                false, codeDone && creditDone);
    }

    /** One roadmap line: "✓ text" muted when done, "N · text" full-contrast
     *  when it's the current step, "N · text" muted when still ahead. */
    private static void bindStep(Context ctx, TextView view, int number, String text,
                                 boolean done, boolean current) {
        String prefix = done ? "✓  " : number + " ·  ";
        view.setText(prefix + text);
        int ink = current
                ? MaterialColors.getColor(view,
                        com.google.android.material.R.attr.colorOnSurface)
                : MaterialColors.getColor(view,
                        com.google.android.material.R.attr.colorOnSurfaceVariant);
        view.setTextColor(ink);
        view.setTypeface(null, current ? Typeface.BOLD : Typeface.NORMAL);
    }

    /** The context chip: read-only (grace) / the purchased plan / raw balance /
     *  the free beta. Warn styling is amber text over amber-at-18%; the neutral
     *  styling is re-applied explicitly because rows recycle. */
    private void bindChip(Context ctx, TextView chip, boolean metered, boolean grace,
                          boolean planKnown) {
        String text;
        if (grace) {
            text = ctx.getString(R.string.cloud_status_chip_readonly);
        } else if (metered) {
            if (planKnown) {
                text = ctx.getString(R.string.buy_credit_plan_size, mPlanSizeGb)
                        + " · " + formatDuration(ctx, coverageMonths(true));
            } else {
                text = formatGbMonths(mQuota.balanceGbMonths) + " "
                        + ctx.getString(R.string.cloud_status_gb_label);
            }
        } else {
            text = ctx.getString(R.string.cloud_status_chip_beta);
        }
        chip.setText(text);
        if (grace) {
            int warn = ContextCompat.getColor(ctx, R.color.backup_warning);
            chip.setTextColor(warn);
            chip.setBackgroundTintList(ColorStateList.valueOf(
                    ColorUtils.setAlphaComponent(warn, 46)));
        } else {
            chip.setTextColor(MaterialColors.getColor(chip,
                    com.google.android.material.R.attr.colorOnSurfaceVariant));
            chip.setBackgroundTintList(null);
        }
    }

    private String captionText(Context ctx, boolean metered, boolean planKnown,
                               int percent, long capBytes) {
        if (mActive) {
            return ctx.getString(R.string.settings_cloud_backup_active);
        }
        if (mFileCount < 0 || mTotalBytes < 0) {
            return ctx.getString(R.string.settings_cloud_backup_usage_unavailable);
        }
        String files = ctx.getResources().getQuantityString(
                R.plurals.settings_cloud_backup_file_count, mFileCount, mFileCount);
        if (!metered && capBytes > 0) {
            return ctx.getString(R.string.cloud_status_caption_beta, files,
                    Formatter.formatShortFileSize(ctx, capBytes));
        }
        if (metered && planKnown && percent >= 0) {
            // A small real usage (721 MB of 200 GB) rounds to 0 — "0% of your
            // plan" next to "3 files" reads as broken, so show "<1" instead.
            // The placeholder is %2$s in every locale for exactly this.
            String pct = (percent == 0 && mTotalBytes > 0) ? "<1" : String.valueOf(percent);
            return ctx.getString(R.string.cloud_status_caption_plan, files, pct);
        }
        return ctx.getString(R.string.cloud_status_facts, files,
                Formatter.formatShortFileSize(ctx, mTotalBytes));
    }

    /** Grace: the top-up-by alert + a runway drained to its last (amber) tick.
     *  Funded metered: "Covered until ~Mar 2027", with "≈ N of M months left" +
     *  month ticks only when the plan shape is known. Unmetered: no runway. */
    private void bindRunway(Context ctx, TextView alert, View runway, View runwayLabels,
                            TextView runwayCovered, TextView runwayLeft, CloudRunwayView ticks,
                            boolean metered, boolean grace, boolean planKnown, int ink) {
        alert.setVisibility(View.GONE);
        runway.setVisibility(View.GONE);
        runwayLabels.setVisibility(View.GONE);
        runwayLeft.setVisibility(View.GONE);
        ticks.setVisibility(View.GONE);
        if (grace) {
            String deadline = mediumDate(mQuota.graceUntil);
            alert.setText(deadline != null
                    ? ctx.getString(R.string.cloud_status_grace_alert, deadline)
                    : ctx.getString(R.string.cloud_status_grace_nodate));
            alert.setVisibility(View.VISIBLE);
            runway.setVisibility(View.VISIBLE);
            ticks.setVisibility(View.VISIBLE);
            ticks.setTicks(planKnown ? mPlanMonths : 12, 1, ink);
            return;
        }
        if (!metered) {
            return;
        }
        // The displayed covered-until is min(server projection, now + coverage)
        // — and each input is individually untrustworthy. The projection is
        // balance ÷ current footprint: absurdly far at low usage ("~Feb 2086"),
        // and an old server could OVERFLOW it into the past — "Covered until
        // ~Feb 1962" on-device, a 528-year projection wrapping int64
        // nanoseconds (the server now reports such projections as "never
        // runs out" and omits the date; the past-guard here stays for stale
        // servers and clock skew). The coverage needs the plan size, which a
        // wiped install doesn't have. Show whichever is available and sane;
        // nothing trustworthy → no runway (silent beats wrong).
        int coverage = coverageMonths(true);
        boolean hasCoverage = planKnown && coverage > 0;
        Instant now = Instant.now();
        Instant projected = parseInstant(mQuota.projectedRunoutAt);
        if (projected != null && projected.isBefore(now)) {
            projected = null; // a past covered-until is always garbage here
        }
        Instant display = projected;
        if (hasCoverage) {
            Instant planEnd = now.plusSeconds(
                    Math.round(coverage * DAYS_PER_MONTH * 86400.0));
            if (display == null || display.isAfter(planEnd)) {
                display = planEnd;
            }
        }
        if (display == null) {
            return;
        }
        runway.setVisibility(View.VISIBLE);
        runwayLabels.setVisibility(View.VISIBLE);
        runwayCovered.setText(ctx.getString(
                R.string.cloud_status_runway_covered, monthYear(display)));
        if (!hasCoverage) {
            return;
        }
        long days = Duration.between(now, display).toDays();
        int monthsLeft = (int) Math.max(0, Math.round(days / DAYS_PER_MONTH));
        monthsLeft = Math.min(monthsLeft, coverage);
        runwayLeft.setText(ctx.getString(
                R.string.cloud_status_runway_left, monthsLeft, coverage));
        runwayLeft.setVisibility(View.VISIBLE);
        ticks.setVisibility(View.VISIBLE);
        ticks.setTicks(coverage, monthsLeft, ink);
    }

    /** Localized coverage ("1 year" / "3 months") — same plurals the buy wizard
     *  uses, so the chip echoes the tile the user bought. */
    private static String formatDuration(Context ctx, int months) {
        if (months > 0 && months % 12 == 0) {
            int years = months / 12;
            return ctx.getResources().getQuantityString(R.plurals.buy_credit_years, years, years);
        }
        return ctx.getResources().getQuantityString(R.plurals.buy_credit_months, months, months);
    }

    /** A GB-months balance, trimming a trailing ".0" (100.0 → "100"). */
    /** Package-visible: the Backups-list header reuses the exact same formatting. */
    static String formatGbMonths(double v) {
        if (v == Math.rint(v)) {
            return Long.toString(Math.round(v));
        }
        return String.format(Locale.getDefault(), "%.1f", v);
    }

    /** An {@link Instant} → a localized "Mar 2027" (short month + year), for
     *  the covered-until date. */
    private static String monthYear(Instant instant) {
        ZonedDateTime z = instant.atZone(ZoneId.systemDefault());
        String[] months = new DateFormatSymbols().getShortMonths();
        return months[z.getMonthValue() - 1] + " " + z.getYear();
    }

    /** Strict RFC3339 parse (same flavour {@link #mediumDate} accepts), or null
     *  so the caller can fall back to the lenient string slice. */
    private static Instant parseInstant(String rfc3339) {
        if (rfc3339 == null) {
            return null;
        }
        try {
            return OffsetDateTime.parse(rfc3339).toInstant();
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** RFC3339 → a localized medium date ("Aug 30, 2026") for the grace
     *  deadline (day precision matters there), or null on parse failure. */
    private static String mediumDate(String rfc3339) {
        if (rfc3339 == null) {
            return null;
        }
        try {
            return OffsetDateTime.parse(rfc3339).toLocalDate()
                    .format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM));
        } catch (RuntimeException e) {
            return null;
        }
    }

}
