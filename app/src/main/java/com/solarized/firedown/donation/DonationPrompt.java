package com.solarized.firedown.donation;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;

/**
 * The donation sheet's state, and the facts {@link DonationPromptPolicy}
 * decides on. Everything is read on the phone; nothing is sent.
 *
 * <p>The state lives in {@code backup_local.xml}, which Auto Backup
 * excludes (backup_rules / data_extraction), so a restore onto a new phone
 * starts over instead of carrying "already asked" or "donated" across, the
 * notification-priming flag's rule. The install date is the package's own
 * {@code firstInstallTime}, which a reinstall resets the same way.</p>
 */
public final class DonationPrompt {

    private static final String LOCAL_PREFS = "backup_local";
    private static final String KEY_LAST_FINISHED = "donation_last_download_finished";
    private static final String KEY_LAST_SHOWN = "donation_prompt_last_shown";
    private static final String KEY_TIMES_SHOWN = "donation_prompt_times_shown";
    private static final String KEY_DONATED = "donation_prompt_donated";

    private DonationPrompt() {}

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(LOCAL_PREFS, Context.MODE_PRIVATE);
    }

    /** A regular (non-vault) download finished: the moment the sheet waits for. */
    public static void noteDownloadFinished(Context context) {
        prefs(context).edit().putLong(KEY_LAST_FINISHED, System.currentTimeMillis()).apply();
    }

    /** Counted when the sheet SHOWS, not on a button, so a sheet the user
     *  never answers (the app killed under it) still counts. */
    public static void markShown(Context context) {
        SharedPreferences p = prefs(context);
        p.edit()
                .putLong(KEY_LAST_SHOWN, System.currentTimeMillis())
                .putInt(KEY_TIMES_SHOWN, p.getInt(KEY_TIMES_SHOWN, 0) + 1)
                .apply();
    }

    /** Donate was tapped, on the sheet or the Settings row: never ask again. */
    public static void markDonated(Context context) {
        prefs(context).edit().putBoolean(KEY_DONATED, true).apply();
    }

    /** Whether the sheet may show now. {@code finishedDownloads} is the
     *  caller's count of finished regular downloads (a database read). */
    public static boolean shouldShow(Context context, int finishedDownloads) {
        SharedPreferences p = prefs(context);
        DonationPromptPolicy.State state = new DonationPromptPolicy.State(
                installedAt(context),
                finishedDownloads,
                p.getLong(KEY_LAST_FINISHED, 0),
                p.getLong(KEY_LAST_SHOWN, 0),
                p.getInt(KEY_TIMES_SHOWN, 0),
                p.getBoolean(KEY_DONATED, false));
        return DonationPromptPolicy.shouldShow(state, System.currentTimeMillis());
    }

    /** The donate page needs the network; with none, the sheet would send
     *  the user to an error page. */
    public static boolean isOnline(Context context) {
        ConnectivityManager cm = context.getSystemService(ConnectivityManager.class);
        if (cm == null) {
            return false;
        }
        Network network = cm.getActiveNetwork();
        if (network == null) {
            return false;
        }
        NetworkCapabilities caps = cm.getNetworkCapabilities(network);
        return caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
    }

    private static long installedAt(Context context) {
        try {
            return context.getPackageManager()
                    .getPackageInfo(context.getPackageName(), 0)
                    .firstInstallTime;
        } catch (PackageManager.NameNotFoundException e) {
            return 0;
        }
    }
}
