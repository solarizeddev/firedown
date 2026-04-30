package com.solarized.firedown;

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

import java.io.File;

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

        if (updateName != null) {
            prefs.edit().remove(Keys.UPDATE_META).apply();
            UpdateNotification.showNotificationSuccess(context, updateName);
        }

        // Clean up the downloaded APK
        File updateFile = new File(context.getFilesDir(), Preferences.UPDATE_APK);
        if (updateFile.exists()) {
            updateFile.delete();
        }
    }

    // ── Notification tap: user chose to install ──────────────────────────────

    private void handleUserTapInstall(Context context, Intent intent) {
        Log.d(TAG, "User requested install from notification");
        String updateName = intent.getStringExtra(Keys.UPDATE_NAME);

        // Use goAsync to keep the receiver alive while the install thread runs.
        // Without this, the system may kill the process after onReceive returns.
        PendingResult pendingResult = goAsync();
        new Thread(() -> {
            try {
                UpdateInstaller.install(context, updateName);
            } finally {
                pendingResult.finish();
            }
        }).start();
    }

    // ── PackageInstaller session callback ────────────────────────────────────

    private void handleSessionCallback(Context context, Intent intent) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -1);
        String message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);

        Log.d(TAG, "Session callback status: " + status);

        switch (status) {
            case PackageInstaller.STATUS_PENDING_USER_ACTION:
                // Use the typed overload on T+ to avoid Parcelable type-confusion.
                Intent confirmationIntent = BuildUtils.hasAndroidTiramisu()
                        ? intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent.class)
                        : intent.getParcelableExtra(Intent.EXTRA_INTENT);
                if (confirmationIntent != null) {
                    confirmationIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    try {
                        context.startActivity(confirmationIntent);
                    } catch (Exception e) {
                        Log.e(TAG, "Failed to launch installer", e);
                        UpdateNotification.showNotificationFailed(context);
                    }
                }
                break;

            case PackageInstaller.STATUS_SUCCESS:
                // Redundant path — MY_PACKAGE_REPLACED is primary.
                // Guard: only show if UPDATE_META still exists (not already
                // consumed by handlePackageReplaced).
                Log.d(TAG, "Session callback: success (redundant path)");
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
                prefs.edit().remove(Keys.UPDATE_META).apply();
                UpdateNotification.showNotificationFailed(context);
                break;

            default:
                Log.e(TAG, "Unknown status: " + status);
                break;
        }
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