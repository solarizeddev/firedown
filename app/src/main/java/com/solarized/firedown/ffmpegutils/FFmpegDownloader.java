package com.solarized.firedown.ffmpegutils;

import android.util.Log;

import java.util.Map;

public class FFmpegDownloader {

    private long mNativeDownloader;

    private static final String TAG = FFmpegDownloader.class.getSimpleName();

    private static final boolean SUPPORTED = FFmpegLoader.ensureLoaded();

    private static final int UNSUPPORTED_ERROR = -1;

    public static final int UNKNOWN_STREAM = -1;

    /**
     * Maximum number of input URLs that can be merged (must match MAX_DOWNLOAD_INPUTS in C).
     */
    private static final int MAX_DOWNLOAD_INPUTS = 3;

    private FFmpegListener mListener;


    public FFmpegDownloader(){
        if(!SUPPORTED){
            Log.w(TAG, "init system NOT SUPPORTED");
            return;
        }
        int result = initDownloader();
        if(result < 0)
            throw new RuntimeException("Error in initDownloader result: " + result);
    }

    public void addListener(FFmpegListener listener){
        mListener = listener;
    }


    private void downloadProgress(long downloadedLength, long totalLength) {
        if(mListener != null){
            mListener.onProgress(downloadedLength, totalLength);
        }
    }

    private void downloadStarted() {
        if(mListener != null){
            mListener.onStarted();
        }
    }

    private void downloadFinished() {
        if(mListener != null){
            mListener.onFinished();
        }
    }

    // ========================================================================
    // Single-input download
    // ========================================================================

    /**
     * Start a download from a single URL.
     * Wraps the input into a length-1 array and delegates to the unified native method.
     *
     * @param dictionary  HTTP headers/options for the input URL
     * @param metadata    output file metadata (nullable)
     * @param fileName    the input URL to download from
     * @param downloadUrl the output file path to write to
     * @param video       preferred video stream number (-1 for auto)
     * @param audio       preferred audio stream number (-1 for auto)
     * @param totalLength total expected size for progress (-1 if unknown)
     */
    public int start(Map<String, String> dictionary, Map<String, String> metadata, String fileName,
                     String downloadUrl, int video, int audio, long totalLength){

        if(!SUPPORTED){
            Log.w(TAG, "start system NOT SUPPORTED");
            return UNSUPPORTED_ERROR;
        }

        Map<String, String> dict = FFmpegUtils.buildFFmpegOptions(dictionary);

        @SuppressWarnings("unchecked")
        Map<String, String>[] dicts = new Map[]{dict};

        // Per-input stream selection: index 0 = the single input
        // fileName = input URL, downloadUrl = output file path
        return startDownloader(new String[]{fileName}, dicts, metadata, downloadUrl,
                new int[]{video}, new int[]{audio}, totalLength);
    }

    // ========================================================================
    // Multi-input download
    // ========================================================================

    /**
     * Start a download merging multiple input URLs into a single output file.
     * For example: one video-only MP4 URL + one audio-only MP4 URL.
     * Stream selection is auto (-1) for all inputs.
     *
     * @param urls         array of input URLs (max 3)
     * @param headers      per-URL headers (nullable entries ok, length must match urls)
     * @param metadata     output file metadata (nullable)
     * @param outputPath   output file path
     * @param totalLength  total expected size for progress (-1 if unknown)
     * @return 0 on success, negative error code on failure
     */
    public int start(String[] urls, Map<String, String>[] headers, Map<String, String> metadata,
                     String outputPath, long totalLength) {

        if (!SUPPORTED) {
            Log.w(TAG, "start system NOT SUPPORTED");
            return UNSUPPORTED_ERROR;
        }

        if (urls == null || urls.length == 0) {
            Log.e(TAG, "start: no URLs provided");
            return UNSUPPORTED_ERROR;
        }

        if (urls.length > MAX_DOWNLOAD_INPUTS) {
            Log.e(TAG, "start: too many inputs (" + urls.length + "), max " + MAX_DOWNLOAD_INPUTS);
            return UNSUPPORTED_ERROR;
        }

        @SuppressWarnings("unchecked")
        Map<String, String>[] dicts = new Map[urls.length];
        for (int i = 0; i < urls.length; i++) {
            if (headers != null && i < headers.length && headers[i] != null) {
                dicts[i] = FFmpegUtils.buildFFmpegOptions(headers[i]);
            }
        }

        // Auto-select streams for all inputs
        return startDownloader(urls, dicts, metadata, outputPath, null, null, totalLength);
    }

    /**
     * Start a download merging multiple input URLs with per-input stream selection.
     *
     * @param urls           array of input URLs (max 3)
     * @param headers        per-URL headers (nullable entries ok)
     * @param metadata       output file metadata (nullable)
     * @param outputPath     output file path
     * @param videoStreams    per-input preferred video stream index (-1 for auto), nullable for all-auto
     * @param audioStreams    per-input preferred audio stream index (-1 for auto), nullable for all-auto
     * @param totalLength    total expected size for progress (-1 if unknown)
     * @return 0 on success, negative error code on failure
     */
    public int start(String[] urls, Map<String, String>[] headers, Map<String, String> metadata,
                     String outputPath, int[] videoStreams, int[] audioStreams, long totalLength) {

        if (!SUPPORTED) {
            Log.w(TAG, "start system NOT SUPPORTED");
            return UNSUPPORTED_ERROR;
        }

        if (urls == null || urls.length == 0) {
            Log.e(TAG, "start: no URLs provided");
            return UNSUPPORTED_ERROR;
        }

        if (urls.length > MAX_DOWNLOAD_INPUTS) {
            Log.e(TAG, "start: too many inputs (" + urls.length + "), max " + MAX_DOWNLOAD_INPUTS);
            return UNSUPPORTED_ERROR;
        }

        @SuppressWarnings("unchecked")
        Map<String, String>[] dicts = new Map[urls.length];
        for (int i = 0; i < urls.length; i++) {
            if (headers != null && i < headers.length && headers[i] != null) {
                dicts[i] = FFmpegUtils.buildFFmpegOptions(headers[i]);
            }
        }

        return startDownloader(urls, dicts, metadata, outputPath, videoStreams, audioStreams, totalLength);
    }

    /**
     * Convenience overload: multi-URL download with shared headers for all inputs.
     */
    public int start(String[] urls, Map<String, String> sharedHeaders, Map<String, String> metadata,
                     String outputPath, long totalLength) {
        @SuppressWarnings("unchecked")
        Map<String, String>[] perUrlHeaders = new Map[urls.length];
        for (int i = 0; i < urls.length; i++) {
            perUrlHeaders[i] = sharedHeaders;
        }
        return start(urls, perUrlHeaders, metadata, outputPath, totalLength);
    }

    // ========================================================================
    // Lifecycle
    // ========================================================================

    public void stop(){

        if(!SUPPORTED){
            Log.w(TAG, "stop system NOT SUPPORTED");
            return;
        }

        stopDownloader();

    }

    public void free(){

        if(!SUPPORTED){
            Log.w(TAG, "free system NOT SUPPORTED");
            return;
        }

        mListener = null;

        deallocDownloader();

    }

    // ========================================================================
    // Native methods
    // ========================================================================

    private native void deallocDownloader();

    private native void stopDownloader();

    private native int initDownloader();

    /**
     * Start downloading from one or more URLs.
     * For single-input, pass length-1 arrays.
     *
     * @param videoStreams per-input preferred video stream index (nullable for all-auto)
     * @param audioStreams per-input preferred audio stream index (nullable for all-auto)
     */
    private native int startDownloader(String[] urls, Map<String, String>[] dictionaries,
                                       Map<String, String> metadata, String outputPath,
                                       int[] videoStreams, int[] audioStreams, long totalLength);

}