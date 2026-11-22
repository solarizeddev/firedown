package com.solarized.firedown.harness;

import com.solarized.firedown.donation.DonationPromptPolicy;
import com.solarized.firedown.donation.DonationPromptPolicy.State;
import com.solarized.firedown.donation.DonationPromptPolicy.Surface;

/**
 * Drives the REAL DonationPromptPolicy (copied from app/src by run.sh): who
 * may be asked, when, how often (90 days to the second ask, a year after
 * each later one, no cap), how (the sheet first, the card after, the card
 * lasting 14 days), that a failure or a running download holds it back,
 * and that a Donate tap pauses it for a year.
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
    private static void checkSurface(String name, Surface expected, int timesShown) {
        checks++;
        Surface actual = DonationPromptPolicy.surfaceFor(timesShown);
        if (actual == expected) {
            System.out.println("ok   " + name);
        } else {
            failures++;
            System.out.println("FAIL " + name + ": expected " + expected + ", got " + actual);
        }
    }

    private static void checkCard(String name, boolean expected, long since, long now) {
        checks++;
        boolean actual = DonationPromptPolicy.cardVisible(since, now);
        if (actual == expected) {
            System.out.println("ok   " + name);
        } else {
            failures++;
            System.out.println("FAIL " + name + ": expected " + expected + ", got " + actual);
        }
    }

    private static State eligible() {
        return new State(NOW - 30 * DAY, 20, NOW - 60_000, 0, false, 0, 0, 0);
    }

    private static State with(State b, Long installedAt, Integer finished, Long lastFinished,
                              Long lastShown, Integer times, Long donateTap) {
        return new State(
                installedAt != null ? installedAt : b.installedAt,
                finished != null ? finished : b.finishedDownloads,
                lastFinished != null ? lastFinished : b.lastFinishedAt,
                b.lastFailedAt,
                b.downloadRunning,
                lastShown != null ? lastShown : b.lastShownAt,
                times != null ? times : b.timesShown,
                donateTap != null ? donateTap : b.lastDonateTapAt);
    }

    private static State failedAt(State b, long failedAt) {
        return new State(b.installedAt, b.finishedDownloads, b.lastFinishedAt, failedAt,
                b.downloadRunning, b.lastShownAt, b.timesShown, b.lastDonateTapAt);
    }

    private static State running(State b) {
        return new State(b.installedAt, b.finishedDownloads, b.lastFinishedAt, b.lastFailedAt,
                true, b.lastShownAt, b.timesShown, b.lastDonateTapAt);
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

        // Rarely: the second ask 90 days after the first, then once a year.
        check("first ask 89 days ago: no", false, with(e, null, null, null, NOW - 89 * DAY, 1, null), NOW);
        check("first ask 90 days ago: yes", true, with(e, null, null, null, NOW - 90 * DAY, 1, null), NOW);
        check("shown in the future (clock moved back): no", false,
                with(e, null, null, null, NOW + DAY, 1, null), NOW);
        check("second ask 90 days ago: no", false, with(e, null, null, null, NOW - 90 * DAY, 2, null), NOW);
        check("second ask 364 days ago: no", false, with(e, null, null, null, NOW - 364 * DAY, 2, null), NOW);
        check("second ask 365 days ago: yes", true, with(e, null, null, null, NOW - 365 * DAY, 2, null), NOW);
        check("tenth ask a year ago: yes (no cap)", true,
                with(e, null, null, null, NOW - 365 * DAY, 10, null), NOW);

        // How: the sheet first, the card after.
        checkSurface("never asked: the sheet", Surface.SHEET, 0);
        checkSurface("asked once: the card", Surface.CARD, 1);
        checkSurface("asked five times: the card", Surface.CARD, 5);
        checkCard("card just started: up", true, NOW, NOW);
        checkCard("card 13 days old: up", true, NOW - 13 * DAY, NOW);
        checkCard("card 14 days old: gone", false, NOW - 14 * DAY, NOW);
        checkCard("no card: gone", false, 0, NOW);
        checkCard("card from the future (clock moved back): gone", false, NOW + DAY, NOW);

        // Not over a failure or a download still running.
        check("a download failed after the last success: no", false, failedAt(e, NOW - 30_000), NOW);
        check("failed at the same moment as the success: no", false, failedAt(e, e.lastFinishedAt), NOW);
        check("a failure before the last success: yes", true, failedAt(e, NOW - 2 * HOUR), NOW);
        check("a failure in the future (clock moved back): no", false, failedAt(e, NOW + HOUR), NOW);
        check("a download is running: no", false, running(e), NOW);

        // A Donate tap pauses it for a year.
        check("Donate tapped a day ago: no", false, with(e, null, null, null, null, null, NOW - DAY), NOW);
        check("Donate tapped 364 days ago: no", false,
                with(e, null, null, null, null, 1, NOW - 364 * DAY), NOW);
        check("Donate tapped 365 days ago: yes", true,
                with(e, null, null, null, NOW - 365 * DAY, 1, NOW - 365 * DAY), NOW);
        check("Donate tapped in the future (clock moved back): no", false,
                with(e, null, null, null, null, null, NOW + DAY), NOW);
        check("Donate tapped long ago, three asks used: yes", true,
                with(e, null, null, null, NOW - 999 * DAY, 3, NOW - 999 * DAY), NOW);

        // A lifetime walk: ask on every eligible moment over five years and
        // record the asks. Downloads finish every day; the user opens
        // Downloads right after each one and always taps Not now.
        long install = NOW;
        int times = 0;
        long lastShown = 0;
        java.util.List<Long> asks = new java.util.ArrayList<>();
        java.util.List<Surface> surfaces = new java.util.ArrayList<>();
        for (int day = 0; day < 5 * 365; day++) {
            long t = install + day * DAY + 10 * HOUR;
            State s = new State(install, day, t - 5 * 60_000, 0, false, lastShown, times, 0);
            if (DonationPromptPolicy.shouldShow(s, t)) {
                surfaces.add(DonationPromptPolicy.surfaceFor(times));
                asks.add((t - install) / DAY);
                lastShown = t;
                times++;
            }
        }
        // Day 21, then 90 days later, then a year apart: 21, 111, 476, 841,
        // 1206, 1571.
        java.util.List<Long> expectedDays = java.util.Arrays.asList(21L, 111L, 476L, 841L, 1206L, 1571L);
        checks++;
        boolean surfacesOk = !surfaces.isEmpty() && surfaces.get(0) == Surface.SHEET;
        for (int i = 1; i < surfaces.size(); i++) {
            surfacesOk &= surfaces.get(i) == Surface.CARD;
        }
        if (asks.equals(expectedDays) && surfacesOk) {
            System.out.println("ok   five years of Not now: asks on days " + asks + ", sheet then cards");
        } else {
            failures++;
            int n = Math.min(asks.size(), 8);
            System.out.println("FAIL five years of Not now: " + asks.size() + " asks, first on days "
                    + asks.subList(0, n) + " as " + surfaces.subList(0, n)
                    + "; expected days " + expectedDays + ", sheet then cards");
        }

        System.out.println();
        System.out.println(checks + " checks, " + failures + " failed");
        if (failures > 0) {
            System.exit(1);
        }
    }
}
