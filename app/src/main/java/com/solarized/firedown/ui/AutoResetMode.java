package com.solarized.firedown.ui;

import androidx.annotation.IntDef;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Describes how the {@link ZooImageView} will reset to its original size
 * once interaction with it stops. {@link #UNDER} will reset when the image is smaller
 * than or equal to its starting size, {@link #OVER} when it's larger than or equal to its starting size,
 * {@link #ALWAYS} in both situations,
 * and {@link #NEVER} causes no reset. Note that when using {@link #NEVER}, the image will still animate
 * to within the screen bounds in certain situations.
 */
@Retention(RetentionPolicy.SOURCE)
@IntDef({AutoResetMode.NEVER, AutoResetMode.UNDER, AutoResetMode.OVER, AutoResetMode.ALWAYS})
public @interface AutoResetMode {

    int UNDER = 0;
    int OVER = 1;
    int ALWAYS = 2;
    int NEVER = 3;

    class Parser {

        @AutoResetMode
        public static int fromInt(final int value) {
            return switch (value) {
                case OVER -> OVER;
                case ALWAYS -> ALWAYS;
                case NEVER -> NEVER;
                default -> UNDER;
            };
        }
    }

}