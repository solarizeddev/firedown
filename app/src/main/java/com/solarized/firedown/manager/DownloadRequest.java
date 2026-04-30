package com.solarized.firedown.manager;

import android.os.Parcel;
import android.os.Parcelable;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.solarized.firedown.data.entity.BrowserDownloadEntity;
import com.solarized.firedown.ffmpegutils.FFmpegEntity;
import com.solarized.firedown.ffmpegutils.FFmpegUtils;

import java.util.ArrayList;

/**
 * Immutable download request — carries only what the download pipeline needs.
 * Built from BrowserDownloadEntity at the moment the user taps "Download".
 * Replaces the pattern of parcelling the entire BrowserDownloadEntity through Intents.
 */
public class DownloadRequest implements Parcelable {

    private final String url;
    @Nullable private final String audioUrl;
    private final String name;
    private final String description;
    private final String origin;
    private final String mimeType;
    private final String headers;
    @Nullable private final String cookieHeader;
    private final int fileType;
    private final int videoNumber;
    private final int audioNumber;
    private final long fileLength;
    private final long durationTime;
    @Nullable private final String durationFormatted;
    private final boolean fileNameForced;
    private final int sessionId;

    // SABR fields — shared (URL + config + PO token from BrowserDownloadEntity)
    @Nullable private final String sabrUrl;
    @Nullable private final String sabrConfig;
    @Nullable private final String sabrPoToken;
    @Nullable private final String sabrClientVersion;

    // SABR fields — per-variant (from selected FFmpegEntity)
    private final int sabrVideoItag;
    @Nullable private final String sabrVideoLastModified;
    @Nullable private final String sabrVideoXtags;
    private final int sabrAudioItag;
    @Nullable private final String sabrAudioLastModified;
    @Nullable private final String sabrAudioXtags;
    @Nullable private final String sabrAudioTrackId;
    private final int sabrTargetHeight;
    private final boolean saveToVault;

    private DownloadRequest(Builder builder) {
        this.url = builder.url;
        this.audioUrl = builder.audioUrl;
        this.name = builder.name;
        this.description = builder.description;
        this.origin = builder.origin;
        this.mimeType = builder.mimeType;
        this.headers = builder.headers;
        this.cookieHeader = builder.cookieHeader;
        this.fileType = builder.fileType;
        this.videoNumber = builder.videoNumber;
        this.audioNumber = builder.audioNumber;
        this.fileLength = builder.fileLength;
        this.durationTime = builder.durationTime;
        this.durationFormatted = builder.durationFormatted;
        this.fileNameForced = builder.fileNameForced;
        this.sessionId = builder.sessionId;
        this.sabrUrl = builder.sabrUrl;
        this.sabrConfig = builder.sabrConfig;
        this.sabrPoToken = builder.sabrPoToken;
        this.sabrClientVersion = builder.sabrClientVersion;
        this.sabrVideoItag = builder.sabrVideoItag;
        this.sabrVideoLastModified = builder.sabrVideoLastModified;
        this.sabrVideoXtags = builder.sabrVideoXtags;
        this.sabrAudioItag = builder.sabrAudioItag;
        this.sabrAudioLastModified = builder.sabrAudioLastModified;
        this.sabrAudioXtags = builder.sabrAudioXtags;
        this.sabrAudioTrackId = builder.sabrAudioTrackId;
        this.sabrTargetHeight = builder.sabrTargetHeight;
        this.saveToVault = builder.saveToVault;
    }

    // ========================================================================
    // Factory — build from BrowserDownloadEntity with selected stream
    // ========================================================================

    /**
     * Creates a DownloadRequest from a BrowserDownloadEntity.
     * Applies the selected stream variant (or first variant if none selected).
     */
    public static DownloadRequest from(BrowserDownloadEntity entity) {
        Builder builder = new Builder(entity.getFileUrl())
                .name(entity.getFileName())
                .description(entity.getFileDescription())
                .origin(entity.getFileOrigin())
                .mimeType(entity.getMimeType())
                .headers(entity.getFileHeaders())
                .cookieHeader(entity.getCookieHeader())
                .fileType(entity.getType())
                .fileLength(entity.getFileLength())
                .durationTime(entity.getDurationTime())
                .durationFormatted(entity.getFileDuration())
                .fileNameForced(entity.isFileNameForced())
                .sessionId(entity.getSessionId())
                .saveToVault(entity.isIncognito())
                .sabrUrl(entity.getSabrUrl())
                .sabrConfig(entity.getSabrConfig())
                .sabrPoToken(entity.getSabrPoToken())
                .sabrClientVersion(entity.getSabrClientVersion());

        // Apply best stream — sort by quality descending, pick first
        ArrayList<FFmpegEntity> streams = entity.getStreams();
        FFmpegEntity selected;
        if (streams != null && !streams.isEmpty()) {
            streams.sort(FFmpegUtils.FFmpegEntityComparator);
            selected = streams.get(0);
        } else {
            selected = entity.getSelectedStream();
        }
        if (selected != null) {
            applySabrFields(builder, selected);
            String streamUrl = selected.getStreamUrl();
            if (!TextUtils.isEmpty(streamUrl)) {
                builder.url(streamUrl);
            }
            builder.audioUrl(selected.getStreamAudioUrl())
                    .videoNumber(selected.getVideoStreamNumber())
                    .audioNumber(selected.getAudioStreamNumber());
        } else {
            builder.videoNumber(entity.getVideoNumber())
                    .audioNumber(entity.getAudioNumber());
        }

        return builder.build();
    }

    /**
     * Creates a DownloadRequest for a specific variant selection.
     */
    public static DownloadRequest from(BrowserDownloadEntity entity, FFmpegEntity selectedStream) {
        Builder builder = new Builder(entity.getFileUrl())
                .name(entity.getFileName())
                .description(entity.getFileDescription())
                .origin(entity.getFileOrigin())
                .mimeType(entity.getMimeType())
                .headers(entity.getFileHeaders())
                .cookieHeader(entity.getCookieHeader())
                .fileType(entity.getType())
                .fileLength(entity.getFileLength())
                .durationTime(entity.getDurationTime())
                .durationFormatted(entity.getFileDuration())
                .fileNameForced(entity.isFileNameForced())
                .saveToVault(entity.isIncognito())
                .sessionId(entity.getSessionId())
                .sabrUrl(entity.getSabrUrl())
                .sabrConfig(entity.getSabrConfig())
                .sabrPoToken(entity.getSabrPoToken())
                .sabrClientVersion(entity.getSabrClientVersion());

        if (selectedStream != null) {
            applySabrFields(builder, selectedStream);
            String streamUrl = selectedStream.getStreamUrl();
            if (!TextUtils.isEmpty(streamUrl)) {
                builder.url(streamUrl);
            }
            builder.audioUrl(selectedStream.getStreamAudioUrl())
                    .videoNumber(selectedStream.getVideoStreamNumber())
                    .audioNumber(selectedStream.getAudioStreamNumber());
        }

        return builder.build();
    }

    /** Copy SABR per-variant fields from FFmpegEntity to builder */
    private static void applySabrFields(Builder builder, FFmpegEntity stream) {
        if (stream.hasSabrData()) {
            builder.sabrVideoItag(stream.getSabrVideoItag())
                    .sabrVideoLastModified(stream.getSabrVideoLastModified())
                    .sabrVideoXtags(stream.getSabrVideoXtags())
                    .sabrAudioItag(stream.getSabrAudioItag())
                    .sabrAudioLastModified(stream.getSabrAudioLastModified())
                    .sabrAudioXtags(stream.getSabrAudioXtags())
                    .sabrAudioTrackId(stream.getSabrAudioTrackId())
                    .sabrTargetHeight(stream.getSabrTargetHeight());
        }
    }

    // ========================================================================
    // Getters
    // ========================================================================

    public String getUrl()                  { return url; }
    @Nullable public String getAudioUrl()   { return audioUrl; }
    public String getName()                 { return name; }
    public String getDescription()          { return description; }
    public String getOrigin()               { return origin; }
    public String getMimeType()             { return mimeType; }
    public String getHeaders()              { return headers; }
    @Nullable public String getCookieHeader() { return cookieHeader; }
    public int getFileType()                { return fileType; }
    public int getVideoNumber()             { return videoNumber; }
    public int getAudioNumber()             { return audioNumber; }
    public long getFileLength()             { return fileLength; }
    public long getDurationTime()           { return durationTime; }
    @Nullable public String getDurationFormatted() { return durationFormatted; }
    public boolean isFileNameForced()       { return fileNameForced; }
    public int getSessionId()               { return sessionId; }
    public boolean isSaveToVault() { return saveToVault; }

    // SABR getters
    @Nullable public String getSabrUrl()                { return sabrUrl; }
    @Nullable public String getSabrConfig()             { return sabrConfig; }
    @Nullable public String getSabrPoToken()            { return sabrPoToken; }
    @Nullable public String getSabrClientVersion()      { return sabrClientVersion; }
    public int getSabrVideoItag()                       { return sabrVideoItag; }
    @Nullable public String getSabrVideoLastModified()  { return sabrVideoLastModified; }
    @Nullable public String getSabrVideoXtags()         { return sabrVideoXtags; }
    public int getSabrAudioItag()                       { return sabrAudioItag; }
    @Nullable public String getSabrAudioLastModified()  { return sabrAudioLastModified; }
    @Nullable public String getSabrAudioXtags()         { return sabrAudioXtags; }
    @Nullable public String getSabrAudioTrackId()       { return sabrAudioTrackId; }
    public int getSabrTargetHeight()                    { return sabrTargetHeight; }

    public boolean hasAudioUrl() {
        return audioUrl != null && !audioUrl.isEmpty();
    }

    public boolean hasSabrData() {
        return sabrUrl != null && !sabrUrl.isEmpty()
                && sabrConfig != null && !sabrConfig.isEmpty()
                && sabrVideoItag > 0 && sabrAudioItag > 0;
    }

    public StreamSelection toStreamSelection() {
        return new StreamSelection(videoNumber, audioNumber, audioUrl);
    }

    // ========================================================================
    // Builder
    // ========================================================================

    public static class Builder {
        private String url;
        private String audioUrl;
        private String name = "";
        private String description = "";
        private String origin = "";
        private String mimeType = "";
        private String headers = "";
        private String cookieHeader;
        private int fileType;
        private int videoNumber = -1;
        private int audioNumber = -1;
        private long fileLength;
        private long durationTime;
        private String durationFormatted;
        private boolean fileNameForced;
        private int sessionId;
        private String sabrUrl;
        private String sabrConfig;
        private String sabrPoToken;
        private String sabrClientVersion;
        private int sabrVideoItag;
        private String sabrVideoLastModified;
        private String sabrVideoXtags;
        private int sabrAudioItag;
        private String sabrAudioLastModified;
        private String sabrAudioXtags;
        private String sabrAudioTrackId;
        private int sabrTargetHeight;
        private boolean saveToVault;

        public Builder(String url) {
            this.url = url;
        }

        public Builder url(String url)                      { this.url = url; return this; }
        public Builder audioUrl(String audioUrl)            { this.audioUrl = audioUrl; return this; }
        public Builder name(String name)                    { this.name = name; return this; }
        public Builder description(String description)      { this.description = description; return this; }
        public Builder origin(String origin)                { this.origin = origin; return this; }
        public Builder mimeType(String mimeType)            { this.mimeType = mimeType; return this; }
        public Builder headers(String headers)              { this.headers = headers; return this; }
        public Builder cookieHeader(String cookieHeader)    { this.cookieHeader = cookieHeader; return this; }
        public Builder fileType(int fileType)               { this.fileType = fileType; return this; }
        public Builder videoNumber(int videoNumber)         { this.videoNumber = videoNumber; return this; }
        public Builder audioNumber(int audioNumber)         { this.audioNumber = audioNumber; return this; }
        public Builder fileLength(long fileLength)          { this.fileLength = fileLength; return this; }
        public Builder durationTime(long durationTime)      { this.durationTime = durationTime; return this; }
        public Builder durationFormatted(String formatted)  { this.durationFormatted = formatted; return this; }
        public Builder fileNameForced(boolean forced)       { this.fileNameForced = forced; return this; }
        public Builder sessionId(int sessionId)             { this.sessionId = sessionId; return this; }
        public Builder sabrUrl(String url)                  { this.sabrUrl = url; return this; }
        public Builder sabrConfig(String config)            { this.sabrConfig = config; return this; }
        public Builder sabrPoToken(String token)            { this.sabrPoToken = token; return this; }
        public Builder sabrClientVersion(String v)         { this.sabrClientVersion = v; return this; }
        public Builder sabrVideoItag(int itag)              { this.sabrVideoItag = itag; return this; }
        public Builder sabrVideoLastModified(String lmt)    { this.sabrVideoLastModified = lmt; return this; }
        public Builder sabrVideoXtags(String xtags)         { this.sabrVideoXtags = xtags; return this; }
        public Builder sabrAudioItag(int itag)              { this.sabrAudioItag = itag; return this; }
        public Builder sabrAudioLastModified(String lmt)    { this.sabrAudioLastModified = lmt; return this; }
        public Builder sabrAudioXtags(String xtags)         { this.sabrAudioXtags = xtags; return this; }
        public Builder sabrAudioTrackId(String id)          { this.sabrAudioTrackId = id; return this; }
        public Builder sabrTargetHeight(int height)         { this.sabrTargetHeight = height; return this; }

        public Builder saveToVault(boolean vault) { this.saveToVault = vault; return this; }

        public DownloadRequest build() {
            return new DownloadRequest(this);
        }
    }

    // ========================================================================
    // Parcelable
    // ========================================================================

    protected DownloadRequest(Parcel in) {
        url = in.readString();
        audioUrl = in.readString();
        name = in.readString();
        description = in.readString();
        origin = in.readString();
        mimeType = in.readString();
        headers = in.readString();
        cookieHeader = in.readString();
        fileType = in.readInt();
        videoNumber = in.readInt();
        audioNumber = in.readInt();
        fileLength = in.readLong();
        durationTime = in.readLong();
        durationFormatted = in.readString();
        fileNameForced = in.readByte() != 0;
        sessionId = in.readInt();
        sabrUrl = in.readString();
        sabrConfig = in.readString();
        sabrPoToken = in.readString();
        sabrClientVersion = in.readString();
        sabrVideoItag = in.readInt();
        sabrVideoLastModified = in.readString();
        sabrVideoXtags = in.readString();
        sabrAudioItag = in.readInt();
        sabrAudioLastModified = in.readString();
        sabrAudioXtags = in.readString();
        sabrAudioTrackId = in.readString();
        sabrTargetHeight = in.readInt();
        saveToVault = in.readByte() != 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeString(url);
        dest.writeString(audioUrl);
        dest.writeString(name);
        dest.writeString(description);
        dest.writeString(origin);
        dest.writeString(mimeType);
        dest.writeString(headers);
        dest.writeString(cookieHeader);
        dest.writeInt(fileType);
        dest.writeInt(videoNumber);
        dest.writeInt(audioNumber);
        dest.writeLong(fileLength);
        dest.writeLong(durationTime);
        dest.writeString(durationFormatted);
        dest.writeByte((byte) (fileNameForced ? 1 : 0));
        dest.writeInt(sessionId);
        dest.writeString(sabrUrl);
        dest.writeString(sabrConfig);
        dest.writeString(sabrPoToken);
        dest.writeString(sabrClientVersion);
        dest.writeInt(sabrVideoItag);
        dest.writeString(sabrVideoLastModified);
        dest.writeString(sabrVideoXtags);
        dest.writeInt(sabrAudioItag);
        dest.writeString(sabrAudioLastModified);
        dest.writeString(sabrAudioXtags);
        dest.writeString(sabrAudioTrackId);
        dest.writeInt(sabrTargetHeight);
        dest.writeByte((byte) (saveToVault ? 1 : 0));
    }

    @Override
    public int describeContents() {
        return 0;
    }

    public static final Creator<DownloadRequest> CREATOR = new Creator<>() {
        @Override
        public DownloadRequest createFromParcel(Parcel in) {
            return new DownloadRequest(in);
        }

        @Override
        public DownloadRequest[] newArray(int size) {
            return new DownloadRequest[size];
        }
    };
}