package com.solarized.firedown.utils;

import android.util.Log;

import com.solarized.firedown.BuildConfig;

/**
 * Debug-only logging helpers.
 *
 * <p>Two jobs, both required by the project's logging discipline:</p>
 *
 * <ul>
 *   <li>{@link #d(String, String)} — gates on {@link BuildConfig#DEBUG}, which
 *       is a compile-time constant, so R8 folds the branch away and the call
 *       site (including the string concatenation that built the message)
 *       is dead-code-eliminated in release. A release build is silent.</li>
 *   <li>{@link #preview(CharSequence)} — truncates user-controlled text.
 *       Page titles, filenames, clipboard text and URL-bar content are all
 *       unbounded; logging one whole (a multi-hundred-KB paste has happened)
 *       multiplies the blob through logcat and churns the calling thread.</li>
 * </ul>
 *
 * <p>The 128-char cap and the {@code …(N)} length suffix are the convention
 * this class was extracted to keep in one place — private copies had already
 * appeared in {@code AutoCompleteEditText} and {@code GeckoComponents}, and a
 * third was about to.</p>
 */
public final class DebugLog {

    /** Enough to identify any URL, title or filename in a log line. */
    private static final int PREVIEW_MAX = 128;

    private DebugLog() {}

    /** Debug-only {@link Log#d}. Compiles away entirely in release. */
    public static void d(String tag, String message) {
        if (BuildConfig.DEBUG) {
            Log.d(tag, message);
        }
    }

    /** Truncate user-controlled text for logging. Never returns null. */
    public static String preview(CharSequence text) {
        if (text == null) {
            return "null";
        }
        if (text.length() <= PREVIEW_MAX) {
            return text.toString();
        }
        return text.subSequence(0, PREVIEW_MAX) + "…(" + text.length() + ")";
    }
}
