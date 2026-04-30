package com.solarized.firedown.manager;

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
        context.setStopped(false);
        context.setDeleted(false);
        context.setCurrentThread(Thread.currentThread());

        // Notify service — adds task to active list for stop/delete lookup
        if (onStarted != null) onStarted.run();

        try {
            StoragePaths.ensureDownloadPath(context.getContext());
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);

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