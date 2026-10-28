package com.solarized.firedown.ffmpegutils;

import android.util.Log;

public class FFmpegGifMaker {

    private long mNativeGifMaker;

    private static final String TAG = FFmpegGifMaker.class.getSimpleName();

    public static final int DEFAULT_FPS = 12;
    public static final int DEFAULT_WIDTH = 320;

    private static final boolean SUPPORTED = FFmpegLoader.ensureLoaded();

    private static final int UNSUPPORTED_ERROR = -1;
    private static final int INIT_FAILED_ERROR = -2;

    /* Same volatile pattern as FFmpegEncoder: native callbacks fire on the
     * worker thread, addListener is called from whatever thread set the
     * task up — without volatile the worker is not guaranteed to see the
     * listener assignment. */
    private volatile FFmpegListener mListener;

    private final boolean mInitOk;

    private final Object mNativeLock = new Object();

    /* Guarded by mNativeLock. */
    private boolean mFreed;


    public FFmpegGifMaker() {
        boolean ok = false;
        if (!SUPPORTED) {
            Log.w(TAG, "init system NOT SUPPORTED");
        } else if (initGifMaker() == 0) {
            ok = true;
        } else {
            Log.e(TAG, "initGifMaker failed");
        }
        mInitOk = ok;
    }

    public void addListener(FFmpegListener listener) {
        mListener = listener;
    }


    private void gifMakerProgress(long currentLength, long totalLength) {
        FFmpegListener l = mListener;
        if (l != null) {
            l.onProgress(currentLength, totalLength);
        }
    }

    private void gifMakerStarted() {
        FFmpegListener l = mListener;
        if (l != null) {
            l.onStarted();
        }
    }

    private void gifMakerFinished() {
        FFmpegListener l = mListener;
        if (l != null) {
            l.onFinished();
        }
    }

    public int start(String inputPath, String outputPath,
                     long startMs, long endMs, int fps, int width) {

        if (!SUPPORTED) {
            Log.w(TAG, "start system NOT SUPPORTED");
            return UNSUPPORTED_ERROR;
        }

        if (!mInitOk) {
            Log.w(TAG, "start native gif maker not initialised");
            return INIT_FAILED_ERROR;
        }

        return startGifMaker(inputPath, outputPath, startMs, endMs, fps, width);
    }

    public void stop() {
        if (!SUPPORTED || !mInitOk) {
            Log.w(TAG, "stop system NOT SUPPORTED or not initialised");
            return;
        }
        stopGifMaker();
    }

    /* interrupt() comes from the main thread (Cancel), free() from the
     * worker once start() returned. Native interrupt reads the struct
     * pointer and then writes through it, so the two must not overlap: a
     * free() between those steps made the write a use-after-free. After
     * free() an interrupt has nothing left to stop. */
    public void interrupt() {
        if (!SUPPORTED || !mInitOk) {
            Log.w(TAG, "interrupt system NOT SUPPORTED or not initialised");
            return;
        }
        synchronized (mNativeLock) {
            if (!mFreed) {
                interruptGifMaker();
            }
        }
    }

    public void free() {
        if (!SUPPORTED || !mInitOk) {
            Log.w(TAG, "free system NOT SUPPORTED or not initialised");
            return;
        }
        synchronized (mNativeLock) {
            if (mFreed) {
                return;
            }
            mFreed = true;
            deallocGifMaker();
        }
    }

    private native int initGifMaker();

    private native int startGifMaker(String inputPath, String outputPath,
                                     long startMs, long endMs, int fps, int width);

    private native void stopGifMaker();

    private native void interruptGifMaker();

    private native void deallocGifMaker();
}
