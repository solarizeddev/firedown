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
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * Owns the update download lifecycle on top of the system DownloadManager:
 * the "pending download" record (id + the UpdatePlan being followed + the
 * APK's expected digest) and the verified "ready" record (an APK that passed
 * SHA-256 + signature and is safe to install). UpdateWorker starts a
 * download; UpdateDownloadReceiver hands the finished one to
 * UpdateFinalizeWorker, which rebuilds the APK when the download was a patch
 * or an .xz, verifies it and reports back here; UpdateInstallReceiver clears
 * the ready record on install.
 *
 * Why a single class: the enqueue path must be atomic (the one-time check on
 * app open and the 6h periodic can fire close together — without serialization
 * both could enqueue the same APK twice to the same path), and the failure /
 * fail-over / verify / promote-to-ready transitions all mutate the same
 * SharedPreferences record, so they live together behind one monitor.
 */
public final class UpdateDownloader {

    private static final String TAG = "UpdateDownloader";

    /** Serializes the whole enqueue/transition critical section in-process. */
    private static final Object LOCK = new Object();

    /**
     * Exponential backoff for a failing download. Each failed (everything
     * tried) round grows the delay BACKOFF_BASE_MS * 2^(round-1), capped at
     * BACKOFF_MAX_MS — so retries go 15m, 30m, 1h, 2h, 4h, 6h, 6h, … The app
     * never gives up; the cap just keeps a permanently-failing download from
     * being re-fetched more than ~once every 6h. A newer version or a success
     * resets it. The growing delay (not a hard attempt cap) is what prevents the
     * every-app-open re-download loop without abandoning the update.
     */
    private static final long BACKOFF_BASE_MS = 15L * 60L * 1000L;      // 15 min
    private static final long BACKOFF_MAX_MS = 6L * 60L * 60L * 1000L;  // 6 h

    /**
     * How long a claim by UpdateFinalizeWorker keeps a finished download safe
     * from {@link #start}. Far longer than a rebuild takes; a claim this old
     * belongs to a worker that died, and the download is started over.
     */
    private static final long FINALIZE_CLAIM_MS = 30L * 60L * 1000L;

    private UpdateDownloader() {}

    private static SharedPreferences prefs(Context context) {
        return PreferenceManager.getDefaultSharedPreferences(context);
    }

    /**
     * Begin downloading the update along {@code plan} (see UpdatePlan). No-op
     * while a download is in flight, or while a finished one is waiting to be
     * (or being) rebuilt and verified.
     *
     * @param apkSha256 the top-level sha256: what the APK must hash to,
     *        whichever step produced it
     */
    public static void start(Context context, UpdatePlan plan, String apkSha256, String name, int versionCode) {
        synchronized (LOCK) {
            if (isBackingOff(context, versionCode)) {
                // Inside the backoff window for this version — let the scheduled
                // retry worker fire instead of re-downloading on this check (so
                // an app open during backoff doesn't restart the download).
                Log.d(TAG, "within backoff window for version " + versionCode + "; skipping");
                return;
            }
            if (inFlightLocked(context)) {
                return;
            }
            enqueueLocked(context, plan, apkSha256, name, versionCode);
        }
    }

    /**
     * The current download failed (DownloadManager error, or a file that is
     * not the one status.json named): try the step's next mirror, then the
     * next step. When nothing is left, back off — no tight retry loop:
     * DownloadManager already retries transient network errors internally.
     */
    public static void onAttemptFailed(Context context) {
        synchronized (LOCK) {
            Pending pending = readPendingLocked(context);
            if (pending != null && pending.plan.nextUrl()) {
                Log.w(TAG, "download attempt failed; trying " + pending.plan.current().kind
                        + " from mirror " + pending.plan.urlIndex());
                enqueueLocked(context, pending.plan, pending.apkSha256, pending.name, pending.versionCode);
                return;
            }
            Log.w(TAG, "download attempt failed; nothing left to try, backing off");
            backOffLocked(context, pending == null ? -1 : pending.versionCode);
        }
    }

    /**
     * The patch or .xz downloaded intact but did not rebuild the expected
     * APK. Every mirror serves the same file, so skip the whole step, and
     * remember it for this version so later rounds don't try it again.
     */
    public static void onStepFailed(Context context) {
        synchronized (LOCK) {
            Pending pending = readPendingLocked(context);
            if (pending == null) {
                return;
            }
            UpdatePlan.Kind kind = pending.plan.current().kind;
            if (kind == UpdatePlan.Kind.PATCH) {
                prefs(context).edit().putInt(Keys.UPDATE_PATCH_FAILED_VERSION, pending.versionCode).apply();
            } else if (kind == UpdatePlan.Kind.XZ) {
                prefs(context).edit().putInt(Keys.UPDATE_XZ_FAILED_VERSION, pending.versionCode).apply();
            }
            if (pending.plan.nextStep()) {
                Log.w(TAG, kind + " did not rebuild the update; trying " + pending.plan.current().kind);
                enqueueLocked(context, pending.plan, pending.apkSha256, pending.name, pending.versionCode);
                return;
            }
            backOffLocked(context, pending.versionCode);
        }
    }

    /**
     * Something on THIS device failed (storage full, a file it could not
     * write): the downloaded file may be fine, so don't walk the plan — wait,
     * then start the whole plan again.
     */
    public static void backOff(Context context) {
        synchronized (LOCK) {
            Pending pending = readPendingLocked(context);
            backOffLocked(context, pending == null ? -1 : pending.versionCode);
        }
    }

    /** Kinds that already failed to rebuild {@code versionCode} (see {@link #onStepFailed}). */
    public static Set<UpdatePlan.Kind> failedKinds(Context context, int versionCode) {
        SharedPreferences p = prefs(context);
        Set<UpdatePlan.Kind> kinds = EnumSet.noneOf(UpdatePlan.Kind.class);
        if (p.getInt(Keys.UPDATE_PATCH_FAILED_VERSION, -1) == versionCode) {
            kinds.add(UpdatePlan.Kind.PATCH);
        }
        if (p.getInt(Keys.UPDATE_XZ_FAILED_VERSION, -1) == versionCode) {
            kinds.add(UpdatePlan.Kind.XZ);
        }
        return kinds;
    }

    /** The download record, as UpdateFinalizeWorker needs it. */
    public static final class Pending {
        public final long downloadId;
        public final UpdatePlan plan;
        public final String apkSha256;
        public final String name;
        public final int versionCode;

        Pending(long downloadId, UpdatePlan plan, String apkSha256, String name, int versionCode) {
            this.downloadId = downloadId;
            this.plan = plan;
            this.apkSha256 = apkSha256;
            this.name = name;
            this.versionCode = versionCode;
        }
    }

    /**
     * Claims the finished download {@code downloadId} for rebuilding and
     * verifying; null when the record is about another download (a stale or
     * duplicate broadcast). While claimed, {@link #start} leaves the download
     * and its file alone. Unique work per download id means one claimant; a
     * claimant that is re-run after its process died claims again.
     */
    public static Pending beginFinalize(Context context, long downloadId) {
        synchronized (LOCK) {
            Pending pending = readPendingLocked(context);
            if (pending == null || pending.downloadId != downloadId) {
                return null;
            }
            prefs(context).edit()
                    .putLong(Keys.UPDATE_DOWNLOAD_FINALIZING_AT, System.currentTimeMillis())
                    .apply();
            return pending;
        }
    }

    /** Where a step's download lands. The APK step downloads the update APK itself. */
    public static File fileFor(Context context, UpdatePlan.Kind kind) {
        File apk = Preferences.getUpdateApkFile(context);
        switch (kind) {
            case PATCH:
                return new File(apk.getParentFile(), Preferences.UPDATE_APK + ".patch");
            case XZ:
                return new File(apk.getParentFile(), Preferences.UPDATE_APK + ".xz");
            default:
                return apk;
        }
    }

    /** The temporary name an APK is rebuilt under before it is moved into place. */
    public static File partFile(Context context) {
        File apk = Preferences.getUpdateApkFile(context);
        return new File(apk.getParentFile(), Preferences.UPDATE_APK + ".part");
    }

    /** Must be called under LOCK. */
    private static void backOffLocked(Context context, int versionCode) {
        // Remove the failed DownloadManager row (and any partial file) so they
        // don't accumulate across backoff rounds. Safe here — nothing waits on
        // it any more; a SUCCESSFUL download is only removed once its file has
        // been used or refused (DownloadManager.remove deletes the file).
        removeDownload(context, prefs(context).getLong(Keys.UPDATE_DOWNLOAD_ID, -1));
        deleteArtifacts(context);
        scheduleBackoff(context, versionCode);
        clearPendingLocked(context);
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

    /**
     * Must be called under LOCK. True when the recorded download must be left
     * alone: still downloading (PENDING/RUNNING, or PAUSED while
     * DownloadManager waits out its own transient-failure retry), or finished
     * and claimed by UpdateFinalizeWorker. A finished download nobody claimed
     * (its broadcast was missed — e.g. the app was force-stopped) is handed to
     * the worker now instead of being downloaded again.
     */
    private static boolean inFlightLocked(Context context) {
        SharedPreferences p = prefs(context);
        long previousId = p.getLong(Keys.UPDATE_DOWNLOAD_ID, -1);
        if (previousId == -1) {
            return false;
        }
        DownloadManager dm = (DownloadManager) context.getSystemService(Context.DOWNLOAD_SERVICE);
        if (dm == null) {
            return false;
        }
        int status = queryStatus(dm, previousId);
        if (status == DownloadManager.STATUS_PENDING
                || status == DownloadManager.STATUS_RUNNING
                || status == DownloadManager.STATUS_PAUSED) {
            Log.d(TAG, "a download is already in flight; not enqueuing again");
            return true;
        }
        if (status == DownloadManager.STATUS_SUCCESSFUL) {
            long claimedAt = p.getLong(Keys.UPDATE_DOWNLOAD_FINALIZING_AT, 0L);
            long age = System.currentTimeMillis() - claimedAt;
            if (claimedAt == 0L) {
                Log.d(TAG, "a finished download was never verified; handing it over");
                UpdateFinalizeWorker.enqueue(context, previousId);
                return true;
            }
            // A clock set back makes the age negative: still a live claim.
            if (age < FINALIZE_CLAIM_MS) {
                Log.d(TAG, "a finished download is being verified; not enqueuing again");
                return true;
            }
        }
        return false;
    }

    /** Must be called under LOCK. Enqueues the plan's current step and records it. */
    private static void enqueueLocked(Context context, UpdatePlan plan, String apkSha256,
                                      String name, int versionCode) {
        DownloadManager dm = (DownloadManager) context.getSystemService(Context.DOWNLOAD_SERVICE);
        // DownloadManager can be disabled by the user, and it needs an external
        // files dir to write to. If either is unavailable, skip quietly — the
        // next periodic check tries again.
        if (dm == null || context.getExternalFilesDir(null) == null) {
            Log.w(TAG, "DownloadManager/external storage unavailable; skipping");
            return;
        }

        SharedPreferences p = prefs(context);
        long previousId = p.getLong(Keys.UPDATE_DOWNLOAD_ID, -1);
        if (previousId != -1) {
            dm.remove(previousId);
        }

        // DownloadManager refuses to overwrite an existing destination, so clear
        // any stale/partial file first — and the APK, which a patch or an .xz
        // is about to be turned into.
        deleteArtifacts(context);
        File apk = Preferences.getUpdateApkFile(context);
        if (apk.exists()) {
            apk.delete();
        }
        File dest = fileFor(context, plan.current().kind);

        long downloadId;
        try {
            DownloadManager.Request request = new DownloadManager.Request(Uri.parse(plan.currentUrl()));
            // Same browser UA + X-App-Version as the status fetch so the
            // Cloudflare front (or the GitHub fallback) sees a consistent client
            // across both the check and the download.
            request.addRequestHeader(BrowserHeaders.USER_AGENT, BrowserHeaders.getDefaultUserAgentString());
            request.addRequestHeader(BrowserHeaders.X_APP_VERSION, App.getVersionName());
            request.setDestinationInExternalFilesDir(context, null, dest.getName());
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
            clearPendingLocked(context);
            return;
        }

        Log.d(TAG, "downloading " + plan.current().kind + " for version " + versionCode
                + " from mirror " + plan.urlIndex());
        p.edit()
                .putLong(Keys.UPDATE_DOWNLOAD_ID, downloadId)
                .putString(Keys.UPDATE_DOWNLOAD_PLAN, plan.toJson())
                .putString(Keys.UPDATE_DOWNLOAD_SHA, apkSha256)
                .putString(Keys.UPDATE_DOWNLOAD_NAME, name == null ? "" : name)
                .putInt(Keys.UPDATE_DOWNLOAD_VERSION_CODE, versionCode)
                .remove(Keys.UPDATE_DOWNLOAD_FINALIZING_AT)
                .remove(Keys.UPDATE_DOWNLOAD_URLS)
                .remove(Keys.UPDATE_DOWNLOAD_URL_INDEX)
                .apply();
    }

    /**
     * Must be called under LOCK. The record, or null when there is none. A
     * record written before plans existed (URL list + index) reads as an
     * APK-only plan, so a download in flight across this update still lands.
     */
    private static Pending readPendingLocked(Context context) {
        SharedPreferences p = prefs(context);
        long id = p.getLong(Keys.UPDATE_DOWNLOAD_ID, -1);
        String sha = p.getString(Keys.UPDATE_DOWNLOAD_SHA, null);
        int versionCode = p.getInt(Keys.UPDATE_DOWNLOAD_VERSION_CODE, -1);
        if (id == -1 || sha == null || versionCode <= 0) {
            return null;
        }
        UpdatePlan plan = UpdatePlan.fromJson(p.getString(Keys.UPDATE_DOWNLOAD_PLAN, null));
        if (plan == null) {
            List<String> urls = splitUrls(p.getString(Keys.UPDATE_DOWNLOAD_URLS, ""));
            if (urls.isEmpty()) {
                return null;
            }
            plan = UpdatePlan.apkOnly(urls, sha, p.getInt(Keys.UPDATE_DOWNLOAD_URL_INDEX, 0));
        }
        return new Pending(id, plan, sha, p.getString(Keys.UPDATE_DOWNLOAD_NAME, ""), versionCode);
    }

    /**
     * Promote a verified APK to "ready" and clear the download record. After
     * this, {@link #isVerifiedReady} returns true and the install prompt / sheet
     * may offer it.
     */
    public static void markReady(Context context, int versionCode, String name) {
        synchronized (LOCK) {
            // The DownloadManager row is left alone: for an APK step its file
            // IS the ready APK, and remove() would delete it. A patch or .xz
            // has been used, so its file goes.
            deleteArtifacts(context);
            prefs(context).edit()
                    .putBoolean(Keys.UPDATE_READY, true)
                    .putInt(Keys.UPDATE_READY_VERSION_CODE, versionCode)
                    .putString(Keys.UPDATE_READY_NAME, name == null ? "" : name)
                    .remove(Keys.UPDATE_DOWNLOAD_ID)
                    .remove(Keys.UPDATE_DOWNLOAD_PLAN)
                    .remove(Keys.UPDATE_DOWNLOAD_URLS)
                    .remove(Keys.UPDATE_DOWNLOAD_URL_INDEX)
                    .remove(Keys.UPDATE_DOWNLOAD_SHA)
                    .remove(Keys.UPDATE_DOWNLOAD_NAME)
                    .remove(Keys.UPDATE_DOWNLOAD_VERSION_CODE)
                    .remove(Keys.UPDATE_DOWNLOAD_FINALIZING_AT)
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
     * Remember a version's changelog + name (from status.json) so the in-app
     * sheet can show it later — the sheet has no access to the manifest at
     * display time. Single slot keyed by versionCode; the worker refreshes it on
     * every check (even when the version isn't newer), which is also what lets
     * the DEBUG preview show the real notes.
     */
    public static void setChangelog(Context context, int versionCode, String versionName, String changelog) {
        prefs(context).edit()
                .putInt(Keys.UPDATE_CHANGELOG_VERSION_CODE, versionCode)
                .putString(Keys.UPDATE_CHANGELOG_NAME, versionName == null ? "" : versionName)
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

    /** Last-seen changelog text (any version), for the DEBUG preview; "" if none. */
    public static String getLastChangelog(Context context) {
        return prefs(context).getString(Keys.UPDATE_CHANGELOG_TEXT, "");
    }

    /** Last-seen version name (any version), for the DEBUG preview; "" if none. */
    public static String getLastChangelogName(Context context) {
        return prefs(context).getString(Keys.UPDATE_CHANGELOG_NAME, "");
    }

    public static String readyVersionName(Context context) {
        return prefs(context).getString(Keys.UPDATE_READY_NAME, "");
    }

    /** Drop the pending-download record (does not touch any verified file). */
    public static void clearPending(Context context) {
        synchronized (LOCK) {
            clearPendingLocked(context);
        }
    }

    private static void clearPendingLocked(Context context) {
        prefs(context).edit()
                .remove(Keys.UPDATE_DOWNLOAD_ID)
                .remove(Keys.UPDATE_DOWNLOAD_PLAN)
                .remove(Keys.UPDATE_DOWNLOAD_URLS)
                .remove(Keys.UPDATE_DOWNLOAD_URL_INDEX)
                .remove(Keys.UPDATE_DOWNLOAD_SHA)
                .remove(Keys.UPDATE_DOWNLOAD_NAME)
                .remove(Keys.UPDATE_DOWNLOAD_VERSION_CODE)
                .remove(Keys.UPDATE_DOWNLOAD_FINALIZING_AT)
                .apply();
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
            deleteArtifacts(context);
        }
    }

    /** The patch, the .xz and a half-rebuilt APK: never needed once a step is over. */
    private static void deleteArtifacts(Context context) {
        File[] files = {
                fileFor(context, UpdatePlan.Kind.PATCH),
                fileFor(context, UpdatePlan.Kind.XZ),
                partFile(context),
        };
        for (File f : files) {
            if (f.exists()) {
                f.delete();
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

    static int queryStatus(DownloadManager dm, long id) {
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

    // The URL list of a record written before plans existed: newline-joined
    // (URLs never contain a newline).
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
