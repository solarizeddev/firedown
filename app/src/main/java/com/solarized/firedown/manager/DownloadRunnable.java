package com.solarized.firedown.manager;

import android.os.Process;
import android.util.Log;

import com.solarized.firedown.StoragePaths;
import com.solarized.firedown.utils.MessageHelper;

import java.io.IOException;

/**
 * Unified download runnable — delegates to a DownloadStrategy.
 * All boilerplate (thread setup, interrupt checks, state management) lives here.
 * Replaces BasicRunnable, FFmpegRunnable, BrowserRunnable, TimedTextRunnable.
 */
public class DownloadRunnable implements Runnable {

    private static final String TAG = DownloadRunnable.class.getSimpleName();

    private final DownloadRequest request;
    private final DownloadContext context;
    private final DownloadCallback callback;
    private final DownloadStrategy strategy;
    private final Runnable onStarted;
    private final Runnable onComplete;

    public DownloadRunnable(DownloadRequest request,
                            DownloadContext context,
                            DownloadCallback callback,
                            DownloadStrategy strategy,
                            Runnable onStarted,
                            Runnable onComplete) {
        this.request = request;
        this.context = context;
        this.callback = callback;
        this.strategy = strategy;
        this.onStarted = onStarted;
        this.onComplete = onComplete;
    }

    @Override
    public void run() {
        // Do NOT reset the stop/delete flags here. The context is built fresh
        // per download (they start false), and a queued download the user
        // deleted or finished before it ever ran carries its cancellation IN
        // those flags: the engine's pool.remove() misses when the pool had
        // just dequeued this runnable, so run() is the last line of defence —
        // wiping the flags made such a download run to completion (and, with
        // delete, recreate the file the user had just removed). The
        // isInterrupted() check below now honours them.
        //
        // Clear a STALE interrupt before publishing this thread: the engine
        // interrupts context.getCurrentThread() to unblock a socket read, and
        // that can land on a pool thread that has already finished the
        // download it was aimed at (the finally below clears the flag, but an
        // interrupt arriving after that clear survives into the next
        // runnable). Nobody can be interrupting THIS download yet — the
        // engine only learns our thread from setCurrentThread — so any flag
        // set right now belongs to a previous one. Without this, the next
        // download's first blocking call threw InterruptedException and the
        // catch below treated it as a quiet cancellation: a PROGRESS row that
        // never moved again, no error, no notification. Found by
        // scripts/engine-harness stress.
        Thread.interrupted();
        context.setCurrentThread(Thread.currentThread());

        // Notify service — adds task to active list for stop/delete lookup
        if (onStarted != null) onStarted.run();

        try {
            StoragePaths.ensureDownloadPath(context.getContext());
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);

            if (context.isInterrupted()) {
                Log.d(TAG, "Interrupted before start");
                return;
            }

            strategy.execute(request, context, callback);

        } catch (IllegalArgumentException | IOException e) {
            Log.e(TAG, "Download failed", e);
            Throwable cause = e.getCause();
            if (cause != null) {
                String msg = cause.getMessage();
                if (msg != null && msg.contains("ENOSPC")) {
                    callback.onError(MessageHelper.EXTERNAL_STORAGE);
                } else {
                    callback.onError(MessageHelper.IOEXCEPTION);
                }
            } else {
                callback.onError(MessageHelper.IOEXCEPTION);
            }
        } catch (Exception e) {
            // okhttp is Kotlin and lets InterruptedException propagate without
            // declaring it — when RunnableManager calls Thread.interrupt() to
            // unblock an in-flight TCP connect or socket read, it bubbles all
            // the way out of RealCall.execute() bypassing the Java throws
            // contract. Treat it as a normal cancellation: clear the interrupt
            // (the finally below does this), don't fire onError, exit quietly.
            // Catching Exception (not InterruptedException directly) because
            // the try body's declared checked exception is IOException only,
            // so the compiler rejects a direct catch.
            if (e instanceof InterruptedException) {
                if (context.isStopped() || context.isDeleted()) {
                    Log.d(TAG, "Download interrupted (cancellation)", e);
                } else {
                    // An interrupt nobody asked for — a misdirected one from the
                    // race above landing after our clear. Quietly exiting would
                    // leave the row PROGRESS forever; an ERROR row is honest
                    // and the user can retry it (the partial file resumes).
                    Log.w(TAG, "Download interrupted with no stop/delete pending — reporting as error");
                    callback.onError(MessageHelper.IOEXCEPTION);
                }
            } else {
                Log.e(TAG, "Download unexpected failure", e);
                callback.onError(MessageHelper.IOEXCEPTION);
            }
        } finally {
            /* Clear any stale thread interrupt flag before returning the thread
             * to the pool. Thread.interrupt() is used by RunnableManager to break
             * InputStream.read() during stop/delete. If the flag isn't cleared,
             * the next download reusing this thread would fail immediately. */
            Thread.interrupted();
            context.setCurrentThread(null);
            if (onComplete != null) onComplete.run();
        }
    }

    public void stop() {
        context.setStopped(true);
        strategy.stop();
    }

    public void delete() {
        context.setStopped(true);
        context.setDeleted(true);
        strategy.stop();
    }

    public boolean isStopped() {
        return context.isStopped();
    }

    public boolean isDeleted() {
        return context.isDeleted();
    }
}