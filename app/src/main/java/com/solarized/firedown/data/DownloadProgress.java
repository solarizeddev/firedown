package com.solarized.firedown.data;

import androidx.annotation.NonNull;

/**
 * What the bottom bar's Downloads button shows for one browsing mode: nothing
 * running, a percentage for the whole group of downloads, or "running, no
 * total known" (a live recording, a server that sends no size). Built by
 * {@code DownloadProgressGroup} on the download engine's thread; a value, so
 * an unchanged one is not posted again.
 */
public final class DownloadProgress {

    /** Nothing is downloading in this mode. */
    public static final int IDLE = 0;
    /** {@link #percent} of the group is done. */
    public static final int DETERMINATE = 1;
    /** Something is downloading and no running download knows its total. */
    public static final int INDETERMINATE = 2;

    public static final DownloadProgress NONE = new DownloadProgress(IDLE, 0, false);

    public final int state;
    /** 0..100 while {@link #DETERMINATE}, 0 otherwise. */
    public final int percent;
    /**
     * {@link #IDLE} only: the group just ended and its last download finished
     * (not deleted, not failed). The button may show the line full for a
     * moment before it goes back to the icon.
     */
    public final boolean completed;

    private DownloadProgress(int state, int percent, boolean completed) {
        this.state = state;
        this.percent = percent;
        this.completed = completed;
    }

    public static DownloadProgress idle(boolean completed) {
        return completed ? new DownloadProgress(IDLE, 0, true) : NONE;
    }

    public static DownloadProgress determinate(int percent) {
        return new DownloadProgress(DETERMINATE, Math.max(0, Math.min(100, percent)), false);
    }

    public static DownloadProgress indeterminate() {
        return new DownloadProgress(INDETERMINATE, 0, false);
    }

    public boolean isActive() {
        return state != IDLE;
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
        return state == other.state && percent == other.percent && completed == other.completed;
    }

    @Override
    public int hashCode() {
        return (state * 31 + percent) * 2 + (completed ? 1 : 0);
    }

    @NonNull
    @Override
    public String toString() {
        switch (state) {
            case DETERMINATE:
                return "DownloadProgress{" + percent + "%}";
            case INDETERMINATE:
                return "DownloadProgress{indeterminate}";
            default:
                return completed ? "DownloadProgress{idle, completed}" : "DownloadProgress{idle}";
        }
    }
}
