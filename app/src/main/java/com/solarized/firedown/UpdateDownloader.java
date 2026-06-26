package com.solarized.firedown;

import android.app.DownloadManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.util.Log;

import androidx.preference.PreferenceManager;
import androidx.work.Constraints;
import androidx.work.ExistingWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;

import com.solarized.firedown.utils.BrowserHeaders;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

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
     * Exponential backoff for a failing download. Each failed (all-mirrors)
     * round grows the delay BACKOFF_BASE_MS * 2^(round-1), capped at
     * BACKOFF_MAX_MS — so retries go 15m, 30m, 1h, 2h, 4h, 6h, 6h, … The app
     * never gives up; the cap just keeps a permanently-failing download from
     * being re-fetched more than ~once every 6h. A newer version or a success
     * resets it. The growing delay (not a hard attempt cap) is what prevents the
     * every-app-open re-download loop without abandoning the update.
     */
    private static final long BACKOFF_BASE_MS = 15L * 60L * 1000L;      // 15 min
    private static final long BACKOFF_MAX_MS = 6L * 60L * 60L * 1000L;  // 6 h

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
            if (isBackingOff(context, versionCode)) {
                // Inside the backoff window for this version — let the scheduled
                // retry worker fire instead of re-downloading on this check (so
                // an app open during backoff doesn't restart the download).
                Log.d(TAG, "within backoff window for version " + versionCode + "; skipping");
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
                // All mirrors for this version failed this round. Schedule the
                // next retry with exponential backoff (never gives up) and clear
                // the pending record. NO immediate re-enqueue here.
                Log.w(TAG, "download attempt failed; mirrors exhausted, backing off");
                // Remove the failed DownloadManager row (and any partial file)
                // so they don't accumulate across backoff rounds. Safe here —
                // it's a FAILED download; we never remove a SUCCESSFUL one
                // (DownloadManager.remove would delete the APK we need).
                removeDownload(context, p.getLong(Keys.UPDATE_DOWNLOAD_ID, -1));
                scheduleBackoff(context, versionCode);
                clearPending(context);
            }
        }
    }

    /**
     * Must be called under LOCK. Grows the per-version backoff and schedules the
     * next retry. Delay = BACKOFF_BASE_MS * 2^(round-1), capped at BACKOFF_MAX_MS.
     */
    private static void scheduleBackoff(Context context, int versionCode) {
        if (versionCode <= 0) {
            return;
        }
        SharedPreferences p = prefs(context);
        int failedVersion = p.getInt(Keys.UPDATE_FAILED_VERSION_CODE, -1);
        int round = ((failedVersion == versionCode) ? p.getInt(Keys.UPDATE_FAILED_COUNT, 0) : 0) + 1;

        int exponent = Math.min(round - 1, 20); // guard the shift against overflow
        long target = Math.min(BACKOFF_BASE_MS * (1L << exponent), BACKOFF_MAX_MS);
        // ±15% jitter so a cohort that failed together — e.g. GitHub goes down
        // and many clients fail over to firedown.app at once — doesn't retry in
        // lock-step and re-saturate the fallback host.
        double jitter = 0.85 + (ThreadLocalRandom.current().nextDouble() * 0.30); // [0.85, 1.15)
        long delay = (long) (target * jitter);

        p.edit()
                .putInt(Keys.UPDATE_FAILED_VERSION_CODE, versionCode)
                .putInt(Keys.UPDATE_FAILED_COUNT, round)
                .putLong(Keys.UPDATE_NEXT_RETRY_AT, System.currentTimeMillis() + delay)
                .apply();

        scheduleRetry(context, delay);
        Log.w(TAG, "retry #" + round + " for version " + versionCode
                + " in ~" + (delay / 60000L) + " min");
    }

    /** True while inside the backoff window for this version. */
    public static boolean isBackingOff(Context context, int versionCode) {
        SharedPreferences p = prefs(context);
        if (p.getInt(Keys.UPDATE_FAILED_VERSION_CODE, -1) != versionCode) {
            return false;
        }
        return System.currentTimeMillis() < p.getLong(Keys.UPDATE_NEXT_RETRY_AT, 0L);
    }

    /**
     * Schedule a one-shot UpdateWorker after {@code delayMs}. REPLACE so the
     * newest backoff (longest delay) supersedes any pending retry; CONNECTED so
     * it waits for network. This is what makes the retry happen without the user
     * opening the app — and what keeps it bounded (one pending retry at a time).
     */
    private static void scheduleRetry(Context context, long delayMs) {
        Constraints constraints = new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build();
        OneTimeWorkRequest retry = new OneTimeWorkRequest.Builder(UpdateWorker.class)
                .setConstraints(constraints)
                .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
                .build();
        WorkManager.getInstance(context).enqueueUniqueWork(
                "update_retry", ExistingWorkPolicy.REPLACE, retry);
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
                    // Success — drop the backoff state so it can't carry over to
                    // a later version.
                    .remove(Keys.UPDATE_FAILED_VERSION_CODE)
                    .remove(Keys.UPDATE_FAILED_COUNT)
                    .remove(Keys.UPDATE_NEXT_RETRY_AT)
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

    /**
     * Remember a version's changelog (from status.json) so the in-app sheet can
     * show it later — the sheet has no access to the manifest at display time.
     * Single slot keyed by versionCode; the worker refreshes it on every check.
     */
    public static void setChangelog(Context context, int versionCode, String changelog) {
        prefs(context).edit()
                .putInt(Keys.UPDATE_CHANGELOG_VERSION_CODE, versionCode)
                .putString(Keys.UPDATE_CHANGELOG_TEXT, changelog == null ? "" : changelog)
                .apply();
    }

    /** The stored changelog for {@code versionCode}, or "" if none matches. */
    public static String getChangelogFor(Context context, int versionCode) {
        SharedPreferences p = prefs(context);
        if (p.getInt(Keys.UPDATE_CHANGELOG_VERSION_CODE, -1) != versionCode) {
            return "";
        }
        return p.getString(Keys.UPDATE_CHANGELOG_TEXT, "");
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

    /** Remove a DownloadManager row (and its file) by id; no-op for -1. */
    private static void removeDownload(Context context, long id) {
        if (id == -1) {
            return;
        }
        DownloadManager dm = (DownloadManager) context.getSystemService(Context.DOWNLOAD_SERVICE);
        if (dm != null) {
            dm.remove(id);
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
