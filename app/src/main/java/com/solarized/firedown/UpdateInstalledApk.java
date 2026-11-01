package com.solarized.firedown;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.preference.PreferenceManager;

import com.solarized.firedown.update.UpdatePatch;

import java.io.File;
import java.io.IOException;

/**
 * The APK this app is running from, which an update patch starts from.
 *
 * A patch rebuilds the new release byte for byte from the PUBLISHED file of
 * the old one, so it only applies when the installed APK is that file. It is
 * for a normal install: Android stores the APK it was handed, unchanged, as
 * base.apk. The hash decides: status.json names each patch's starting APK by
 * its SHA-256, and a phone whose APK hashes to none of them downloads the
 * compressed or the plain APK instead. An install split into several APKs
 * (never how Firedown ships) is not one published file, so it gets no patch.
 */
public final class UpdateInstalledApk {

    private static final String TAG = "UpdateInstalledApk";

    private UpdateInstalledApk() {}

    /** The installed base.apk, or null when the app is installed as split APKs. */
    @Nullable
    public static File file(Context context) {
        ApplicationInfo info = context.getApplicationInfo();
        if (info.splitSourceDirs != null && info.splitSourceDirs.length > 0) {
            return null;
        }
        if (info.sourceDir == null) {
            return null;
        }
        File apk = new File(info.sourceDir);
        return apk.isFile() ? apk : null;
    }

    /**
     * Lower-case hex SHA-256 of the installed APK, or null when it can't be
     * read. Reading 200 MB takes a second or two, so the result is kept under
     * the APK's path, size and install time and computed once per installed
     * build. Call off the main thread.
     */
    @Nullable
    public static String sha256(Context context) {
        File apk = file(context);
        if (apk == null) {
            return null;
        }
        String key = apk.getPath() + "|" + apk.length() + "|" + lastUpdateTime(context);
        SharedPreferences p = PreferenceManager.getDefaultSharedPreferences(context);
        if (key.equals(p.getString(Keys.UPDATE_INSTALLED_SHA_KEY, null))) {
            String cached = p.getString(Keys.UPDATE_INSTALLED_SHA, null);
            if (cached != null) {
                return cached;
            }
        }
        try {
            String sha = UpdatePatch.sha256Hex(apk, null);
            p.edit()
                    .putString(Keys.UPDATE_INSTALLED_SHA_KEY, key)
                    .putString(Keys.UPDATE_INSTALLED_SHA, sha)
                    .apply();
            return sha;
        } catch (IOException e) {
            Log.w(TAG, "could not hash the installed APK", e);
            return null;
        }
    }

    private static long lastUpdateTime(Context context) {
        try {
            PackageInfo info = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            return info.lastUpdateTime;
        } catch (PackageManager.NameNotFoundException e) {
            return 0L;
        }
    }
}
