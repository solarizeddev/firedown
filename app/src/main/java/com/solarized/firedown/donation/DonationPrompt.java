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
 * starts over instead of carrying "already asked" or a Donate tap across, the
 * notification-priming flag's rule. The install date is the package's own
 * {@code firstInstallTime}, which a reinstall resets the same way.</p>
 */
public final class DonationPrompt {

    private static final String LOCAL_PREFS = "backup_local";
    private static final String KEY_LAST_FINISHED = "donation_last_download_finished";
    private static final String KEY_FINISHED_COUNT = "donation_finished_downloads";
    private static final String KEY_LAST_FAILED = "donation_last_download_failed";
    private static final String KEY_LAST_SHOWN = "donation_prompt_last_shown";
    private static final String KEY_TIMES_SHOWN = "donation_prompt_times_shown";
    private static final String KEY_DONATE_TAPPED_AT = "donation_prompt_donate_tapped_at";
    /** Before 1.2.12 a Donate tap ended the asks for good, with no time. */
    private static final String KEY_DONATED_LEGACY = "donation_prompt_donated";

    private DonationPrompt() {}

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(LOCAL_PREFS, Context.MODE_PRIVATE);
    }

    /** A regular (non-vault) download finished: the moment the sheet waits
     *  for. Also counted, for the install's life: the Downloads list only
     *  holds what the user kept. Download threads call this concurrently. */
    public static synchronized void noteDownloadFinished(Context context) {
        SharedPreferences p = prefs(context);
        p.edit()
                .putLong(KEY_LAST_FINISHED, System.currentTimeMillis())
                .putInt(KEY_FINISHED_COUNT, p.getInt(KEY_FINISHED_COUNT, 0) + 1)
                .apply();
    }

    /** A regular download ended in an error: no ask until one works again. */
    public static void noteDownloadFailed(Context context) {
        prefs(context).edit().putLong(KEY_LAST_FAILED, System.currentTimeMillis()).apply();
    }

    /** Counted when the sheet SHOWS (its first onCreate), not on a button,
     *  so a sheet the user never answers (the app killed under it) still
     *  counts, and a navigation that never opened it does not. */
    public static synchronized void markShown(Context context) {
        SharedPreferences p = prefs(context);
        p.edit()
                .putLong(KEY_LAST_SHOWN, System.currentTimeMillis())
                .putInt(KEY_TIMES_SHOWN, p.getInt(KEY_TIMES_SHOWN, 0) + 1)
                .apply();
    }

    /** Donate was tapped, on the sheet or the Settings row: pause the asks
     *  (DonationPromptPolicy.DONATE_TAP_PAUSE_MS). */
    public static void markDonateTapped(Context context) {
        prefs(context).edit()
                .putLong(KEY_DONATE_TAPPED_AT, System.currentTimeMillis())
                .remove(KEY_DONATED_LEGACY)
                .apply();
    }

    /** Whether the sheet may show now. {@code finishedInList} is the
     *  caller's count of finished regular downloads in the list (a database
     *  read); downloads from before the lifetime counter existed are only
     *  there. */
    public static boolean shouldShow(Context context, int finishedInList, boolean downloadRunning) {
        SharedPreferences p = prefs(context);
        long tappedAt = p.getLong(KEY_DONATE_TAPPED_AT, 0);
        if (tappedAt == 0 && p.getBoolean(KEY_DONATED_LEGACY, false)) {
            // A tap from before the pause had no time: start the pause now.
            markDonateTapped(context);
            tappedAt = System.currentTimeMillis();
        }
        DonationPromptPolicy.State state = new DonationPromptPolicy.State(
                installedAt(context),
                Math.max(finishedInList, p.getInt(KEY_FINISHED_COUNT, 0)),
                p.getLong(KEY_LAST_FINISHED, 0),
                p.getLong(KEY_LAST_FAILED, 0),
                downloadRunning,
                p.getLong(KEY_LAST_SHOWN, 0),
                p.getInt(KEY_TIMES_SHOWN, 0),
                tappedAt);
        return DonationPromptPolicy.shouldShow(state, System.currentTimeMillis());
    }

    /** The donate page needs the internet; with none, or behind a captive
     *  portal (connected but not validated), the sheet would send the user
     *  to an error or login page. */
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
        return caps != null
                && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
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
