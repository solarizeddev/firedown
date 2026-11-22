package com.solarized.firedown.donation;

/**
 * When to ask for a donation, and how. Pure Java (no Android), so
 * {@code scripts/donation-harness} drives it on a JDK.
 *
 * <p>It asks only someone who uses the app, right after a download
 * worked, rarely:
 * <ul>
 *   <li>installed for {@link #MIN_INSTALL_AGE_MS} and
 *       {@link #MIN_FINISHED_DOWNLOADS} downloads finished over the
 *       install's life (deleted ones count);</li>
 *   <li>a regular download finished in the last {@link #RECENT_FINISH_MS},
 *       no download failed since, and none is running;</li>
 *   <li>the second ask {@link #SECOND_ASK_GAP_MS} after the first, every
 *       later one {@link #LATER_ASK_GAP_MS} after the one before, with no
 *       lifetime cap (an app funded only by donations asks once a year, as
 *       Wikipedia and Thunderbird do);</li>
 *   <li>not within {@link #DONATE_TAP_PAUSE_MS} of a tap on Donate, here or
 *       in Settings. A tap is not a donation (a curious look, a page that
 *       failed to load), so it pauses the asks instead of ending them.</li>
 * </ul>
 * The FIRST ask is the sheet; every later one is a dismissible card in the
 * Downloads list's banner slot ({@link #surfaceFor}), which stays until it
 * is dismissed or tapped, has been shown on {@link #CARD_MAX_DISPLAYS}
 * visits, or {@link #CARD_LIFETIME_MS} have passed since it was first
 * shown: a count of visits, so a daily user is not shown it for two weeks,
 * and an age counted from the first showing, so someone who is away for a
 * week does not lose the ask unseen. A visit is an hour
 * ({@link #VISIT_MS}): returning from the player or the lock screen is the
 * same visit, and the visit that used the last showing keeps the card. A modal every year
 * would wear on the people who picked the app for being quiet.
 *
 * <p>A clock that moved backwards past a stored time reads as "not yet":
 * the ask waits rather than comes early.</p>
 */
public final class DonationPromptPolicy {

    private static final long DAY_MS = 24L * 60 * 60 * 1000;

    public static final long MIN_INSTALL_AGE_MS = 21 * DAY_MS;
    public static final int MIN_FINISHED_DOWNLOADS = 15;
    public static final long RECENT_FINISH_MS = 60L * 60 * 1000;
    public static final long SECOND_ASK_GAP_MS = 90 * DAY_MS;
    public static final long LATER_ASK_GAP_MS = 365 * DAY_MS;
    public static final long DONATE_TAP_PAUSE_MS = 365 * DAY_MS;
    public static final long CARD_LIFETIME_MS = 7 * DAY_MS;
    public static final int CARD_MAX_DISPLAYS = 3;
    public static final long VISIT_MS = 60L * 60 * 1000;

    /** How an ask is shown. */
    public enum Surface { SHEET, CARD }

    private DonationPromptPolicy() {}

    /** What the policy reads; 0 means "never" for every time. */
    public static final class State {
        public final long installedAt;
        public final int finishedDownloads;
        public final long lastFinishedAt;
        public final long lastFailedAt;
        public final boolean downloadRunning;
        public final long lastShownAt;
        public final int timesShown;
        public final long lastDonateTapAt;

        public State(long installedAt, int finishedDownloads, long lastFinishedAt,
                     long lastFailedAt, boolean downloadRunning,
                     long lastShownAt, int timesShown, long lastDonateTapAt) {
            this.installedAt = installedAt;
            this.finishedDownloads = finishedDownloads;
            this.lastFinishedAt = lastFinishedAt;
            this.lastFailedAt = lastFailedAt;
            this.downloadRunning = downloadRunning;
            this.lastShownAt = lastShownAt;
            this.timesShown = timesShown;
            this.lastDonateTapAt = lastDonateTapAt;
        }
    }

    /** The first ask is the sheet, every later one the card. */
    public static Surface surfaceFor(int timesShown) {
        return timesShown <= 0 ? Surface.SHEET : Surface.CARD;
    }

    /** The wait before the next ask, given how many there have been. */
    public static long gapAfter(int timesShown) {
        return timesShown <= 1 ? SECOND_ASK_GAP_MS : LATER_ASK_GAP_MS;
    }

    /** Whether a showing now is a new visit (and counts), or part of the
     *  visit of the last one. A clock that moved back is the same visit. */
    public static boolean isNewVisit(long lastDisplayAt, long now) {
        return lastDisplayAt <= 0 || now - lastDisplayAt >= VISIT_MS;
    }

    /**
     * Whether the card may show now.
     *
     * @param started       a card ask is pending (started, not answered)
     * @param firstShownAt  when it was first shown, 0 if never
     * @param displays      visits it has been shown on
     * @param lastDisplayAt when the last of those visits began, 0 if none
     */
    public static boolean cardVisible(boolean started, long firstShownAt, int displays,
                                      long lastDisplayAt, long now) {
        if (!started) {
            return false;
        }
        if (firstShownAt > 0) {
            long age = now - firstShownAt;
            if (age < 0 || age >= CARD_LIFETIME_MS) {
                return false;
            }
        }
        // Used up, unless this is the visit that used the last showing.
        return displays < CARD_MAX_DISPLAYS || !isNewVisit(lastDisplayAt, now);
    }

    public static boolean shouldShow(State s, long now) {
        if (s.lastDonateTapAt > 0) {
            long sinceTap = now - s.lastDonateTapAt;
            if (sinceTap < 0 || sinceTap < DONATE_TAP_PAUSE_MS) {
                return false;
            }
        }
        if (s.installedAt <= 0 || now - s.installedAt < MIN_INSTALL_AGE_MS) {
            return false;
        }
        if (s.finishedDownloads < MIN_FINISHED_DOWNLOADS) {
            return false;
        }
        if (s.lastFinishedAt <= 0) {
            return false;
        }
        long sinceFinish = now - s.lastFinishedAt;
        if (sinceFinish < 0 || sinceFinish > RECENT_FINISH_MS) {
            return false;
        }
        if (s.lastFailedAt > 0 && (s.lastFailedAt >= s.lastFinishedAt || s.lastFailedAt > now)) {
            return false;
        }
        if (s.downloadRunning) {
            return false;
        }
        if (s.lastShownAt > 0) {
            long sinceShown = now - s.lastShownAt;
            if (sinceShown < 0 || sinceShown < gapAfter(s.timesShown)) {
                return false;
            }
        }
        return true;
    }
}
