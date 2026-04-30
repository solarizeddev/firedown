package com.solarized.firedown.geckoview.media;

import android.Manifest;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.os.Build;
import android.os.IBinder;
import android.support.v4.media.MediaMetadataCompat;
import android.support.v4.media.session.MediaSessionCompat;
import android.support.v4.media.session.PlaybackStateCompat;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.ActivityCompat;
import androidx.media.app.NotificationCompat.MediaStyle;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import com.solarized.firedown.App;
import com.solarized.firedown.R;
import com.solarized.firedown.glide.DomainThumbnail;
import com.solarized.firedown.phone.BrowserActivity;
import com.solarized.firedown.utils.BuildUtils;
import com.solarized.firedown.utils.FileUriHelper;
import com.solarized.firedown.IntentActions;
import com.solarized.firedown.Keys;
import com.solarized.firedown.utils.NotificationID;
import com.solarized.firedown.utils.UrlStringUtils;

import org.mozilla.geckoview.MediaSession;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import javax.inject.Inject;

import dagger.hilt.android.AndroidEntryPoint;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Foreground service that keeps the process alive during media playback and shows an ongoing
 * notification with playback controls.
 *
 * <p>Aligned with upstream {@code AbstractMediaSessionService} /
 * {@code MediaSessionServiceDelegate} from android-components:
 *
 * <ul>
 *   <li><b>Returns {@code START_NOT_STICKY}</b> — upstream never uses START_STICKY for media
 *       services; a restarted-with-null-intent service immediately stops itself, which is fragile.
 *   <li><b>{@code ACTION_AUDIO_BECOMING_NOISY} receiver</b> — registers/unregisters a
 *       {@link BroadcastReceiver} when playback starts/stops so that unplugging headphones
 *       automatically pauses media. Upstream calls this "BecomingNoisyReceiver".
 *   <li><b>Audio focus: full state machine</b> — handles GAIN (resume if was transiently lost),
 *       LOSS (pause, do not auto-resume), and LOSS_TRANSIENT (pause, schedule resume on regain).
 *       Also sets {@code setWillPauseWhenDucked(false)} so the system handles ducking natively.
 *   <li><b>Notification shows one action at a time</b> — play OR pause based on current state,
 *       with {@code setShowActionsInCompactView(0)} — not both actions always visible.
 *   <li><b>{@code METADATA_KEY_ART}</b> used instead of {@code METADATA_KEY_ALBUM_ART} —
 *       matches upstream; ALBUM_ART is for a smaller secondary image, ART is the primary artwork.
 *   <li><b>PlaybackStateCompat actions</b> — upstream sets
 *       {@code ACTION_PLAY_PAUSE | ACTION_PLAY | ACTION_PAUSE} without {@code ACTION_STOP}.
 *   <li><b>Notification explicitly cancelled on shutdown</b> — prevents a stale notification
 *       lingering after the service is destroyed (upstream bug 2005988 pattern).
 *   <li><b>{@code onTaskRemoved} implemented</b> — stops media and shuts down cleanly when
 *       the user swipes the app away from recents.
 * </ul>
 */
@AndroidEntryPoint
public class GeckoMediaPlaybackService extends Service {

    private static final String TAG = "GeckoMediaPlayback";
    public static final String MEDIA_SESSION_TAG  = "com.solarized.firedown.media.session";

    @Inject GeckoMediaController mGeckoMediaController;
    @Inject
    OkHttpClient mOkHttpClient;

    private MediaSessionCompat       mediaSessionCompat;
    private NotificationManagerCompat mNotificationManager;
    private AudioManager              mAudioManager;
    private AudioFocusRequest         mFocusRequest;

    // ── Audio focus state ─────────────────────────────────────────────────────────────────────────

    /**
     * {@code true} if focus was lost transiently while we were playing — we should resume
     * automatically when focus is regained.  Mirrors upstream {@code resumeOnFocusGain}.
     */
    private boolean mResumeOnFocusGain  = false;

    /**
     * {@code true} if focus was delayed (AUDIOFOCUS_REQUEST_DELAYED) — resume when granted.
     * Mirrors upstream {@code playDelayed}.
     */
    private boolean mPlayDelayed = false;

    // ── Headphone-unplug receiver ─────────────────────────────────────────────────────────────────

    /**
     * Pauses playback when the user unplugs headphones ({@code ACTION_AUDIO_BECOMING_NOISY}).
     * Upstream calls this "BecomingNoisyReceiver".
     * Only registered while we are in the PLAYING state; unregistered on pause/stop/destroy.
     */
    private final BroadcastReceiver mNoisyAudioReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (AudioManager.ACTION_AUDIO_BECOMING_NOISY.equals(intent.getAction())) {
                Log.d(TAG, "Audio becoming noisy — pausing");
                mGeckoMediaController.pause();
            }
        }
    };
    private boolean mNoisyReceiverRegistered = false;

    private int     mCurrentPlaybackState = PlaybackStateCompat.STATE_NONE;
    private boolean mIsForegroundService  = false;
    private static boolean sIsRunning = false;

    // ── Lifecycle ─────────────────────────────────────────────────────────────────────────────────

    @Override
    public void onCreate() {
        super.onCreate();
        sIsRunning = true;
        mNotificationManager = NotificationManagerCompat.from(this);
        setupAudioManager();
        setupMediaSession();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || intent.getAction() == null) {
            return START_NOT_STICKY;
        }

        String action   = intent.getAction();
        GeckoMetaData meta = mGeckoMediaController.getGeckoMetaData();

        Log.d(TAG, "onStartCommand action: " + action);

        if (meta == null && !action.equals(IntentActions.MEDIA_STOP)) {
            shutdown();
            return START_NOT_STICKY;
        }

        handleAction(action, meta, intent);
        return START_NOT_STICKY;
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        // User swiped the app away from recents — stop media and shut down cleanly.
        // Mirrors upstream MediaSessionServiceDelegate.onTaskRemoved().
        Log.d(TAG, "onTaskRemoved — stopping media");
        mGeckoMediaController.stop();
        shutdown();
    }

    @Override
    public void onDestroy() {
        sIsRunning = false;
        unregisterNoisyReceiverIfNeeded();
        mAudioManager.abandonAudioFocusRequest(mFocusRequest);
        mediaSessionCompat.release();
        mGeckoMediaController.stop();
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) { return null; }

    public static boolean isRunning() { return sIsRunning; }

    // ── Action handling ───────────────────────────────────────────────────────────────────────────

    private void handleAction(String action, GeckoMetaData meta) {
        handleAction(action, meta, null);
    }

    private void handleAction(String action, GeckoMetaData meta, @Nullable Intent intent) {
        Log.d(TAG, "handleAction: " + action);

        long position;
        long duration;
        float rate;

        if (intent != null && intent.hasExtra(Keys.MEDIA_POSITION)) {
            position = TimeUnit.SECONDS.toMillis((long) intent.getDoubleExtra(Keys.MEDIA_POSITION, 0));
            duration = TimeUnit.SECONDS.toMillis((long) intent.getDoubleExtra(Keys.MEDIA_DURATION, 0));
            rate     = intent.getFloatExtra(Keys.MEDIA_RATE, 1.0f);
        } else {
            MediaSession.PositionState pos = mGeckoMediaController.getPositionState();
            position = pos != null ? TimeUnit.SECONDS.toMillis((long) pos.position) : 0;
            duration = pos != null ? TimeUnit.SECONDS.toMillis((long) pos.duration) : 0;
            rate     = pos != null ? (float) pos.playbackRate : 1.0f;
        }

        switch (action) {
            case IntentActions.MEDIA_PLAY:
                mGeckoMediaController.play();
                updatePlayingState(action, position, duration, rate, meta);
                break;

            case IntentActions.MEDIA_PAUSE:
                mGeckoMediaController.pause();
                updatePausedState(action, position, duration, rate, meta);
                break;

            case IntentActions.MEDIA_METADATA:
            case IntentActions.MEDIA_POSITION:
                updateNotification(mCurrentPlaybackState, action, position, duration, rate, meta);
                break;

            case IntentActions.MEDIA_STOP:
                shutdown();
                break;
        }
    }

    /**
     * Estimates the current playback position based on the last known state.
     * If we were playing, advances the position by elapsed wall-clock time × playback rate.
     * If paused, returns the last known position as-is.
     */
    private long estimateCurrentPosition() {
        PlaybackStateCompat state = mediaSessionCompat.getController().getPlaybackState();
        if (state == null) {
            MediaSession.PositionState pos = mGeckoMediaController.getPositionState();
            return pos != null ? TimeUnit.SECONDS.toMillis((long) pos.position) : 0;
        }

        long lastPosition = state.getPosition();
        if (state.getState() == PlaybackStateCompat.STATE_PLAYING) {
            long elapsed = android.os.SystemClock.elapsedRealtime() - state.getLastPositionUpdateTime();
            float rate = state.getPlaybackSpeed();
            lastPosition += (long) (elapsed * rate);
        }

        long duration = getDuration();
        if (duration > 0 && lastPosition > duration) {
            lastPosition = duration;
        }

        return Math.max(0, lastPosition);
    }

    private long getDuration() {
        MediaSession.PositionState pos = mGeckoMediaController.getPositionState();
        return pos != null ? TimeUnit.SECONDS.toMillis((long) pos.duration) : 0;
    }

    private float getRate() {
        MediaSession.PositionState pos = mGeckoMediaController.getPositionState();
        return pos != null ? (float) pos.playbackRate : 1.0f;
    }

    // ── Playback state transitions ────────────────────────────────────────────────────────────────

    private void updatePlayingState(String action, long pos, long duration, float rate, GeckoMetaData meta) {
        registerNoisyReceiverIfNeeded();
        requestAudioFocus();
        updateNotification(PlaybackStateCompat.STATE_PLAYING, action, pos, duration, rate, meta);
    }

    private void updatePausedState(String action, long pos, long duration, float rate, GeckoMetaData meta) {
        unregisterNoisyReceiverIfNeeded();
        updateNotification(PlaybackStateCompat.STATE_PAUSED, action, pos, duration, rate, meta);
    }

    // ── Notification management ───────────────────────────────────────────────────────────────────

    private void updateNotification(int state, String action, long pos, long duration, float rate, GeckoMetaData meta) {

        Log.d(TAG, "updateNotification: state=" + state
                + " iconBitmap=" + (meta.getIconBitmap() != null)
                + " bitmap=" + (meta.getBitmap() != null));

        if(action.equals(IntentActions.MEDIA_METADATA) && meta.getBitmap() == null && meta.getIconBitmap() == null){
            fetchBitmap(meta);
        }

        mCurrentPlaybackState = state;

        // ── MediaSession metadata ──
        // Upstream uses METADATA_KEY_ART (primary artwork), not METADATA_KEY_ALBUM_ART.
        // METADATA_KEY_DURATION is set to -1 when unknown (upstream pattern).
        long durationValue = duration > 0 ? duration : -1L;
        mediaSessionCompat.setMetadata(new MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE,  meta.getTitle())
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, meta.getArtist())
                .putBitmap(MediaMetadataCompat.METADATA_KEY_ART,    meta.getBitmap())
                .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, durationValue)
                .build());

        // ── MediaSession playback state ──
        // Upstream actions: ACTION_PLAY_PAUSE | ACTION_PLAY | ACTION_PAUSE (no ACTION_STOP).
        mediaSessionCompat.setPlaybackState(new PlaybackStateCompat.Builder()
                .setActions(PlaybackStateCompat.ACTION_PLAY_PAUSE
                        | PlaybackStateCompat.ACTION_PLAY
                        | PlaybackStateCompat.ACTION_PAUSE)
                .setState(state, pos, rate)
                .build());

        // ── Build notification ──
        // Upstream shows exactly one action at a time (pause when playing, play when paused).
        // NotificationCompat.Builder has no clearActions() API, so we rebuild the builder
        // on every update. This is the only way to replace actions without accessing internal
        // fields (mActions) which triggers a library-group access warning.
        boolean isPlaying = (state == PlaybackStateCompat.STATE_PLAYING);
        NotificationCompat.Builder notification = buildNotification(meta, isPlaying);

        if (isPlaying) {
            try {
                startForeground(NotificationID.MEDIA_ID, notification.build());
                mIsForegroundService = true;
            } catch (IllegalStateException e) {
                // App is no longer in a state where foreground services are allowed
                // (e.g., screen off, backgrounded between event and delivery).
                // Shut down gracefully — the notification can't be shown anyway.
                Log.w(TAG, "Cannot start foreground — shutting down", e);
                shutdown();
            }
        } else {
            stopForeground(false);
            mIsForegroundService = false;
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    == PackageManager.PERMISSION_GRANTED) {
                mNotificationManager.notify(NotificationID.MEDIA_ID, notification.build());
            }
        }


    }

    /**
     * Builds a fresh {@link NotificationCompat.Builder} for the given state.
     *
     * <p>Rebuilt on every update instead of mutating a shared builder because
     * {@link NotificationCompat.Builder} has no public {@code clearActions()} API and accessing
     * the internal {@code mActions} field directly triggers an inter-library-group access warning.
     * Upstream reconstructs the notification from scratch on each state change as well.
     */
    private NotificationCompat.Builder buildNotification(GeckoMetaData meta, boolean isPlaying) {
        int actionIcon;
        String actionLabel;
        Intent actionIntent;
        int actionRequestCode;

        if (isPlaying) {
            actionIcon        = R.drawable.media_action_pause;
            actionLabel       = "Pause";
            actionIntent      = new Intent(this, GeckoMediaPlaybackService.class).setAction(IntentActions.MEDIA_PAUSE);
            actionRequestCode = 1;
        } else {
            actionIcon        = R.drawable.media_action_play;
            actionLabel       = "Play";
            actionIntent      = new Intent(this, GeckoMediaPlaybackService.class).setAction(IntentActions.MEDIA_PLAY);
            actionRequestCode = 2;
        }

        int pendingFlags = BuildUtils.hasAndroidS() ? PendingIntent.FLAG_IMMUTABLE : 0;

        // Upstream: one action shown in compact view at slot 0.
        MediaStyle style = new MediaStyle()
                .setMediaSession(mediaSessionCompat.getSessionToken())
                .setShowActionsInCompactView(0);

        return new NotificationCompat.Builder(this, App.MEDIA_NOTIFICATION_ID)
                .setStyle(style)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setContentTitle(meta.getTitle())
                .setContentText(meta.getArtist())
                .setLargeIcon(meta.getBitmap() != null ? meta.getBitmap() : meta.getIconBitmap())
                .setSmallIcon(isPlaying ? R.drawable.media_playing : R.drawable.media_paused)
                .setOngoing(isPlaying)
                .setContentIntent(createContentIntent(meta.getSessionId()))
                .addAction(new NotificationCompat.Action(
                        actionIcon, actionLabel,
                        PendingIntent.getService(this, actionRequestCode, actionIntent, pendingFlags)));
    }

    /**
     * Stops the foreground service, explicitly cancels the notification, releases the
     * MediaSession, and calls {@code stopSelf()}.
     *
     * <p>Upstream explicitly cancels the notification in {@code shutdown()} to prevent it
     * from persisting after the service is destroyed (with STOP_FOREGROUND_DETACH semantics).
     */
    private void shutdown() {
        Log.d(TAG, "shutDown");
        unregisterNoisyReceiverIfNeeded();
        stopForeground(true);
        mIsForegroundService = false;
        // Explicit cancel — prevents stale notification lingering after stopSelf().
        mNotificationManager.cancel(NotificationID.MEDIA_ID);
        mediaSessionCompat.release();
        stopSelf();
    }

    // ── Audio setup ───────────────────────────────────────────────────────────────────────────────

    private void setupAudioManager() {
        mAudioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        AudioAttributes playbackAttributes = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build();

        mFocusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(playbackAttributes)
                // Match upstream: do NOT pause on ducking — let the system handle volume reduction.
                .setWillPauseWhenDucked(false)
                .setOnAudioFocusChangeListener(mAudioFocusListener)
                .build();
    }

    private void requestAudioFocus() {
        int result = mAudioManager.requestAudioFocus(mFocusRequest);
        if (result == AudioManager.AUDIOFOCUS_REQUEST_FAILED) {
            Log.w(TAG, "Audio focus request failed — pausing");
            mGeckoMediaController.pause();
            mPlayDelayed = false;
            mResumeOnFocusGain = false;
        } else if (result == AudioManager.AUDIOFOCUS_REQUEST_DELAYED) {
            mGeckoMediaController.pause();
            mPlayDelayed = true;
            mResumeOnFocusGain = false;
        } else {
            // AUDIOFOCUS_REQUEST_GRANTED
            mPlayDelayed = false;
            mResumeOnFocusGain = false;
        }
    }

    /**
     * Full audio focus state machine matching upstream {@code AudioFocus.onAudioFocusChange}.
     *
     * <ul>
     *   <li>GAIN: if we paused due to a transient loss, resume playback.
     *   <li>LOSS: pause, do not schedule a resume.
     *   <li>LOSS_TRANSIENT: pause, schedule a resume when focus returns.
     * </ul>
     */
    private final AudioManager.OnAudioFocusChangeListener mAudioFocusListener = focusChange -> {
        Log.d(TAG, "onAudioFocusChange: " + focusChange);
        switch (focusChange) {
            case AudioManager.AUDIOFOCUS_GAIN:
                if (mPlayDelayed || mResumeOnFocusGain) {
                    mGeckoMediaController.play();
                    mPlayDelayed       = false;
                    mResumeOnFocusGain = false;
                }
                break;

            case AudioManager.AUDIOFOCUS_LOSS:
                mGeckoMediaController.pause();
                mResumeOnFocusGain = false;
                mPlayDelayed       = false;
                break;

            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT:
                // Only schedule a resume if we were actually playing before the loss.
                mResumeOnFocusGain = (mCurrentPlaybackState == PlaybackStateCompat.STATE_PLAYING);
                mGeckoMediaController.pause();
                mPlayDelayed = false;
                break;

            default:
                Log.d(TAG, "Unhandled focus change: " + focusChange);
                break;
            // Ducking (AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK) is intentionally not handled here.
            // setWillPauseWhenDucked(false) + system automatic ducking handles volume reduction.
        }
    };

    // ── Noisy receiver ────────────────────────────────────────────────────────────────────────────

    private void registerNoisyReceiverIfNeeded() {
        if (mNoisyReceiverRegistered) return;
        IntentFilter filter = new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(mNoisyAudioReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(mNoisyAudioReceiver, filter);
        }
        mNoisyReceiverRegistered = true;
        Log.d(TAG, "BecomingNoisyReceiver registered");
    }

    private void unregisterNoisyReceiverIfNeeded() {
        if (!mNoisyReceiverRegistered) return;
        unregisterReceiver(mNoisyAudioReceiver);
        mNoisyReceiverRegistered = false;
        Log.d(TAG, "BecomingNoisyReceiver unregistered");
    }

    // ── MediaSession setup ────────────────────────────────────────────────────────────────────────

    private void setupMediaSession() {
        mediaSessionCompat = new MediaSessionCompat(this, MEDIA_SESSION_TAG);
        mediaSessionCompat.setCallback(mMediaSessionCallback);
        mediaSessionCompat.setActive(true);
    }

    private final MediaSessionCompat.Callback mMediaSessionCallback = new MediaSessionCompat.Callback() {
        @Override public void onPlay()  { mGeckoMediaController.play(); }
        @Override public void onPause() { mGeckoMediaController.pause(); }
        @Override public void onStop()  { shutdown(); }
    };

    // ── Content intent ────────────────────────────────────────────────────────────────────────────

    private PendingIntent createContentIntent(int sessionId) {
        Intent intent = new Intent(this, BrowserActivity.class);
        intent.setAction(IntentActions.MAIN_MEDIA);
        intent.putExtra(Keys.ITEM_ID, sessionId);
        int flags = BuildUtils.hasAndroidS()
                ? PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
                : PendingIntent.FLAG_UPDATE_CURRENT;
        return PendingIntent.getActivity(this, 0, intent, flags);
    }

    // ── Artwork fetch ─────────────────────────────────────────────────────────────────────────────

    private void fetchBitmap(GeckoMetaData meta) {

        Log.d(TAG, "fetchBitmap: icon=" + meta.getIcon()
                + " iconBitmap=" + (meta.getIconBitmap() != null));

        String iconUrl = meta.getIcon();

        if (TextUtils.isEmpty(iconUrl)) {
            setDefaultThumbnail(meta);
            return;
        }

        // Handle data: URIs — decode the embedded image directly
        if (iconUrl.startsWith("data:image/")) {
            try {
                String base64 = iconUrl.substring(iconUrl.indexOf(",") + 1);
                byte[] bytes = android.util.Base64.decode(base64, android.util.Base64.DEFAULT);
                Bitmap bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
                if (bitmap != null) {
                    meta.setIconBitmap(bitmap);
                    meta.setBitmap(bitmap);
                }
            } catch (Exception e) {
                Log.e(TAG, "Failed to decode data URI bitmap", e);
                meta.setBitmap(DomainThumbnail.generate(this, meta.getUrl(), 0));
            }
            return; // no notifyMetaData() — we're already inside updateNotification
        }

        if (!UrlStringUtils.isHttpOrHttps(iconUrl)) {
            meta.setBitmap(DomainThumbnail.generate(GeckoMediaPlaybackService.this, meta.getUrl(), 0));
            return;
        }

        Request request = new Request.Builder().url(iconUrl).build();
        mOkHttpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                Log.e(TAG, "Bitmap fetch failed", e);
                setDefaultThumbnail(meta);
                notifyMetaData(); // safe — iconBitmap is now set, won't re-fetch
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                try (ResponseBody body = response.body()) {
                    if (response.isSuccessful() && FileUriHelper.isImage(body.contentType())) {
                        Bitmap bitmap = BitmapFactory.decodeStream(body.byteStream());
                        if (bitmap != null) {
                            meta.setIconBitmap(bitmap);
                            meta.setBitmap(bitmap);
                            notifyMetaData();
                            return;
                        }
                    }
                    setDefaultThumbnail(meta);
                    notifyMetaData();
                }
            }
        });
    }

    private void setDefaultThumbnail(GeckoMetaData meta) {
        meta.setBitmap(DomainThumbnail.generate(this, meta.getUrl(), 0));
        meta.setIconBitmap(meta.getBitmap()); // prevent re-fetch
    }

    private void notifyMetaData(){
        Log.d(TAG, "notifyMetaData — sending MEDIA_METADATA to service");
        // Trigger metadata refresh via the service intent mechanism.
        startService(new Intent(getApplicationContext(),
                GeckoMediaPlaybackService.class)
                .setAction(IntentActions.MEDIA_METADATA));
    }

    public boolean ismIsForegroundService() {
        return mIsForegroundService;
    }

    public void setmIsForegroundService(boolean mIsForegroundService) {
        this.mIsForegroundService = mIsForegroundService;
    }
}