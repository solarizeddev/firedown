package com.solarized.firedown.data;

import androidx.annotation.NonNull;

/**
 * What the bottom bar's Downloads button shows for one browsing mode: nothing
 * running, a percentage for the whole group of downloads, "running, no
 * percentage" (a live recording, a server that sends no size, a download that
 * has not moved yet), or "the group just finished". Built by
 * {@code DownloadProgressGroup} on the download engine's thread; a value, so
 * an unchanged one is not posted again.
 */
public final class DownloadProgress {

    /** Nothing is downloading in this mode. */
    public static final int IDLE = 0;
    /** {@link #percent} of the group is done. */
    public static final int DETERMINATE = 1;
    /** Something is downloading and there is no percentage to show. */
    public static final int INDETERMINATE = 2;
    /**
     * The group ended with at least one download saved. Shown for a minute,
     * or until Downloads is opened ({@code TaskRepository}).
     */
    public static final int FINISHED = 3;

    public static final DownloadProgress NONE = new DownloadProgress(IDLE, 0, 0);

    public final int state;
    /** 1..100 while {@link #DETERMINATE}, 0 otherwise. */
    public final int percent;
    /** Downloads of the group still running or queued; 0 unless active. */
    public final int count;

    private DownloadProgress(int state, int percent, int count) {
        this.state = state;
        this.percent = percent;
        this.count = count;
    }

    public static DownloadProgress determinate(int percent, int count) {
        return new DownloadProgress(DETERMINATE, Math.max(0, Math.min(100, percent)), Math.max(1, count));
    }

    public static DownloadProgress indeterminate(int count) {
        return new DownloadProgress(INDETERMINATE, 0, Math.max(1, count));
    }

    public static DownloadProgress finished() {
        return new DownloadProgress(FINISHED, 0, 0);
    }

    /** Something is downloading: the ring shows. */
    public boolean isActive() {
        return state == DETERMINATE || state == INDETERMINATE;
    }

    public boolean isFinished() {
        return state == FINISHED;
    }

    /**
     * What to show next. The engine reports idle again as soon as it looks
     * at its empty lists, so a finished mark is not replaced by idle: only a
     * new download, its minute running out or Downloads being opened ends it.
     */
    public static DownloadProgress next(DownloadProgress shown, DownloadProgress incoming) {
        if (shown.state == FINISHED && incoming.state == IDLE) {
            return shown;
        }
        return incoming;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof DownloadProgress)) {
            return false;
        }
        DownloadProgress other = (DownloadProgress) o;
        return state == other.state && percent == other.percent && count == other.count;
    }

    @Override
    public int hashCode() {
        return (state * 31 + percent) * 31 + count;
    }

    @NonNull
    @Override
    public String toString() {
        switch (state) {
            case DETERMINATE:
                return "DownloadProgress{" + percent + "% of " + count + "}";
            case INDETERMINATE:
                return "DownloadProgress{indeterminate, " + count + "}";
            case FINISHED:
                return "DownloadProgress{finished}";
            default:
                return "DownloadProgress{idle}";
        }
    }
}
