package com.solarized.firedown.donation;

/**
 * When the donation sheet may show. Pure Java (no Android), so
 * {@code scripts/donation-harness} drives it on a JDK.
 *
 * <p>The sheet asks only someone who uses the app, right after a download
 * worked, rarely, and never again once they tapped Donate:
 * <ul>
 *   <li>installed for {@link #MIN_INSTALL_AGE_MS} and holding
 *       {@link #MIN_FINISHED_DOWNLOADS} finished downloads;</li>
 *   <li>a regular download finished in the last {@link #RECENT_FINISH_MS};</li>
 *   <li>at least {@link #MIN_GAP_MS} since the last time it showed, and
 *       {@link #MAX_TIMES_SHOWN} times in all;</li>
 *   <li>never after a tap on Donate, here or in Settings.</li>
 * </ul>
 * A clock that moved backwards past a stored time reads as "not yet": the
 * sheet waits rather than shows early.
 */
public final class DonationPromptPolicy {

    private static final long DAY_MS = 24L * 60 * 60 * 1000;

    public static final long MIN_INSTALL_AGE_MS = 21 * DAY_MS;
    public static final int MIN_FINISHED_DOWNLOADS = 15;
    public static final long RECENT_FINISH_MS = 60L * 60 * 1000;
    public static final long MIN_GAP_MS = 182 * DAY_MS;
    public static final int MAX_TIMES_SHOWN = 3;

    private DonationPromptPolicy() {}

    /** What the policy reads; 0 means "never" for every time. */
    public static final class State {
        public final long installedAt;
        public final int finishedDownloads;
        public final long lastFinishedAt;
        public final long lastShownAt;
        public final int timesShown;
        public final boolean donated;

        public State(long installedAt, int finishedDownloads, long lastFinishedAt,
                     long lastShownAt, int timesShown, boolean donated) {
            this.installedAt = installedAt;
            this.finishedDownloads = finishedDownloads;
            this.lastFinishedAt = lastFinishedAt;
            this.lastShownAt = lastShownAt;
            this.timesShown = timesShown;
            this.donated = donated;
        }
    }

    public static boolean shouldShow(State s, long now) {
        if (s.donated || s.timesShown >= MAX_TIMES_SHOWN) {
            return false;
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
        if (s.lastShownAt > 0 && now - s.lastShownAt < MIN_GAP_MS) {
            return false;
        }
        return true;
    }
}
