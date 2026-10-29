package com.solarized.firedown.harness;

import com.solarized.firedown.donation.DonationPromptPolicy;
import com.solarized.firedown.donation.DonationPromptPolicy.State;

/**
 * Drives the REAL DonationPromptPolicy (copied from app/src by run.sh): who
 * may be asked, when, how often, and that a Donate tap ends it.
 */
public final class DonationPromptHarness {

    private static final long DAY = 24L * 60 * 60 * 1000;
    private static final long HOUR = 60L * 60 * 1000;
    /** An arbitrary "now", far from the epoch so every subtraction is real. */
    private static final long NOW = 1_800_000_000_000L;

    private static int failures;
    private static int checks;

    private static void check(String name, boolean expected, State s, long now) {
        checks++;
        boolean actual = DonationPromptPolicy.shouldShow(s, now);
        if (actual == expected) {
            System.out.println("ok   " + name);
        } else {
            failures++;
            System.out.println("FAIL " + name + ": expected " + expected + ", got " + actual);
        }
    }

    /** Someone who qualifies: installed a month ago, 20 downloads, one a minute ago. */
    private static State eligible() {
        return new State(NOW - 30 * DAY, 20, NOW - 60_000, 0, 0, false);
    }

    private static State with(State b, Long installedAt, Integer finished, Long lastFinished,
                              Long lastShown, Integer times, Boolean donated) {
        return new State(
                installedAt != null ? installedAt : b.installedAt,
                finished != null ? finished : b.finishedDownloads,
                lastFinished != null ? lastFinished : b.lastFinishedAt,
                lastShown != null ? lastShown : b.lastShownAt,
                times != null ? times : b.timesShown,
                donated != null ? donated : b.donated);
    }

    public static void main(String[] args) {
        State e = eligible();
        check("eligible: shows", true, e, NOW);

        // Earn it first.
        check("installed 20 days ago: no", false, with(e, NOW - 20 * DAY, null, null, null, null, null), NOW);
        check("installed exactly 21 days ago: yes", true, with(e, NOW - 21 * DAY, null, null, null, null, null), NOW);
        check("unknown install time: no", false, with(e, 0L, null, null, null, null, null), NOW);
        check("14 downloads: no", false, with(e, null, 14, null, null, null, null), NOW);
        check("15 downloads: yes", true, with(e, null, 15, null, null, null, null), NOW);

        // Right after something worked.
        check("no download recorded: no", false, with(e, null, null, 0L, null, null, null), NOW);
        check("last download 59 min ago: yes", true, with(e, null, null, NOW - 59 * 60_000L, null, null, null), NOW);
        check("last download 61 min ago: no", false, with(e, null, null, NOW - 61 * 60_000L, null, null, null), NOW);
        check("last download in the future (clock moved back): no", false,
                with(e, null, null, NOW + HOUR, null, null, null), NOW);

        // Rarely.
        check("shown 100 days ago: no", false, with(e, null, null, null, NOW - 100 * DAY, 1, null), NOW);
        check("shown 181 days ago: no", false, with(e, null, null, null, NOW - 181 * DAY, 1, null), NOW);
        check("shown 182 days ago: yes", true, with(e, null, null, null, NOW - 182 * DAY, 1, null), NOW);
        check("shown in the future (clock moved back): no", false,
                with(e, null, null, null, NOW + DAY, 1, null), NOW);
        check("shown twice, a year ago: yes", true, with(e, null, null, null, NOW - 365 * DAY, 2, null), NOW);
        check("shown three times: never again", false, with(e, null, null, null, NOW - 999 * DAY, 3, null), NOW);

        // A Donate tap ends it.
        check("donated: never", false, with(e, null, null, null, null, null, true), NOW);
        check("donated, a year later: never", false,
                with(e, null, null, null, NOW - 365 * DAY, 1, true), NOW);

        // A lifetime walk: ask on every eligible moment over five years and
        // count the asks. Downloads finish every day; the user opens
        // Downloads right after each one and always taps Not now.
        long install = NOW;
        long t = install;
        int times = 0;
        long lastShown = 0;
        long firstAsk = 0;
        long minGap = Long.MAX_VALUE;
        for (int day = 0; day < 5 * 365; day++) {
            t = install + day * DAY + 10 * HOUR;
            State s = new State(install, day, t - 5 * 60_000, lastShown, times, false);
            if (DonationPromptPolicy.shouldShow(s, t)) {
                if (lastShown > 0) {
                    minGap = Math.min(minGap, t - lastShown);
                } else {
                    firstAsk = t;
                }
                lastShown = t;
                times++;
            }
        }
        checks++;
        if (times == 3 && firstAsk - install >= 21 * DAY && minGap >= 182 * DAY) {
            System.out.println("ok   five years of Not now: 3 asks, first after day "
                    + (firstAsk - install) / DAY + ", " + minGap / DAY + " days apart at least");
        } else {
            failures++;
            System.out.println("FAIL five years of Not now: " + times + " asks, first on day "
                    + (firstAsk - install) / DAY + ", min gap " + (minGap == Long.MAX_VALUE ? -1 : minGap / DAY) + " days");
        }

        System.out.println();
        System.out.println(checks + " checks, " + failures + " failed");
        if (failures > 0) {
            System.exit(1);
        }
    }
}
