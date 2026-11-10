package com.solarized.firedown;

import android.app.ActivityManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInstaller;
import android.util.Log;

import androidx.core.content.IntentSanitizer;
import androidx.preference.PreferenceManager;

import com.solarized.firedown.utils.BuildUtils;
import com.solarized.firedown.utils.Utils;

public class UpdateInstallReceiver extends BroadcastReceiver {

    private static final String ACTION_CONFIRM_INSTALL = "android.content.pm.action.CONFIRM_INSTALL";
    public static final String ACTION_INSTALL_UPDATE = "com.solarized.firedown.action.INSTALL_UPDATE";

    private static final String TAG = UpdateInstallReceiver.class.getName();

    @Override
    public void onReceive(Context context, Intent intent) {
        Log.d(TAG, "onReceive: " + Utils.intentToString(intent));

        if (Intent.ACTION_MY_PACKAGE_REPLACED.equals(intent.getAction())) {
            handlePackageReplaced(context);
            return;
        }

        if (ACTION_INSTALL_UPDATE.equals(intent.getAction())) {
            handleUserTapInstall(context, intent);
            return;
        }

        handleSessionCallback(context, intent);
    }

    // ── MY_PACKAGE_REPLACED: primary success detection path ──────────────────

    private void handlePackageReplaced(Context context) {
        Log.d(TAG, "Package replaced — checking for pending update");
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        String updateName = prefs.getString(Keys.UPDATE_META, null);

        // A progress notification left at "Installing…" when Android replaced
        // the app goes either way (the success notification below cancels it
        // too, but only when it posts).
        UpdateNotification.cancelInstallProgress(context);
        UpdateNotification.cancelConfirmInstall(context);
        if (updateName != null) {
            prefs.edit().remove(Keys.UPDATE_META).apply();
            UpdateNotification.showNotificationSuccess(context, updateName);
        }

        // The update is installed — drop the verified "ready" record (and the
        // downloaded APK it points at) plus any leftover pending-download record,
        // so the in-app sheet / notification don't re-offer an installed version.
        UpdateDownloader.clearReady(context);
        UpdateDownloader.clearPending(context);
    }

    // ── Notification tap: user chose to install ──────────────────────────────

    private void handleUserTapInstall(Context context, Intent intent) {
        Log.d(TAG, "User requested install from notification");
        // The copy runs in a worker: a receiver may hold its broadcast for 60 s
        // at most, and a slow phone copying the APK past that was killed
        // mid-copy, leaving its installer session open.
        UpdateInstallWorker.enqueue(context, intent.getStringExtra(Keys.UPDATE_NAME));
    }

    // ── PackageInstaller session callback ────────────────────────────────────

    private void handleSessionCallback(Context context, Intent intent) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -1);
        String message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);

        Log.d(TAG, "Session callback status: " + status);

        switch (status) {
            case PackageInstaller.STATUS_PENDING_USER_ACTION:
                // Android's dialog takes over from the sheet / notification.
                UpdateInstaller.onAndroidAnswered(context);
                // Use the typed overload on T+ to avoid Parcelable type-confusion.
                Intent confirmationIntent = BuildUtils.hasAndroidTiramisu()
                        ? intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent.class)
                        : intent.getParcelableExtra(Intent.EXTRA_INTENT);
                if (confirmationIntent != null) {
                    // In the background Android drops this start without a
                    // word (Android 10 and later block activity starts from the
                    // background), so the dialog is also offered as a
                    // notification; the install's outcome removes it.
                    if (!appInForeground()) {
                        UpdateNotification.showConfirmInstall(context, confirmationIntent);
                    }
                    confirmationIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    try {
                        context.startActivity(confirmationIntent);
                    } catch (Exception e) {
                        Log.e(TAG, "Failed to launch installer", e);
                        UpdateNotification.showConfirmInstall(context, confirmationIntent);
                    }
                }
                break;

            case PackageInstaller.STATUS_SUCCESS:
                // Redundant path — MY_PACKAGE_REPLACED is primary.
                // Guard: only show if UPDATE_META still exists (not already
                // consumed by handlePackageReplaced).
                Log.d(TAG, "Session callback: success (redundant path)");
                UpdateInstaller.onAndroidAnswered(context);
                UpdateNotification.cancelConfirmInstall(context);
                String updateName = prefs.getString(Keys.UPDATE_META, null);
                if (updateName != null) {
                    prefs.edit().remove(Keys.UPDATE_META).apply();
                    UpdateNotification.showNotificationSuccess(context, updateName);
                }
                break;

            case PackageInstaller.STATUS_FAILURE:
            case PackageInstaller.STATUS_FAILURE_ABORTED:
            case PackageInstaller.STATUS_FAILURE_BLOCKED:
            case PackageInstaller.STATUS_FAILURE_CONFLICT:
            case PackageInstaller.STATUS_FAILURE_INCOMPATIBLE:
            case PackageInstaller.STATUS_FAILURE_INVALID:
            case PackageInstaller.STATUS_FAILURE_STORAGE:
                Log.e(TAG, "Installation failed: " + message);
                UpdateInstaller.onAndroidRefused(context);
                UpdateNotification.cancelConfirmInstall(context);
                prefs.edit().remove(Keys.UPDATE_META).apply();
                UpdateNotification.showNotificationFailed(context);
                break;

            default:
                Log.e(TAG, "Unknown status: " + status);
                break;
        }
    }

    /** An activity of this app is on screen, so the dialog's start is allowed. */
    private static boolean appInForeground() {
        ActivityManager.RunningAppProcessInfo info = new ActivityManager.RunningAppProcessInfo();
        try {
            ActivityManager.getMyMemoryState(info);
        } catch (RuntimeException e) {
            return false;
        }
        return info.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND;
    }

    private static IntentSanitizer.Builder getBuilder() {
        IntentSanitizer.Builder sanitizer = new IntentSanitizer.Builder();
        sanitizer.allowAnyComponent();
        sanitizer.allowExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, Boolean.class);
        sanitizer.allowExtra(Intent.EXTRA_INSTALLER_PACKAGE_NAME, String.class);
        sanitizer.allowExtra(PackageInstaller.EXTRA_SESSION_ID, Integer.class);
        sanitizer.allowFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        sanitizer.allowAction(ACTION_CONFIRM_INSTALL);
        // Allow the system's package installer, whatever it is
        sanitizer.allowPackage(p -> true);
        return sanitizer;
    }
}