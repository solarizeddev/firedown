package com.solarized.firedown.phone.player;

import androidx.annotation.Nullable;
import androidx.media3.exoplayer.ExoPlayer;

import com.solarized.firedown.data.entity.DownloadEntity;

/**
 * Main-thread-only rendezvous between {@code MediaViewerFragment} (which
 * builds and normally owns the ExoPlayer) and {@link PlayerPlaybackService}
 * (which keeps that player audible while the activity is backgrounded).
 *
 * <p>Deliberately a plain static singleton, not a Hilt binding: the player
 * activity/fragment pair is not on the Hilt graph, and everything here is
 * touched only from the main thread (fragment lifecycle callbacks, the
 * service's onStartCommand/onDestroy, MediaSessionCompat callbacks created
 * on the main looper), so no synchronization is needed — mirror of the
 * reasoning that keeps GeckoMediaController's state main-thread confined.
 *
 * <p>Ownership contract: the fragment owns release() for as long as it is
 * alive. When the fragment is destroyed WHILE background playback is
 * running (the system reclaimed the backgrounded activity), it calls
 * {@link #markOwnerDetached()} instead of releasing, and the service
 * releases the player in its own onDestroy. {@link #adopt} re-takes
 * ownership when the activity is relaunched (notification tap) onto the
 * same file, so exactly one party ever holds release duty.
 */
public final class PlaybackHub {

    private static ExoPlayer sPlayer;
    private static DownloadEntity sEntity;

    /**
     * WHICH fragment currently owns release duty — identity, not a flag.
     * Load-bearing for the singleTask relaunch: the fragment replace runs
     * with setReorderingAllowed(true), which brings the NEW fragment up
     * (adopt + attach) BEFORE the old one's onDestroy runs. Without the
     * owner identity, the old fragment's teardown then marked the player
     * owner-detached even though its successor had just adopted it, and
     * the service's shutdown released the player out from under the live
     * UI — every control click after that hit media3's dead internal
     * thread ("sending message to a Handler on a dead thread").
     */
    private static Object sOwner;

    /**
     * True from the moment PlayerActivity decides (in onStop) that playback
     * continues in the background until the activity returns to the
     * foreground (onStart) or the service shuts playback down. While true,
     * MediaViewerFragment.onStop must NOT stop the player.
     */
    private static boolean sBackgroundActive;

    /**
     * True when the owning fragment was destroyed without releasing the
     * player (background playback outlived the activity) — release duty has
     * passed to the service. Reset by {@link #attach}/{@link #adopt}.
     */
    private static boolean sOwnerDetached;

    private PlaybackHub() {
    }

    /**
     * Register the player under the given owner (the fragment) — called
     * both after building a fresh player and after {@link #adopt}, so the
     * owner identity always tracks the LATEST fragment holding the player.
     */
    public static void attach(ExoPlayer player, DownloadEntity entity, Object owner) {
        sPlayer = player;
        sEntity = entity;
        sOwner = owner;
        sOwnerDetached = false;
    }

    /**
     * Whether the given fragment still owns release duty. A fragment whose
     * player was adopted by a successor (the reordered-replace case above)
     * gets false and must neither release nor mark itself detached.
     */
    public static boolean isOwner(Object owner) {
        return sOwner == owner;
    }

    /**
     * A relaunched fragment (notification tap while background playback
     * runs) takes the live player back instead of building a second one —
     * without this the reopened activity would layer a fresh player over
     * the one still playing. Matches on the file path; a different file
     * returns null and the caller builds fresh (the previous fragment's
     * teardown has already handled the old player).
     */
    @Nullable
    public static ExoPlayer adopt(@Nullable DownloadEntity entity) {
        if (sPlayer != null && sEntity != null && entity != null
                && sEntity.getFilePath() != null
                && sEntity.getFilePath().equals(entity.getFilePath())) {
            sOwnerDetached = false;
            return sPlayer;
        }
        return null;
    }

    public static void markOwnerDetached() {
        sOwner = null;
        sOwnerDetached = true;
    }

    public static boolean isOwnerDetached() {
        return sOwnerDetached;
    }

    /**
     * Forget the given player if it is the registered one. Identity-gated so
     * a stale caller can never clear a successor's registration.
     */
    public static void clearIfHolds(@Nullable ExoPlayer player) {
        if (player != null && sPlayer == player) {
            sPlayer = null;
            sEntity = null;
            sOwner = null;
            sOwnerDetached = false;
            sBackgroundActive = false;
        }
    }

    @Nullable
    public static ExoPlayer player() {
        return sPlayer;
    }

    @Nullable
    public static DownloadEntity entity() {
        return sEntity;
    }

    public static void setBackgroundActive(boolean active) {
        sBackgroundActive = active;
    }

    public static boolean isBackgroundActive() {
        return sBackgroundActive;
    }
}
