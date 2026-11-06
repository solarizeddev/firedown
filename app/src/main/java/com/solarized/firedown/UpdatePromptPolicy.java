package com.solarized.firedown;

/**
 * When the "Update ready" sheet may open by itself. Pure Java, so the rules
 * run in a plain JVM test ({@code UpdatePromptPolicyTest}).
 *
 * <p>The sheet opens by itself only (1) when an update is ready and not
 * snoozed, (2) on a screen where nothing is being read or watched — the
 * home screen, Downloads, Settings — or on the first screen after the app
 * starts, and (3) while nothing runs that installing would stop. Installing
 * closes Firedown (on Android 12 and newer at once, with no question), so a
 * running download, a GIF or Safe Folder task, a file share or playback
 * would stop. It used to open on every resume of every screen, over a page
 * being watched and over the lock screen.
 *
 * <p>The user can always open it on purpose from the ⋮ menu's "Update
 * ready" row (and Settings → About); then none of these rules apply, and
 * the sheet says what would stop.
 */
public final class UpdatePromptPolicy {

    private UpdatePromptPolicy() {}

    /** Where the user is when the app comes to the front. */
    public enum Place {
        /** The browser's home screen (regular or private). */
        HOME,
        /** A screen of the app's own: Downloads, Settings. */
        SCREEN,
        /** A web page in the browser. */
        PAGE,
        /** Never here: the lock screen, the Safe Folder, a page viewer. */
        NEVER
    }

    /** What installing would stop, in the order the sheet names it. */
    public enum Busy {
        NONE,
        DOWNLOAD,
        TASK,
        SHARE,
        PLAYBACK
    }

    /** How long a dismissal holds the sheet back. */
    public static final long SNOOZE_INTERVAL_MS = 24L * 60 * 60 * 1000;

    /**
     * Times the sheet may open by itself for one version. After that it waits
     * for the user (the ⋮ row, About, the notification).
     */
    public static final int MAX_PROMPTS = 3;

    /** The first thing running that installing would stop, or NONE. */
    public static Busy busy(boolean downloading, boolean task, boolean share, boolean playback) {
        if (downloading) {
            return Busy.DOWNLOAD;
        }
        if (task) {
            return Busy.TASK;
        }
        if (share) {
            return Busy.SHARE;
        }
        if (playback) {
            return Busy.PLAYBACK;
        }
        return Busy.NONE;
    }

    /** True while a dismissal of THIS version still holds the sheet back. */
    public static boolean snoozed(int readyVersion, int snoozedVersion, long snoozedUntil, long now) {
        return snoozedVersion == readyVersion && now < snoozedUntil;
    }

    /**
     * Whether the sheet opens by itself now.
     *
     * @param firstScreen the first screen resumed since the app started: the
     *        user has just opened the app and is not reading anything yet
     */
    public static boolean autoShow(boolean ready, boolean snoozed, Place place,
                                   boolean firstScreen, Busy busy) {
        if (!ready || snoozed || busy != Busy.NONE) {
            return false;
        }
        switch (place) {
            case HOME:
            case SCREEN:
                return true;
            case PAGE:
                return firstScreen;
            default:
                return false;
        }
    }

    /** The snooze a dismissal writes: how many times so far, and until when. */
    public static final class Snooze {
        public final int count;
        public final long until;

        Snooze(int count, long until) {
            this.count = count;
            this.until = until;
        }
    }

    /**
     * The snooze after a dismissal. A different stored version is the first
     * dismissal of this update, so its count starts over; the
     * {@link #MAX_PROMPTS}th makes it permanent.
     */
    public static Snooze afterDismissal(int readyVersion, int snoozedVersion, int snoozedCount, long now) {
        int count = snoozedVersion == readyVersion ? snoozedCount + 1 : 1;
        long until = count >= MAX_PROMPTS ? Long.MAX_VALUE : now + SNOOZE_INTERVAL_MS;
        return new Snooze(count, until);
    }
}
