package com.solarized.firedown;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.hilt.work.HiltWorker;
import androidx.work.Worker;
import androidx.work.WorkerParameters;


import com.solarized.firedown.data.di.Qualifiers;
import com.solarized.firedown.utils.BrowserHeaders;

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

    private final SharedPreferences mSharedPreferences;

    private final Context mContext;

    @AssistedInject
    public UpdateWorker(
            @Assisted @NonNull Context context,
            @Assisted @NonNull WorkerParameters params,
            SharedPreferences sharedPreferences,
            @Qualifiers.AppVersion int currentVersion,
            OkHttpClient okHttpClient
    ){
        super(context, params);
        this.mSharedPreferences = sharedPreferences;
        this.mContext = context;
        this.okHttpClient = okHttpClient;
        this.mCurrentVersion = currentVersion;
        this.updateFile = new File(context.getFilesDir(), Preferences.UPDATE_APK);
    }

    @NonNull
    @Override
    public Result doWork() {
        Log.d("UpdateWorker", "Checking for updates...");

        try {
            // 1. Fetch Status (Synchronous)
            Request statusRequest = new Request.Builder()
                    .url(Preferences.UPDATE_URL)
                    .addHeader(BrowserHeaders.X_REQUEST_ID,
                            mSharedPreferences.getString(Preferences.UNIQUE_ID, ""))
                    .addHeader(BrowserHeaders.X_APP_VERSION, App.getVersionName())
                    .build();

            try (Response response = okHttpClient.newCall(statusRequest).execute()) {

                if (!response.isSuccessful())
                    return Result.retry();

                JSONObject json = new JSONObject(response.body().string());
                int remoteVersion = json.getInt("versionCode");
                String updateUrl = json.getString("updateUrl");
                String remoteSha = json.getString("sha256");
                String versionName = json.getString("versionName");

                // 2. Logic Flow
                if (remoteVersion > mCurrentVersion) {
                    if (isUpdateAlreadyDownloaded(remoteVersion)) {
                        UpdateNotification.showInstallPrompt(mContext, versionName);
                    } else {
                        return downloadApk(updateUrl, remoteSha, versionName);
                    }
                }
            }
            return Result.success();

        } catch (Exception e) {
            Log.e("UpdateWorker", "Update check failed", e);
            return Result.retry();
        }
    }

    private Result downloadApk(String url, String remoteSha, String name) throws IOException {
        Request downloadRequest = new Request.Builder().url(url).addHeader(BrowserHeaders.X_REQUEST_ID,
                mSharedPreferences.getString(Preferences.UNIQUE_ID, "")).build();

        try (Response response = okHttpClient.newCall(downloadRequest).execute()) {
            if (!response.isSuccessful()) return Result.retry();

            // Write to disk using Okio
            try (BufferedSink sink = Okio.buffer(Okio.sink(updateFile))) {
                sink.writeAll(response.body().source());
            }

            // Verify SHA256
            String localSha = DigestUtils.sha256Hex(new FileInputStream(updateFile));
            if (!localSha.equalsIgnoreCase(remoteSha)) {
                updateFile.delete();
                return Result.retry();
            }

            UpdateNotification.showInstallPrompt(mContext, name);
            return Result.success();
        }
    }

    private boolean isUpdateAlreadyDownloaded(int remoteVersion) {
        if (!updateFile.exists()) return false;
        PackageInfo pi = mContext.getPackageManager()
                .getPackageArchiveInfo(updateFile.getAbsolutePath(), 0);
        return pi != null && pi.versionCode >= remoteVersion;
    }

}