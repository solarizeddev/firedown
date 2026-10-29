package com.solarized.firedown.utils;

import android.app.Activity;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.provider.Settings;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationManagerCompat;

import com.solarized.firedown.App;

/**
 * Whether the app's notifications reach the user, and the way to the
 * system's settings for them.
 *
 * <p>The first-run priming sheet asks once; someone who says "Not now" or
 * denies the system prompt is never asked again by Android. A long task then
 * runs in the foreground with a notification nobody sees, so the Downloads
 * screen says it once ({@link #claimTaskHint}) and Settings keeps a row
 * that opens the system page ({@link #openSettings}).
 */
public final class AppNotifications {

    /* Install-local, excluded from Auto Backup: a reinstall resets the
     * system permission, so the hint is shown again too. */
    private static final String LOCAL_PREFS = "backup_local";
    private static final String KEY_TASK_HINT_SHOWN = "notifications.task.hint.shown";

    private AppNotifications() {
    }

    /**
     * False when the app's notifications are blocked (the Android 13
     * permission, or the app's switch on any version) or the Downloads
     * channel, which every progress notification uses, is turned off.
     */
    public static boolean enabled(@NonNull Context context) {
        NotificationManagerCompat manager = NotificationManagerCompat.from(context);
        if (!manager.areNotificationsEnabled()) {
            return false;
        }
        return !downloadsChannelOff(manager);
    }

    /**
     * Opens the system's notification settings for the app: the Downloads
     * channel's page when only that channel is off, else the app's page,
     * whose switch also grants the Android 13 permission. Falls back to the
     * app's info page. False when no settings screen answered.
     */
    public static boolean openSettings(@NonNull Context context) {
        NotificationManagerCompat manager = NotificationManagerCompat.from(context);
        String pkg = context.getPackageName();
        Intent intent;
        if (manager.areNotificationsEnabled() && downloadsChannelOff(manager)) {
            intent = new Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, pkg)
                    .putExtra(Settings.EXTRA_CHANNEL_ID, App.DOWNLOADS_NOTIFICATION_ID);
        } else {
            intent = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, pkg);
        }
        if (start(context, intent)) {
            return true;
        }
        Intent details = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", pkg, null));
        return start(context, details);
    }

    /**
     * True the first time it is asked on this install, false after: the
     * Downloads screen's one hint when a task starts with notifications off.
     */
    public static boolean claimTaskHint(@NonNull Context context) {
        SharedPreferences prefs = context.getSharedPreferences(LOCAL_PREFS, Context.MODE_PRIVATE);
        if (prefs.getBoolean(KEY_TASK_HINT_SHOWN, false)) {
            return false;
        }
        prefs.edit().putBoolean(KEY_TASK_HINT_SHOWN, true).apply();
        return true;
    }

    private static boolean downloadsChannelOff(@NonNull NotificationManagerCompat manager) {
        NotificationChannel channel = manager.getNotificationChannel(App.DOWNLOADS_NOTIFICATION_ID);
        return channel != null && channel.getImportance() == NotificationManager.IMPORTANCE_NONE;
    }

    private static boolean start(@NonNull Context context, @NonNull Intent intent) {
        if (!(context instanceof Activity)) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        }
        try {
            context.startActivity(intent);
            return true;
        } catch (ActivityNotFoundException e) {
            return false;
        }
    }
}
