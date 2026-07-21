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

/**
 * The Cloud Backup status hero (custom-layout {@link Preference}, styled by
 * {@code preference_cloud_status.xml}). One card, four states:
 *
 * <ul>
 *   <li><b>Unmetered beta</b> — big backed-up size, usage bar vs the included
 *       cap ({@code Quota.bytesLimit}), "Free during the beta" chip.</li>
 *   <li><b>Metered, funded</b> — ONE model, prepaid credit measured in TIME:
 *       the backed-up-size headline + a Today→date timeline
 *       ({@link CloudTimelineView}) to the server's projected runout, duration
 *       centred beneath. No plan chip, no % bar (see onBindViewHolder — the
 *       plan was an invented shape; GB-months never appears in user-facing
 *       text). No projection (nothing backed up / effectively-never runout) →
 *       the "credit active, lasts for years at this usage" line instead, so a
 *       funded account always shows its credit.</li>
 *   <li><b>Grace / read-only</b> — amber data ink + the top-up-by deadline
 *       (amber, never colorError red — the app's attention convention).</li>
 *   <li><b>Not set up</b> — a dashed empty state pointing at the download
 *       sheet's ⋮ backup action.</li>
 * </ul>
 *
 * A running transfer swaps the caption for the live "Transfer in progress…"
 * line. {@link #setPlan}'s stored purchase shape now only backs the onboarding
 * roadmap's offline step-② check-off and the starter-credit-vs-purchase label.
 */
public class CloudStatusPreference extends Preference {

    /** Never let a non-empty usage render a 0-width bar — a sliver of ink keeps
     *  the meter reading as alive rather than broken. */
    private static final int MIN_BAR_PERCENT = 2;
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
        CloudTimelineView timeline = (CloudTimelineView) holder.findViewById(R.id.cb_timeline);
        TextView runwayNow = (TextView) holder.findViewById(R.id.cb_runway_now);
        TextView runwayCovered = (TextView) holder.findViewById(R.id.cb_runway_covered);
        TextView runwayDuration = (TextView) holder.findViewById(R.id.cb_runway_duration);

        boolean metered = mQuota != null && mQuota.metered;
        boolean grace = metered && mQuota.readOnly;
        int ink = ContextCompat.getColor(ctx, grace ? R.color.backup_warning : R.color.brand_orange);

        // ONE mental model — prepaid credit measured in TIME. The metered hero
        // is headline + timeline, nothing else: "854 MB backed up · it's
        // covered until ~DATE at this usage; backing up more moves the date
        // closer." The previous render spoke THREE models at once — a plan
        // chip ("Up to 450 GB · 1 year", a shape INVENTED by normalizing the
        // balance to 12 months; there is no plan, purchases accumulate into
        // one balance), a "% of your plan" bar against that invented cap, and
        // the timeline — and on-device it read as contradictory (the recurring
        // "GB-months is confusing" complaint). The plan fiction, its % bar,
        // and the balance-normalization/coverage-months helpers are deleted;
        // GB-months never appears in user-facing text. The usage bar remains
        // ONLY for the unmetered beta, whose byte cap is a REAL denominator.

        // Headline: how much of the user's stuff is safe.
        big.setText(mTotalBytes >= 0 ? Formatter.formatShortFileSize(ctx, mTotalBytes) : "—");
        bigUnit.setText(R.string.cloud_status_backed_up);

        bindChip(ctx, chip, metered, grace);

        long capBytes = -1;
        if (!metered && mQuota != null && mQuota.bytesLimit > 0) {
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

        caption.setText(captionText(ctx, metered, capBytes));

        bindRunway(ctx, alert, runway, timeline, runwayNow, runwayCovered, runwayDuration,
                metered, grace, ink);
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
        // A balance the user never PAID for is the server's one-time starter
        // credit (granted at registration). Showing "✓ Add storage credit"
        // then would claim a purchase that never happened — label the step as
        // the included trial instead. A local plan shape (a real purchase on
        // this install) wins over the trial label.
        boolean starterOnly = creditDone
                && mPlanSizeGb <= 0
                && mQuota != null && mQuota.metered && mQuota.starterGrantedAt != null;
        bindStep(ctx, stepCode, 1, ctx.getString(R.string.settings_sync_create_title),
                codeDone, !codeDone);
        bindStep(ctx, stepCredit, 2, ctx.getString(starterOnly
                        ? R.string.cloud_starter_credit_step
                        : R.string.buy_credit_title),
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

    /** The context chip: read-only (grace, amber) / the free beta (unmetered).
     *  Metered-funded shows NO chip — the old plan/balance chip was one of the
     *  three competing models (see onBindViewHolder). Warn styling is amber
     *  text over amber-at-18%; the neutral styling is re-applied explicitly
     *  because rows recycle. */
    private void bindChip(Context ctx, TextView chip, boolean metered, boolean grace) {
        if (metered && !grace) {
            chip.setVisibility(View.GONE);
            return;
        }
        chip.setVisibility(View.VISIBLE);
        chip.setText(grace
                ? ctx.getString(R.string.cloud_status_chip_readonly)
                : ctx.getString(R.string.cloud_status_chip_beta));
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

    private String captionText(Context ctx, boolean metered, long capBytes) {
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
        // Metered: the headline right above IS the backed-up size, so repeating
        // it here read as a stutter — pair the file count with the trust line.
        return files + " · " + ctx.getString(R.string.cloud_backup_header_encrypted);
    }

    /**
     * The "time as space" runway: a gradient timeline from Today (solid dot)
     * to the covered-until date (soft dot), with the duration centred beneath
     * — each fact appears once (the date is a place on the line, the duration
     * its length; the old "Covered until X / ≈ N of M months left" + ticks
     * restated one fact three times, and "of M" was degenerate under
     * balance-derived coverage). Grace: the alert carries the copy, the
     * timeline goes amber with the top-up DEADLINE as its endpoint.
     * Unmetered: no runway.
     */
    private void bindRunway(Context ctx, TextView alert, View runway,
                            CloudTimelineView timeline, TextView runwayNow,
                            TextView runwayCovered, TextView runwayDuration,
                            boolean metered, boolean grace, int ink) {
        alert.setVisibility(View.GONE);
        runway.setVisibility(View.GONE);
        runwayDuration.setVisibility(View.GONE);
        if (grace) {
            String deadline = mediumDate(mQuota.graceUntil);
            alert.setText(deadline != null
                    ? ctx.getString(R.string.cloud_status_grace_alert, deadline)
                    : ctx.getString(R.string.cloud_status_grace_nodate));
            alert.setVisibility(View.VISIBLE);
            if (deadline != null) {
                runway.setVisibility(View.VISIBLE);
                timeline.setInk(ink); // amber
                runwayNow.setText(R.string.cloud_status_timeline_today);
                runwayCovered.setText(deadline);
            }
            return;
        }
        if (!metered) {
            return;
        }
        // The timeline's endpoint is the SERVER's projected runout — balance ÷
        // current footprint, the one honest date in the prepaid-time model.
        // (The old min(projection, now + plan-coverage) clamp died with the
        // plan fiction: coverage was balance ÷ an invented plan size, so the
        // clamp just restated the fiction as a date.) The past-guard stays for
        // stale servers / clock skew: an old server could overflow a
        // centuries-out projection into the PAST ("~Feb 1962" on-device);
        // current servers report those as "never runs out" and omit the date.
        Instant now = Instant.now();
        Instant projected = parseInstant(mQuota.projectedRunoutAt);
        if (projected != null && projected.isBefore(now)) {
            projected = null;
        }
        if (projected == null) {
            // No date because the credit effectively never runs out at this
            // usage (nothing backed up yet, or a balance past the server's
            // 30-year projection horizon). Say exactly that — a funded account
            // must never render with no trace of its credit (with the plan
            // chip and % bar gone, this line is the balance's only voice
            // here). Funded is known: non-grace metered with a zero balance
            // carries a runout stamp and lands in the grace branch above.
            if (mQuota.balanceGbMonths > 0) {
                alert.setText(R.string.cloud_status_credit_active);
                alert.setVisibility(View.VISIBLE);
            }
            return;
        }
        runway.setVisibility(View.VISIBLE);
        timeline.setInk(ink);
        runwayNow.setText(R.string.cloud_status_timeline_today);
        runwayCovered.setText("~" + monthYear(projected));
        long days = Duration.between(now, projected).toDays();
        int months = (int) Math.max(1, Math.round(days / DAYS_PER_MONTH));
        runwayDuration.setText(ctx.getString(R.string.cloud_status_timeline_coverage,
                formatDuration(ctx, months)));
        runwayDuration.setVisibility(View.VISIBLE);
    }

    /**
     * "≈ 1 year of coverage" from the server's projected runout, or null when
     * unknown/unmetered — the Backups-list header uses this where it used to
     * print the raw GB-months balance (the internal ledger unit never appears
     * in user-facing text; time is the only currency the UI speaks).
     */
    static String coverageLabel(Context ctx, StorageApiClient.Quota quota) {
        if (quota == null || !quota.metered) {
            return null;
        }
        Instant now = Instant.now();
        Instant projected = parseInstant(quota.projectedRunoutAt);
        if (projected == null || projected.isBefore(now)) {
            return null;
        }
        long days = Duration.between(now, projected).toDays();
        int months = (int) Math.max(1, Math.round(days / DAYS_PER_MONTH));
        return ctx.getString(R.string.cloud_status_timeline_coverage,
                formatDuration(ctx, months));
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
