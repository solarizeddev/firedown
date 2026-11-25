package com.solarized.firedown;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.InstallSourceInfo;
import android.content.pm.PackageManager;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

/**
 * Where this install came from, as one word from a fixed list, sent on the
 * update ping as {@code X-App-Source} so the ping statistics can count
 * installs per source beside DAU/MAU.
 *
 * <p>A word from a fixed list, never the installer's package name: an
 * unusual installer's name would single out a handful of phones. Anything
 * not on the list is {@link #OTHER}.
 *
 * <p>Read ONCE and kept. Android remembers only the LAST app that installed
 * the APK, and Firedown's own updater installs every update it downloads, so
 * after one in-app update the installer reads as Firedown itself. The first
 * reading is stored in {@code backup_local.xml} (excluded from Auto Backup,
 * like the token secret) and sent from then on. An install that had already
 * updated itself before this build reads as {@link #APP}: its origin is
 * lost. Such a reading is the one that may be replaced later, when a store
 * or a manual install puts another installer on record.
 *
 * <p>Sent only on the firedown.app request, with the token, and disclosed in
 * the website's privacy page beside the update check (the honest-copy rule:
 * the bullet changes with the header set). The server keeps only the values
 * of {@link #VALUES} and drops anything else.
 */
public final class UpdatePingSource {

    /** Request header carrying the source. Logged by nginx as a column. */
    public static final String HEADER = "X-App-Source";

    public static final String FDROID = "fdroid";
    public static final String DROIDIFY = "droidify";
    public static final String NEOSTORE = "neostore";
    public static final String OBTAINIUM = "obtainium";
    public static final String PLAY = "play";
    /** Installed by hand: a browser or a file manager, through Android's installer. */
    public static final String SIDELOAD = "sideload";
    /** Firedown installed itself: an in-app update, the origin before it unknown. */
    public static final String APP = "app";
    public static final String OTHER = "other";

    /** Every value the header can carry; the server's list must match. */
    public static final String[] VALUES = {
            FDROID, DROIDIFY, NEOSTORE, OBTAINIUM, PLAY, SIDELOAD, APP, OTHER
    };

    private static final String LOCAL_PREFS = "backup_local";
    private static final String KEY_SOURCE = "update_ping_source";

    private UpdatePingSource() {
    }

    /** The stored source, reading the installer the first time. */
    @NonNull
    public static String current(@NonNull Context context) {
        SharedPreferences prefs = context.getSharedPreferences(LOCAL_PREFS, Context.MODE_PRIVATE);
        String stored = prefs.getString(KEY_SOURCE, null);
        String read = bucketFor(installerOf(context), context.getPackageName());
        String next = choose(stored, read);
        if (!next.equals(stored)) {
            prefs.edit().putString(KEY_SOURCE, next).apply();
        }
        return next;
    }

    /**
     * The value to keep: the stored one, unless there is none or it is an
     * unknown one ({@link #APP}, or a value this build no longer knows) and
     * the installer now names a real source.
     */
    @VisibleForTesting
    @NonNull
    static String choose(@Nullable String stored, @NonNull String read) {
        if (stored == null || !isKnown(stored)) {
            return read;
        }
        if (APP.equals(stored) && !APP.equals(read)) {
            return read;
        }
        return stored;
    }

    /** The bucket for an installer's package name. */
    @VisibleForTesting
    @NonNull
    static String bucketFor(@Nullable String installer, @NonNull String ownPackage) {
        if (installer == null || installer.isEmpty()) {
            return SIDELOAD;
        }
        if (installer.equals(ownPackage) || installer.startsWith("com.solarized.firedown")) {
            return APP;
        }
        switch (installer) {
            case "org.fdroid.fdroid":
            case "org.fdroid.basic":
                return FDROID;
            case "com.looker.droidify":
                return DROIDIFY;
            case "com.machiav3lli.fdroid":
                return NEOSTORE;
            case "com.android.vending":
                return PLAY;
            case "com.google.android.packageinstaller":
            case "com.android.packageinstaller":
            case "com.samsung.android.packageinstaller":
                return SIDELOAD;
            default:
                break;
        }
        if (installer.startsWith("dev.imranr.obtainium")) {
            return OBTAINIUM;
        }
        return OTHER;
    }

    @VisibleForTesting
    static boolean isKnown(@Nullable String value) {
        if (value == null) {
            return false;
        }
        for (String known : VALUES) {
            if (known.equals(value)) {
                return true;
            }
        }
        return false;
    }

    /** The app that installed this APK last, or null when none is recorded. */
    @Nullable
    private static String installerOf(@NonNull Context context) {
        PackageManager pm = context.getPackageManager();
        String own = context.getPackageName();
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                InstallSourceInfo info = pm.getInstallSourceInfo(own);
                return info.getInstallingPackageName();
            }
            return pm.getInstallerPackageName(own);
        } catch (PackageManager.NameNotFoundException | RuntimeException e) {
            return null;
        }
    }
}
