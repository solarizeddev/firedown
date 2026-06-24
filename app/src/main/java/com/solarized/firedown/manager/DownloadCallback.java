package com.solarized.firedown.manager;

/**
 * Callback interface for download runnables to report progress and results.
 * Replaces the write-side of the old RunnableMethods god interface.
 * Runnables receive this to report back — they don't read task state through it.
 */
public interface DownloadCallback {

    void onProgress(int percent, long downloaded, long total);

    /**
     * Signals that the byte download is done and a post-download mux/finishing
     * pass has started. Drives the transient "Finishing…" row state. Honoured
     * even when the task is sealed — the user-finish path seals to FINISHED
     * before its partial-data mux runs, but the row should still show
     * "Finishing…" for the duration of that mux.
     */
    void onProcessing();

    void onStatusChanged(int status);

    void onError(int errorType);

    void onNameResolved(String name);

    void onMimeResolved(String mimeType);

    void onFileSizeKnown(long size);

    String onFilePathResolved(String path);

    void onImgResolved(String imgPath);

    void onLiveStream(boolean isLive);

    void onDescriptionResolved(String description);

    void onDurationResolved(long duration, String formatted);

    void onFinished();
}