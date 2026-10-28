package com.solarized.firedown.ffmpegutils;
/** The libraries "load"; FFmpegGifMaker/FFmpegEncoder read this once, in
 *  their static initialiser, exactly like the app. */
public final class FFmpegLoader {
    public static volatile boolean supported = true;
    private FFmpegLoader() {}
    public static synchronized boolean ensureLoaded() { return supported; }
    public static boolean isSupported() { return supported; }
}
