package com.solarized.firedown.manager;

import java.io.IOException;

/**
 * Strategy for executing a download.
 * Each implementation handles a specific download type (HTTP, FFmpeg mux, FFmpeg merge, Gecko, TimedText).
 * Strategies are stateless — all input comes from DownloadRequest, all output goes through DownloadCallback.
 */
public interface DownloadStrategy {

    /**
     * Execute the download.
     *
     * @param request   immutable download parameters
     * @param context   shared runtime context (OkHttp client, paths, thread management)
     * @param callback  report progress and results back to the task
     * @throws IOException on download failure
     */
    void execute(DownloadRequest request, DownloadContext context, DownloadCallback callback) throws IOException;

    /**
     * Stop the download gracefully.
     */
    void stop();
}
