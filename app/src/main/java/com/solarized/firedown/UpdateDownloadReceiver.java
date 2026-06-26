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
 * SHA-256 + signing certificate before promoting anything to "ready".
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

        // Not our download (or a stale/duplicate broadcast for a completed one) —
        // ignore. We do NOT clear the record here: the transition (markReady /
        // onAttemptFailed) owns clearing it, so the mirror list survives for a
        // failover.
        if (completedId == -1 || completedId != expectedId) {
            return;
        }

        String expectedSha = prefs.getString(Keys.UPDATE_DOWNLOAD_SHA, null);
        String versionName = prefs.getString(Keys.UPDATE_DOWNLOAD_NAME, "");
        int versionCode = prefs.getInt(Keys.UPDATE_DOWNLOAD_VERSION_CODE, -1);

        // Hashing + signature checks read the whole APK from disk — keep the
        // process alive while a worker thread does it (onReceive must not
        // block, and must not touch disk on the main thread).
        PendingResult pendingResult = goAsync();
        new Thread(() -> {
            try {
                process(context, completedId, expectedSha, versionName, versionCode);
            } finally {
                pendingResult.finish();
            }
        }).start();
    }

    private void process(Context context, long downloadId, String expectedSha,
                         String versionName, int versionCode) {
        if (!isDownloadSuccessful(context, downloadId)) {
            Log.w(TAG, "Update download did not complete successfully");
            UpdateDownloader.onAttemptFailed(context);
            return;
        }

        File apk = Preferences.getUpdateApkFile(context);
        if (apk == null || !apk.exists()) {
            Log.w(TAG, "Update download reported complete but file is missing");
            UpdateDownloader.onAttemptFailed(context);
            return;
        }

        if (!UpdateApkVerifier.verifySha256(apk, expectedSha)) {
            Log.e(TAG, "Update APK digest mismatch — discarding");
            if (apk.exists()) apk.delete();
            UpdateDownloader.onAttemptFailed(context);
            return;
        }

        if (!UpdateApkVerifier.verifySignature(context, apk)) {
            Log.e(TAG, "Update APK signature mismatch — discarding");
            if (apk.exists()) apk.delete();
            UpdateDownloader.onAttemptFailed(context);
            return;
        }

        // Verified — promote to "ready" (clears the download record) and surface
        // it. The (silent) notification is ALWAYS posted, so the update is
        // reachable whether or not the app is open; the in-app sheet
        // (UpdateAvailableSheet, shown on resume) is the additional prominent
        // surface while the app is in use.
        UpdateDownloader.markReady(context, versionCode, versionName);
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
}
