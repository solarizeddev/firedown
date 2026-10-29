package com.solarized.firedown.phone.player;

import android.view.HapticFeedbackConstants;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.media3.common.Player;

import java.util.Locale;

/**
 * Press and hold the video to play at 2× until the finger lifts, YouTube's
 * gesture. Shared by both players: the caller starts it from its gesture
 * detector's onLongPress and ends it on ACTION_UP / ACTION_CANCEL, and also
 * when the screen goes away mid-hold (onStop, PiP, teardown), since a hold
 * interrupted that way never gets its lift.
 *
 * <p>The speed before the hold comes back on release, so a video at 1.25×
 * returns to 1.25×. Only a playing player at under 2× takes a hold: a paused
 * one has nothing to speed up, and one already at 2× would change nothing.</p>
 */
public final class HoldToSpeedUp {

    public static final float HOLD_SPEED = 2f;

    private final TextView mBadge;

    @Nullable
    private Player mPlayer;

    private float mSpeedBefore = PlaybackSpeeds.NORMAL;

    public HoldToSpeedUp(@NonNull TextView badge) {
        mBadge = badge;
        mBadge.setText(PlaybackSpeeds.label(HOLD_SPEED, Locale.getDefault()));
    }

    /** Starts a hold; true when it took (the caller then hides its controller). */
    public boolean start(@Nullable Player player, @NonNull View feedbackView) {
        if (mPlayer != null || player == null || !player.isPlaying()) {
            return false;
        }
        float speed = player.getPlaybackParameters().speed;
        if (speed >= HOLD_SPEED) {
            return false;
        }
        mPlayer = player;
        mSpeedBefore = speed;
        player.setPlaybackSpeed(HOLD_SPEED);
        feedbackView.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        mBadge.setVisibility(View.VISIBLE);
        return true;
    }

    /** Ends a hold, putting the speed back. Safe to call when none is running. */
    public void end() {
        Player player = mPlayer;
        if (player == null) {
            return;
        }
        mPlayer = null;
        player.setPlaybackSpeed(mSpeedBefore);
        mBadge.setVisibility(View.GONE);
    }

    public boolean isHolding() {
        return mPlayer != null;
    }
}
