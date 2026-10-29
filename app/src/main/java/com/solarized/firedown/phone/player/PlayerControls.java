package com.solarized.firedown.phone.player;

import android.graphics.Color;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.core.view.ViewCompat;

import com.solarized.firedown.R;

import java.util.Locale;

/**
 * The bottom row's Playback speed button (exo_media_viewer_controller), bound
 * the same way in both players: the value as its text, coral while it is not
 * 1×, and the value as its spoken state after "Playback speed".
 */
public final class PlayerControls {

    private PlayerControls() {
    }

    public static void bindSpeed(@NonNull TextView button, float speed) {
        String label = PlaybackSpeeds.label(speed, Locale.getDefault());
        button.setText(label);
        boolean normal = Math.abs(speed - PlaybackSpeeds.NORMAL) < 0.001f;
        button.setTextColor(normal ? Color.WHITE
                : ContextCompat.getColor(button.getContext(), R.color.brand_orange));
        ViewCompat.setStateDescription(button, label);
    }
}
