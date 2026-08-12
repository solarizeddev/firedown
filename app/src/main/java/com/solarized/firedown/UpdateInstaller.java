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
        File updateFile = Preferences.getUpdateApkFile(context);

        if (updateFile == null || !updateFile.exists()) {
            Log.e(TAG, "Update APK not found");
            UpdateNotification.showNotificationFailed(context);
            return;
        }

        // The session is created OUTSIDE the try so the finally can always
        // reach it. An uncommitted session that is never abandoned STAYS
        // ALLOCATED — PackageInstaller caps active sessions per installer, and
        // on this app that cap is the difference between "one install failed"
        // and "updates are permanently broken": every retry would leak another
        // session until createSession itself starts throwing, with no user-
        // visible cause and no way out but clearing app data. For an off-store
        // APK, whose ONLY update path this is, that is unrecoverable in the
        // field. So: abandon on any failure, close always.
        PackageInstaller.Session session = null;
        boolean committed = false;
        try {
            session = createSession(context);

            // `out` is now closed by try-with-resources too. It used to be
            // closed only on the success path, so a mid-copy IOException (disk
            // full, storage unmounted — this APK lives on external files dir)
            // leaked the stream as well as the session.
            try (InputStream in = new FileInputStream(updateFile);
                 OutputStream out = session.openWrite("package", 0, updateFile.length())) {
                byte[] buffer = new byte[65536];
                int bytesRead;
                while ((bytesRead = in.read(buffer)) != -1) {
                    out.write(buffer, 0, bytesRead);
                }
                session.fsync(out);
            }

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
            committed = true;
            Log.d(TAG, "APK installation initiated");
        } catch (Exception e) {
            // Deliberately Exception, not IOException. Every caller runs this
            // on a BARE THREAD (the sheet, the About row, and the notification
            // receiver's goAsync worker), so an unchecked throw here reached
            // the default handler and KILLED THE PROCESS rather than failing
            // one install. createSession/commit can throw SecurityException
            // (install-packages permission refused by an OEM policy) and
            // IllegalStateException (session cap reached), neither of which is
            // an IOException.
            Log.e(TAG, "Error during APK installation", e);
            UpdateNotification.showNotificationFailed(context);
        } finally {
            if (session != null) {
                if (!committed) {
                    try {
                        session.abandon();
                    } catch (Exception ignored) {
                        // Abandon is best-effort cleanup; the system reclaims
                        // stale sessions eventually. Never let it mask the
                        // original failure.
                    }
                }
                session.close();
            }
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