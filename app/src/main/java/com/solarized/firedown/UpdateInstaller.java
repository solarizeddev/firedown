package com.solarized.firedown;

import android.annotation.SuppressLint;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
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

    /**
     * How long "Installing…" waits for Android to answer the committed
     * session (its dialog, or the app being replaced) before the surfaces stop
     * showing the wait. Android checks the APK in that time; it is a moment on
     * most phones, so this only bounds a callback that never comes.
     */
    private static final long ANSWER_TIMEOUT_MS = 60_000;

    /** The update notification is a notification: at most two posts a second. */
    private static final long NOTIFICATION_INTERVAL_MS = 500;

    private static final UpdateInstallProgress sProgress = new UpdateInstallProgress();
    private static final MutableLiveData<UpdateInstallProgress.Snapshot> sState =
            new MutableLiveData<>(UpdateInstallProgress.Snapshot.IDLE);
    private static final Handler sMain = new Handler(Looper.getMainLooper());

    private UpdateInstaller() {}

    /**
     * What the install tap is doing, for the sheet and the About row. Every
     * transition is posted on its own (never coalesced), so an observer sees
     * PREPARING before FAILED even when they land a moment apart.
     */
    public static LiveData<UpdateInstallProgress.Snapshot> state() {
        return sState;
    }

    /**
     * Copies the verified APK into a PackageInstaller session and commits it.
     * Runs synchronously — the caller is responsible for threading (see
     * {@link UpdateInstallReceiver#handleUserTapInstall}, which uses goAsync).
     * One install at a time: a call while another runs does nothing.
     *
     * @param fromNotification the update notification turns into the progress
     */
    @SuppressLint("RequestInstallPackagesPolicy")
    public static void install(Context caller, String updateName, boolean fromNotification) {
        // The application context: the answer timeout below outlives a
        // receiver's restricted context.
        Context context = caller.getApplicationContext();
        UpdateInstallProgress.Snapshot started = sProgress.begin(updateName, fromNotification);
        if (started == null) {
            Log.d(TAG, "install already running");
            return;
        }
        long generation = started.generation;
        publish(context, started);

        File updateFile = Preferences.getUpdateApkFile(context);

        if (updateFile == null || !updateFile.exists()) {
            Log.e(TAG, "Update APK not found");
            publish(context, sProgress.failed(generation));
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
            long total = updateFile.length();
            long lastNotified = 0;
            try (InputStream in = new FileInputStream(updateFile);
                 OutputStream out = session.openWrite("package", 0, total)) {
                byte[] buffer = new byte[65536];
                long copied = 0;
                int bytesRead;
                while ((bytesRead = in.read(buffer)) != -1) {
                    out.write(buffer, 0, bytesRead);
                    copied += bytesRead;
                    UpdateInstallProgress.Snapshot moved = sProgress.copied(generation, copied, total);
                    if (moved != null) {
                        // Android's own record of the copy, for whatever
                        // system surface follows install sessions.
                        session.setStagingProgress(moved.percent / 100f);
                        sMain.post(() -> sState.setValue(moved));
                        long now = SystemClock.elapsedRealtime();
                        if (fromNotification && now - lastNotified >= NOTIFICATION_INTERVAL_MS) {
                            lastNotified = now;
                            UpdateNotification.showInstallProgress(context, moved.percent);
                        }
                    }
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

            // INSTALLING before the commit, not after: Android's answer is a
            // broadcast on the main thread and must never find this install
            // still PREPARING (it would be ignored, and the wait would run to
            // the timeout).
            publish(context, sProgress.committed(generation));
            session.commit(pendingIntent.getIntentSender());
            committed = true;
            Log.d(TAG, "APK installation initiated");
            sMain.postDelayed(() -> {
                UpdateInstallProgress.Snapshot quiet = sProgress.timedOut(generation);
                if (quiet != null) {
                    publish(context, quiet);
                }
            }, ANSWER_TIMEOUT_MS);
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
            publish(context, sProgress.failed(generation));
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

    /**
     * Android answered the committed session: its confirmation dialog is up
     * ({@code STATUS_PENDING_USER_ACTION}) or the install finished. The wait
     * is over for every surface.
     */
    public static void onAndroidAnswered(Context context) {
        publish(context, sProgress.answered());
    }

    /** Android refused the committed session before answering. */
    public static void onAndroidRefused(Context context) {
        publish(context, sProgress.refused());
    }

    /**
     * Hands a transition to the sheet and the About row, and to the update
     * notification when the install started there. A failure's notification
     * is the caller's (the existing "Update failed" one, which replaces the
     * progress: both use the prompt's id).
     */
    private static void publish(Context context, UpdateInstallProgress.Snapshot s) {
        if (s == null) {
            return;
        }
        sMain.post(() -> sState.setValue(s));
        if (!s.fromNotification) {
            return;
        }
        switch (s.phase) {
            case PREPARING:
                UpdateNotification.showInstallProgress(context, s.percent);
                break;
            case INSTALLING:
                UpdateNotification.showInstallProgress(context, -1);
                break;
            case IDLE:
                UpdateNotification.cancelInstallProgress(context);
                break;
            default:
                break;
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