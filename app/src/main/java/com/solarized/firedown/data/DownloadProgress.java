package com.solarized.firedown.data;

import androidx.annotation.NonNull;

/**
 * What the bottom bar's Downloads button shows for one browsing mode: nothing
 * running, a percentage for the whole group of downloads, or "running, no
 * percentage" (a live recording, a server that sends no size, a download that
 * has not moved yet). Built by {@code DownloadProgressGroup} on the download
 * engine's thread; a value, so an unchanged one is not posted again.
 */
public final class DownloadProgress {

    /** Nothing is downloading in this mode. */
    public static final int IDLE = 0;
    /** {@link #percent} of the group is done. */
    public static final int DETERMINATE = 1;
    /** Something is downloading and there is no percentage to show. */
    public static final int INDETERMINATE = 2;

    public static final DownloadProgress NONE = new DownloadProgress(IDLE, 0);

    public final int state;
    /** 1..100 while {@link #DETERMINATE}, 0 otherwise. */
    public final int percent;

    private DownloadProgress(int state, int percent) {
        this.state = state;
        this.percent = percent;
    }

    public static DownloadProgress determinate(int percent) {
        return new DownloadProgress(DETERMINATE, Math.max(0, Math.min(100, percent)));
    }

    public static DownloadProgress indeterminate() {
        return new DownloadProgress(INDETERMINATE, 0);
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
        return state == other.state && percent == other.percent;
    }

    @Override
    public int hashCode() {
        return state * 31 + percent;
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
                return "DownloadProgress{idle}";
        }
    }
}
