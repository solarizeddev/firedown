package com.solarized.firedown;

import android.app.DownloadManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.util.Log;

import androidx.preference.PreferenceManager;

import com.solarized.firedown.utils.BrowserHeaders;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Owns the update-APK download lifecycle on top of the system DownloadManager:
 * the "pending download" record (id + mirror list + index + expected digest)
 * and the verified "ready" record (an APK that passed SHA-256 + signature and
 * is safe to install). UpdateWorker starts a download; UpdateDownloadReceiver
 * reports the outcome; UpdateInstallReceiver clears the ready record on install.
 *
 * Why a single class: the enqueue path must be atomic (the one-time check on
 * app open and the 6h periodic can fire close together — without serialization
 * both could enqueue the same APK twice to the same path), and the failure /
 * mirror-failover / verify / promote-to-ready transitions all mutate the same
 * SharedPreferences record, so they live together behind one monitor.
 */
public final class UpdateDownloader {

    private static final String TAG = "UpdateDownloader";

    /** Serializes the whole enqueue/transition critical section in-process. */
    private static final Object LOCK = new Object();

    /**
     * After this many full (all-mirrors-tried) failed rounds for one version,
     * stop re-downloading it. Each round happens on a separate check (app open /
     * periodic), so without this a permanently-failing download (404, always-bad
     * digest) would be re-fetched on EVERY app open forever. A newer version
     * resets the counter; a successful download clears it.
     */
    private static final int MAX_DOWNLOAD_ATTEMPTS = 3;

    private UpdateDownloader() {}

    private static SharedPreferences prefs(Context context) {
        return PreferenceManager.getDefaultSharedPreferences(context);
    }

    /**
     * Begin downloading the update (first mirror). No-op if a download for this
     * APK is already in flight (the in-flight guard in {@link #enqueueAt}).
     */
    public static void start(Context context, List<String> urls, String sha, String name, int versionCode) {
        if (urls == null || urls.isEmpty()) {
            return;
        }
        synchronized (LOCK) {
            if (hasExhaustedRetries(context, versionCode)) {
                // Already failed MAX_DOWNLOAD_ATTEMPTS times for this version —
                // don't re-download it on this (or any further) check. A newer
                // version won't match the stored versionCode, so it still tries.
                Log.w(TAG, "giving up on version " + versionCode + " after "
                        + MAX_DOWNLOAD_ATTEMPTS + " failed attempts");
                return;
            }
            enqueueAt(context, urls, 0, sha, name, versionCode);
        }
    }

    /**
     * A download (or its verification) failed: fail over to the next mirror if
     * one is left, otherwise clear the pending record so the next check / app
     * open re-enqueues from the top (rather than waiting silently). No tight
     * retry loop — DownloadManager already retries transient network errors
     * internally, and every app-open check re-tries a cleared download.
     */
    public static void onAttemptFailed(Context context) {
        synchronized (LOCK) {
            SharedPreferences p = prefs(context);
            List<String> urls = splitUrls(p.getString(Keys.UPDATE_DOWNLOAD_URLS, ""));
            int index = p.getInt(Keys.UPDATE_DOWNLOAD_URL_INDEX, 0);
            String sha = p.getString(Keys.UPDATE_DOWNLOAD_SHA, null);
            String name = p.getString(Keys.UPDATE_DOWNLOAD_NAME, "");
            int versionCode = p.getInt(Keys.UPDATE_DOWNLOAD_VERSION_CODE, -1);

            if (index + 1 < urls.size() && sha != null && versionCode > 0) {
                Log.w(TAG, "download attempt failed; failing over to mirror " + (index + 1));
                enqueueAt(context, urls, index + 1, sha, name, versionCode);
            } else {
                // All mirrors for this version failed this round. Count it (so a
                // permanently-failing download stops after MAX_DOWNLOAD_ATTEMPTS
                // rather than re-fetching on every check) and clear the pending
                // record. NO re-enqueue here — the next app-open/periodic check
                // re-tries from the top until the cap is hit.
                Log.w(TAG, "download attempt failed; mirrors exhausted");
                recordFailedRound(context, versionCode);
                clearPending(context);
            }
        }
    }

    /** Must be called under LOCK. Bumps the per-version failed-round counter. */
    private static void recordFailedRound(Context context, int versionCode) {
        if (versionCode <= 0) {
            return;
        }
        SharedPreferences p = prefs(context);
        int failedVersion = p.getInt(Keys.UPDATE_FAILED_VERSION_CODE, -1);
        int count = (failedVersion == versionCode) ? p.getInt(Keys.UPDATE_FAILED_COUNT, 0) : 0;
        p.edit()
                .putInt(Keys.UPDATE_FAILED_VERSION_CODE, versionCode)
                .putInt(Keys.UPDATE_FAILED_COUNT, count + 1)
                .apply();
    }

    /** True once a version has failed MAX_DOWNLOAD_ATTEMPTS full rounds. */
    public static boolean hasExhaustedRetries(Context context, int versionCode) {
        SharedPreferences p = prefs(context);
        return p.getInt(Keys.UPDATE_FAILED_VERSION_CODE, -1) == versionCode
                && p.getInt(Keys.UPDATE_FAILED_COUNT, 0) >= MAX_DOWNLOAD_ATTEMPTS;
    }

    /** Must be called under LOCK. */
    private static void enqueueAt(Context context, List<String> urls, int index,
                                  String sha, String name, int versionCode) {
        DownloadManager dm = (DownloadManager) context.getSystemService(Context.DOWNLOAD_SERVICE);
        // DownloadManager can be disabled by the user, and it needs an external
        // files dir to write to. If either is unavailable, skip quietly — the
        // next periodic check tries again.
        if (dm == null || context.getExternalFilesDir(null) == null) {
            Log.w(TAG, "DownloadManager/external storage unavailable; skipping");
            return;
        }

        SharedPreferences p = prefs(context);

        // In-flight guard: if a previous download is still PENDING/RUNNING, leave
        // it alone (this is the concurrent one-time + periodic case). Only a
        // finished/failed/absent previous download is replaced.
        long previousId = p.getLong(Keys.UPDATE_DOWNLOAD_ID, -1);
        if (previousId != -1) {
            int status = queryStatus(dm, previousId);
            // PENDING/RUNNING = in flight; PAUSED = DownloadManager is doing its
            // OWN transient-failure retry/wait — in all three, leave it alone so
            // we neither double-enqueue (the concurrent one-time + periodic case)
            // nor cancel a retry DownloadManager is about to resume.
            if (status == DownloadManager.STATUS_PENDING
                    || status == DownloadManager.STATUS_RUNNING
                    || status == DownloadManager.STATUS_PAUSED) {
                Log.d(TAG, "a download is already in flight; not enqueuing again");
                return;
            }
            dm.remove(previousId);
        }

        // DownloadManager refuses to overwrite an existing destination, so clear
        // any stale/partial file first.
        File dest = Preferences.getUpdateApkFile(context);
        if (dest != null && dest.exists()) {
            dest.delete();
        }

        long downloadId;
        try {
            DownloadManager.Request request = new DownloadManager.Request(Uri.parse(urls.get(index)));
            // Same browser UA + X-App-Version as the status fetch so the
            // Cloudflare front (or the GitHub fallback) sees a consistent client
            // across both the check and the download.
            request.addRequestHeader(BrowserHeaders.USER_AGENT, BrowserHeaders.getDefaultUserAgentString());
            request.addRequestHeader(BrowserHeaders.X_APP_VERSION, App.getVersionName());
            request.setDestinationInExternalFilesDir(context, null, Preferences.UPDATE_APK);
            // No download-progress notification — the only notification we want
            // is the install prompt after verification (needs
            // DOWNLOAD_WITHOUT_NOTIFICATION, already held). Allow metered to
            // match the previous behaviour; not roaming.
            request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_HIDDEN);
            request.setAllowedOverMetered(true);
            request.setAllowedOverRoaming(false);
            downloadId = dm.enqueue(request);
        } catch (Exception e) {
            // Bad URL scheme, DownloadManager disabled mid-call, etc. Drop the
            // pending record so the next check starts clean.
            Log.e(TAG, "enqueue failed", e);
            clearPending(context);
            return;
        }

        p.edit()
                .putLong(Keys.UPDATE_DOWNLOAD_ID, downloadId)
                .putString(Keys.UPDATE_DOWNLOAD_URLS, joinUrls(urls))
                .putInt(Keys.UPDATE_DOWNLOAD_URL_INDEX, index)
                .putString(Keys.UPDATE_DOWNLOAD_SHA, sha)
                .putString(Keys.UPDATE_DOWNLOAD_NAME, name == null ? "" : name)
                .putInt(Keys.UPDATE_DOWNLOAD_VERSION_CODE, versionCode)
                .apply();
    }

    /**
     * Promote a verified APK to "ready" and clear the download record. After
     * this, {@link #isVerifiedReady} returns true and the install prompt / sheet
     * may offer it.
     */
    public static void markReady(Context context, int versionCode, String name) {
        synchronized (LOCK) {
            prefs(context).edit()
                    .putBoolean(Keys.UPDATE_READY, true)
                    .putInt(Keys.UPDATE_READY_VERSION_CODE, versionCode)
                    .putString(Keys.UPDATE_READY_NAME, name == null ? "" : name)
                    .remove(Keys.UPDATE_DOWNLOAD_ID)
                    .remove(Keys.UPDATE_DOWNLOAD_URLS)
                    .remove(Keys.UPDATE_DOWNLOAD_URL_INDEX)
                    .remove(Keys.UPDATE_DOWNLOAD_SHA)
                    .remove(Keys.UPDATE_DOWNLOAD_NAME)
                    .remove(Keys.UPDATE_DOWNLOAD_VERSION_CODE)
                    // Success — drop the failed-round counter so it can't carry
                    // over to a later version.
                    .remove(Keys.UPDATE_FAILED_VERSION_CODE)
                    .remove(Keys.UPDATE_FAILED_COUNT)
                    .apply();
        }
    }

    /**
     * True iff a SHA+signature-VERIFIED APK for {@code atLeastVersion} (or
     * newer) is on disk. The install shortcut gates on this rather than on a
     * bare file-exists + archive-versionCode read, so a complete-but-unverified
     * file (e.g. a download whose completion broadcast was missed) is NOT
     * trusted — it gets re-downloaded and re-verified instead.
     */
    public static boolean isVerifiedReady(Context context, int atLeastVersion) {
        SharedPreferences p = prefs(context);
        if (!p.getBoolean(Keys.UPDATE_READY, false)) {
            return false;
        }
        if (p.getInt(Keys.UPDATE_READY_VERSION_CODE, -1) < atLeastVersion) {
            return false;
        }
        File apk = Preferences.getUpdateApkFile(context);
        return apk != null && apk.exists();
    }

    public static int readyVersionCode(Context context) {
        return prefs(context).getInt(Keys.UPDATE_READY_VERSION_CODE, -1);
    }

    public static String readyVersionName(Context context) {
        return prefs(context).getString(Keys.UPDATE_READY_NAME, "");
    }

    /** Drop the pending-download record (does not touch any verified file). */
    public static void clearPending(Context context) {
        synchronized (LOCK) {
            prefs(context).edit()
                    .remove(Keys.UPDATE_DOWNLOAD_ID)
                    .remove(Keys.UPDATE_DOWNLOAD_URLS)
                    .remove(Keys.UPDATE_DOWNLOAD_URL_INDEX)
                    .remove(Keys.UPDATE_DOWNLOAD_SHA)
                    .remove(Keys.UPDATE_DOWNLOAD_NAME)
                    .remove(Keys.UPDATE_DOWNLOAD_VERSION_CODE)
                    .apply();
        }
    }

    /** Drop the verified "ready" record and delete the APK from disk. */
    public static void clearReady(Context context) {
        synchronized (LOCK) {
            prefs(context).edit()
                    .remove(Keys.UPDATE_READY)
                    .remove(Keys.UPDATE_READY_VERSION_CODE)
                    .remove(Keys.UPDATE_READY_NAME)
                    .apply();
            File apk = Preferences.getUpdateApkFile(context);
            if (apk != null && apk.exists()) {
                apk.delete();
            }
        }
    }

    private static int queryStatus(DownloadManager dm, long id) {
        DownloadManager.Query query = new DownloadManager.Query().setFilterById(id);
        try (Cursor cursor = dm.query(query)) {
            if (cursor != null && cursor.moveToFirst()) {
                int col = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS);
                if (col >= 0) {
                    return cursor.getInt(col);
                }
            }
        }
        return -1;
    }

    // URLs never contain a newline, so newline-join is a safe, dependency-free
    // encoding for the SharedPreferences string.
    private static String joinUrls(List<String> urls) {
        return String.join("\n", urls);
    }

    private static List<String> splitUrls(String joined) {
        List<String> out = new ArrayList<>();
        if (joined == null || joined.isEmpty()) {
            return out;
        }
        for (String u : joined.split("\n")) {
            if (!u.isEmpty()) {
                out.add(u);
            }
        }
        return out;
    }
}
