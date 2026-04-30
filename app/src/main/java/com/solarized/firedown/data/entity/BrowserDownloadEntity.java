package com.solarized.firedown.data.entity;


import android.os.Parcel;
import android.os.Parcelable;
import android.text.TextUtils;
import android.util.Log;
import android.webkit.URLUtil;

import androidx.annotation.NonNull;
import com.solarized.firedown.ffmpegutils.FFmpegEntity;
import com.solarized.firedown.ffmpegutils.FFmpegUtils;
import com.solarized.firedown.geckoview.GeckoState;
import com.solarized.firedown.manager.UrlType;
import com.solarized.firedown.utils.BrowserHeaders;
import com.solarized.firedown.utils.Utils;
import com.solarized.firedown.utils.WebUtils;

import org.apache.commons.collections4.CollectionUtils;
import org.mozilla.geckoview.WebResponse;

import java.util.ArrayList;
import java.util.Map;
import java.util.UUID;


public class BrowserDownloadEntity implements Parcelable, Comparable<BrowserDownloadEntity> {

    private static final String TAG = BrowserDownloadEntity.class.getSimpleName();

    int uid;

    int sessionId;

    int tabId;

    int fileType;

    int videoNumber;

    int audioNumber;

    long fileLength;

    long updateTime;

    long creationTime;

    long durationTime;

    String requestId;

    String fileDescription;

    String fileDuration;

    String fileName;

    String fileUrl;

    String fileTitle;

    String mimeType;

    String fileOrigin;

    String fileHeaders;

    String fileThumbnail;

    String cookieHeader;

    // SABR shared data (same for all variants of a video)
    String sabrUrl;
    String sabrConfig;
    String sabrPoToken;
    String sabrClientVersion;

    ArrayList<FFmpegEntity> mStreams;

    ArrayList<FFmpegTagEntity> mTags;

    boolean fileNameForced;

    boolean hasVariants;

    boolean isAudio;

    // Transient — not parcelled. Set right before download starts.
    int selectedStreamIndex = -1;

    boolean incognito;


    public BrowserDownloadEntity(GeckoState geckoState){
        WebResponse response = geckoState.getWebResponse();
        String origin = geckoState.getEntityUri();
        int sessionId = geckoState.getEntityId();
        boolean isGecko = response.body != null;
        String mimeType = response.headers.get(BrowserHeaders.CONTENT_TYPE);
        String contentDisposition = response.headers.get(BrowserHeaders.CONTENT_DISPOSITION);
        String fileName = WebUtils.getFileNameFromURL(response.uri);
        String contentName = WebUtils.getFileNameFromDisposition(contentDisposition);
        String contentLength = response.headers.get(BrowserHeaders.CONTENT_LENGTH);
        long length = WebUtils.getLengthFromHeaders(contentLength);
        setSessionId(sessionId);
        setUid(UUID.randomUUID().hashCode());
        setFileName(TextUtils.isEmpty(contentName) ? fileName : contentName);
        setFileLength(length);
        setMimeType(mimeType);
        setFileUrl(response.uri);
        setHeaders(response.headers);
        setType(isGecko ? UrlType.GECKO.getValue() : UrlType.FILE.getValue());
        setFileOrigin(origin);
    }


    public BrowserDownloadEntity(){
        audioNumber = -1;
        videoNumber = -1;
        mTags = new ArrayList<>();
        mStreams = new ArrayList<>();
        creationTime = System.currentTimeMillis();
    }


    protected BrowserDownloadEntity(Parcel in) {
        uid = in.readInt();
        sessionId = in.readInt();
        tabId = in.readInt();
        fileType = in.readInt();
        videoNumber = in.readInt();
        audioNumber = in.readInt();
        fileLength = in.readLong();
        updateTime = in.readLong();
        creationTime = in.readLong();
        durationTime = in.readLong();
        requestId = in.readString();
        fileDescription = in.readString();
        fileDuration = in.readString();
        fileName = in.readString();
        fileUrl = in.readString();
        fileTitle = in.readString();
        mimeType = in.readString();
        fileOrigin = in.readString();
        fileHeaders = in.readString();
        fileThumbnail = in.readString();
        mStreams = in.createTypedArrayList(FFmpegEntity.CREATOR);
        mTags = in.createTypedArrayList(FFmpegTagEntity.CREATOR);
        fileNameForced = in.readByte() != 0;
        hasVariants = in.readByte() != 0;
        isAudio= in.readByte() != 0;
        cookieHeader = in.readString();
        sabrUrl = in.readString();
        sabrConfig = in.readString();
        sabrPoToken = in.readString();
        sabrClientVersion = in.readString();
        incognito = in.readByte() != 0;
    }

    public static final Creator<BrowserDownloadEntity> CREATOR = new Creator<>() {
        @Override
        public BrowserDownloadEntity createFromParcel(Parcel in) {
            return new BrowserDownloadEntity(in);
        }

        @Override
        public BrowserDownloadEntity[] newArray(int size) {
            return new BrowserDownloadEntity[size];
        }
    };


    public String getFileTitle() {
        return fileTitle;
    }

    public void setFileTitle(String fileTitle) {
        this.fileTitle = fileTitle;
    }

    public long getFileLength() {return fileLength;}

    public int getAudioNumber(){
        return audioNumber;
    }

    public int getVideoNumber(){
        return videoNumber;
    }

    public String getFileOrigin() {
        return fileOrigin;
    }

    public String getFileThumbnail() {
        return fileThumbnail;
    }

    public long getDurationTime() {
        return durationTime;
    }

    public long getCreationTime() {
        return creationTime;
    }

    public String getFileName() {
        return fileName;
    }

    public String getMimeType() {
        return mimeType;
    }

    public int getSessionId() {
        return sessionId;
    }

    public String getFileUrl() {
        return fileUrl;
    }

    public String getFileDuration() {
        return fileDuration;
    }

    public String getFileHeaders() {
        return fileHeaders;
    }

    public int getType() {
        return fileType;
    }


    public String getCookieHeader() {
        return cookieHeader;
    }

    public void setCookieHeader(String cookieHeader) {
        this.cookieHeader = cookieHeader;
    }

    public void setFileDescription(String fileDescription) {
        this.fileDescription = fileDescription;
    }

    public String getFileDescription() {
        return fileDescription;
    }

    public void setCreationTime(long creationTime) {
        this.creationTime = creationTime;
    }

    public void setFileThumbnail(String fileThumbnail) {
        this.fileThumbnail = fileThumbnail;
    }

    public void setFileNameForced(boolean fileNameForced) {
        this.fileNameForced = fileNameForced;
    }

    public boolean isFileNameForced() {
        return fileNameForced;
    }

    public void setDurationTime(long durationTime) {
        this.durationTime = durationTime;
    }

    public ArrayList<FFmpegTagEntity> getTags() {
        return mTags;
    }

    public void setTags(ArrayList<FFmpegTagEntity> mTags) {
        this.mTags = mTags;
    }

    public ArrayList<FFmpegEntity> getStreams() {
        return mStreams;
    }

    public void setStreams(ArrayList<FFmpegEntity> mStreams) {
        this.mStreams = mStreams;
    }

    public void setSelectedStreamIndex(int index) {
        this.selectedStreamIndex = index;
    }

    public int getSelectedStreamIndex() {
        return selectedStreamIndex;
    }

    /**
     * Returns the FFmpegEntity the user selected in the variant picker.
     * Falls back to the first stream when variants exist but no explicit
     * selection was made (e.g. direct download without opening the picker).
     * Returns null for single-stream downloads with no variants.
     */
    public FFmpegEntity getSelectedStream() {
        if (mStreams == null || mStreams.isEmpty()) return null;
        if (selectedStreamIndex >= 0 && selectedStreamIndex < mStreams.size()) {
            return mStreams.get(selectedStreamIndex);
        }
        // Default to first (best quality — already sorted by VariantProcessor/adapter)
        if (hasVariants || mStreams.size() > 0) {
            return mStreams.get(0);
        }
        return null;
    }

    public void setFileDuration(long duration){
        Log.d(TAG, "setFileDuration: " + duration);
        setDurationTime(duration);
        if(duration > 0) {
            fileDuration = FFmpegUtils.getFileDuration(duration);
        }
    }

    public void setAudio(boolean audio) {
        isAudio = audio;
    }

    public boolean isAudio() {
        return isAudio;
    }

    public void setVideoNumber(int number){
        videoNumber = number;
    }

    public void setAudioNumber(int number){
        audioNumber = number;
    }

    public void setFileName(String fileName) {
        this.fileName = fileName;
    }

    public boolean getHasVariants() {
        return hasVariants;
    }

    public String getRequestId() {
        return requestId;
    }

    public void setSessionId(int sessionId) {
        this.sessionId = sessionId;
    }

    public void setRequestId(String requestId) {
        this.requestId = requestId;
    }

    public void setFileUrl(String fileUrl) {
        this.fileUrl = fileUrl;
    }

    public int getUid() {
        return uid;
    }


    public void setUid(int id) {
        this.uid = id;
    }

    public void setHeaders(Map<String, String> headers) {
        this.fileHeaders = Utils.mapToString(headers);
    }

    public void setHasVariants(boolean hasVariants) {
        this.hasVariants = hasVariants;
    }

    public void setMimeType(String mimeType) { this.mimeType = mimeType; }

    public void setFileLength(long length){
        this.fileLength = length;
    }

    public void setFileOrigin(String origin){
        this.fileOrigin = origin;
    }

    public void setUpdateTime(long updateTime) {
        this.updateTime = updateTime;
    }

    public long getUpdateTime() {
        return updateTime;
    }

    public void setType(int type){
        this.fileType = type;
    }

    public void setTabId(int sessionId) {
        this.tabId = sessionId;
    }

    public int getTabId() {
        return tabId;
    }

    // SABR getters/setters

    public String getSabrUrl() {
        return sabrUrl;
    }

    public void setSabrUrl(String sabrUrl) {
        this.sabrUrl = sabrUrl;
    }

    public String getSabrConfig() {
        return sabrConfig;
    }

    public void setSabrConfig(String sabrConfig) {
        this.sabrConfig = sabrConfig;
    }

    public String getSabrPoToken() {
        return sabrPoToken;
    }

    public void setSabrPoToken(String sabrPoToken) {
        this.sabrPoToken = sabrPoToken;
    }

    public String getSabrClientVersion() {
        return sabrClientVersion;
    }

    public void setSabrClientVersion(String v) {
        this.sabrClientVersion = v;
    }

    public boolean isIncognito() { return incognito; }

    public void setIncognito(boolean incognito) { this.incognito = incognito; }


    public BrowserDownloadEntity(BrowserDownloadEntity entity){
        this.uid = entity.getUid();
        this.sessionId = entity.getSessionId();
        this.fileType = entity.getType();
        this.fileName = entity.getFileName();
        this.mimeType = entity.getMimeType();
        this.fileUrl = entity.getFileUrl();
        this.fileDescription = entity.getFileDescription();
        this.fileTitle = entity.getFileTitle();
        this.fileLength = entity.getFileLength();
        this.fileHeaders = entity.getFileHeaders();
        this.fileOrigin = entity.getFileOrigin();
        this.fileDuration = entity.getFileDuration();
        this.mStreams = new ArrayList<>(entity.getStreams());
        this.audioNumber = entity.getAudioNumber();
        this.videoNumber = entity.getVideoNumber();
        this.hasVariants = entity.getHasVariants();
        this.tabId = entity.getTabId();
        this.requestId = entity.getRequestId();
        this.updateTime = entity.getUpdateTime();
        this.creationTime = entity.getCreationTime();
        this.durationTime = entity.getDurationTime();
        this.fileNameForced = entity.isFileNameForced();
        this.fileThumbnail = entity.getFileThumbnail();
        this.mTags = entity.getTags();
        this.isAudio = entity.isAudio();
        this.cookieHeader = entity.getCookieHeader();
        this.sabrUrl = entity.getSabrUrl();
        this.sabrConfig = entity.getSabrConfig();
        this.sabrPoToken = entity.getSabrPoToken();
        this.sabrClientVersion = entity.getSabrClientVersion();
        this.incognito = entity.isIncognito();
    }


    public boolean isEmpty(){
        String url = getFileUrl();
        return TextUtils.isEmpty(url) || !URLUtil.isValidUrl(url);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeInt(uid);
        dest.writeInt(sessionId);
        dest.writeInt(tabId);
        dest.writeInt(fileType);
        dest.writeInt(videoNumber);
        dest.writeInt(audioNumber);
        dest.writeLong(fileLength);
        dest.writeLong(updateTime);
        dest.writeLong(creationTime);
        dest.writeLong(durationTime);
        dest.writeString(requestId);
        dest.writeString(fileDescription);
        dest.writeString(fileDuration);
        dest.writeString(fileName);
        dest.writeString(fileUrl);
        dest.writeString(fileTitle);
        dest.writeString(mimeType);
        dest.writeString(fileOrigin);
        dest.writeString(fileHeaders);
        dest.writeString(fileThumbnail);
        dest.writeTypedList(mStreams);
        dest.writeTypedList(mTags);
        dest.writeByte((byte) (fileNameForced ? 1 : 0));
        dest.writeByte((byte) (hasVariants ? 1 : 0));
        dest.writeByte((byte) (isAudio ? 1 : 0));
        dest.writeString(cookieHeader);
        dest.writeString(sabrUrl);
        dest.writeString(sabrConfig);
        dest.writeString(sabrPoToken);
        dest.writeString(sabrClientVersion);
        dest.writeByte((byte) (incognito ? 1 : 0));
    }

    public static boolean isEqual(BrowserDownloadEntity oldItem, BrowserDownloadEntity newItem){

        if(oldItem == null && newItem == null) {
            return true;
        } else if(oldItem != null && newItem != null){
            return oldItem.getFileName() != null && oldItem.getFileName().equals(newItem.getFileName())
                    && oldItem.getFileUrl() != null && oldItem.getFileUrl().equals(newItem.getFileUrl())
                    && oldItem.getFileTitle() != null && oldItem.getFileTitle().equals(newItem.getFileTitle())
                    && oldItem.getFileOrigin() != null && oldItem.getFileOrigin().equals(newItem.getFileOrigin())
                    && oldItem.getFileDescription() != null && oldItem.getFileDescription().equals(newItem.getFileDescription())
                    && oldItem.getFileDuration() != null && oldItem.getFileDuration().equals(newItem.getFileDuration())
                    && oldItem.getMimeType() != null && oldItem.getMimeType().equals(newItem.getMimeType())
                    && oldItem.getStreams() != null && newItem.getStreams() != null && CollectionUtils.isEqualCollection(oldItem.getStreams(), newItem.getStreams())
                    && oldItem.getTags() != null && newItem.getTags() != null && CollectionUtils.isEqualCollection(oldItem.getTags(), newItem.getTags())
                    && oldItem.getFileHeaders() != null && (oldItem.getFileHeaders().equals(newItem.getFileHeaders()));
        } else{
            return false;
        }
    }

    @Override
    public int compareTo(BrowserDownloadEntity o) {
        return Long.compare(creationTime, (o).getCreationTime());
    }
}