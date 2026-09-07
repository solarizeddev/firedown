package com.solarized.firedown.manager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * A scripted download thread. The real one runs a strategy to completion;
 * this one blocks until the harness releases it (a natural finish) or the
 * engine stops/deletes it, then unwinds exactly as the real one does —
 * {@code onRunComplete} in a finally. Thread.interrupt() unblocks it like it
 * unblocks a real socket read.
 */
public class DownloadRunnable implements Runnable {
    public static final List<DownloadRunnable> ALL = Collections.synchronizedList(new ArrayList<>());

    private final DownloadTask task;
    private final CountDownLatch release = new CountDownLatch(1);
    private final CountDownLatch done = new CountDownLatch(1);
    public volatile boolean started, completed, stopped, deleted, interrupted;

    DownloadRunnable(DownloadTask task) { this.task = task; ALL.add(this); }

    @Override public void run() {
        started = true;
        task.onStarted();
        try {
            release.await();
        } catch (InterruptedException e) {
            interrupted = true;
        } finally {
            completed = true;
            task.onRunComplete();
            done.countDown();
        }
    }

    /** The download reached its end on its own (strategy → onFinished). */
    public void finishNaturally() { task.onFinished(); release.countDown(); }
    public void stop() { stopped = true; release.countDown(); }
    public void delete() { stopped = true; deleted = true; release.countDown(); }
    public boolean isStopped() { return stopped; }
    public boolean isDeleted() { return deleted; }
    public boolean awaitDone(long ms) throws InterruptedException { return done.await(ms, TimeUnit.MILLISECONDS); }
}
