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
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
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
 * When the Download Manager can't take a download (the user disabled the
 * Download Manager app, or there is no external files dir), the same step
 * is downloaded by UpdateDirectDownloadWorker instead. Its id is negative
 * ({@link #isDirect}); everything else, the plan, the files, the claim and
 * the verification, is the same.
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

    /**
     * How far in the future a claim may be and still hold: a clock set back
     * while a rebuild runs (a network time correction) must not have
     * start() take the download from under a live worker. Hours beyond it
     * can only be a claim written under a clock that was far ahead.
     */
    private static final long CLAIM_CLOCK_SLACK_MS = 6L * 60L * 60L * 1000L;

    /**
     * Free space kept beyond what a patch or .xz step needs (the downloaded
     * file plus the APK it is rebuilt into), so an update never fills the
     * phone to the byte.
     */
    public static final long SPACE_MARGIN_BYTES = 32L * 1024L * 1024L;

    /**
     * A patch or .xz whose rebuild failed on this device this many times for
     * one version (storage, an unreadable file) is skipped like one that
     * rebuilt the wrong APK. Retrying a step that keeps failing here would
     * download it again every backoff round and never reach the APK.
     */
    private static final int MAX_DEVICE_FAILURES = 2;

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
     * The download {@code downloadId} failed (DownloadManager error, or a file
     * that is not the one status.json named): try the step's next mirror, then
     * the next step. When nothing is left, back off — no tight retry loop:
     * DownloadManager already retries transient network errors internally.
     * Ignored when the record has moved on to another download.
     */
    public static void onAttemptFailed(Context context, long downloadId) {
        synchronized (LOCK) {
            Pending pending = readPendingLocked(context);
            if (pending == null || pending.downloadId != downloadId) {
                Log.w(TAG, "attempt failure for a download no longer recorded; ignored");
                return;
            }
            if (pending.plan.nextUrl()) {
                Log.w(TAG, "download attempt failed; trying " + pending.plan.current().kind
                        + " from mirror " + pending.plan.urlIndex());
                enqueueLocked(context, pending.plan, pending.apkSha256, pending.name, pending.versionCode);
                return;
            }
            Log.w(TAG, "download attempt failed; nothing left to try, backing off");
            backOffLocked(context, pending.versionCode);
        }
    }

    /**
     * The patch or .xz downloaded intact but did not rebuild the expected
     * APK. Every mirror serves the same file, so skip the whole step, and
     * remember it for this version so later rounds don't try it again.
     */
    public static void onStepFailed(Context context, long downloadId) {
        synchronized (LOCK) {
            Pending pending = readPendingLocked(context);
            if (pending == null || pending.downloadId != downloadId) {
                Log.w(TAG, "step failure for a download no longer recorded; ignored");
                return;
            }
            stepFailedLocked(context, pending);
        }
    }

    /**
     * Something on THIS device failed (storage full, a file it could not
     * read or write): the downloaded file may be fine, so the first time,
     * wait and start the whole plan again. A patch or .xz that fails here
     * {@link #MAX_DEVICE_FAILURES} times for a version is skipped, so the
     * ladder still reaches the APK.
     */
    public static void onDeviceFailed(Context context, long downloadId) {
        synchronized (LOCK) {
            Pending pending = readPendingLocked(context);
            if (pending == null || pending.downloadId != downloadId) {
                Log.w(TAG, "device failure for a download no longer recorded; ignored");
                return;
            }
            deviceFailedLocked(context, pending);
        }
    }

    /** Must be called under LOCK. See {@link #onDeviceFailed}. */
    private static void deviceFailedLocked(Context context, Pending pending) {
        UpdatePlan.Kind kind = pending.plan.current().kind;
        if (kind != UpdatePlan.Kind.APK) {
            SharedPreferences p = prefs(context);
            String step = pending.versionCode + ":" + kind.name();
            int count = (step.equals(p.getString(Keys.UPDATE_DEVICE_FAILED_STEP, null))
                    ? p.getInt(Keys.UPDATE_DEVICE_FAILED_COUNT, 0) : 0) + 1;
            if (count >= MAX_DEVICE_FAILURES) {
                p.edit().remove(Keys.UPDATE_DEVICE_FAILED_STEP)
                        .remove(Keys.UPDATE_DEVICE_FAILED_COUNT).apply();
                Log.w(TAG, kind + " failed on this device " + count + " times; skipping it");
                stepFailedLocked(context, pending);
                return;
            }
            p.edit().putString(Keys.UPDATE_DEVICE_FAILED_STEP, step)
                    .putInt(Keys.UPDATE_DEVICE_FAILED_COUNT, count).apply();
        }
        backOffLocked(context, pending.versionCode);
    }

    /** Must be called under LOCK. Records the step's kind as failed and moves on. */
    private static void stepFailedLocked(Context context, Pending pending) {
        UpdatePlan.Kind kind = pending.plan.current().kind;
        if (kind == UpdatePlan.Kind.PATCH) {
            prefs(context).edit().putInt(Keys.UPDATE_PATCH_FAILED_VERSION, pending.versionCode).apply();
        } else if (kind == UpdatePlan.Kind.XZ) {
            prefs(context).edit().putInt(Keys.UPDATE_XZ_FAILED_VERSION, pending.versionCode).apply();
        }
        if (pending.plan.nextStep()) {
            Log.w(TAG, kind + " did not give the update; trying " + pending.plan.current().kind);
            enqueueLocked(context, pending.plan, pending.apkSha256, pending.name, pending.versionCode);
            return;
        }
        backOffLocked(context, pending.versionCode);
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

    /**
     * About how large the next APK will be: the installed one plus a tenth.
     * 0 when the installed APK can't be read.
     */
    public static long expectedApkBytes(Context context) {
        File installed = UpdateInstalledApk.file(context);
        long base = installed == null ? 0L : installed.length();
        return base + base / 10;
    }

    /** True when the files dir can hold the step's download and the APK rebuilt from it. */
    private static boolean roomForStep(Context context, UpdatePlan.Step step) {
        File dir = Preferences.getUpdateApkFile(context).getParentFile();
        if (dir == null) {
            return false;
        }
        return dir.getUsableSpace() >= step.size + expectedApkBytes(context) + SPACE_MARGIN_BYTES;
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
        return backoffActive(System.currentTimeMillis(), p.getLong(Keys.UPDATE_NEXT_RETRY_AT, 0L));
    }

    /**
     * True while {@code nextRetryAt} is still ahead of {@code now}, and no
     * further ahead than a backoff can be. A retry time further out was written
     * under a clock that was later set back (a phone that booted in 2030 and
     * then got the network time): believed, it would hold this version back
     * until the clock caught up, since only a newer release escapes it. The
     * window then counts as over.
     */
    static boolean backoffActive(long now, long nextRetryAt) {
        long remaining = nextRetryAt - now;
        return remaining > 0 && remaining <= BACKOFF_MAX_MS + BACKOFF_MAX_MS / 5;
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
        Pending pending = readPendingLocked(context);
        if (pending == null) {
            // An id with no readable record: nothing could ever finish it.
            removeDownload(context, previousId);
            clearPendingLocked(context);
            return false;
        }
        boolean finished;
        if (isDirect(previousId)) {
            if (p.getLong(Keys.UPDATE_DIRECT_DONE, 0L) != previousId) {
                // Queued, running or waiting out a retry; enqueued again
                // (KEEP: a no-op then) so a download whose work was lost
                // resumes its part file instead of standing still.
                Log.d(TAG, "a direct download is in flight; not enqueuing again");
                UpdateDirectDownloadWorker.enqueue(context, previousId);
                return true;
            }
            finished = true;
        } else {
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
            finished = status == DownloadManager.STATUS_SUCCESSFUL;
        }
        if (finished) {
            long claimedAt = p.getLong(Keys.UPDATE_DOWNLOAD_FINALIZING_AT, 0L);
            long age = System.currentTimeMillis() - claimedAt;
            if (claimedAt == 0L) {
                Log.d(TAG, "a finished download was never verified; handing it over");
                UpdateFinalizeWorker.enqueue(context, previousId);
                return true;
            }
            // A clock set back makes the age negative. A little is a live
            // claim; more than a claim's whole lifetime says nothing about when
            // it was taken, and believed, it would stop every download for as
            // long as the clock was off. Such a claim counts as stale.
            if (claimLive(age)) {
                Log.d(TAG, "a finished download is being verified; not enqueuing again");
                return true;
            }
            // WorkManager stops a worker after 10 minutes, so a claim this old
            // belongs to one that died — its process crashed or ran out of
            // memory rebuilding. Doing that again would die again: a patch or
            // .xz is skipped; the APK (only hashed) is handed over again.
            if (pending.plan.current().kind != UpdatePlan.Kind.APK) {
                Log.w(TAG, "the " + pending.plan.current().kind + " rebuild never finished; skipping it");
                stepFailedLocked(context, pending);
            } else {
                UpdateFinalizeWorker.enqueue(context, previousId);
            }
            return true;
        }
        return false;
    }

    /** A finalize claim {@code age} ms old (negative: the clock moved back) still holds. */
    static boolean claimLive(long age) {
        return age > -CLAIM_CLOCK_SLACK_MS && age < FINALIZE_CLAIM_MS;
    }

    /** Must be called under LOCK. Enqueues the plan's current step and records it. */
    private static void enqueueLocked(Context context, UpdatePlan plan, String apkSha256,
                                      String name, int versionCode) {
        DownloadManager dm = (DownloadManager) context.getSystemService(Context.DOWNLOAD_SERVICE);
        SharedPreferences p = prefs(context);
        removeDownload(context, p.getLong(Keys.UPDATE_DOWNLOAD_ID, -1));

        // DownloadManager refuses to overwrite an existing destination, so clear
        // any stale/partial file first — and the APK, which a patch or an .xz
        // is about to be turned into.
        deleteArtifacts(context);
        File apk = Preferences.getUpdateApkFile(context);
        if (apk.exists()) {
            apk.delete();
        }
        // That file may have been a verified update of an older version (a
        // newer release came out before it was installed): the record that
        // vouched for it goes with it. Left behind, it would offer "Update to
        // <older>" over the next download's PARTIAL file, which this same path
        // is now being written to.
        clearReadyRecordLocked(context);

        // A patch or .xz needs room for itself AND the APK rebuilt from it.
        // When there isn't, go to the APK now: DownloadManager checks the
        // APK's own size before transferring a byte.
        while (plan.current().kind != UpdatePlan.Kind.APK && !roomForStep(context, plan.current())) {
            Log.w(TAG, "not enough free space for the " + plan.current().kind + " step; skipping it");
            plan.nextStep();
        }
        File dest = fileFor(context, plan.current().kind);

        // The Download Manager when it can take the download: it survives the
        // app's process and needs nothing of ours to run. It can't when the
        // user disabled the Download Manager app (enqueue throws, every time)
        // or there is no external files dir for it to write to; then the
        // download is direct (UpdateDirectDownloadWorker), into the same file.
        long downloadId = -1;
        if (dm != null && context.getExternalFilesDir(null) != null) {
            downloadId = enqueueSystemLocked(context, dm, plan, dest);
        }
        boolean direct = downloadId == -1;
        if (direct) {
            downloadId = newDirectId();
        }

        Log.d(TAG, "downloading " + plan.current().kind + " for version " + versionCode
                + " from mirror " + plan.urlIndex() + (direct ? " directly" : ""));
        p.edit()
                .putLong(Keys.UPDATE_DOWNLOAD_ID, downloadId)
                .putString(Keys.UPDATE_DOWNLOAD_PLAN, plan.toJson())
                .putString(Keys.UPDATE_DOWNLOAD_SHA, apkSha256)
                .putString(Keys.UPDATE_DOWNLOAD_NAME, name == null ? "" : name)
                .putInt(Keys.UPDATE_DOWNLOAD_VERSION_CODE, versionCode)
                .remove(Keys.UPDATE_DOWNLOAD_FINALIZING_AT)
                .remove(Keys.UPDATE_DOWNLOAD_URLS)
                .remove(Keys.UPDATE_DOWNLOAD_URL_INDEX)
                .remove(Keys.UPDATE_DIRECT_DONE)
                .remove(Keys.UPDATE_DIRECT_STALLS)
                .apply();
        // After the record: the worker reads it.
        if (direct) {
            UpdateDirectDownloadWorker.enqueue(context, downloadId);
        }
    }

    /** Must be called under LOCK. A Download Manager download of {@code dest}, or -1 when it refused. */
    private static long enqueueSystemLocked(Context context, DownloadManager dm, UpdatePlan plan, File dest) {
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
            return dm.enqueue(request);
        } catch (Exception e) {
            // The Download Manager app disabled by the user throws here, on
            // every check; so does one disabled mid-call.
            Log.w(TAG, "the Download Manager refused the update download; downloading directly", e);
            return -1;
        }
    }

    // ── progress, for the screens that show it ───────────────────────────

    /** What the update download is doing now, for About's row and the update sheet. */
    public static final class Progress {

        public enum Phase {
            /** No update is being downloaded. */
            NONE,
            /** A step of the plan is downloading. */
            DOWNLOADING,
            /** Downloaded: being rebuilt from a patch or .xz, and verified. */
            PREPARING,
            /** A download failed; the next try waits out its backoff. */
            WAITING
        }

        public static final Progress NONE = new Progress(Phase.NONE, 0L, 0L, -1, "");

        public final Phase phase;
        /** Bytes of the current step on disk (DOWNLOADING only). */
        public final long bytes;
        /** The current step's size, 0 when unknown. */
        public final long total;
        public final int versionCode;
        /** "" when unknown. */
        public final String name;

        Progress(Phase phase, long bytes, long total, int versionCode, String name) {
            this.phase = phase;
            this.bytes = bytes;
            this.total = total;
            this.versionCode = versionCode;
            this.name = name == null ? "" : name;
        }

        /** 0–100, or -1 when the size is unknown. */
        public int percent() {
            if (phase != Phase.DOWNLOADING || total <= 0) {
                return -1;
            }
            return (int) Math.min(100L, Math.max(0L, bytes * 100L / total));
        }

        public boolean active() {
            return phase != Phase.NONE;
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof Progress)) {
                return false;
            }
            Progress p = (Progress) o;
            return phase == p.phase && bytes == p.bytes && total == p.total
                    && versionCode == p.versionCode && name.equals(p.name);
        }

        @Override
        public int hashCode() {
            return phase.hashCode() * 31 + Long.hashCode(bytes) * 17 + versionCode;
        }
    }

    /**
     * What the update download is doing now. The size is the current STEP's
     * (a patch falling back to the APK starts again at 0 of a bigger size),
     * never a total over the plan. Off the main thread: a Download Manager
     * download is a query to its provider.
     */
    public static Progress progress(Context context) {
        Pending pending;
        long finalizingAt;
        synchronized (LOCK) {
            pending = readPendingLocked(context);
            finalizingAt = prefs(context).getLong(Keys.UPDATE_DOWNLOAD_FINALIZING_AT, 0L);
        }
        if (pending == null) {
            SharedPreferences p = prefs(context);
            int failed = p.getInt(Keys.UPDATE_FAILED_VERSION_CODE, -1);
            if (failed > App.getVersionCode() && isBackingOff(context, failed)) {
                String name = p.getInt(Keys.UPDATE_CHANGELOG_VERSION_CODE, -1) == failed
                        ? p.getString(Keys.UPDATE_CHANGELOG_NAME, "") : "";
                return new Progress(Progress.Phase.WAITING, 0L, 0L, failed, name);
            }
            return Progress.NONE;
        }
        long total = pending.plan.current().size;
        if (finalizingAt != 0L && !claimLive(System.currentTimeMillis() - finalizingAt)) {
            // A claim a dead worker left: nothing is being checked. The next
            // check hands the download on (inFlightLocked); until then the
            // row offers that check rather than a check that never ends.
            return Progress.NONE;
        }
        if (finalizingAt != 0L) {
            return new Progress(Progress.Phase.PREPARING, 0L, total, pending.versionCode, pending.name);
        }
        long id = pending.downloadId;
        if (isDirect(id)) {
            if (prefs(context).getLong(Keys.UPDATE_DIRECT_DONE, 0L) == id) {
                return new Progress(Progress.Phase.PREPARING, 0L, total, pending.versionCode, pending.name);
            }
            long bytes = directPartFile(context, id).length();
            return new Progress(Progress.Phase.DOWNLOADING, bytes, total, pending.versionCode, pending.name);
        }
        DownloadManager dm = (DownloadManager) context.getSystemService(Context.DOWNLOAD_SERVICE);
        if (dm == null) {
            return new Progress(Progress.Phase.DOWNLOADING, 0L, total, pending.versionCode, pending.name);
        }
        DownloadManager.Query query = new DownloadManager.Query().setFilterById(id);
        try (Cursor cursor = dm.query(query)) {
            if (cursor != null && cursor.moveToFirst()) {
                int statusCol = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS);
                int bytesCol = cursor.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR);
                int totalCol = cursor.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES);
                int status = statusCol >= 0 ? cursor.getInt(statusCol) : -1;
                if (status == DownloadManager.STATUS_SUCCESSFUL) {
                    return new Progress(Progress.Phase.PREPARING, 0L, total, pending.versionCode, pending.name);
                }
                long bytes = bytesCol >= 0 ? Math.max(0L, cursor.getLong(bytesCol)) : 0L;
                long reported = totalCol >= 0 ? cursor.getLong(totalCol) : -1L;
                if (reported > 0) {
                    total = reported;
                }
                return new Progress(Progress.Phase.DOWNLOADING, bytes, total, pending.versionCode, pending.name);
            }
        } catch (RuntimeException e) {
            // The Download Manager turned off while it holds the download:
            // the progress is unknown, never a crash of the screen asking.
            Log.w(TAG, "could not read the progress of download " + id, e);
        }
        return new Progress(Progress.Phase.DOWNLOADING, 0L, total, pending.versionCode, pending.name);
    }

    // ── direct downloads (UpdateDirectDownload) ──────────────────────────

    /** True for the id of a direct download: negative, and never -1 ("none"). */
    public static boolean isDirect(long downloadId) {
        return downloadId < -1;
    }

    private static long newDirectId() {
        return -2L - ThreadLocalRandom.current().nextLong(1L << 48);
    }

    /** Where a direct download writes until it is complete: one file per download. */
    public static File directPartFile(Context context, long downloadId) {
        File apk = Preferences.getUpdateApkFile(context);
        return new File(apk.getParentFile(), Preferences.UPDATE_APK + ".dl" + (-downloadId));
    }

    /** The record, when it is about the direct download {@code downloadId}; else null. */
    static Pending directPending(Context context, long downloadId) {
        synchronized (LOCK) {
            Pending pending = readPendingLocked(context);
            if (pending == null || pending.downloadId != downloadId || !isDirect(downloadId)) {
                return null;
            }
            return pending;
        }
    }

    /**
     * The direct download {@code downloadId} wrote all of {@code part}: moves
     * it to the step's file and hands it to UpdateFinalizeWorker, as the
     * Download Manager's completion broadcast does. Only while the record
     * names this download; a stale one's part is deleted. False when nothing
     * was handed over.
     */
    static boolean completeDirect(Context context, long downloadId, File part) {
        synchronized (LOCK) {
            Pending pending = readPendingLocked(context);
            if (pending == null || pending.downloadId != downloadId) {
                part.delete();
                return false;
            }
            File dest = fileFor(context, pending.plan.current().kind);
            try {
                Files.move(part.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException | RuntimeException e) {
                Log.e(TAG, "could not move the finished update download into place", e);
                part.delete();
                deviceFailedLocked(context, pending);
                return false;
            }
            prefs(context).edit()
                    .putLong(Keys.UPDATE_DIRECT_DONE, downloadId)
                    .remove(Keys.UPDATE_DIRECT_STALLS)
                    .apply();
            UpdateFinalizeWorker.enqueue(context, downloadId);
            return true;
        }
    }

    /**
     * One more run of the direct download that downloaded nothing; the count
     * so far, or -1 when the record names another download.
     */
    static int countDirectStall(Context context, long downloadId) {
        synchronized (LOCK) {
            SharedPreferences p = prefs(context);
            if (p.getLong(Keys.UPDATE_DOWNLOAD_ID, -1) != downloadId) {
                return -1;
            }
            int count = p.getInt(Keys.UPDATE_DIRECT_STALLS, 0) + 1;
            p.edit().putInt(Keys.UPDATE_DIRECT_STALLS, count).apply();
            return count;
        }
    }

    /** The direct download {@code downloadId} made progress. */
    static void resetDirectStalls(Context context, long downloadId) {
        synchronized (LOCK) {
            SharedPreferences p = prefs(context);
            if (p.getLong(Keys.UPDATE_DOWNLOAD_ID, -1) == downloadId) {
                p.edit().remove(Keys.UPDATE_DIRECT_STALLS).apply();
            }
        }
    }

    /**
     * True when the download {@code downloadId} finished: the Download
     * Manager says so, or a direct download handed its file over.
     */
    public static boolean isDownloadComplete(Context context, long downloadId) {
        if (isDirect(downloadId)) {
            return prefs(context).getLong(Keys.UPDATE_DIRECT_DONE, 0L) == downloadId;
        }
        DownloadManager dm = (DownloadManager) context.getSystemService(Context.DOWNLOAD_SERVICE);
        if (dm == null) {
            return false;
        }
        return queryStatus(dm, downloadId) == DownloadManager.STATUS_SUCCESSFUL;
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
    public static void markReady(Context context, long downloadId, int versionCode, String name) {
        synchronized (LOCK) {
            Pending pending = readPendingLocked(context);
            if (pending == null || pending.downloadId != downloadId) {
                Log.w(TAG, "verified a download no longer recorded; ignored");
                return;
            }
            // For an APK step the DownloadManager row's file IS the ready APK,
            // and remove() would delete it, so the row is only forgotten. A
            // patch or .xz has been used: its row goes with its file.
            if (pending.plan.current().kind != UpdatePlan.Kind.APK) {
                removeDownload(context, downloadId);
            }
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
                    .remove(Keys.UPDATE_DIRECT_DONE)
                    .remove(Keys.UPDATE_DIRECT_STALLS)
                    .remove(Keys.UPDATE_DEVICE_FAILED_STEP)
                    .remove(Keys.UPDATE_DEVICE_FAILED_COUNT)
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
        // A recorded download writes to the APK's own path (the APK step), or
        // is about to rebuild it there (a patch or an .xz). The enqueue clears
        // the ready record; this is the belt for a record left by an older
        // build of this class.
        if (p.getLong(Keys.UPDATE_DOWNLOAD_ID, -1) != -1) {
            return false;
        }
        File apk = Preferences.getUpdateApkFile(context);
        return apk != null && apk.exists();
    }

    /**
     * How long status.json must keep naming a version older than the ready
     * APK before that APK is dropped as a pulled release. Two checks this far
     * apart are needed: a mirror or a CDN edge can serve the previous file
     * after a release (publish-release.sh updates the GitHub copy before the
     * website's deploy, and a deploy can fail), and dropping a good update
     * costs a full download. A day: a pulled release stays offered at most a
     * day longer, which costs nothing that matters.
     */
    static final long PULLED_CONFIRM_MS = 24L * 60L * 60L * 1000L;

    /**
     * status.json, as just fetched, names {@code manifestVersionCode}. When a
     * verified APK of a NEWER version waits on disk, that release was pulled
     * (status.json went back): the first sighting is remembered, and a later
     * check at least {@link #PULLED_CONFIRM_MS} on drops the APK and its
     * record, so the sheet, the menu row and About stop offering it. Any
     * sighting of that version or a newer one forgets the first.
     */
    public static void onManifestSeen(Context context, int manifestVersionCode) {
        synchronized (LOCK) {
            SharedPreferences p = prefs(context);
            if (!p.getBoolean(Keys.UPDATE_READY, false)
                    || p.getInt(Keys.UPDATE_READY_VERSION_CODE, -1) <= manifestVersionCode) {
                if (p.contains(Keys.UPDATE_PULLED_SEEN_AT)) {
                    p.edit().remove(Keys.UPDATE_PULLED_SEEN_AT).apply();
                }
                return;
            }
            long now = System.currentTimeMillis();
            long seenAt = p.getLong(Keys.UPDATE_PULLED_SEEN_AT, 0L);
            // Unset, or in the future (the clock moved back): start counting now.
            if (seenAt <= 0L || seenAt > now) {
                p.edit().putLong(Keys.UPDATE_PULLED_SEEN_AT, now).apply();
                return;
            }
            if (now - seenAt < PULLED_CONFIRM_MS) {
                return;
            }
            Log.w(TAG, "the ready update " + p.getInt(Keys.UPDATE_READY_VERSION_CODE, -1)
                    + " is no longer offered (status.json names " + manifestVersionCode + "); dropping it");
            clearReadyRecordLocked(context);
            File apk = Preferences.getUpdateApkFile(context);
            if (apk != null && apk.exists()) {
                apk.delete();
            }
        }
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

    /**
     * Drop the pending-download record and its DownloadManager row (which
     * deletes its file: a download still running when the app was updated
     * by another route would otherwise land unclaimed, up to 205 MB, and stay
     * until the next release). A verified APK is not touched: markReady
     * already removed it from the record.
     */
    public static void clearPending(Context context) {
        synchronized (LOCK) {
            removeDownload(context, prefs(context).getLong(Keys.UPDATE_DOWNLOAD_ID, -1));
            deleteArtifacts(context);
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
                .remove(Keys.UPDATE_DIRECT_DONE)
                .remove(Keys.UPDATE_DIRECT_STALLS)
                .apply();
    }

    /** Drop the verified "ready" record and delete the APK from disk. */
    public static void clearReady(Context context) {
        synchronized (LOCK) {
            clearReadyRecordLocked(context);
            File apk = Preferences.getUpdateApkFile(context);
            if (apk != null && apk.exists()) {
                apk.delete();
            }
            deleteArtifacts(context);
        }
    }

    /**
     * Must be called under LOCK. The ready record only; the file is the
     * caller's. The "Tap to update" notification that offered it goes too:
     * tapped later, it would install whatever is at the APK's path by then.
     * Nothing is done while an install runs (that notification is then its
     * progress, and the record is not cleared on that path).
     */
    private static void clearReadyRecordLocked(Context context) {
        if (prefs(context).getBoolean(Keys.UPDATE_READY, false) && !UpdateInstaller.isInstalling()) {
            UpdateNotification.cancelInstallProgress(context);
        }
        prefs(context).edit()
                .remove(Keys.UPDATE_READY)
                .remove(Keys.UPDATE_READY_VERSION_CODE)
                .remove(Keys.UPDATE_READY_NAME)
                .remove(Keys.UPDATE_PULLED_SEEN_AT)
                .apply();
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
        // Direct downloads' part files, whichever download wrote them (a
        // cancelled run can still be writing one for a moment).
        File dir = Preferences.getUpdateApkFile(context).getParentFile();
        String[] names = dir == null ? null : dir.list();
        if (names != null) {
            for (String n : names) {
                if (n.startsWith(Preferences.UPDATE_APK + ".dl")) {
                    new File(dir, n).delete();
                }
            }
        }
    }

    /** Remove a DownloadManager row (and its file) by id; no-op for -1. */
    private static void removeDownload(Context context, long id) {
        if (id == -1) {
            return;
        }
        if (isDirect(id)) {
            UpdateDirectDownloadWorker.cancel(context, id);
            directPartFile(context, id).delete();
            return;
        }
        DownloadManager dm = (DownloadManager) context.getSystemService(Context.DOWNLOAD_SERVICE);
        if (dm == null) {
            return;
        }
        try {
            dm.remove(id);
        } catch (RuntimeException e) {
            // The Download Manager app disabled by the user throws here; one of
            // the callers (the app-replaced receiver) runs on the main thread.
            Log.w(TAG, "could not remove download " + id, e);
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
        } catch (RuntimeException e) {
            // A Download Manager disabled after the download was recorded:
            // unknown, so the next check downloads again (directly).
            Log.w(TAG, "could not read download " + id, e);
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
