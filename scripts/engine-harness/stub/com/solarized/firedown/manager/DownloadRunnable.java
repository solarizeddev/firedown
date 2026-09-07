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
        Thread.interrupted();   // the real run() clears a stale flag before publishing its thread
        // A real download creates its output file the moment it starts; the
        // engine's collision guard (filePathInTasks → File.exists) relies on it.
        try { new java.io.File(task.getFilePath()).createNewFile(); } catch (java.io.IOException ignored) {}
        task.onStarted();
        try {
            // The real run() checks context.isInterrupted() before executing
            // the strategy: a delete/stop that landed before the thread got
            // going ends it here (the flags are never reset).
            if (!stopped) release.await();
        } catch (InterruptedException e) {
            interrupted = true;
            // the real run(): an interrupt with no stop/delete pending is an ERROR, not a quiet exit
            if (!stopped && !deleted) task.onError(com.solarized.firedown.utils.MessageHelper.IOEXCEPTION);
        } finally {
            Thread.interrupted();   // the real finally clears the flag before the thread returns to the pool
            completed = true;
            task.onRunComplete();
            done.countDown();
        }
    }

    /** The download reached its end on its own (strategy → onFinished). */
    public void finishNaturally() { task.onFinished(); release.countDown(); }
    public void stop() { stopped = true; release.countDown(); }
    public void delete() { stopped = true; deleted = true; release.countDown(); }   // the repository removes the file
    public boolean isStopped() { return stopped; }
    public int taskId() { return task.getFileId(); }
    public boolean isDeleted() { return deleted; }
    public boolean awaitDone(long ms) throws InterruptedException { return done.await(ms, TimeUnit.MILLISECONDS); }
}
