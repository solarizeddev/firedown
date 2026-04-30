package com.solarized.firedown.manager;

import android.content.Context;

import androidx.annotation.NonNull;

import com.solarized.firedown.StoragePaths;
import com.solarized.firedown.utils.BrowserHeaders;
import com.solarized.firedown.utils.Utils;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

import okhttp3.OkHttpClient;

/**
 * Shared runtime context for download strategies.
 * Provides the OkHttp client, file-path management, headers, and thread-control
 * hooks. Created once per download task and passed to the strategy.
 *
 * <p>The {@code OkHttpClient} is carried here so strategies never need to reach
 * into a static field — they just call {@link #getOkHttpClient()}.
 */
public class DownloadContext {

    private final OkHttpClient okHttpClient;
    private final String filePath;
    private final Map<String, String> headers;
    private final String userAgent;
    private final Context context;
    private volatile boolean stopped;
    private volatile boolean deleted;
    private Thread currentThread;

    public DownloadContext(@NonNull OkHttpClient okHttpClient,
                           @NonNull Context context,
                           String filePath,
                           String rawHeaders,
                           String cookieHeader) {
        this.context = context;
        this.okHttpClient = okHttpClient;
        this.filePath = filePath;
        this.userAgent = BrowserHeaders.getDefaultUserAgentString();

        // Parse headers
        this.headers = new HashMap<>();
        if (rawHeaders != null && !rawHeaders.isEmpty()) {
            this.headers.putAll(Utils.stringToMap(rawHeaders));
        }
        if (cookieHeader != null && !cookieHeader.isEmpty()) {
            this.headers.put("Cookie", cookieHeader);
        }
    }

    @NonNull
    public OkHttpClient getOkHttpClient() {
        return okHttpClient;
    }

    public File getOutputFile() {
        return new File(StoragePaths.getDownloadPath(context), new File(filePath).getName());
    }

    @NonNull
    public Context getContext() {
        return context;
    }

    public String getFilePath() {
        return filePath;
    }

    public Map<String, String> getHeaders() {
        return headers;
    }

    public String getUserAgent() {
        return userAgent;
    }

    public boolean isStopped() {
        return stopped;
    }

    public void setStopped(boolean stopped) {
        this.stopped = stopped;
    }

    public boolean isDeleted() {
        return deleted;
    }

    public void setDeleted(boolean deleted) {
        this.deleted = deleted;
    }

    public Thread getCurrentThread() {
        return currentThread;
    }

    public void setCurrentThread(Thread thread) {
        this.currentThread = thread;
    }

    public boolean isInterrupted() {
        return Thread.currentThread().isInterrupted() || stopped || deleted;
    }
}