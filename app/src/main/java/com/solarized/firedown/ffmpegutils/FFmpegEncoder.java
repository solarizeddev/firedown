package com.solarized.firedown.ffmpegutils;

import android.util.Log;

import java.util.Map;

public class FFmpegEncoder {

    private long mNativeEncoder;

    private static final String TAG = FFmpegEncoder.class.getSimpleName();

    public static final int UNKNOWN_STREAM = -1;

    private static final boolean SUPPORTED = FFmpegLoader.ensureLoaded();

    private static final int UNSUPPORTED_ERROR = -1;
    private static final int INIT_FAILED_ERROR = -2;

    /* [BUG FIX] mListener was a plain field. The native callbacks
     * (encoderProgress / encoderStarted / encoderFinished) are invoked
     * from the transcode worker thread, while addListener is called from
     * whatever thread constructed the encoder (typically the UI thread).
     * Without volatile, the worker thread is not guaranteed to ever see
     * the assignment performed by addListener. */
    private volatile FFmpegListener mListener;

    /* [BUG FIX] Track whether native init actually succeeded so the
     * other entry points can decline to call into a half-initialised
     * encoder. The native side previously published a partly-built
     * encoder pointer to mNativeEncoder before all fallible setup
     * completed; even with that fixed, a return code of 0 from
     * initEncoder is the only signal the Java side has that there is
     * a real native encoder to talk to. */
    private final boolean mInitOk;


    public FFmpegEncoder() {
        boolean ok = false;
        if (!SUPPORTED) {
            Log.w(TAG, "init system NOT SUPPORTED");
        } else if (initEncoder() == 0) {
            ok = true;
        } else {
            Log.e(TAG, "initEncoder failed");
        }
        mInitOk = ok;
    }

    public void addListener(FFmpegListener listener) {
        mListener = listener;
    }


    private void encoderProgress(long downloadedLength, long totalLength) {
        FFmpegListener l = mListener;
        if (l != null) {
            l.onProgress(downloadedLength, totalLength);
        }
    }

    private void encoderStarted() {
        FFmpegListener l = mListener;
        if (l != null) {
            l.onStarted();
        }
    }

    private void encoderFinished() {
        FFmpegListener l = mListener;
        if (l != null) {
            l.onFinished();
        }
    }

    public int start(Map<String, String> dictionary, Map<String, String> metadata, String fileName,
                     String downloadUrl, int video, int audio) {

        if (!SUPPORTED) {
            Log.w(TAG, "start system NOT SUPPORTED");
            return UNSUPPORTED_ERROR;
        }

        if (!mInitOk) {
            Log.w(TAG, "start native encoder not initialised");
            return INIT_FAILED_ERROR;
        }

        return startEncoder(dictionary, metadata, fileName, downloadUrl, video, audio);
    }

    public void stop() {

        if (!SUPPORTED || !mInitOk) {
            Log.w(TAG, "stop system NOT SUPPORTED or not initialised");
            return;
        }

        stopEncoder();
    }

    public void interrupt() {
        if (!SUPPORTED || !mInitOk) {
            Log.w(TAG, "interrupt system NOT SUPPORTED or not initialised");
            return;
        }

        interruptEncoder();
    }

    public void free() {

        if (!SUPPORTED || !mInitOk) {
            /* [BUG FIX] Log message originally said "stop system NOT SUPPORTED"
             * here — copy-paste from the stop() method. */
            Log.w(TAG, "free system NOT SUPPORTED or not initialised");
            return;
        }

        deallocEncoder();
    }

    private native void deallocEncoder();

    private native void stopEncoder();

    private native int initEncoder();

    private native void interruptEncoder();

    private native int startEncoder(Map<String, String> dictionary, Map<String, String> metadata, String fileName,
                                    String downloadUrl, int video, int audio);


}