package com.solarized.firedown.ffmpegutils;

import android.util.Log;

public final class FFmpegLoader {

    private static final String TAG = FFmpegLoader.class.getSimpleName();

    public enum State { UNKNOWN, SUPPORTED, UNSUPPORTED }

    private static volatile State sState = State.UNKNOWN;

    private FFmpegLoader() {}

    public static synchronized boolean ensureLoaded() {
        if (sState != State.UNKNOWN) {
            return sState == State.SUPPORTED;
        }
        try {
            System.loadLibrary("avutil");
            System.loadLibrary("swscale");
            System.loadLibrary("swresample");
            System.loadLibrary("avcodec");
            System.loadLibrary("avformat");
            System.loadLibrary("avfilter");
            System.loadLibrary("firedown");
            sState = State.SUPPORTED;
            Log.d(TAG, "Native libraries loaded successfully");
        } catch (UnsatisfiedLinkError e) {
            // FIX #12: state is UNSUPPORTED for this app session, but
            // we don't permanently poison the flag from a constructor failure.
            sState = State.UNSUPPORTED;
            Log.e(TAG, "Failed to load native libraries", e);
        }
        return sState == State.SUPPORTED;
    }

    public static boolean isSupported() {
        return sState == State.SUPPORTED;
    }
}