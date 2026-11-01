package com.solarized.firedown;

import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import androidx.preference.PreferenceManager;

/**
 * Receives DownloadManager's completion broadcast for the update download.
 *
 * This is the payoff of moving the download to DownloadManager: the
 * receiver is declared in the manifest, so when the (system-managed) download
 * finishes the OS can COLD-START our process to deliver ACTION_DOWNLOAD_COMPLETE
 * and post the install prompt — even if the app was evicted mid-download. (It
 * does NOT help the force-stopped case: a stopped app receives no broadcasts
 * and runs no jobs. Only opening the app, or a battery-optimization exemption,
 * covers that — same ceiling Signal lives with.)
 *
 * It only hands the download to UpdateFinalizeWorker: rebuilding a patch or
 * an .xz and hashing the APK take longer than a receiver may run.
 *
 * exported="true" is required because this is a system broadcast from a
 * different uid. That's safe here because the receiver is inert against a
 * spoofed broadcast: it acts only on the download id WE recorded, the worker
 * re-queries DownloadManager (which only returns our own downloads), and then
 * re-verifies SHA-256 + signing certificate before promoting anything to
 * "ready".
 */
public class UpdateDownloadReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!DownloadManager.ACTION_DOWNLOAD_COMPLETE.equals(intent.getAction())) {
            return;
        }

        long completedId = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1);
        long expectedId = PreferenceManager.getDefaultSharedPreferences(context)
                .getLong(Keys.UPDATE_DOWNLOAD_ID, -1);

        // Not our download (or a stale/duplicate broadcast for a completed one) —
        // ignore. We do NOT clear the record here: the transitions in
        // UpdateDownloader own clearing it, so the plan survives a fail-over.
        if (completedId == -1 || completedId != expectedId) {
            return;
        }
        UpdateFinalizeWorker.enqueue(context, completedId);
    }
}
