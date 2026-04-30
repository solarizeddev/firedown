package com.solarized.firedown.ffmpegutils;

public interface FFmpegListener {
    void onProgress(long downloadedLength, long totalLength);
    void onStarted();
    void onFinished();
}
