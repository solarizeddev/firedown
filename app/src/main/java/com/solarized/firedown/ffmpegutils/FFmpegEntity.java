package com.solarized.firedown.ffmpegutils;

import android.os.Parcel;
import android.os.Parcelable;

import androidx.annotation.NonNull;



public class FFmpegEntity implements Parcelable {

    int codecType;

    int videoNumber;

    int audioNumber;

    int bitrate;

    String streamDescription;

    String streamUrl;

    String info;

    String videoCodec;

    String audioCodec;

    String streamAudioUrl;

    // SABR FormatId fields — per-variant (each quality has its own itag/lmt/xtags)
    int sabrVideoItag;
    String sabrVideoLastModified;
    String sabrVideoXtags;
    int sabrAudioItag;
    String sabrAudioLastModified;
    String sabrAudioXtags;
    String sabrAudioTrackId;
    int sabrTargetHeight;

    public FFmpegEntity(){
        videoNumber = FFmpegConstants.UNKNOWN_STREAM;
        audioNumber = FFmpegConstants.UNKNOWN_STREAM;
        bitrate = -1;
    }

    protected FFmpegEntity(Parcel in) {
        codecType = in.readInt();
        videoNumber = in.readInt();
        audioNumber = in.readInt();
        streamDescription = in.readString();
        streamUrl = in.readString();
        info = in.readString();
        videoCodec = in.readString();
        audioCodec = in.readString();
        bitrate = in.readInt();
        streamAudioUrl = in.readString();
        sabrVideoItag = in.readInt();
        sabrVideoLastModified = in.readString();
        sabrVideoXtags = in.readString();
        sabrAudioItag = in.readInt();
        sabrAudioLastModified = in.readString();
        sabrAudioXtags = in.readString();
        sabrAudioTrackId = in.readString();
        sabrTargetHeight = in.readInt();
    }

    public static final Creator<FFmpegEntity> CREATOR = new Creator<>() {
        @Override
        public FFmpegEntity createFromParcel(Parcel in) {
            return new FFmpegEntity(in);
        }

        @Override
        public FFmpegEntity[] newArray(int size) {
            return new FFmpegEntity[size];
        }
    };

    public int getVideoStreamNumber(){
        return videoNumber;
    }

    public int getAudioStreamNumber(){
        return audioNumber;
    }

    public boolean isAudioOnly(){
        return videoNumber == FFmpegConstants.UNKNOWN_STREAM;
    }

    public boolean isVideoOnly(){
        return audioNumber == FFmpegConstants.UNKNOWN_STREAM;
    }


    public int getCodecType(){
        return codecType;
    }

    public void setCodecType(int codecType) {
        this.codecType = codecType;
    }

    public String getStreamDescription(){
        return streamDescription;
    }

    public void setVideoStreamNumber(int number){
        this.videoNumber = number;
    }

    public void setAudioStreamNumber(int number){
        this.audioNumber = number;
    }

    public void setStreamDescription(String description){
        this.streamDescription = description;
    }

    public void setStreamUrl(String streamUrl){
        this.streamUrl = streamUrl;
    }

    public String getStreamUrl() {
        return streamUrl;
    }

    public void setStreamAudioUrl(String streamAudioUrl) {
        this.streamAudioUrl = streamAudioUrl;
    }

    public String getStreamAudioUrl() {
        return streamAudioUrl;
    }

    public String getInfo(){
        return info;
    }

    public void setInfo(String info){
        this.info = info;
    }

    public String getVideoCodec() {
        return videoCodec;
    }

    public void setVideoCodec(String videoCodec) {
        this.videoCodec = videoCodec;
    }

    public String getAudioCodec() {
        return audioCodec;
    }

    public void setAudioCodec(String audioCodec) {
        this.audioCodec = audioCodec;
    }

    public int getBitrate() {
        return bitrate;
    }

    public void setBitrate(int bitrate) {
        this.bitrate = bitrate;
    }

    // SABR getters/setters

    public int getSabrVideoItag() { return sabrVideoItag; }
    public void setSabrVideoItag(int itag) { this.sabrVideoItag = itag; }

    public String getSabrVideoLastModified() { return sabrVideoLastModified; }
    public void setSabrVideoLastModified(String lmt) { this.sabrVideoLastModified = lmt; }

    public String getSabrVideoXtags() { return sabrVideoXtags; }
    public void setSabrVideoXtags(String xtags) { this.sabrVideoXtags = xtags; }

    public int getSabrAudioItag() { return sabrAudioItag; }
    public void setSabrAudioItag(int itag) { this.sabrAudioItag = itag; }

    public String getSabrAudioLastModified() { return sabrAudioLastModified; }
    public void setSabrAudioLastModified(String lmt) { this.sabrAudioLastModified = lmt; }

    public String getSabrAudioXtags() { return sabrAudioXtags; }
    public void setSabrAudioXtags(String xtags) { this.sabrAudioXtags = xtags; }

    public String getSabrAudioTrackId() { return sabrAudioTrackId; }
    public void setSabrAudioTrackId(String id) { this.sabrAudioTrackId = id; }

    public int getSabrTargetHeight() { return sabrTargetHeight; }
    public void setSabrTargetHeight(int height) { this.sabrTargetHeight = height; }

    public boolean hasSabrData() {
        return sabrVideoItag > 0 && sabrAudioItag > 0;
    }

    /**
     * Builds a human-readable codec label from the available codec fields.
     * Returns null if no codec info is available.
     *
     * Examples: "H.264 / AAC", "VP9", "Opus", "H.264 / AAC • 4500 kbps"
     */
    public String getCodecLabel() {
        StringBuilder sb = new StringBuilder();
        if (videoCodec != null && !videoCodec.isEmpty()) {
            sb.append(videoCodec);
        }
        if (audioCodec != null && !audioCodec.isEmpty()) {
            if (sb.length() > 0) sb.append(" / ");
            sb.append(audioCodec);
        }
        if (bitrate > 0) {
            if (sb.length() > 0) sb.append(" • ");
            sb.append(bitrate).append(" kbps");
        }
        return sb.length() > 0 ? sb.toString() : null;
    }


    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeInt(codecType);
        dest.writeInt(videoNumber);
        dest.writeInt(audioNumber);
        dest.writeString(streamDescription);
        dest.writeString(streamUrl);
        dest.writeString(info);
        dest.writeString(videoCodec);
        dest.writeString(audioCodec);
        dest.writeInt(bitrate);
        dest.writeString(streamAudioUrl);
        dest.writeInt(sabrVideoItag);
        dest.writeString(sabrVideoLastModified);
        dest.writeString(sabrVideoXtags);
        dest.writeInt(sabrAudioItag);
        dest.writeString(sabrAudioLastModified);
        dest.writeString(sabrAudioXtags);
        dest.writeString(sabrAudioTrackId);
        dest.writeInt(sabrTargetHeight);
    }
}