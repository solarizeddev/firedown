package com.solarized.firedown.okhttp;

public interface ProgressListener {
    void update(long bytesRead, long contentLength);
}