package com.solarized.firedown.p2pshare;

import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.os.SystemClock;
import android.text.format.Formatter;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.Observer;

import com.solarized.firedown.App;
import com.solarized.firedown.BaseActivity;
import com.solarized.firedown.R;
import com.solarized.firedown.phone.DownloadsActivity;
import com.solarized.firedown.utils.DebugLog;
import com.solarized.firedown.utils.NotificationID;

import javax.inject.Inject;

import dagger.hilt.android.AndroidEntryPoint;

/**
 * Foreground {@code dataSync} service that keeps a P2P share ALIVE while its
 * screen is gone — the "Send directly" fix for the old rule that the sender
 * had to sit on the share screen for the whole transfer (session lifetime
 * was view lifetime). The session itself is {@link P2pShareController}'s; this
 * service owns nothing but the process's foreground standing and the
 * notification that goes with it. It is started by the controller the
 * moment a session must outlive its screen (the sender's offer is minted,
 * the receiver accepted), observes {@link P2pShareController#getState()},
 * repaints the notification on every change that matters (stage, peer, a
 * whole percent), and stops ITSELF when the state goes null (cancelled) or
 * terminal (DONE/ERROR — the final notification stays, detached).
 *
 * <p>The {@code RunnableManager} shape: {@code dataSync} with the Android 15
 * 6-hour cap handled through {@code onTimeout} (the share is failed honestly
 * — nothing can keep it alive past the cap), and a refused
 * {@code startForeground} (quota spent, background-start window missed) never
 * crashes: the share keeps running for as long as the screen is open, which
 * is exactly the pre-service behaviour.
 *
 * <p>The notification's Cancel action ends the session through the
 * controller; its tap reopens the live screen through
 * {@link DownloadsActivity#EXTRA_OPEN_P2P}, where the screen attaches to the
 * running session and renders the same snapshot.
 */
@AndroidEntryPoint
public class P2pShareService extends Service {

    private static final String TAG = "P2pShareService";

    public static final String ACTION_START = "com.solarized.firedown.p2pshare.START";
    public static final String ACTION_CANCEL = "com.solarized.firedown.p2pshare.CANCEL";

    @Inject
    P2pShareController mController;

    private final Observer<ShareState> mObserver = this::onState;
    private boolean mObserving;
    private boolean mForeground;
    private boolean mFinished;
    // What the notification currently shows, so a progress storm (the engine
    // posts many events per second) rebuilds it only on a visible change.
    private ShareState.Stage mShownStage;
    private int mShownPercent = -1;
    private String mShownPeer;
    private long mShownAt;

    /** Bytes and speed in the text go stale between whole-percent steps; refresh this often. */
    private static final long PROGRESS_REFRESH_MS = 1000;

    /**
     * Start (or re-poke) the service for the controller's current session.
     * Never throws: Android 12+ refuses a foreground-service start from the
     * background (ForegroundServiceStartNotAllowedException), and the callers
     * run after async hops (the accept's disk task, a relay fetch). The share
     * then runs on without the service for as long as its screen keeps the
     * process alive — the pre-service behaviour — instead of crashing.
     */
    public static void start(@NonNull Context context) {
        Intent intent = new Intent(context, P2pShareService.class).setAction(ACTION_START);
        try {
            ContextCompat.startForegroundService(context, intent);
        } catch (RuntimeException e) {
            DebugLog.d(TAG, "startForegroundService refused: " + e);
        }
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_CANCEL.equals(action)) {
            DebugLog.d(TAG, "cancel from notification");
            // The controller clears its state; the observer below (or the
            // fall-through when nothing was ever observed) shuts us down.
            mController.stop();
            if (!mObserving) {
                shutdown(true);
            }
            return START_NOT_STICKY;
        }
        // startForegroundService() demands a startForeground() from EVERY
        // start — before the state is consulted, so a session that ended in
        // the gap between the controller's start and this call still goes
        // foreground once and then cleanly down, never "did not start in
        // time". A refusal is terminal for the service only.
        ShareState current = mController.peekState();
        if (!goForeground(buildNotification(current))) {
            return START_NOT_STICKY;
        }
        if (!mObserving) {
            mObserving = true;
            // Replays the current snapshot at once.
            mController.getState().observeForever(mObserver);
        }
        return START_NOT_STICKY;
    }

    private boolean goForeground(@NonNull NotificationCompat.Builder notification) {
        try {
            startForeground(NotificationID.P2P_SHARE_ID, notification.build());
            mForeground = true;
            return true;
        } catch (RuntimeException e) {
            // ForegroundServiceStartNotAllowedException and relatives — the
            // quota is spent or the start window was missed. The share runs
            // on without foreground standing (its screen keeps it alive as
            // before); only this service gives up.
            DebugLog.d(TAG, "startForeground refused: " + e);
            mFinished = true;
            stopSelf();
            return false;
        }
    }

    private void onState(@Nullable ShareState state) {
        if (mFinished) {
            return;
        }
        if (state == null) {
            shutdown(true);
            return;
        }
        if (!state.isLive()) {
            // The outcome stays in the shade, detached from the service.
            goForeground(buildNotification(state));
            shutdown(false);
            return;
        }
        int percent = state.total > 0 ? (int) (state.done * 100 / state.total) : -1;
        long now = SystemClock.elapsedRealtime();
        boolean changed = state.stage != mShownStage || percent != mShownPercent
                || (state.peerDevice == null ? mShownPeer != null
                : !state.peerDevice.equals(mShownPeer))
                || (state.stage == ShareState.Stage.TRANSFERRING
                && now - mShownAt >= PROGRESS_REFRESH_MS);
        if (!changed) {
            return;
        }
        mShownStage = state.stage;
        mShownPercent = percent;
        mShownPeer = state.peerDevice;
        mShownAt = now;
        // startForeground again rather than notify(): it updates in place and
        // is exempt from the POST_NOTIFICATIONS runtime grant a plain notify
        // needs on 33+.
        goForeground(buildNotification(state));
    }

    private void shutdown(boolean removeNotification) {
        if (mFinished) {
            return;
        }
        mFinished = true;
        if (mForeground) {
            try {
                stopForeground(removeNotification ? STOP_FOREGROUND_REMOVE : STOP_FOREGROUND_DETACH);
            } catch (RuntimeException e) {
                DebugLog.d(TAG, "stopForeground failed: " + e);
            }
            mForeground = false;
        }
        stopSelf();
    }

    @Override
    public void onDestroy() {
        if (mObserving) {
            mObserving = false;
            mController.getState().removeObserver(mObserver);
        }
        super.onDestroy();
    }

    /**
     * Android 15 caps a dataSync service at 6 cumulative hours per day and
     * calls this on the main thread; missing the few seconds it grants ends
     * the process. A share that long has nothing to fall back to — fail it
     * honestly (the row says so, Try again starts clean) and leave.
     */
    @RequiresApi(api = Build.VERSION_CODES.VANILLA_ICE_CREAM)
    @Override
    public void onTimeout(int startId, int fgsType) {
        DebugLog.d(TAG, "onTimeout startId=" + startId + " fgsType=" + fgsType);
        mController.failForSystemTimeout();
        shutdown(true);
    }

    @RequiresApi(api = Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    @Override
    public void onTimeout(int startId) {
        DebugLog.d(TAG, "onTimeout startId=" + startId);
        mController.failForSystemTimeout();
        shutdown(true);
    }

    /* ── notification ───────────────────────────────────────────────────── */

    private NotificationCompat.Builder buildNotification(@Nullable ShareState state) {
        boolean send = state == null || state.role == ShareState.Role.SEND;
        String file = state == null || state.fileName == null ? "" : state.fileName;
        String title = getString(send ? R.string.p2p_notif_sending : R.string.p2p_notif_receiving, file);

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, App.DOWNLOADS_NOTIFICATION_ID)
                .setSmallIcon(R.drawable.ic_p2p_send_24)
                .setContentTitle(title)
                .setContentIntent(contentIntent(send))
                .setOnlyAlertOnce(true)
                .setSilent(true)
                .setShowWhen(false);

        if (state == null || state.isLive()) {
            builder.setOngoing(true)
                    .addAction(R.drawable.ic_baseline_close_24, getString(R.string.cancel),
                            cancelIntent());
            if (state == null) {
                builder.setContentText(getString(R.string.p2p_preparing));
                return builder;
            }
            switch (state.stage) {
                case READY -> builder.setContentText(state.peerDevice == null
                        ? getString(R.string.p2p_notif_waiting)
                        : getString(R.string.p2p_notif_waiting_for, state.peerDevice));
                case CONNECTING, CONNECTED -> builder.setContentText(getString(R.string.p2p_state_connecting));
                case TRANSFERRING -> {
                    builder.setContentText(getString(R.string.p2p_progress_of,
                            Formatter.formatShortFileSize(this, state.done),
                            Formatter.formatShortFileSize(this, state.total),
                            Formatter.formatShortFileSize(this, state.rate)));
                    if (state.total > 0) {
                        builder.setProgress(100, (int) (state.done * 100 / state.total), false);
                    } else {
                        builder.setProgress(0, 0, true);
                    }
                }
                default -> builder.setContentText(getString(R.string.p2p_preparing));
            }
            return builder;
        }
        // Terminal: a plain, dismissible outcome.
        builder.setOngoing(false).setAutoCancel(true);
        if (state.stage == ShareState.Stage.DONE) {
            builder.setContentText(send
                    ? getString(R.string.p2p_done_sent_detail,
                            Formatter.formatShortFileSize(this, state.bytes))
                    : getString(R.string.p2p_done_received));
        } else {
            String code = state.errorCode == null ? ShareState.ERROR_ENGINE : state.errorCode;
            builder.setContentText(getString(
                    P2pShareCopy.errorText(code, false, state.arrivedRemote,
                            Boolean.FALSE.equals(state.relayAvailable))));
        }
        return builder;
    }

    /** Tap → the live share screen (the screen attaches to the running session). */
    private PendingIntent contentIntent(boolean send) {
        Intent intent = BaseActivity.screenIntent(this, DownloadsActivity.class)
                .putExtra(DownloadsActivity.EXTRA_OPEN_P2P,
                        send ? DownloadsActivity.P2P_SEND : DownloadsActivity.P2P_RECEIVE);
        return PendingIntent.getActivity(this, send ? 1 : 2, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private PendingIntent cancelIntent() {
        Intent intent = new Intent(this, P2pShareService.class).setAction(ACTION_CANCEL);
        return PendingIntent.getService(this, 3, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }
}
