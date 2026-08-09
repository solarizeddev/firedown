package com.solarized.firedown.phone.player;

import android.Manifest;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.support.v4.media.MediaMetadataCompat;
import android.support.v4.media.session.MediaSessionCompat;
import android.support.v4.media.session.PlaybackStateCompat;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.media.app.NotificationCompat.MediaStyle;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;

import com.solarized.firedown.App;
import com.solarized.firedown.GlideHelper;
import com.solarized.firedown.Keys;
import com.solarized.firedown.R;
import com.solarized.firedown.data.entity.DownloadEntity;
import com.solarized.firedown.phone.PlayerActivity;
import com.solarized.firedown.utils.BuildUtils;
import com.solarized.firedown.utils.NotificationID;
import com.solarized.firedown.utils.WebUtils;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Foreground {@code mediaPlayback} service that keeps the LOCAL-file player
 * ({@code MediaViewerFragment}'s ExoPlayer, shared via {@link PlaybackHub})
 * playing while {@code PlayerActivity} is backgrounded — screen off, lock
 * screen, Home without PiP — with a MediaStyle notification for lock-screen
 * / notification-shade control.
 *
 * <p>Modeled on {@code GeckoMediaPlaybackService} (the browser-media twin),
 * but deliberately slimmer: the ExoPlayer is built with
 * {@code setAudioAttributes(..., handleAudioFocus=true)} and
 * {@code setHandleAudioBecomingNoisy(true)}, so the audio-focus state
 * machine and the becoming-noisy receiver that service hand-rolls are
 * handled inside media3 here — do not re-add them.
 *
 * <p>Lifecycle contract (see {@link PlaybackHub} for the ownership half):
 * <ul>
 *   <li>{@link #ACTION_START} — sent by PlayerActivity.onStop when playback
 *       should continue; binds to the hub player and goes foreground.
 *   <li>PlayerActivity.onStart calls {@code stopService(...)} when the UI is
 *       back — onDestroy detaches WITHOUT touching playback.
 *   <li>{@link #ACTION_STOP} (notification dismiss / session stop) — pauses
 *       playback, then stops the service; the paused player stays in memory
 *       so returning to the activity resumes at position.
 *   <li>If the fragment was destroyed while backgrounded (owner detached),
 *       onDestroy also releases the player — exactly one release, ever.
 * </ul>
 */
public class PlayerPlaybackService extends Service {

    public static final String ACTION_START =
            "com.solarized.firedown.phone.player.START";
    public static final String ACTION_PLAY =
            "com.solarized.firedown.phone.player.PLAY";
    public static final String ACTION_PAUSE =
            "com.solarized.firedown.phone.player.PAUSE";
    public static final String ACTION_STOP =
            "com.solarized.firedown.phone.player.STOP";

    private static final String MEDIA_SESSION_TAG =
            "com.solarized.firedown.player.session";

    /**
     * Bounded artwork decode — notification/lock-screen size only. 512 is
     * the same album-art ceiling GeckoMediaPlaybackService uses.
     */
    private static final int ARTWORK_MAX_DIM = 512;

    private ExoPlayer mPlayer;
    private DownloadEntity mEntity;
    private MediaSessionCompat mMediaSession;
    private NotificationManagerCompat mNotificationManager;
    private Bitmap mArtwork;
    private ExecutorService mArtworkExecutor;
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());

    /**
     * Mirrors the player into the notification + session state. Play/pause
     * flips rebuild the notification (one action at a time, the upstream
     * pattern); seeks refresh the position the lock-screen scrubber
     * extrapolates from.
     */
    private final Player.Listener mPlayerListener = new Player.Listener() {
        @Override
        public void onIsPlayingChanged(boolean isPlaying) {
            updateFromPlayer();
        }

        @Override
        public void onPositionDiscontinuity(@NonNull Player.PositionInfo oldPosition,
                                            @NonNull Player.PositionInfo newPosition,
                                            int reason) {
            updateFromPlayer();
        }

        @Override
        public void onPlaybackStateChanged(int playbackState) {
            if (playbackState == Player.STATE_ENDED) {
                // Session over. Via stopPlaybackAndSelf (pause is a no-op
                // at ENDED) so the background flag disarms too: left
                // armed, a later system reclaim of the stopped activity
                // would mark the player owner-detached with NO service
                // left to ever release it. The player itself stays for
                // the activity to show, unless the owner already
                // detached — onDestroy releases it then.
                stopPlaybackAndSelf();
            }
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        mNotificationManager = NotificationManagerCompat.from(this);
        mMediaSession = new MediaSessionCompat(this, MEDIA_SESSION_TAG);
        mMediaSession.setCallback(new MediaSessionCompat.Callback() {
            @Override
            public void onPlay() {
                if (mPlayer != null) mPlayer.play();
            }

            @Override
            public void onPause() {
                if (mPlayer != null) mPlayer.pause();
            }

            @Override
            public void onSeekTo(long pos) {
                if (mPlayer != null) mPlayer.seekTo(pos);
            }

            @Override
            public void onStop() {
                stopPlaybackAndSelf();
            }
        });
        mMediaSession.setActive(true);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : null;
        if (action == null) {
            // START_NOT_STICKY restart with a null intent — nothing to
            // resume (the hub died with the process). Same stance as the
            // Gecko media service.
            stopSelf();
            return START_NOT_STICKY;
        }

        switch (action) {
            case ACTION_START:
                ExoPlayer player = PlaybackHub.player();
                if (player == null) {
                    // Nothing to keep alive (the hub died with the
                    // process, or was cleared) — any armed flag is stale.
                    PlaybackHub.setBackgroundActive(false);
                    stopSelf();
                    break;
                }
                if (mPlayer != player) {
                    if (mPlayer != null) mPlayer.removeListener(mPlayerListener);
                    mPlayer = player;
                    mPlayer.addListener(mPlayerListener);
                }
                mEntity = PlaybackHub.entity();
                mMediaSession.setSessionActivity(createContentIntent());
                loadArtworkAsync();
                updateFromPlayer();
                break;

            case ACTION_PLAY:
                if (mPlayer != null) mPlayer.play();
                break;

            case ACTION_PAUSE:
                if (mPlayer != null) mPlayer.pause();
                break;

            case ACTION_STOP:
                stopPlaybackAndSelf();
                break;
        }
        return START_NOT_STICKY;
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        // User swiped the app from recents — that is a quit, not a
        // background request. Pause and shut down (release happens in
        // onDestroy if the fragment is already gone).
        stopPlaybackAndSelf();
    }

    @Override
    public void onDestroy() {
        if (mArtworkExecutor != null) {
            mArtworkExecutor.shutdownNow();
            mArtworkExecutor = null;
        }
        // A pending artwork post would retain this instance (and run
        // updateFromPlayer against torn-down state) — drop it.
        mMainHandler.removeCallbacksAndMessages(null);
        if (mPlayer != null) {
            mPlayer.removeListener(mPlayerListener);
            // Release duty passed here only when the fragment was destroyed
            // mid-background-playback (see PlaybackHub's ownership
            // contract); the common path — activity back in foreground
            // called stopService — must leave the live player alone.
            if (PlaybackHub.isOwnerDetached()) {
                mPlayer.release();
                PlaybackHub.clearIfHolds(mPlayer);
            }
            mPlayer = null;
        }
        // Deliberately NO setBackgroundActive(false) here: onDestroy also
        // runs for the PREVIOUS instance after a rapid resume →
        // re-background cycle (the activity's stopService is processed
        // after the new session already armed), and a blanket disarm
        // clobbered the fresh session — leaving it running with the flag
        // false, so a later fragment reclaim released the player under
        // the live service. The disarm lives where it means "this
        // session is over": stopPlaybackAndSelf (notification dismiss /
        // task removed / ENDED / no player), clearIfHolds (detached
        // release above), and the activity's own onStart.
        mEntity = null;
        stopForeground(true);
        mNotificationManager.cancel(NotificationID.PLAYER_MEDIA_ID);
        mMediaSession.release();
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void stopPlaybackAndSelf() {
        // This session is over — disarm so MediaViewerFragment's lifecycle
        // reverts to normal stop-on-background behavior (see the onDestroy
        // comment for why the disarm lives here and not there).
        PlaybackHub.setBackgroundActive(false);
        if (mPlayer != null) {
            mPlayer.pause();
        }
        stopSelf();
    }

    // ── Notification / session state ─────────────────────────────────────

    private void updateFromPlayer() {
        if (mPlayer == null) return;
        boolean isPlaying = mPlayer.isPlaying();
        long position = mPlayer.getCurrentPosition();
        long duration = mPlayer.getDuration();
        float speed = mPlayer.getPlaybackParameters().speed;

        String title = mEntity != null ? mEntity.getFileName() : null;
        String subtitle = domainOf(mEntity);

        mMediaSession.setMetadata(new MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, title)
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, subtitle)
                .putBitmap(MediaMetadataCompat.METADATA_KEY_ART, mArtwork)
                .putLong(MediaMetadataCompat.METADATA_KEY_DURATION,
                        duration > 0 ? duration : -1L)
                .build());

        mMediaSession.setPlaybackState(new PlaybackStateCompat.Builder()
                .setActions(PlaybackStateCompat.ACTION_PLAY_PAUSE
                        | PlaybackStateCompat.ACTION_PLAY
                        | PlaybackStateCompat.ACTION_PAUSE
                        | PlaybackStateCompat.ACTION_SEEK_TO)
                .setState(isPlaying
                                ? PlaybackStateCompat.STATE_PLAYING
                                : PlaybackStateCompat.STATE_PAUSED,
                        position, speed)
                .build());

        NotificationCompat.Builder notification =
                buildNotification(title, subtitle, isPlaying);

        if (isPlaying) {
            try {
                startForeground(NotificationID.PLAYER_MEDIA_ID, notification.build());
            } catch (IllegalStateException e) {
                // Foreground promotion denied (background-start window
                // missed on some OEMs). Without FGS protection the session
                // can't be kept honest — pause and shut down, same stance
                // as GeckoMediaPlaybackService; the paused player stays
                // for the activity to resume.
                stopPlaybackAndSelf();
            }
        } else {
            // Paused in the background: demote so the notification is
            // dismissible; its delete intent (ACTION_STOP) tears the
            // service down cleanly. Same id, so notify() updates in place.
            // Unlike startForeground (exempt), a plain notify() needs the
            // POST_NOTIFICATIONS runtime grant on 33+ — same guard as
            // GeckoMediaPlaybackService.
            stopForeground(false);
            if (ActivityCompat.checkSelfPermission(this,
                    Manifest.permission.POST_NOTIFICATIONS)
                    == PackageManager.PERMISSION_GRANTED) {
                mNotificationManager.notify(NotificationID.PLAYER_MEDIA_ID,
                        notification.build());
            }
        }
    }

    private NotificationCompat.Builder buildNotification(@Nullable String title,
                                                         @Nullable String subtitle,
                                                         boolean isPlaying) {
        int actionIcon;
        int actionLabel;
        String actionAction;
        int actionRequestCode;
        if (isPlaying) {
            actionIcon = R.drawable.media_action_pause;
            actionLabel = R.string.pip_pause;
            actionAction = ACTION_PAUSE;
            actionRequestCode = 1;
        } else {
            actionIcon = R.drawable.media_action_play;
            actionLabel = R.string.pip_play;
            actionAction = ACTION_PLAY;
            actionRequestCode = 2;
        }

        int pendingFlags = BuildUtils.hasAndroidS() ? PendingIntent.FLAG_IMMUTABLE : 0;
        PendingIntent actionIntent = PendingIntent.getService(this, actionRequestCode,
                new Intent(this, PlayerPlaybackService.class).setAction(actionAction),
                pendingFlags);
        PendingIntent deleteIntent = PendingIntent.getService(this, 3,
                new Intent(this, PlayerPlaybackService.class).setAction(ACTION_STOP),
                pendingFlags);

        MediaStyle style = new MediaStyle()
                .setMediaSession(mMediaSession.getSessionToken())
                .setShowActionsInCompactView(0);

        return new NotificationCompat.Builder(this, App.MEDIA_NOTIFICATION_ID)
                .setStyle(style)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setContentTitle(title)
                .setContentText(subtitle)
                .setLargeIcon(mArtwork)
                .setSmallIcon(isPlaying ? R.drawable.media_playing : R.drawable.media_paused)
                .setOngoing(isPlaying)
                .setContentIntent(createContentIntent())
                .setDeleteIntent(deleteIntent)
                .addAction(new NotificationCompat.Action(
                        actionIcon, getString(actionLabel), actionIntent));
    }

    /**
     * Tapping the notification reopens the player on the SAME entity.
     * PlayerActivity is singleTask, so this lands in onNewIntent →
     * loadFromIntent, and the fresh MediaViewerFragment adopts the live
     * player from the hub instead of building a second one.
     */
    private PendingIntent createContentIntent() {
        Intent intent = new Intent(this, PlayerActivity.class);
        if (mEntity != null) {
            intent.putExtra(Keys.ITEM_ID, mEntity);
        }
        int flags = BuildUtils.hasAndroidS()
                ? PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
                : PendingIntent.FLAG_UPDATE_CURRENT;
        return PendingIntent.getActivity(this, 0, intent, flags);
    }

    /**
     * Lock-screen artwork: the same Glide model the Downloads list renders
     * (video frame / embedded album art / image), synchronously on a worker
     * thread, bounded to notification size. Best-effort — a miss leaves the
     * MediaStyle notification without a large icon, which is fine.
     */
    private void loadArtworkAsync() {
        if (mArtwork != null || mEntity == null) return;
        if (mArtworkExecutor == null) {
            mArtworkExecutor = Executors.newSingleThreadExecutor();
        }
        DownloadEntity entity = mEntity;
        mArtworkExecutor.execute(() -> {
            Bitmap bitmap = GlideHelper.downloadThumbSync(
                    getApplicationContext(), entity, ARTWORK_MAX_DIM);
            if (bitmap == null) return;
            mMainHandler.post(() -> {
                if (mEntity != entity) return;
                mArtwork = bitmap;
                updateFromPlayer();
            });
        });
    }

    @Nullable
    private static String domainOf(@Nullable DownloadEntity entity) {
        if (entity == null) return null;
        String url = entity.getFileUrl();
        if (TextUtils.isEmpty(url)) return null;
        String domain = WebUtils.getDomainName(url);
        return TextUtils.isEmpty(domain) ? null : domain;
    }
}
