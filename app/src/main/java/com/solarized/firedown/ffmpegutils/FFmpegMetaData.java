package com.solarized.firedown.ffmpegutils;

import android.graphics.Bitmap;
import android.text.TextUtils;
import android.util.Log;

import com.solarized.firedown.manager.UrlType;

import java.nio.ByteBuffer;
import java.util.Map;

public class FFmpegMetaData {

    private static final String TAG = FFmpegMetaData.class.getName();

    private String mFormatName;

    private long mDuration;

    private Bitmap mBitmap;

    private ByteBuffer mByteBuffer;

    private FFmpegStreamInfo[] mFFmpegStreamInfo;

    private Map<String, String> mMetadata;

    public void setStreamsInfo(FFmpegStreamInfo[] streamsInfo){
        mFFmpegStreamInfo = streamsInfo;
    }

    public void setMetaData(Map<String, String> data){
        mMetadata = data;
    }

    public Map<String, String> getMetadata(){
        return mMetadata;
    }

    public Bitmap getBitmap() {
        return mBitmap;
    }

    public void bitmapRender() {
        mBitmap.copyPixelsFromBuffer(mByteBuffer);
    }

    public void bitmapError() {
        mBitmap = null;
    }

    public void setDuration(long duration) {
        Log.d(TAG, "setDuration: " + duration);
        mDuration = duration;
    }

    public void setInputFormatName(String formatName) {
        Log.d(TAG, "setInputFormatName: " + formatName);
        mFormatName = formatName;
    }

    public long getDuration() {
        return mDuration;
    }

    public int getWidth() {

        FFmpegStreamInfo[] fFmpegStreamInfos = getFFmpegStreamInfo();

        if (fFmpegStreamInfos != null) {
            for (FFmpegStreamInfo info : fFmpegStreamInfos) {

                if (info == null)
                    continue;

                FFmpegStreamInfo.CodecType codecType = info.getMediaType();

                if (codecType == FFmpegStreamInfo.CodecType.VIDEO) {

                    return info.getWidth();
                }
            }
        }

        return 0;
    }


    public int getHeight() {

        FFmpegStreamInfo[] FFmpegStreamInfos = getFFmpegStreamInfo();

        if (FFmpegStreamInfos != null) {
            for (FFmpegStreamInfo info : FFmpegStreamInfos) {

                if (info == null)
                    continue;

                FFmpegStreamInfo.CodecType codecType = info.getMediaType();

                if (codecType == FFmpegStreamInfo.CodecType.VIDEO) {

                    return info.getHeight();
                }
            }
        }

        return 0;
    }

    public ByteBuffer bitmapInit(int size, int width, int height) {
        if (width <= 0 || height <= 0)
            return null;
        mBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        mByteBuffer = ByteBuffer.allocateDirect(size);
        return mByteBuffer;
    }

    public String getFormatName() {
        return mFormatName;
    }

    public FFmpegStreamInfo[] getFFmpegStreamInfo() {
        return mFFmpegStreamInfo;
    }

    public boolean isValidMedia() {
        if (mFFmpegStreamInfo == null) {
            return false;
        }

        Log.d(TAG, "isValidMedia: " + mFormatName);

        if (!TextUtils.isEmpty(mFormatName) &&
                (mFormatName.equals("mpegts")
                        || mFormatName.equals("iso5")
                        || mFormatName.equals("h263")
                        || mFormatName.equals("lrc")
                        || mFormatName.equals("timed_id3")))
            return false;

        if(mFormatName.equals("svg_pipe"))
            return true;

        if (mFormatName.equals("gif") || mFormatName.equals("png_pipe") || mFormatName.equals("webp_pipe") || mFormatName.equals("jpeg_pipe")) {
            // This is a tracking gif
            if (getHeight() == 1 && getWidth() == 1) {
                return false;
            }
        }

        for (FFmpegStreamInfo info : mFFmpegStreamInfo) {

            if (info == null)
                continue;

            FFmpegStreamInfo.CodecType codecType = info.getMediaType();




            if (codecType == FFmpegStreamInfo.CodecType.AUDIO) {
//                Log.d(TAG, "Format Valid Audio Name: " + mFormatName + " codecType: " + codecType.name() + " pixelFormat:" + info);
                return true;
            }
            else if (codecType == FFmpegStreamInfo.CodecType.VIDEO) {
                Log.d(TAG, "Format Valid Video Name: " + mFormatName + " codecType: " + codecType.name() + " pixelFormat:" + info.getPixelFormat() + " codecName: " + info.getCodecName());
                //Do not add videos whose pixel format is not recognized
                //return info.getPixelFormat() >= 0;
                return true;
            }
            else if (codecType == FFmpegStreamInfo.CodecType.SUBTITLE) {
                return true;
            }
        }

        return false;
    }

    public boolean isProgressive(){
        if (mFFmpegStreamInfo == null || TextUtils.isEmpty(mFormatName)) {
            return false;
        }
        return mFormatName.equals("hls")
                || mFormatName.equals("dash");
    }

    public boolean isAudio() {
        if (mFFmpegStreamInfo == null || TextUtils.isEmpty(mFormatName)) {
            return false;
        }

        //If adasptative only audio

        boolean hasVideo = false;
        boolean hasAudio = false;

        for (FFmpegStreamInfo info : getFFmpegStreamInfo()) {
            if (info == null)
                continue;
            if (info.getMediaType() == FFmpegStreamInfo.CodecType.VIDEO) {
                hasVideo = true;
            }
            if (info.getMediaType() == FFmpegStreamInfo.CodecType.AUDIO) {
                hasAudio = true;
            }
        }

        if(hasAudio && !hasVideo)
            return true;


        Log.d(TAG, "Format Audio Name: " + mFormatName);

        return (mFormatName.equals("mp3")
                || mFormatName.equals("vorbis")
                || mFormatName.equals("aac")
                || mFormatName.equals("ogg"));
    }


    public boolean isImage() {
        if (mFFmpegStreamInfo == null || TextUtils.isEmpty(mFormatName)) {
            return false;
        }

        Log.d(TAG, "Format Image Name: " + mFormatName);

        return (mFormatName.equals("webp")
                || mFormatName.contains("jpeg")
                || mFormatName.equals("png")
                || mFormatName.equals("image")
                || mFormatName.equals("image2")
                || mFormatName.equals("png_pipe")
                || mFormatName.equals("webp_pipe")
                || mFormatName.equals("bmp_pipe")
                || mFormatName.equals("cri_pipe")
                || mFormatName.equals("dds_pipe")
                || mFormatName.equals("dpx_pipe")
                || mFormatName.equals("exr_pipe")
                || mFormatName.equals("gem_pipe")
                || mFormatName.equals("gif_pipe")
                || mFormatName.equals("hdr_pipe")
                || mFormatName.equals("j2k_pipe")
                || mFormatName.equals("jpeg_pipe")
                || mFormatName.equals("jpegls_pipe")
                || mFormatName.equals("jpegxl_pipe")
                || mFormatName.equals("pam_pipe")
                || mFormatName.equals("pbm_pipe")
                || mFormatName.equals("pcx_pipe")
                || mFormatName.equals("pfm_pipe")
                || mFormatName.equals("pgmyuv_pipe")
                || mFormatName.equals("pgm_pipe")
                || mFormatName.equals("pgx_pipe")
                || mFormatName.equals("phm_pipe")
                || mFormatName.equals("photocd_pipe")
                || mFormatName.equals("pictor_pipe")
                || mFormatName.equals("ppm_pipe")
                || mFormatName.equals("psd_pipe")
                || mFormatName.equals("qdraw_pipe")
                || mFormatName.equals("qoi_pipe")
                || mFormatName.equals("sgi_pipe")
                || mFormatName.equals("svg_pipe")
                || mFormatName.equals("sunrast_pipe")
                || mFormatName.equals("tiff_pipe")
                || mFormatName.equals("vbn_pipe")
                || mFormatName.equals("xbm_pipe")
                || mFormatName.equals("xpm_pipe")
                || mFormatName.equals("xwd_pipe")
                || mFormatName.equals("gif")
                || mFormatName.equals("ico"));
    }

    public boolean isSubtitle() {
        if (mFFmpegStreamInfo == null || TextUtils.isEmpty(mFormatName)) {
            return false;
        }

        Log.d(TAG, "Format Subtitle Name: " + mFormatName);

        return (mFormatName.equals("subrip")
                || mFormatName.contains("webvtt"));
    }

    public void release() {
        if (mBitmap != null) {
            mBitmap = null;
        }
        if (mByteBuffer != null) {
            mByteBuffer.clear();
            mByteBuffer = null;
        }
    }

    public void printMetaData() {
        if (mMetadata != null) {
            for (Map.Entry<String, String> entry : mMetadata.entrySet()) {
                Log.d(TAG, "key: " + (entry.getKey() + "/" + entry.getValue()));
            }
        }
    }

    public int getType(){
        if(mFormatName.contains("hls") || mFormatName.contains("dash"))
            return UrlType.MEDIA.getValue();
        return UrlType.FILE.getValue();
    }




}
