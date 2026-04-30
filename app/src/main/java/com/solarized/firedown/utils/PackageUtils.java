package com.solarized.firedown.utils;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;

/**
 * APK / package inspection helpers. Previously part of {@code Storage}, moved here
 * because none of this relates to storage paths or free-space queries.
 */
public final class PackageUtils {

    private static final String TAG = PackageUtils.class.getSimpleName();

    private PackageUtils() {
        // Utility class — no instances.
    }

    /**
     * Returns {@code true} if the APK at {@code file} has a different versionCode than
     * {@code version}, or the file does not exist, or the archive cannot be parsed.
     * Returns {@code false} only when we can positively confirm the versions match.
     */
    public static boolean isDifferentVersion(@NonNull Context context, int version, @Nullable File file) {
        if (file == null || !file.exists()) {
            return true;
        }
        try {
            PackageManager pm = context.getPackageManager();
            PackageInfo pi = pm.getPackageArchiveInfo(file.getAbsolutePath(), 0);
            if (pi == null) {
                return true;
            }
            // versionCode is deprecated API-28+ in favor of longVersionCode, but kept for
            // compatibility with existing call sites.
            return pi.versionCode != version;
        } catch (RuntimeException e) {
            Log.e(TAG, "isDifferentVersion", e);
            return true;
        }
    }
}
