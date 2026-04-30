package com.solarized.firedown.manager;

/**
 * Callback interface for download runnables to report progress and results.
 * Replaces the write-side of the old RunnableMethods god interface.
 * Runnables receive this to report back — they don't read task state through it.
 */
public interface DownloadCallback {

    void onProgress(int percent, long downloaded, long total);

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