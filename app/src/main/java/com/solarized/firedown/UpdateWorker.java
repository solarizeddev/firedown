package com.solarized.firedown;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.content.pm.SigningInfo;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.hilt.work.HiltWorker;
import androidx.work.Worker;
import androidx.work.WorkerParameters;


import com.solarized.firedown.data.di.Qualifiers;
import com.solarized.firedown.utils.BrowserHeaders;
import com.solarized.firedown.utils.BuildUtils;

import org.apache.commons.codec.digest.DigestUtils;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;

import dagger.assisted.Assisted;
import dagger.assisted.AssistedInject;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okio.BufferedSink;
import okio.Okio;

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
        this.updateFile = new File(context.getFilesDir(), Preferences.UPDATE_APK);
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

        try {
            JSONObject json = new JSONObject(body);
            int remoteVersion = json.getInt("versionCode");
            String updateUrl = json.getString("updateUrl");
            String remoteSha = json.getString("sha256");
            String versionName = json.getString("versionName");

            if (remoteVersion > mCurrentVersion) {
                if (isUpdateAlreadyDownloaded(remoteVersion)) {
                    UpdateNotification.showInstallPrompt(mContext, versionName);
                } else {
                    return downloadApk(updateUrl, remoteSha, versionName);
                }
            }
            return Result.success();

        } catch (Exception e) {
            Log.e("UpdateWorker", "Update check failed (parse)", e);
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

    private Result downloadApk(String url, String remoteSha, String name) throws IOException {
        // Same browser UA as the status fetch — the APK download hits the
        // same Cloudflare front (or the GitHub fallback) and should look
        // like the same client.
        Request downloadRequest = new Request.Builder()
                .url(url)
                .addHeader(BrowserHeaders.USER_AGENT, BrowserHeaders.getDefaultUserAgentString())
                .build();

        try (Response response = okHttpClient.newCall(downloadRequest).execute()) {
            if (!response.isSuccessful() || response.body() == null) return Result.retry();

            // Write to disk using Okio
            try (BufferedSink sink = Okio.buffer(Okio.sink(updateFile))) {
                sink.writeAll(response.body().source());
            }

            // Verify SHA256
            String localSha;
            try (FileInputStream fis = new FileInputStream(updateFile)) {
                localSha = DigestUtils.sha256Hex(fis);
            }
            if (!localSha.equalsIgnoreCase(remoteSha)) {
                updateFile.delete();
                return Result.retry();
            }

            // Verify the downloaded APK is signed by the same key as the installed app.
            // SHA256 alone trusts the manifest; signature check anchors trust to the device.
            if (!verifyApkSignature(updateFile)) {
                updateFile.delete();
                return Result.retry();
            }

            UpdateNotification.showInstallPrompt(mContext, name);
            return Result.success();
        }
    }

    private boolean verifyApkSignature(File apk) {
        try {
            PackageManager pm = mContext.getPackageManager();
            String installedPackage = mContext.getPackageName();
            Signature[] downloaded;
            Signature[] installed;

            if (BuildUtils.hasAndroidP()) {
                PackageInfo dl = pm.getPackageArchiveInfo(apk.getAbsolutePath(),
                        PackageManager.GET_SIGNING_CERTIFICATES);
                PackageInfo cur = pm.getPackageInfo(installedPackage,
                        PackageManager.GET_SIGNING_CERTIFICATES);
                if (dl == null || dl.signingInfo == null || cur.signingInfo == null) return false;

                SigningInfo dlInfo = dl.signingInfo;
                SigningInfo curInfo = cur.signingInfo;
                downloaded = dlInfo.hasMultipleSigners()
                        ? dlInfo.getApkContentsSigners()
                        : dlInfo.getSigningCertificateHistory();
                installed = curInfo.hasMultipleSigners()
                        ? curInfo.getApkContentsSigners()
                        : curInfo.getSigningCertificateHistory();
            } else {
                @SuppressWarnings("deprecation")
                PackageInfo dl = pm.getPackageArchiveInfo(apk.getAbsolutePath(),
                        PackageManager.GET_SIGNATURES);
                @SuppressWarnings("deprecation")
                PackageInfo cur = pm.getPackageInfo(installedPackage,
                        PackageManager.GET_SIGNATURES);
                if (dl == null) return false;
                downloaded = dl.signatures;
                installed = cur.signatures;
            }

            if (downloaded == null || installed == null
                    || downloaded.length == 0 || installed.length == 0) {
                return false;
            }

            for (Signature d : downloaded) {
                boolean matched = false;
                for (Signature i : installed) {
                    if (d.equals(i)) {
                        matched = true;
                        break;
                    }
                }
                if (!matched) return false;
            }
            return true;
        } catch (Exception e) {
            Log.e("UpdateWorker", "Signature verification failed", e);
            return false;
        }
    }

    private boolean isUpdateAlreadyDownloaded(int remoteVersion) {
        if (!updateFile.exists()) return false;
        PackageInfo pi = mContext.getPackageManager()
                .getPackageArchiveInfo(updateFile.getAbsolutePath(), 0);
        return pi != null && pi.versionCode >= remoteVersion;
    }

}