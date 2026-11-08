package com.solarized.firedown.manager;

import com.solarized.firedown.data.DownloadProgress;

/**
 * The bottom bar's progress for ONE browsing mode (regular or Safe Folder):
 * every download of the current group as an equal share, averaged.
 *
 * <ul>
 *   <li>Equal shares, not bytes: most downloads are streams that report how
 *       much of the video's running time is done, never a byte total.</li>
 *   <li>A group lasts from the first download until nothing runs. A download
 *       that finishes stays in it at 100% until then (Firefox's batch), so
 *       the bar never steps back when a short one finishes. One added
 *       meanwhile joins it, so the bar drops a step (as in Chrome and
 *       Firefox). A queued one counts at 0%.</li>
 *   <li>No percentage to show is {@link DownloadProgress#INDETERMINATE}: no
 *       running download knows its total (a live recording, a server that
 *       sends no size), or nothing has moved yet. A download's first seconds
 *       (a YouTube download mints its token, a stream opens) read as working,
 *       never as a bar stuck at 0%. A download with no known total is left
 *       out of the average.</li>
 *   <li>A failed or deleted download leaves the group.</li>
 * </ul>
 *
 * Not thread-safe: the engine calls it on its own thread only.
 */
final class DownloadProgressGroup {

    /** A running download whose total is not known. */
    static final int UNKNOWN = -1;

    private int mDone;

    /** A download of this mode left the lists: finished, or failed/deleted. */
    void onFinished(boolean succeeded) {
        if (succeeded) {
            mDone++;
        }
    }

    /**
     * @param running each running or queued download of this mode: 0..100, or
     *                {@link #UNKNOWN}. Ends the group when empty.
     */
    DownloadProgress update(int[] running) {
        if (running.length == 0) {
            mDone = 0;
            return DownloadProgress.NONE;
        }
        long sum = 100L * mDone;
        int known = mDone;
        boolean anyRunningKnown = false;
        for (int percent : running) {
            if (percent == UNKNOWN) {
                continue;
            }
            anyRunningKnown = true;
            sum += Math.max(0, Math.min(100, percent));
            known++;
        }
        if (!anyRunningKnown || sum == 0) {
            return DownloadProgress.indeterminate();
        }
        return DownloadProgress.determinate((int) (sum / known));
    }
}
