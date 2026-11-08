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
 *       that finishes stays in it at 100% until then (Firefox's batch), or
 *       the line would fall back each time a short one finished. One added
 *       meanwhile joins it, so the line drops a step (as in Chrome and
 *       Firefox). A queued one counts at 0%.</li>
 *   <li>A download with no known total (a live recording, a server that
 *       sends no size) is left out of the average; when every running one is
 *       like that, the result is {@link DownloadProgress#INDETERMINATE}.</li>
 *   <li>A failed or deleted download leaves the group.</li>
 * </ul>
 *
 * Not thread-safe: the engine calls it on its own thread only.
 */
final class DownloadProgressGroup {

    /** A running download whose total is not known. */
    static final int UNKNOWN = -1;

    private int mDone;
    private boolean mLastSucceeded;

    /** A download of this mode left the lists: finished, or failed/deleted. */
    void onFinished(boolean succeeded) {
        if (succeeded) {
            mDone++;
        }
        mLastSucceeded = succeeded;
    }

    /**
     * @param running each running or queued download of this mode: 0..100, or
     *                {@link #UNKNOWN}. Ends the group when empty.
     */
    DownloadProgress update(int[] running) {
        if (running.length == 0) {
            boolean completed = mDone > 0 && mLastSucceeded;
            mDone = 0;
            mLastSucceeded = false;
            return DownloadProgress.idle(completed);
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
        if (!anyRunningKnown) {
            return DownloadProgress.indeterminate();
        }
        return DownloadProgress.determinate((int) (sum / known));
    }
}
