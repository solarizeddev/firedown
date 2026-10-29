package com.solarized.firedown.phone.player;

import java.text.NumberFormat;
import java.util.Locale;

/**
 * The player's playback speeds and how they are written. Pure Java, so the
 * player-subtitles harness runs it on a JDK.
 */
public final class PlaybackSpeeds {

    /** The Playback speed sheet's choices, slowest first. */
    public static final float[] SPEEDS = {0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f};

    public static final float NORMAL = 1f;

    private PlaybackSpeeds() {
    }

    /** The choice nearest {@code speed}, ties to the slower one. */
    public static int nearestIndex(float speed) {
        int best = 0;
        for (int i = 1; i < SPEEDS.length; i++) {
            if (Math.abs(SPEEDS[i] - speed) < Math.abs(SPEEDS[best] - speed)) {
                best = i;
            }
        }
        return best;
    }

    /** "0.75×" with the locale's decimal separator; a whole number has none ("2×"). */
    public static String label(float speed, Locale locale) {
        NumberFormat format = NumberFormat.getNumberInstance(locale);
        format.setMinimumFractionDigits(0);
        format.setMaximumFractionDigits(2);
        return format.format(speed) + "×";
    }
}
