package com.solarized.firedown;

import android.annotation.SuppressLint;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.util.Log;

import androidx.preference.PreferenceManager;

import com.solarized.firedown.utils.BuildUtils;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

public final class UpdateInstaller {

    private static final String TAG = UpdateInstaller.class.getName();
    private static final int SESSION_ID = 123;

    private UpdateInstaller() {}

    /**
     * Runs synchronously — caller is responsible for threading.
     * See {@link UpdateInstallReceiver#handleUserTapInstall} which uses goAsync().
     */
    @SuppressLint("RequestInstallPackagesPolicy")
    public static void install(Context context, String updateName) {
        try {
            File updateFile = new File(context.getFilesDir(), Preferences.UPDATE_APK);

            if (!updateFile.exists()) {
                Log.e(TAG, "Update APK not found");
                UpdateNotification.showNotificationFailed(context);
                return;
            }

            PackageInstaller.Session session = createSession(context);

            try (InputStream in = new FileInputStream(updateFile)) {
                OutputStream out = session.openWrite("package", 0, updateFile.length());
                byte[] buffer = new byte[65536];
                int bytesRead;
                while ((bytesRead = in.read(buffer)) != -1) {
                    out.write(buffer, 0, bytesRead);
                }
                session.fsync(out);
                out.close();

                PendingIntent pendingIntent = PendingIntent.getBroadcast(
                        context,
                        SESSION_ID,
                        new Intent(context, UpdateInstallReceiver.class),
                        BuildUtils.hasAndroidS() ?
                                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE
                                : PendingIntent.FLAG_UPDATE_CURRENT
                );

                PreferenceManager.getDefaultSharedPreferences(context)
                        .edit()
                        .putString(Keys.UPDATE_META, updateName)
                        .apply();

                session.commit(pendingIntent.getIntentSender());
                session.close();
                Log.d(TAG, "APK installation initiated");
            }
        } catch (IOException e) {
            Log.e(TAG, "Error during APK installation", e);
        }
    }

    private static PackageInstaller.Session createSession(Context context) throws IOException {
        PackageInstaller packageInstaller = context.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams params =
                new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        if (BuildUtils.hasAndroidS()) {
            params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED);
        }
        int sessionId = packageInstaller.createSession(params);
        return packageInstaller.openSession(sessionId);
    }
}