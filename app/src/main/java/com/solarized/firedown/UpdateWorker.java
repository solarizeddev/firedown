package com.solarized.firedown;

import android.app.DownloadManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.net.Uri;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.hilt.work.HiltWorker;
import androidx.preference.PreferenceManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;


import com.solarized.firedown.data.di.Qualifiers;
import com.solarized.firedown.utils.BrowserHeaders;

import java.io.File;
import java.io.IOException;

import dagger.assisted.Assisted;
import dagger.assisted.AssistedInject;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

@HiltWorker
public class UpdateWorker extends Worker {

    private final OkHttpClient okHttpClient;
    private final File updateFile;

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
        this.updateFile = Preferences.getUpdateApkFile(context);
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

        try {
            if (manifest.isNewerThan(mCurrentVersion)) {
                if (isUpdateAlreadyDownloaded(manifest.versionCode)) {
                    UpdateNotification.showInstallPrompt(mContext, manifest.versionName);
                } else {
                    // Hand the actual APK download to the system DownloadManager
                    // and return — UpdateDownloadReceiver verifies it and posts
                    // the install prompt when the broadcast arrives (which can
                    // cold-start the app, so the download surviving a process
                    // eviction still ends in a notification).
                    enqueueDownload(manifest.updateUrl, manifest.sha256,
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

    /**
     * Enqueues the APK download with the system DownloadManager and records the
     * metadata UpdateDownloadReceiver needs to verify the finished file. The
     * download then proceeds independently of this worker (and of the app
     * process), and completion is handled by the manifest-declared receiver.
     *
     * Note this is the APK *file* download only — the status.json check
     * (fetchStatusJson, the MAU-bearing request with its custom headers) stays
     * on OkHttp and is untouched.
     */
    private void enqueueDownload(String url, String remoteSha, String name, int remoteVersion) {
        DownloadManager dm = (DownloadManager) mContext.getSystemService(Context.DOWNLOAD_SERVICE);
        // DownloadManager can be disabled by the user, and it needs an external
        // files dir to write to. If either is unavailable, skip quietly — the
        // next periodic check tries again.
        if (dm == null || mContext.getExternalFilesDir(null) == null) {
            Log.w("UpdateWorker", "DownloadManager/external storage unavailable; skipping");
            return;
        }

        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(mContext);

        // Drop any previous in-flight/queued download and stale file so a
        // changed manifest can't leave two downloads racing for the same path.
        long previousId = prefs.getLong(Keys.UPDATE_DOWNLOAD_ID, -1);
        if (previousId != -1) {
            dm.remove(previousId);
        }
        if (updateFile.exists()) {
            updateFile.delete();
        }

        DownloadManager.Request request = new DownloadManager.Request(Uri.parse(url));
        // Same browser UA as the status fetch so the Cloudflare front (or the
        // GitHub fallback) sees a consistent client. Deliberately NOT the
        // X-App-Version header — that is the MAU signal and belongs only on the
        // status.json call.
        request.addRequestHeader(BrowserHeaders.USER_AGENT, BrowserHeaders.getDefaultUserAgentString());
        request.setDestinationInExternalFilesDir(mContext, null, Preferences.UPDATE_APK);
        // No download-progress notification — the only notification we want is
        // the install prompt after verification (needs DOWNLOAD_WITHOUT_NOTIFICATION,
        // already held). Allow metered/cellular to match the previous OkHttp
        // path's behaviour (it downloaded over any connection); not roaming.
        request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_HIDDEN);
        request.setAllowedOverMetered(true);
        request.setAllowedOverRoaming(false);

        long downloadId = dm.enqueue(request);
        prefs.edit()
                .putLong(Keys.UPDATE_DOWNLOAD_ID, downloadId)
                .putString(Keys.UPDATE_DOWNLOAD_SHA, remoteSha)
                .putString(Keys.UPDATE_DOWNLOAD_NAME, name == null ? "" : name)
                .putInt(Keys.UPDATE_DOWNLOAD_VERSION_CODE, remoteVersion)
                .apply();
    }

    private boolean isUpdateAlreadyDownloaded(int remoteVersion) {
        if (!updateFile.exists()) return false;
        PackageInfo pi = mContext.getPackageManager()
                .getPackageArchiveInfo(updateFile.getAbsolutePath(), 0);
        return pi != null && pi.versionCode >= remoteVersion;
    }

}