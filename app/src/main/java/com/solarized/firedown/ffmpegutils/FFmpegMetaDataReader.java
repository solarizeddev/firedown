package com.solarized.firedown.ffmpegutils;

import android.text.TextUtils;
import android.util.Log;

import com.solarized.firedown.utils.FileUriHelper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

public class FFmpegMetaDataReader {

    private static final String TAG = FFmpegMetaDataReader.class.getSimpleName();

    private long mNativeMetadataReader;

    /**
     * Multi-stream support: up to MAX_METADATA_STREAMS (3) input channels.
     * Index 0 is always the primary. For single-input calls only index 0 is used.
     */
    private static final int MAX_METADATA_STREAMS = 3;

    private final ReadableByteChannel[] mInputChannels = new ReadableByteChannel[MAX_METADATA_STREAMS];

    private final InputStream[] mInputStreams = new InputStream[MAX_METADATA_STREAMS];

    private final long[] mStreamLengths = new long[MAX_METADATA_STREAMS];

    private final long[] mLastReadEndPositions = new long[MAX_METADATA_STREAMS];

    private FFmpegMetaData mFFmpegMetaData;

    private static final int UNKNOWN_STREAM = -1;

    private static final boolean SUPPORTED = FFmpegLoader.ensureLoaded();

    public FFmpegMetaDataReader() {
        if (!SUPPORTED) {
            Log.w(TAG, "Native libraries not available");
            return;
        }
        try {
            int result = initMetadataReader();
            if (result < 0) {
                throw new RuntimeException("initMetadataReader failed with: " + result);
            }
            mFFmpegMetaData = new FFmpegMetaData();
        } catch (UnsatisfiedLinkError e) {
            Log.e(TAG, "FFmpegMetaDataReader init failed", e);
            throw new RuntimeException("Native init failed", e);
        }
    }

    // ========================================================================
    // Single-input entry points
    // ========================================================================

    public FFmpegMetaData getStreamInfo(InputStream inputStream, String url, long length, boolean bitmap) throws IOException {
        if (!SUPPORTED) {
            throw new IOException("ABI Not supported");
        }

        if (inputStream == null) {
            Log.e(TAG, "Error extracting metaData InputStream null");
            return null;
        }

        clearStreamState();
        mStreamLengths[0] = length;
        mInputStreams[0] = inputStream;
        mInputChannels[0] = Channels.newChannel(inputStream);

        if (extractMetadataInputStream(new InputStream[]{inputStream}, new String[]{url}, bitmap) < 0) {
            Log.e(TAG, "Error extracting metaData InputStream: " + inputStream);
            return null;
        }

        return mFFmpegMetaData;
    }

    public FFmpegMetaData getStreamInfo(String uri, Map<String, String> headers, boolean bitmap) throws IOException {
        if (!SUPPORTED) {
            throw new IOException("ABI Not supported");
        }

        if (TextUtils.isEmpty(uri)) {
            Log.e(TAG, "Error extracting metaData Uri empty: " + uri);
            return null;
        }

        Map<String, String> dict = FFmpegUtils.buildFFmpegOptions(headers);

        @SuppressWarnings("unchecked")
        Map<String, String>[] dicts = new Map[]{dict};

        if (extractMetadata(new String[]{uri}, dicts, bitmap) < 0) {
            Log.e(TAG, "Error extracting metaData Uri: " + uri);
            return null;
        }

        return mFFmpegMetaData;
    }

    // ========================================================================
    // Multi-input entry points
    // ========================================================================

    /**
     * Read metadata from multiple URL inputs and merge their streams.
     * For example: one video-only MP4 URL + one audio-only MP4 URL.
     *
     * @param urls     array of URLs (max 3)
     * @param headers  per-URL headers (nullable entries ok, array length must match urls)
     * @param bitmap   whether to extract a thumbnail
     * @return merged FFmpegMetaData or null on error
     */
    public FFmpegMetaData getStreamInfo(String[] urls, Map<String, String>[] headers, boolean bitmap) throws IOException {
        if (!SUPPORTED) {
            throw new IOException("ABI Not supported");
        }

        if (urls == null || urls.length == 0) {
            Log.e(TAG, "Error extracting metaData: no URLs provided");
            return null;
        }

        if (urls.length > MAX_METADATA_STREAMS) {
            Log.e(TAG, "Error: too many inputs (" + urls.length + "), max " + MAX_METADATA_STREAMS);
            return null;
        }

        @SuppressWarnings("unchecked")
        Map<String, String>[] dicts = new Map[urls.length];

        for (int i = 0; i < urls.length; i++) {
            if (headers != null && i < headers.length && headers[i] != null) {
                dicts[i] = FFmpegUtils.buildFFmpegOptions(headers[i]);
            }
        }

        if (extractMetadata(urls, dicts, bitmap) < 0) {
            Log.e(TAG, "Error extracting metaData multi URLs");
            return null;
        }

        return mFFmpegMetaData;
    }

    /**
     * Add Codecs info.
     */
    private String getRelatedAudioCodec(int bitrate, FFmpegStreamInfo[] fFmpegStreamInfo) {
        for (FFmpegStreamInfo info : fFmpegStreamInfo) {
            if (info == null)
                continue;
            if (info.getMediaType() == FFmpegStreamInfo.CodecType.AUDIO && bitrate == info.getBitRate())
                return info.getCodecName();
        }
        return null;
    }

    /**
     * Convenience overload: multi-URL with shared headers.
     */
    public FFmpegMetaData getStreamInfo(String[] urls, Map<String, String> sharedHeaders, boolean bitmap) throws IOException {
        @SuppressWarnings("unchecked")
        Map<String, String>[] perUrlHeaders = new Map[urls.length];
        for (int i = 0; i < urls.length; i++) {
            perUrlHeaders[i] = sharedHeaders;
        }
        return getStreamInfo(urls, perUrlHeaders, bitmap);
    }

    /**
     * Read metadata from multiple InputStream inputs and merge their streams.
     *
     * @param inputStreams  array of InputStreams (max 3)
     * @param urls         per-stream filenames/URLs for format probing
     * @param lengths      per-stream content lengths (use -1 if unknown)
     * @param bitmap       whether to extract a thumbnail
     * @return merged FFmpegMetaData or null on error
     */
    public FFmpegMetaData getStreamInfo(InputStream[] inputStreams, String[] urls, long[] lengths, boolean bitmap) throws IOException {
        if (!SUPPORTED) {
            throw new IOException("ABI Not supported");
        }

        if (inputStreams == null || inputStreams.length == 0) {
            Log.e(TAG, "Error extracting metaData: no InputStreams provided");
            return null;
        }

        if (inputStreams.length > MAX_METADATA_STREAMS) {
            Log.e(TAG, "Error: too many inputs (" + inputStreams.length + "), max " + MAX_METADATA_STREAMS);
            return null;
        }

        if (urls == null || urls.length != inputStreams.length) {
            Log.e(TAG, "Error: urls array must match inputStreams length");
            return null;
        }

        clearStreamState();

        for (int i = 0; i < inputStreams.length; i++) {
            if (inputStreams[i] == null) {
                Log.e(TAG, "Error: InputStream[" + i + "] is null");
                return null;
            }
            mInputStreams[i] = inputStreams[i];
            mInputChannels[i] = Channels.newChannel(inputStreams[i]);
            mStreamLengths[i] = (lengths != null && i < lengths.length) ? lengths[i] : -1;
            mLastReadEndPositions[i] = 0;
        }

        if (extractMetadataInputStream(inputStreams, urls, bitmap) < 0) {
            Log.e(TAG, "Error extracting metaData multi InputStreams");
            return null;
        }

        return mFFmpegMetaData;
    }


    // ========================================================================
    // Lifecycle
    // ========================================================================

    public void release() {
        if (!SUPPORTED) {
            Log.w(TAG, "dealloc system NOT SUPPORTED");
            return;
        }
        for (int i = 0; i < MAX_METADATA_STREAMS; i++) {
            try {
                if (mInputStreams[i] != null) {
                    mInputStreams[i].close();
                    mInputStreams[i] = null;
                }
            } catch (IOException e) {
                Log.e(TAG, "release stream " + i, e);
            }
            mInputChannels[i] = null;
        }
        mFFmpegMetaData.release();
        mFFmpegMetaData = null;
        deallocMetadataReader();
    }


    public void stop() {
        if (!SUPPORTED) {
            Log.w(TAG, "dealloc system NOT SUPPORTED");
            return;
        }
        stopMetadataReader();
    }

    // ========================================================================
    // Callbacks from native code
    // ========================================================================

    private void setDuration(long duration) {
        mFFmpegMetaData.setDuration(duration);
    }

    private void bitmapRender() {
        mFFmpegMetaData.bitmapRender();
    }

    private void bitmapError() {
        mFFmpegMetaData.bitmapError();
    }

    private ByteBuffer bitmapInit(int size, int width, int height) {
        if (width <= 0 || height <= 0)
            return null;
        return mFFmpegMetaData.bitmapInit(size, width, height);
    }

    private void setStreamsInfo(FFmpegStreamInfo[] streamInfo) {
        mFFmpegMetaData.setStreamsInfo(streamInfo);
    }

    /**
     * Called from native code with the stream index so we route to the correct InputStream.
     * Signature: (IJI)J — seekInputStream(int streamIndex, long seekPos, int whence)
     */
    private long seekInputStream(int streamIndex, long seekPos, int whence) {
        try {
            InputStream is = mInputStreams[streamIndex];

            if (is == null)
                return FFmpegConstants.FFMPEG_AVERROR_EOF;

            // Handle FFmpeg's size request
            if (whence == FFmpegConstants.AVSEEK_SIZE) {
                return mStreamLengths[streamIndex];
            }

            if (whence == FFmpegConstants.SEEK_SET) {
                if (seekPos < mLastReadEndPositions[streamIndex]) {
                    is.reset();
                    mLastReadEndPositions[streamIndex] = 0;
                }
                long toSkip = seekPos - mLastReadEndPositions[streamIndex];
                mLastReadEndPositions[streamIndex] += skipFully(is, toSkip);
            } else if (whence == FFmpegConstants.SEEK_CUR) {
                mLastReadEndPositions[streamIndex] += skipFully(is, seekPos);
            }

            return mLastReadEndPositions[streamIndex];
        } catch (IOException e) {
            Log.e(TAG, "seekInputStream[" + streamIndex + "]", e);
            return FFmpegConstants.FFMPEG_AVERROR_EOF;
        }
    }

    /**
     * InputStream.skip is allowed to skip fewer bytes than requested even when
     * not at EOF. FFmpeg expects the seek target to actually be reached, so we
     * loop until we've skipped the full amount or skip() returns 0 (which
     * effectively means EOF — InputStream.skip's contract). For non-positive
     * input, returns 0 to match skip()'s contract that negative skip is a no-op.
     */
    private static long skipFully(InputStream is, long n) throws IOException {
        if (n <= 0)
            return 0;
        long remaining = n;
        while (remaining > 0) {
            long skipped = is.skip(remaining);
            if (skipped <= 0)
                break;
            remaining -= skipped;
        }
        return n - remaining;
    }

    /**
     * Called from native code with the stream index so we route to the correct InputStream.
     * Signature: (ILjava/nio/ByteBuffer;I)I — readInputStream(int streamIndex, ByteBuffer buffer, int length)
     */
    private int readInputStream(int streamIndex, ByteBuffer buffer, int length) {
        try {
            ReadableByteChannel channel = mInputChannels[streamIndex];

            if (channel != null) {
                buffer.clear();
                buffer.limit(Math.min(length, buffer.capacity()));

                int bytesRead = channel.read(buffer);

                if (bytesRead > 0) {
                    mLastReadEndPositions[streamIndex] += bytesRead;
                    return bytesRead;
                }
                return bytesRead; // -1 for EOF
            }
        } catch (IOException e) {
            Log.e(TAG, "readInputStream[" + streamIndex + "] error", e);
        }
        return FFmpegConstants.FFMPEG_AVERROR_EOF;
    }

    // ========================================================================
    // Format/stream analysis helpers
    // ========================================================================

    public String getMimeType(String contentType) {

        String mFormatName = mFFmpegMetaData.getFormatName();

        boolean hasVideo = hasVideoStream(mFFmpegMetaData.getFFmpegStreamInfo());

        Log.d(TAG, "getMimeType: " + " mFormatName: " + mFormatName);

        if (mFormatName.contains("mp4")) {
            if (!hasVideo)
                return FileUriHelper.MIMETYPE_AUDIO_MP4;
            return FileUriHelper.MIMETYPE_MP4;
        } else if (mFormatName.contains("mp3"))
            return FileUriHelper.AUDIO_MP3;
        else if (mFormatName.contains("webm")) {
            if (!hasVideo)
                return FileUriHelper.MIMETYPE_WEBM_AUDIO;
            return FileUriHelper.MIMETYPE_WEBM;
        } else if (mFormatName.contains("mpegts")) {
            if (!hasVideo)
                return FileUriHelper.MIMETYPE_AUDIO_MP4;
            return FileUriHelper.MIMETYPE_MP4;
        } else if (mFormatName.contains("mpd")) {
            if (!hasVideo)
                return FileUriHelper.MIMETYPE_AUDIO_MP4;
            return FileUriHelper.MIMETYPE_MP4;
        } else if (mFormatName.contains("hls")) {
            if (!hasVideo)
                return FileUriHelper.MIMETYPE_AUDIO_MP4;
            return FileUriHelper.MIMETYPE_MP4;
        } else if (mFormatName.contains("gif"))
            return FileUriHelper.MIMETYPE_GIF;
        else if (mFormatName.contains("png"))
            return FileUriHelper.MIMETYPE_PNG;
        else if (mFormatName.contains("webp"))
            return FileUriHelper.MIMETYPE_WEBP;
        else if (mFormatName.contains("jpeg"))
            return FileUriHelper.MIMETYPE_JPEG;
        else if (mFormatName.contains("lrc"))
            return FileUriHelper.MIMETYPE_TXT;
        else if (mFormatName.contains("image")) {
            if (FileUriHelper.isSVG(contentType))
                return FileUriHelper.MIMETYPE_SVG;
            return FileUriHelper.MIMETYPE_JPEG;
        } else if (mFormatName.contains("ico"))
            return FileUriHelper.MIMETYPE_ICO;
        else if (mFormatName.contains("flv"))
            return FileUriHelper.MIMETYPE_X_FLV;
        else if (mFormatName.contains("srt"))
            return FileUriHelper.MIMETYPE_SRT;
        else if (mFormatName.contains("webvtt"))
            return FileUriHelper.MIMETYPE_VTT;
        else if (mFormatName.contains("svg_pipe"))
            return FileUriHelper.MIMETYPE_SVG;
        else if (mFormatName.contains("ogg")) {
            if (!hasVideo)
                return FileUriHelper.MIMETYPE_AUDIO_OGG;
            return FileUriHelper.MIMETYPE_VIDEO_OGG;
        } else {
            return FileUriHelper.MIMETYPE_MP4;
        }
    }

    public ArrayList<FFmpegEntity> getStreams() {
        if (mFFmpegMetaData == null) {
            return null;
        }

        ArrayList<FFmpegEntity> arrayList = new ArrayList<>();

        FFmpegStreamInfo[] fFmpegStreamInfos = mFFmpegMetaData.getFFmpegStreamInfo();

        if (fFmpegStreamInfos != null) {
            for (FFmpegStreamInfo info : fFmpegStreamInfos) {

                if (info == null)
                    continue;

                FFmpegStreamInfo.CodecType codecType = info.getMediaType();

                if (codecType == FFmpegStreamInfo.CodecType.VIDEO) {
                    FFmpegEntity FFmpegEntity = new FFmpegEntity();
                    int bitrate = info.getBitRate();
                    FFmpegEntity.setCodecType(FFmpegStreamInfo.CodecType.VIDEO.getValue());
                    if (info.getHeight() != 0 && info.getWidth() != 0) {
                        if (mFFmpegMetaData.isImage()) {
                            if (info.getCodecName().contains("svg")) {
                                FFmpegEntity.setInfo(String.format(Locale.getDefault(), "%dx%d", info.getEncodedWidth(), info.getEncodedHeight()));
                            } else {
                                FFmpegEntity.setInfo(String.format(Locale.getDefault(), "%dx%d", info.getWidth(), info.getHeight()));
                            }
                        } else {
                            FFmpegEntity.setInfo(String.format(Locale.getDefault(), "%dp", info.getHeight()));
                        }
                    }
                    FFmpegEntity.setVideoStreamNumber(info.getStreamNumber());
                    FFmpegEntity.setAudioStreamNumber(getRelatedAudioNumber(bitrate, fFmpegStreamInfos));
                    FFmpegEntity.setStreamDescription(info.getDisplayDescription());
                    FFmpegEntity.setVideoCodec(info.getCodecName());
                    FFmpegEntity.setBitrate(bitrate);
                    FFmpegEntity.setAudioCodec(getRelatedAudioCodec(bitrate, fFmpegStreamInfos));
                    arrayList.add(FFmpegEntity);
                }
            }

            if (arrayList.isEmpty()) {

                for (FFmpegStreamInfo info : fFmpegStreamInfos) {

                    if (info == null)
                        continue;

                    FFmpegStreamInfo.CodecType codecType = info.getMediaType();

                    if (codecType == FFmpegStreamInfo.CodecType.AUDIO) {
                        FFmpegEntity FFmpegEntity = new FFmpegEntity();
                        FFmpegEntity.setInfo(String.format(Locale.getDefault(), "%d Khz", info.getSamplingRate()));
                        FFmpegEntity.setCodecType(FFmpegStreamInfo.CodecType.AUDIO.getValue());
                        FFmpegEntity.setAudioStreamNumber(info.getStreamNumber());
                        FFmpegEntity.setStreamDescription(info.getDisplayDescription());
                        FFmpegEntity.setAudioCodec(info.getCodecName());
                        FFmpegEntity.setBitrate(info.getBitRate());
                        arrayList.add(FFmpegEntity);
                    }
                }
            }
        }

        return arrayList;

    }


    private boolean hasVideoStream(FFmpegStreamInfo[] fFmpegStreamInfo) {
        for (FFmpegStreamInfo info : fFmpegStreamInfo) {

            if (info == null)
                continue;

            FFmpegStreamInfo.CodecType codecType = info.getMediaType();

            if (codecType == FFmpegStreamInfo.CodecType.VIDEO)
                return true;

        }
        return false;
    }

    private boolean hasAudioStream(FFmpegStreamInfo[] fFmpegStreamInfo) {
        for (FFmpegStreamInfo info : fFmpegStreamInfo) {

            if (info == null)
                continue;

            FFmpegStreamInfo.CodecType codecType = info.getMediaType();

            if (codecType == FFmpegStreamInfo.CodecType.AUDIO)
                return true;

        }
        return false;
    }

    private int getRelatedAudioNumber(int bitrate, FFmpegStreamInfo[] fFmpegStreamInfo) {
        for (FFmpegStreamInfo info : fFmpegStreamInfo) {

            if (info == null)
                continue;

            FFmpegStreamInfo.CodecType codecType = info.getMediaType();

            if (codecType == FFmpegStreamInfo.CodecType.AUDIO && bitrate == info.getBitRate())
                return info.getStreamNumber();

        }
        return UNKNOWN_STREAM;
    }

    private void setInputFormatName(String formatName) {
        Log.d(TAG, "setInputFormatName: " + formatName);
        mFFmpegMetaData.setInputFormatName(formatName);
    }

    private void setMetaData(Map<String, String> data) {
        mFFmpegMetaData.setMetaData(data);
    }

    // ========================================================================
    // Internal helpers
    // ========================================================================

    private void clearStreamState() {
        for (int i = 0; i < MAX_METADATA_STREAMS; i++) {
            mInputChannels[i] = null;
            mInputStreams[i] = null;
            mStreamLengths[i] = 0;
            mLastReadEndPositions[i] = 0;
        }
    }

    // ========================================================================
    // Native methods
    // ========================================================================

    /**
     * Extract metadata from one or more URLs.
     * For single-input, pass a length-1 array.
     */
    private native int extractMetadata(String[] urls, Map<String, String>[] dictionaries, boolean bitmap);

    /**
     * Extract metadata from one or more InputStreams.
     * For single-input, pass a length-1 array.
     */
    private native int extractMetadataInputStream(InputStream[] streams, String[] filenames, boolean bitmap);

    private native int initMetadataReader();

    private native void deallocMetadataReader();

    private native void stopMetadataReader();

}