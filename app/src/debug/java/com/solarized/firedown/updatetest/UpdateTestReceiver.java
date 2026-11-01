package com.solarized.firedown.updatetest;

import android.app.Notification;
import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.service.notification.StatusBarNotification;

import androidx.core.app.NotificationManagerCompat;
import androidx.preference.PreferenceManager;

import com.solarized.firedown.App;
import com.solarized.firedown.Keys;
import com.solarized.firedown.Preferences;
import com.solarized.firedown.UpdateDownloader;
import com.solarized.firedown.UpdateInstallProgress;
import com.solarized.firedown.UpdateInstallReceiver;
import com.solarized.firedown.UpdateInstaller;
import com.solarized.firedown.UpdateNotification;
import com.solarized.firedown.utils.NotificationID;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Debug builds only: the app side of scripts/update-install-test.sh. The
 * script drives the update install from a laptop and needs four things only
 * the app can do: say where the ready APK goes, mark one ready, post the
 * "Tap to update" notification (or tap it), and report the install state and
 * the update notifications. Every answer is the broadcast's result data,
 * which {@code am broadcast} prints.
 *
 * <p>The manifest entry requires {@code android.permission.DUMP} from the
 * sender: the adb shell holds it, no installed app can.
 */
public class UpdateTestReceiver extends BroadcastReceiver {

    private static final String PREFIX = "com.solarized.firedown.updatetest.";
    private static final String WHERE = PREFIX + "WHERE";
    private static final String MARK_READY = PREFIX + "MARK_READY";
    private static final String CLEAR = PREFIX + "CLEAR";
    private static final String POST_PROMPT = PREFIX + "POST_PROMPT";
    private static final String TAP_PROMPT = PREFIX + "TAP_PROMPT";
    private static final String STATE = PREFIX + "STATE";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        String answer;
        if (WHERE.equals(action)) {
            answer = where(context);
        } else if (MARK_READY.equals(action)) {
            answer = markReady(context, intent);
        } else if (CLEAR.equals(action)) {
            answer = clear(context);
        } else if (POST_PROMPT.equals(action)) {
            answer = postPrompt(context, intent);
        } else if (TAP_PROMPT.equals(action)) {
            answer = tapPrompt(context, intent);
        } else if (STATE.equals(action)) {
            answer = state(context);
        } else {
            answer = "error unknown action " + action;
        }
        setResultCode(answer.startsWith("error") ? 0 : 1);
        setResultData(answer);
    }

    private static String where(Context context) {
        File apk = Preferences.getUpdateApkFile(context);
        File dir = apk.getParentFile();
        if (dir != null && !dir.exists()) {
            dir.mkdirs();
        }
        return "path=" + apk.getAbsolutePath() + " installed=" + App.getVersionCode();
    }

    /** What {@code UpdateDownloader.markReady} leaves behind, without a download. */
    private static String markReady(Context context, Intent intent) {
        int code = intent.getIntExtra("code", -1);
        String name = intent.getStringExtra("name");
        if (code <= 0) {
            return "error no code";
        }
        File apk = Preferences.getUpdateApkFile(context);
        PreferenceManager.getDefaultSharedPreferences(context).edit()
                .putBoolean(Keys.UPDATE_READY, true)
                .putInt(Keys.UPDATE_READY_VERSION_CODE, code)
                .putString(Keys.UPDATE_READY_NAME, name == null ? "" : name)
                .remove(Keys.UPDATE_PROMPT_SNOOZE_VERSION)
                .remove(Keys.UPDATE_PROMPT_SNOOZE_UNTIL)
                .remove(Keys.UPDATE_PROMPT_SNOOZE_COUNT)
                .commit();
        return "ready path=" + apk.getAbsolutePath()
                + " size=" + (apk.exists() ? apk.length() : -1)
                + " readable=" + readable(apk)
                + " installed=" + App.getVersionCode();
    }

    private static String clear(Context context) {
        UpdateDownloader.clearReady(context);
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        prefs.edit()
                .remove(Keys.UPDATE_PROMPT_SNOOZE_VERSION)
                .remove(Keys.UPDATE_PROMPT_SNOOZE_UNTIL)
                .remove(Keys.UPDATE_PROMPT_SNOOZE_COUNT)
                .remove(Keys.UPDATE_META)
                .commit();
        NotificationManagerCompat nm = NotificationManagerCompat.from(context);
        nm.cancel(NotificationID.APK_UPDATE_PROMPT_INSTALL);
        nm.cancel(NotificationID.APK_UPDATE_FAILED_INSTALL);
        nm.cancel(NotificationID.APK_UPDATE_SUCCESSFUL_INSTALL);
        return "cleared installed=" + App.getVersionCode();
    }

    private static String postPrompt(Context context, Intent intent) {
        UpdateNotification.showInstallPrompt(context, intent.getStringExtra("name"));
        return "posted notifications="
                + NotificationManagerCompat.from(context).areNotificationsEnabled();
    }

    /** What tapping the "Tap to update" notification sends. */
    private static String tapPrompt(Context context, Intent intent) {
        NotificationManagerCompat.from(context).cancel(NotificationID.APK_UPDATE_PROMPT_INSTALL);
        Intent tap = new Intent(context, UpdateInstallReceiver.class);
        tap.setAction(UpdateInstallReceiver.ACTION_INSTALL_UPDATE);
        tap.putExtra(Keys.UPDATE_NAME, intent.getStringExtra("name"));
        context.sendBroadcast(tap);
        return "tapped";
    }

    /**
     * The install state the sheet and the About row read, then each update
     * notification as {@code n<id>=<what it shows>}.
     */
    private static String state(Context context) {
        UpdateInstallProgress.Snapshot s = UpdateInstaller.state().getValue();
        StringBuilder out = new StringBuilder();
        if (s == null) {
            out.append("phase=NONE");
        } else {
            out.append("phase=").append(s.phase)
                    .append(" percent=").append(s.percent)
                    .append(" gen=").append(s.generation)
                    .append(" notif=").append(s.fromNotification);
        }
        out.append(" installed=").append(App.getVersionCode());
        NotificationManager nm = context.getSystemService(NotificationManager.class);
        StatusBarNotification[] active = nm == null ? null : nm.getActiveNotifications();
        int[] ids = {
                NotificationID.APK_UPDATE_PROMPT_INSTALL,
                NotificationID.APK_UPDATE_FAILED_INSTALL,
                NotificationID.APK_UPDATE_SUCCESSFUL_INSTALL,
        };
        for (int id : ids) {
            out.append(" n").append(id).append('=').append(describe(active, id));
        }
        return out.toString();
    }

    private static String describe(StatusBarNotification[] active, int id) {
        if (active == null) {
            return "unknown";
        }
        for (StatusBarNotification sbn : active) {
            if (sbn.getId() != id) {
                continue;
            }
            Bundle extras = sbn.getNotification().extras;
            if (extras.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE, false)) {
                return "indeterminate";
            }
            int max = extras.getInt(Notification.EXTRA_PROGRESS_MAX, 0);
            if (max > 0) {
                return "progress:" + extras.getInt(Notification.EXTRA_PROGRESS, 0) + "/" + max;
            }
            return "shown";
        }
        return "none";
    }

    private static boolean readable(File file) {
        try (InputStream in = new FileInputStream(file)) {
            return in.read() >= 0;
        } catch (IOException | SecurityException e) {
            return false;
        }
    }
}
