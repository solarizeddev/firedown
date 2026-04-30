package com.solarized.firedown.data.entity;

import android.os.Parcel;
import android.os.Parcelable;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

import com.solarized.firedown.data.Download;

@Entity(tableName = "download")
public class DownloadEntity implements Download, Parcelable {

    @PrimaryKey
    public int uid;

    @ColumnInfo(name = "file_parent_id")
    public int fileParentId;

    @ColumnInfo(name = "file_headers")
    public String fileHeaders;

    @ColumnInfo(name = "file_type")
    public int fileType;

    @ColumnInfo(name = "file_url")
    public String fileUrl;

    @ColumnInfo(name = "file_name")
    public String fileName;

    @ColumnInfo(name = "file_img")
    public String fileImg;

    @ColumnInfo(name = "file_desc")
    public String fileDescription;

    @ColumnInfo(name = "file_path")
    public String filePath;

    @ColumnInfo(name = "file_mime_type")
    public String fileMimeType;

    @ColumnInfo(name = "file_origin_url")
    public String fileOriginUrl;

    @ColumnInfo(name = "file_progress")
    public int fileProgress;

    @ColumnInfo(name = "file_date")
    public long fileDate;

    @ColumnInfo(name = "file_size")
    public long fileSize;

    @ColumnInfo(name = "file_live")
    public boolean fileLive;

    @ColumnInfo(name = "file_error_message")
    public int fileErrorType;

    @ColumnInfo(name = "file_status")
    public int fileStatus;

    @ColumnInfo(name = "file_encrypted")
    public boolean fileEncrypted;

    @ColumnInfo(name = "file_thumbnail_duration")
    public long fileThumbnailDuration;

    @ColumnInfo(name = "file_duration")
    public long fileDuration;

    @ColumnInfo(name = "file_duration_formatted")
    public String fileDurationFormatted;

    @ColumnInfo(name = "file_safe", defaultValue = "0")
    public boolean fileSafe;


    protected DownloadEntity(Parcel in) {
        uid = in.readInt();
        fileParentId = in.readInt();
        fileHeaders = in.readString();
        fileType = in.readInt();
        fileUrl = in.readString();
        fileName = in.readString();
        fileImg = in.readString();
        fileDescription = in.readString();
        filePath = in.readString();
        fileMimeType = in.readString();
        fileOriginUrl = in.readString();
        fileProgress = in.readInt();
        fileDate = in.readLong();
        fileSize = in.readLong();
        fileLive = in.readByte() != 0;
        fileErrorType = in.readInt();
        fileStatus = in.readInt();
        fileEncrypted = in.readByte() != 0;
        fileSafe = in.readByte() != 0;
        fileThumbnailDuration = in.readLong();
        fileDuration = in.readLong();
        fileDurationFormatted = in.readString();
    }

    public static final Creator<DownloadEntity> CREATOR = new Creator<>() {
        @Override
        public DownloadEntity createFromParcel(Parcel in) {
            return new DownloadEntity(in);
        }

        @Override
        public DownloadEntity[] newArray(int size) {
            return new DownloadEntity[size];
        }
    };

    public int getId(){
        return uid;
    }

    @Override
    public int getParentId() {
        return fileParentId;
    }


    public void setId(int id){
        uid = id;
    }

    public int getFileErrorType(){
        return fileErrorType;
    }

    @Override
    public int getFileType() {
        return fileType;
    }

    public void setFileType(int type) {
        fileType = type;
    }

    @Override
    public String getOriginUrl() {
        return fileOriginUrl;
    }

    public void setFileOriginUrl(String origin) {
        fileOriginUrl = origin;
    }

    @Override
    public String getDurationFormatted() {
        return fileDurationFormatted;
    }

    @Override
    public int hashCode() {
        return uid;
    }

    @Override
    public boolean equals(@Nullable Object obj) {
        if(obj instanceof DownloadEntity newEntity) {
            return uid == newEntity.getId();
        }
        return super.equals(obj);
    }


    @Override
    public String getFileHeaders() {
        return fileHeaders;
    }

    public void setFileHeaders(String headers) {
        fileHeaders = headers;
    }

    @Override
    public int getFileStatus() {
        return fileStatus;
    }

    public String getFileStatusDescription() {
        return switch (fileStatus) {
            case Download.ERROR -> "ERROR";
            case Download.FINISHED -> "FINISHED";
            case Download.PROGRESS -> "PROGRESS";
            case Download.QUEUED -> "QUEUED";
            default -> "UNKNOWN";
        };
    }

    public void setFileStatus(int status) {
        fileStatus = status;
    }

    public void setFileErrorType(int type){
        fileErrorType = type;
    }

    public String getFileUrl(){
        return fileUrl;
    }

    public void setFileUrl(String url){
        fileUrl = url;
    }

    public String getFileName(){
        return fileName;
    }

    public void setFileName(String name){
        fileName = name;
    }

    public String getFileImg(){
        return fileImg;
    }

    public void setFileImg(String img){
        fileImg = img;
    }

    public String getFileDescription(){
        return fileDescription;
    }

    public void setFileDescription(String desc){
        fileDescription = desc;
    }

    public String getFileMimeType(){
        return fileMimeType;
    }

    public void setFileMimeType(String mimeType){
        fileMimeType = mimeType;
    }

    public boolean getFileIsLive(){
        return fileLive;
    }

    public void setFilelive(boolean live){
        fileLive = live;
    }

    public String getFilePath(){
        return filePath;
    }

    public void setFilePath(String path){
        filePath = path;
    }

    public int getFileProgress(){
        return fileProgress;
    }

    public void setFileProgress(int progress){
        fileProgress = progress;
    }

    public long getFileDate(){
        return fileDate;
    }

    public void setFileDate(long date){
        fileDate = date;
    }

    public long getFileSize(){
        return fileSize;
    }

    public void setFileSize(long size){
        fileSize = size;
    }

    public void setFileEncrypted(boolean encrypted){
        fileEncrypted = encrypted;
    }

    @Override
    public boolean isFileEncrypted() {
        return fileEncrypted;
    }

    @Override
    public boolean isFileSafe() {
        return fileSafe;
    }

    @Override
    public long getThumbnailDuration() {
        return fileThumbnailDuration;
    }

    @Override
    public long getDuration() {
        return fileDuration;
    }

    public void setFileDurationFormatted(String fileDurationFormatted) {
        this.fileDurationFormatted = fileDurationFormatted;
    }

    public void setFileSafe(boolean fileSafe) {
        this.fileSafe = fileSafe;
    }

    public void setFileThumbnailDuration(long duration) {
        this.fileThumbnailDuration = duration;
    }

    public void setFileDuration(long duration) {
        this.fileDuration = duration;
    }

    public DownloadEntity(Download download){
        parseDownload(download);
    }

    public void parseDownload(Download download){
        this.uid = download.getId();
        this.fileParentId = download.getParentId();
        this.fileType = download.getFileType();
        this.fileUrl = download.getFileUrl();
        this.fileName = download.getFileName();
        this.fileDescription = download.getFileDescription();
        this.fileImg = download.getFileImg();
        this.filePath = download.getFilePath();
        this.fileErrorType = download.getFileErrorType();
        this.fileProgress = download.getFileProgress();
        this.fileStatus = download.getFileStatus();
        this.fileDate = download.getFileDate();
        this.fileSize = download.getFileSize();
        this.fileLive = download.getFileIsLive();
        this.fileMimeType = download.getFileMimeType();
        this.fileHeaders = download.getFileHeaders();
        this.fileOriginUrl = download.getOriginUrl();
        this.fileEncrypted = download.isFileEncrypted();
        this.fileThumbnailDuration = download.getThumbnailDuration();
        this.fileDuration = download.getDuration();
        this.fileDurationFormatted = download.getDurationFormatted();
        this.fileSafe = download.isFileSafe();
    }

    public DownloadEntity(){

    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel parcel, int i) {
        parcel.writeInt(uid);
        parcel.writeInt(fileParentId);
        parcel.writeString(fileHeaders);
        parcel.writeInt(fileType);
        parcel.writeString(fileUrl);
        parcel.writeString(fileName);
        parcel.writeString(fileImg);
        parcel.writeString(fileDescription);
        parcel.writeString(filePath);
        parcel.writeString(fileMimeType);
        parcel.writeString(fileOriginUrl);
        parcel.writeInt(fileProgress);
        parcel.writeLong(fileDate);
        parcel.writeLong(fileSize);
        parcel.writeByte((byte) (fileLive ? 1 : 0));
        parcel.writeInt(fileErrorType);
        parcel.writeInt(fileStatus);
        parcel.writeByte((byte) (fileEncrypted ? 1 : 0));
        parcel.writeByte((byte) (fileSafe ? 1 : 0));
        parcel.writeLong(fileThumbnailDuration);
        parcel.writeLong(fileDuration);
        parcel.writeString(fileDurationFormatted);
    }


}
