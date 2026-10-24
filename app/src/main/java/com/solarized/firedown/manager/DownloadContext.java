package com.solarized.firedown.manager;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.solarized.firedown.geckoview.PoTokenGenerator;
import com.solarized.firedown.utils.BrowserHeaders;
import com.solarized.firedown.utils.Utils;

import org.apache.commons.io.FileUtils;

import java.io.File;
import java.io.IOException;
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
    @Nullable private final PoTokenGenerator poTokenGenerator;
    private volatile boolean stopped;
    private volatile boolean deleted;
    private Thread currentThread;

    public DownloadContext(@NonNull OkHttpClient okHttpClient,
                           @NonNull Context context,
                           String filePath,
                           String rawHeaders,
                           String cookieHeader,
                           @Nullable PoTokenGenerator poTokenGenerator) {
        this.context = context;
        this.okHttpClient = okHttpClient;
        this.filePath = filePath;
        this.userAgent = BrowserHeaders.getDefaultUserAgentString();
        this.poTokenGenerator = poTokenGenerator;

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

    /** Singleton minter for SABR PO tokens. {@code null} only if the runtime
     *  helper wasn't ready yet at context-build time (e.g. very early
     *  shutdown); strategies should null-check and fall back to whatever
     *  token shipped on the {@code DownloadRequest}. */
    @Nullable
    public PoTokenGenerator getPoTokenGenerator() {
        return poTokenGenerator;
    }

    /**
     * The file every strategy writes: the context's path, as the engine
     * resolved it — the download folder for a regular download, the
     * app-private safe folder for a vault one, and for a resume the directory
     * the row names, where its partial file is. It used to be rebuilt from
     * the file's NAME under the public download folder, which put a private
     * tab's download beside the public ones with a vault row pointing at it.
     */
    public File getOutputFile() {
        return new File(filePath);
    }

    /** Makes the output file's directory; the runnable calls it before the strategy runs. */
    public void ensureOutputDirectory() throws IOException {
        File parent = getOutputFile().getParentFile();
        if (parent != null) {
            FileUtils.forceMkdir(parent);
        }
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