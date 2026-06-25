package com.solarized.firedown;

import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.util.Log;

import androidx.preference.PreferenceManager;

import java.io.File;

/**
 * Receives DownloadManager's completion broadcast for the update APK.
 *
 * This is the payoff of moving the APK download to DownloadManager: the
 * receiver is declared in the manifest, so when the (system-managed) download
 * finishes the OS can COLD-START our process to deliver ACTION_DOWNLOAD_COMPLETE
 * and post the install prompt — even if the app was evicted mid-download. (It
 * does NOT help the force-stopped case: a stopped app receives no broadcasts
 * and runs no jobs. Only opening the app, or a battery-optimization exemption,
 * covers that — same ceiling Signal lives with.)
 *
 * exported="true" is required because this is a system broadcast from a
 * different uid. That's safe here because the receiver is inert against a
 * spoofed broadcast: it acts only on the download id WE recorded, re-queries
 * DownloadManager (which only returns our own downloads), and then re-verifies
 * SHA-256 + signing certificate before posting anything.
 */
public class UpdateDownloadReceiver extends BroadcastReceiver {

    private static final String TAG = UpdateDownloadReceiver.class.getName();

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!DownloadManager.ACTION_DOWNLOAD_COMPLETE.equals(intent.getAction())) {
            return;
        }

        long completedId = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1);
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        long expectedId = prefs.getLong(Keys.UPDATE_DOWNLOAD_ID, -1);

        // Not our download (or a stale/duplicate broadcast) — ignore.
        if (completedId == -1 || completedId != expectedId) {
            return;
        }

        String expectedSha = prefs.getString(Keys.UPDATE_DOWNLOAD_SHA, null);
        String versionName = prefs.getString(Keys.UPDATE_DOWNLOAD_NAME, "");

        // Clear the tracking id up front so a repeated broadcast can't
        // re-enter; the verified APK file itself remains for the installer.
        prefs.edit()
                .remove(Keys.UPDATE_DOWNLOAD_ID)
                .remove(Keys.UPDATE_DOWNLOAD_SHA)
                .remove(Keys.UPDATE_DOWNLOAD_NAME)
                .remove(Keys.UPDATE_DOWNLOAD_VERSION_CODE)
                .apply();

        // Hashing + signature checks read the whole APK from disk — keep the
        // process alive while a worker thread does it (onReceive must not
        // block, and must not touch disk on the main thread).
        PendingResult pendingResult = goAsync();
        new Thread(() -> {
            try {
                process(context, completedId, expectedSha, versionName);
            } finally {
                pendingResult.finish();
            }
        }).start();
    }

    private void process(Context context, long downloadId, String expectedSha, String versionName) {
        if (!isDownloadSuccessful(context, downloadId)) {
            Log.w(TAG, "Update download did not complete successfully");
            // Silent: the next periodic check re-enqueues. No "failed" nag for
            // a background download the user never asked for.
            cleanup(context);
            return;
        }

        File apk = Preferences.getUpdateApkFile(context);
        if (apk == null || !apk.exists()) {
            Log.w(TAG, "Update download reported complete but file is missing");
            cleanup(context);
            return;
        }

        if (!UpdateApkVerifier.verifySha256(apk, expectedSha)) {
            Log.e(TAG, "Update APK digest mismatch — discarding");
            cleanup(context);
            return;
        }

        if (!UpdateApkVerifier.verifySignature(context, apk)) {
            Log.e(TAG, "Update APK signature mismatch — discarding");
            cleanup(context);
            return;
        }

        UpdateNotification.showInstallPrompt(context, versionName);
    }

    private boolean isDownloadSuccessful(Context context, long downloadId) {
        DownloadManager dm = (DownloadManager) context.getSystemService(Context.DOWNLOAD_SERVICE);
        if (dm == null) {
            return false;
        }
        DownloadManager.Query query = new DownloadManager.Query().setFilterById(downloadId);
        try (Cursor cursor = dm.query(query)) {
            if (cursor != null && cursor.moveToFirst()) {
                int statusColumn = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS);
                if (statusColumn < 0) {
                    return false;
                }
                int status = cursor.getInt(statusColumn);
                return status == DownloadManager.STATUS_SUCCESSFUL;
            }
        }
        return false;
    }

    private void cleanup(Context context) {
        File apk = Preferences.getUpdateApkFile(context);
        if (apk != null && apk.exists()) {
            apk.delete();
        }
    }
}
