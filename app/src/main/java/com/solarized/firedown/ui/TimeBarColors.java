package com.solarized.firedown.ui;

import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.OptIn;
import androidx.core.graphics.ColorUtils;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.ui.DefaultTimeBar;

import com.solarized.firedown.R;
import com.solarized.firedown.utils.Utils;

/**
 * Paints the media players' time bar from the theme's primary, so it follows
 * the colour theme (accent).
 *
 * <p>Done in code, not layout XML, because two of the four tones are the
 * primary at an ALPHA (buffered 0x80, unplayed 0x26 — the values and their
 * contrast rationale are on {@code @color/player_scrubber_*}), and a style
 * item can't alpha-modulate the Material You overlay's
 * {@code @android:color/system_accent1_*} reference. DefaultTimeBar also reads
 * its colours with {@code TypedArray.getInt}, so a colour state list with
 * {@code android:alpha} is not an option either. The layouts keep the coral
 * values as the pre-bind defaults.
 */
public final class TimeBarColors {

    private static final int BUFFERED_ALPHA = 0x80;
    private static final int UNPLAYED_ALPHA = 0x26;

    private TimeBarColors() {}

    /** Tints the {@code exo_progress} bar under {@code root}, if there is one. */
    @OptIn(markerClass = UnstableApi.class)
    public static void apply(@NonNull View root) {
        View view = root.findViewById(R.id.exo_progress);
        if (!(view instanceof DefaultTimeBar timeBar)) {
            return;
        }
        int primary = Utils.themeColor(root.getContext(),
                com.google.android.material.R.attr.colorPrimary);
        if (primary == 0) {
            return;
        }
        timeBar.setPlayedColor(primary);
        timeBar.setScrubberColor(primary);
        timeBar.setBufferedColor(ColorUtils.setAlphaComponent(primary, BUFFERED_ALPHA));
        timeBar.setUnplayedColor(ColorUtils.setAlphaComponent(primary, UNPLAYED_ALPHA));
    }
}
