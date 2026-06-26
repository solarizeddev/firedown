package com.solarized.firedown;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.hilt.work.HiltWorker;
import androidx.work.Worker;
import androidx.work.WorkerParameters;


import com.solarized.firedown.data.di.Qualifiers;
import com.solarized.firedown.utils.BrowserHeaders;

import java.io.IOException;

import dagger.assisted.Assisted;
import dagger.assisted.AssistedInject;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

@HiltWorker
public class UpdateWorker extends Worker {

    private final OkHttpClient okHttpClient;

    private final int mCurrentVersion;

    private final Context mContext;

    @AssistedInject
    public UpdateWorker(
            @Assisted @NonNull Context context,
            @Assisted @NonNull WorkerParameters params,
            @Qualifiers.AppVersion int currentVersion,
            OkHttpClient okHttpClient
    ){
        super(context, params);
        this.mContext = context;
        this.okHttpClient = okHttpClient;
        this.mCurrentVersion = currentVersion;
    }

    @NonNull
    @Override
    public Result doWork() {
        Log.d("UpdateWorker", "Checking for updates...");

        String body = fetchStatusJson();
        if (body == null) {
            // Every fallback failed (already logged per-attempt). Let WorkManager
            // re-schedule with backoff — transient network issues recover on the
            // next attempt; persistent ISP blocks fall through and the worker
            // retries quietly until the user goes through a non-blocking network.
            return Result.retry();
        }

        UpdateManifest manifest = UpdateManifest.parse(body);
        if (manifest == null) {
            // A fetched-but-unparseable descriptor (malformed JSON, missing
            // field) is treated like a failed fetch — retry rather than act on
            // half-parsed data.
            Log.e("UpdateWorker", "Update check failed: unparseable status descriptor");
            return Result.retry();
        }

        // Remember this version's changelog so the in-app sheet can show it
        // later (the sheet can't reach the manifest at display time).
        UpdateDownloader.setChangelog(mContext, manifest.versionCode, manifest.changelog);

        try {
            if (manifest.isNewerThan(mCurrentVersion)) {
                if (UpdateDownloader.isVerifiedReady(mContext, manifest.versionCode)) {
                    // Already downloaded AND verified on a previous cycle —
                    // re-surface the (silent) install prompt. The in-app sheet
                    // additionally shows on resume while the app is in use.
                    UpdateNotification.showInstallPrompt(mContext, manifest.versionName);
                } else {
                    // Hand the APK download to the system DownloadManager and
                    // return — UpdateDownloadReceiver verifies it and posts the
                    // install prompt when the broadcast arrives (which can
                    // cold-start the app, so a download surviving a process
                    // eviction still ends in a notification).
                    UpdateDownloader.start(mContext, manifest.downloadUrls, manifest.sha256,
                            manifest.versionName, manifest.versionCode);
                }
            }
            return Result.success();

        } catch (Exception e) {
            Log.e("UpdateWorker", "Update check failed", e);
            return Result.retry();
        }
    }

    /**
     * Walks the configured update-status endpoints in order, returning the
     * first response body that comes back 2xx. Returns null if every
     * endpoint fails — caller schedules a retry.
     *
     * Why a fallback chain at all: the primary firedown.app endpoint sits
     * behind Cloudflare. Spain's LaLiga court orders force major ISPs to
     * IP-block large blocks of Cloudflare ranges during match windows
     * (the block hits every Cloudflare-fronted service, not just LaLiga
     * targets); affected users see TCP SYN drops to those IPs and the
     * worker would otherwise retry forever against an unreachable host.
     * The fallback mirror is hosted on GitHub Raw (Azure IPs), which
     * isn't caught by those blocks.
     */
    private String fetchStatusJson() {
        for (String url : Preferences.UPDATE_URL_FALLBACKS) {
            if (url == null || url.isEmpty()) continue;

            // Send the app's stock browser UA instead of OkHttp's default
            // "okhttp/x.y". Cloudflare fronts the primary endpoint and its
            // bot heuristics treat bare library UAs unkindly; the browser UA
            // blends in as ordinary traffic. It's a generic string (no
            // device model / Android version), so nothing extra is leaked.
            Request request = new Request.Builder()
                    .url(url)
                    .addHeader(BrowserHeaders.USER_AGENT, BrowserHeaders.getDefaultUserAgentString())
                    .addHeader(BrowserHeaders.X_APP_VERSION, App.getVersionName())
                    .build();

            try (Response response = okHttpClient.newCall(request).execute()) {
                if (!response.isSuccessful() || response.body() == null) {
                    Log.w("UpdateWorker", "status fetch " + url + " → " + response.code());
                    continue;
                }
                return response.body().string();
            } catch (IOException e) {
                // Most common case here is SocketTimeoutException from an
                // ISP-level IP block (LaLiga / similar). Try the next
                // fallback; only escalate if every endpoint fails.
                Log.w("UpdateWorker", "status fetch " + url + " failed: " + e.getMessage());
            }
        }
        return null;
    }
}
