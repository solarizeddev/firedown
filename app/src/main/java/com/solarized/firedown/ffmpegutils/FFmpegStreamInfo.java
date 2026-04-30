package com.solarized.firedown.ffmpegutils;


import android.util.Log;

import androidx.annotation.NonNull;

import com.solarized.firedown.utils.Utils;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

public class FFmpegStreamInfo {

    private static final String TAG = FFmpegStreamInfo.class.getName();

    public enum CodecType {
        UNKNOWN(0), AUDIO(1), VIDEO(2), SUBTITLE(3), ATTACHMENT(4), NB(5), DATA(6), STREAM(7);
        private final int value;
        CodecType(int value) {
            this.value = value;
        }

        public int getValue() {
            return value;
        }
    }

    private static final Map<String, Locale> sLocaleMap;

    static {
        String[] languages = Locale.getISOLanguages();
        sLocaleMap = new HashMap<>(languages.length);
        for (String language : languages) {
            Locale locale = new Locale(language);
            sLocaleMap.put(locale.getISO3Language(), locale);
        }
    }

    private Map<String, String> mMetadata;

    private String mCodecName;

    private CodecType mMediaType;

    private int mStreamNumber;

    private int mSelectedStream;

    private int mFrameRate;

    private int mWidth;

    private int mHeight;

    private int mEncodedWidth;

    private int mEncodedHeight;

    private int mBitRate;

    private int mSamplingRate;

    private int mPixelFormat;

    private int mSampleFormat;

    public void setMetadata(Map<String, String> metadata) {
        this.mMetadata = metadata;
    }

    private void setMediaTypeInternal(int mediaTypeInternal) {
        mMediaType = CodecType.values()[mediaTypeInternal];
    }

    public void setStreamNumber(int streamNumber) {
        this.mStreamNumber = streamNumber;
    }

    public int getStreamNumber() {
        return this.mStreamNumber;
    }

    public void setSelectedStream(int streamNumber){
        this.mSelectedStream = streamNumber;
    }

    public int getSelectedStream(){
        return this.mSelectedStream;
    }

    public void setFrameRate(int frameRate){
        this.mFrameRate = frameRate;
    }

    public int getFrameRate(){
        return this.mFrameRate;
    }

    public void setWidth(int width){
        this.mWidth = width;
    }

    public int getEncodedWidth(){
        return this.mEncodedWidth;
    }

    public int getEncodedHeight(){
        return this.mEncodedHeight;
    }

    public int getWidth(){
        return this.mWidth;
    }

    public void setHeight(int height){
        this.mHeight = height;
    }

    public void setEncodedHeight(int height){
        this.mEncodedHeight = height;
    }

    public void setEncodedWidth(int width){
        this.mEncodedWidth = width;
    }

    public void setPixelFormat(int pix){
        this.mPixelFormat = pix;
    }

    public void setSampleFormat(int sample){
        this.mSampleFormat = sample;
    }

    public void setCodecName(String mCodecName) {
        Log.d(TAG, "setCodecName: " + mCodecName);
        this.mCodecName = mCodecName;
    }

    public String getCodecName(){
        return mCodecName;
    }

    public boolean isImage(){
        return mCodecName.equals("png") ||
                mCodecName.equals("jpeg") ||
                mCodecName.equals("gif") ||
                mCodecName.equals("webp") ||
                mCodecName.equals("mjpeg") ||
                mCodecName.equals("av1") ||
                mCodecName.equals("bmp");
    }

    public int getHeight(){
        return this.mHeight;
    }

    public int getPixelFormat(){
        return mPixelFormat;
    }

    public int getSampleFormat(){
        return mSampleFormat;
    }


    public void setSamplingRate(int samplingRate){
        this.mSamplingRate = samplingRate;
    }

    public int getSamplingRate(){
        return this.mSamplingRate;
    }

    public void setBitRate(int bitRate){
        this.mBitRate = bitRate;
    }

    public int getBitRate(){
        return this.mBitRate;
    }

    public String getDisplayDescription() {
        if (mMediaType == CodecType.AUDIO) {
            return String.format(Locale.US, "%d kHz (%s)", getSamplingRate(), getCodecName());
        } else if (mMediaType == CodecType.VIDEO) {
            if (isImage()) {
                return String.format(Locale.US, "%d x %d", getWidth(), getHeight());
            }
            return String.format(Locale.US, "%dp (%d x %d)", getHeight(), getWidth(), getHeight());
        }
        return "";
    }

    /**
     * Return stream language locale
     * @return locale or null if not known
     */
    public Locale getLanguage() {
        if (mMetadata == null)
            return null;
        String iso3Langugae = mMetadata.get("language");
        if (iso3Langugae == null)
            return null;
        return sLocaleMap.get(iso3Langugae);
    }

    public String getBitrate() {
        if (mMetadata == null)
            return null;
        if(mMetadata.containsKey("variant_bitrate")){
            String sBitrate = mMetadata.get("variant_bitrate");
            if(sBitrate != null){
                int bitrate = Integer.parseInt(sBitrate);
                return Utils.getBitrate(bitrate);
            }
        }else if(mMetadata.containsKey("videodatarate")){
            String sBitrate = mMetadata.get("videodatarate");
            if(sBitrate != null){
                int bitrate = Integer.parseInt(sBitrate);
                return Utils.getBitrate(bitrate);
            }
        }else if(mMetadata.containsKey("audiodatarate")){
            String sBitrate = mMetadata.get("audiodatarate");
            if(sBitrate != null){
                int bitrate = Integer.parseInt(sBitrate);
                return Utils.getBitrate(bitrate);
            }
        }else if(mBitRate > 0){
            return String.format(Locale.US, "%d Kbps", mBitRate);
        }
        return "-- Kbps";
    }

    public String getRawBitrate() {
        if (mMetadata == null)
            return "0";
        if(mMetadata.containsKey("variant_bitrate")){
            return mMetadata.get("variant_bitrate");
        }else if(mMetadata.containsKey("videodatarate")){
            return mMetadata.get("videodatarate");
        }else if(mMetadata.containsKey("audiodatarate")){
            return mMetadata.get("audiodatarate");
        }else if(mBitRate > 0){
           return String.valueOf(mBitRate);
        }
        return "0";
    }



    public CodecType getMediaType() {
        return mMediaType;
    }

    public Map<String, String> getMetadata() {
        return mMetadata;
    }

    @NonNull
    @Override
    public String toString() {
        Locale language = getLanguage();
        String languageName = language == null ? "unknown" : language.getDisplayName();
        return "{\n" +
                "\tmediaType: " +
                mMediaType +
                "\n" +
                "\tlanguage: " +
                languageName +
                "\n" +
                "\tmetadata " +
                mMetadata +
                "\n" +
                "}";
    }



}

