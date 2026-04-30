package com.solarized.firedown;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Environment;
import android.os.StatFs;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.preference.PreferenceManager;

import com.solarized.firedown.utils.BuildUtils;

import org.apache.commons.io.FileUtils;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Locale;

/**
 * Storage path resolution, free/total space queries, and size formatting.
 *
 * <p>This is a stateless utility class. Every method takes a {@link Context} explicitly
 * so callers control the lifecycle and tests can substitute one. Do not convert this to
 * a singleton — there is nothing to cache and no coordination needed between calls.
 *
 * <p>Package-version inspection previously lived here; it has been moved to
 * {@code PackageUtils} since it has nothing to do with storage.
 */
public final class StoragePaths {

    private static final String TAG = StoragePaths.class.getSimpleName();

    public static final int PERMISSIONS_REQUESTS = 100;

    private static final String FOLDERNAME = "Firedown";
    private static final String SAFE_FOLDERNAME = "safe";
    private static final String CACHE_FOLDERNAME = "content";
    private static final String THUMBS_FOLDERNAME = "thumbs";

    private static final long K = 1024L;
    private static final long M = K * K;
    private static final long G = M * K;
    private static final long T = G * K;

    public static final String ERROR = "0 KB";

    private StoragePaths() {
        // Utility class — no instances.
    }

    // ---------------------------------------------------------------------
    // Folder creation / cleanup
    // ---------------------------------------------------------------------

    public static void clearCacheFolder(@NonNull Context context) {
        File file = new File(getCachePath(context));
        ensureFolder(file, "clearCacheFolder");
        deleteRecursive(file);
    }

    public static void ensureThumbsPath(@NonNull Context context) {
        ensureFolder(new File(getThumbsPath(context)), "ensureThumbsPath");
    }

    public static void ensureDownloadPath(@NonNull Context context) {
        ensureFolder(new File(getDownloadPath(context)), "ensureDownloadPath");
    }

    public static void ensureSafePath(@NonNull Context context) {
        ensureFolder(new File(getSafePath(context)), "ensureSafePath");
    }

    private static void ensureFolder(@NonNull File folder, @NonNull String tag) {
        try {
            FileUtils.forceMkdir(folder);
        } catch (IOException e) {
            Log.e(TAG, tag, e);
        }
    }

    // ---------------------------------------------------------------------
    // Path resolution
    // ---------------------------------------------------------------------

    public static boolean isSDCardAvailable(@NonNull Context context) {
        File[] files = context.getExternalFilesDirs(Environment.DIRECTORY_DOWNLOADS);
        return files != null && files.length > 1 && files[1] != null;
    }

    public static String getDownloadPath(@NonNull Context context) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        if (isSDCardAvailable(context)) {
            int pref = prefs.getInt(Preferences.SETTINGS_DOWNLOADS, Preferences.DEFAULT_DOWNLOADS);
            return (pref == Preferences.DEFAULT_DOWNLOADS)
                    ? getEnvironmentPath(context)
                    : getSDCardPath(context);
        }
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "ExternalStorage state: " + Environment.getExternalStorageState());
        }
        return getEnvironmentPath(context);
    }

    private static String getEnvironmentPath(@NonNull Context context) {
        if (Environment.MEDIA_MOUNTED.equalsIgnoreCase(Environment.getExternalStorageState())) {
            return Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                    + File.separator + FOLDERNAME;
        }
        return context.getFilesDir() + File.separator + FOLDERNAME;
    }

    public static String getSafePath(@NonNull Context context) {
        return context.getFilesDir() + File.separator + SAFE_FOLDERNAME;
    }

    public static String getCachePath(@NonNull Context context) {
        return context.getCacheDir() + File.separator + CACHE_FOLDERNAME;
    }

    public static String getThumbsPath(@NonNull Context context) {
        return context.getCacheDir() + File.separator + THUMBS_FOLDERNAME;
    }

    /**
     * Resolves the removable SD card path.
     *
     * <p>Uses the public {@link StorageVolume} API (available since API 24). On API 30+
     * we use {@link StorageVolume#getDirectory()}; below that we resolve via
     * {@code getExternalFilesDirs()[1]} and walk up to the volume root, since
     * {@code StorageVolume#getPath()} was only made public in API 30.
     *
     * <p>Falls back to app-scoped secondary external storage ({@code getExternalFilesDirs()[1]})
     * when no removable volume is reported — this is always writable without
     * {@code WRITE_EXTERNAL_STORAGE}.
     */
    public static String getSDCardPath(@NonNull Context context) {
        File sdcardRoot = findRemovableVolumeRoot(context);
        if (sdcardRoot != null) {
            return sdcardRoot.getAbsolutePath()
                    + File.separator + Environment.DIRECTORY_DOWNLOADS
                    + File.separator + FOLDERNAME;
        }
        File[] files = context.getExternalFilesDirs(Environment.DIRECTORY_DOWNLOADS);
        if (files != null && files.length > 1 && files[1] != null) {
            return files[1] + File.separator + FOLDERNAME;
        }
        return (files != null && files.length > 0 ? files[0] : context.getFilesDir())
                + File.separator + FOLDERNAME;
    }

    private static File findRemovableVolumeRoot(@NonNull Context context) {
        StorageManager sm = (StorageManager) context.getSystemService(Context.STORAGE_SERVICE);
        if (sm == null) {
            return null;
        }
        try {
            List<StorageVolume> volumes = sm.getStorageVolumes();
            for (StorageVolume volume : volumes) {
                if (!volume.isRemovable() || volume.isPrimary()) {
                    continue;
                }
                if (BuildUtils.hasAndroidR()) {
                    // API 30+: getDirectory() gives the mount point directly.
                    File dir = volume.getDirectory();
                    if (dir != null) {
                        return dir;
                    }
                } else {
                    // API 24–29: getPath() isn't public yet, but getExternalFilesDirs()[1]
                    // lives under the removable volume root, so walk up.
                    File[] files = context.getExternalFilesDirs(null);
                    if (files != null && files.length > 1 && files[1] != null) {
                        return walkUpToVolumeRoot(files[1]);
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "findRemovableVolumeRoot", e);
        }
        return null;
    }

    /**
     * getExternalFilesDirs()[1] typically returns
     * {@code /storage/XXXX-XXXX/Android/data/<pkg>/files} — walk up four levels
     * to reach {@code /storage/XXXX-XXXX}.
     */
    private static File walkUpToVolumeRoot(@NonNull File appScoped) {
        File f = appScoped;
        for (int i = 0; i < 4 && f != null; i++) {
            f = f.getParentFile();
        }
        return f;
    }

    // ---------------------------------------------------------------------
    // Space queries
    // ---------------------------------------------------------------------

    public static boolean externalMemoryAvailable() {
        return Environment.MEDIA_MOUNTED.equals(Environment.getExternalStorageState());
    }

    public static String getAvailableInternalMemorySize() {
        return formatAvailable(Environment.getDataDirectory());
    }

    public static String getTotalInternalMemorySize() {
        return formatTotal(Environment.getDataDirectory());
    }

    public static String getAvailableExternalMemorySize() {
        if (!externalMemoryAvailable()) {
            return ERROR;
        }
        return formatAvailable(Environment.getExternalStorageDirectory());
    }

    public static String getTotalExternalMemorySize() {
        if (!externalMemoryAvailable()) {
            return ERROR;
        }
        return formatTotal(Environment.getExternalStorageDirectory());
    }

    public static String getAvailableSDCardMemorySize(@NonNull Context context) {
        if (!externalMemoryAvailable()) {
            return ERROR;
        }
        try {
            File path = new File(getSDCardPath(context));
            if (!path.exists() && !path.mkdirs()) {
                Log.w(TAG, "getAvailableSDCardMemorySize: mkdirs failed for " + path);
            }
            return formatAvailable(path);
        } catch (IllegalArgumentException e) {
            Log.e(TAG, "getAvailableSDCardMemorySize", e);
            return ERROR;
        }
    }

    private static String formatAvailable(File path) {
        if (path == null) {
            return ERROR;
        }
        try {
            StatFs stat = new StatFs(path.getPath());
            return convertToStringRepresentation(stat.getAvailableBlocksLong() * stat.getBlockSizeLong());
        } catch (IllegalArgumentException e) {
            Log.e(TAG, "formatAvailable: " + path, e);
            return ERROR;
        }
    }

    private static String formatTotal(File path) {
        if (path == null) {
            return ERROR;
        }
        try {
            StatFs stat = new StatFs(path.getPath());
            return convertToStringRepresentation(stat.getBlockCountLong() * stat.getBlockSizeLong());
        } catch (IllegalArgumentException e) {
            Log.e(TAG, "formatTotal: " + path, e);
            return ERROR;
        }
    }

    // ---------------------------------------------------------------------
    // Formatting
    // ---------------------------------------------------------------------

    public static String convertToStringRepresentation(final long value) {
        if (value < 1) {
            return ERROR;
        }
        final long[] dividers = { T, G, M, K, 1 };
        final String[] units = { "TB", "GB", "MB", "KB", "B" };
        for (int i = 0; i < dividers.length; i++) {
            if (value >= dividers[i]) {
                return format(value, dividers[i], units[i]);
            }
        }
        return ERROR;
    }

    private static String format(final long value, final long divider, final String unit) {
        final double result = divider > 1 ? (double) value / (double) divider : (double) value;
        return String.format(Locale.US, "%.1f %s", result, unit);
    }

    // ---------------------------------------------------------------------
    // Internal helpers
    // ---------------------------------------------------------------------

    private static void deleteRecursive(@NonNull File fileOrDirectory) {
        if (!fileOrDirectory.isDirectory()) {
            return;
        }
        File[] children = fileOrDirectory.listFiles();
        if (children == null) {
            return;
        }
        for (File child : children) {
            try {
                FileUtils.forceDelete(child);
            } catch (IOException e) {
                Log.e(TAG, "deleteRecursive: " + child, e);
            }
        }
    }
}