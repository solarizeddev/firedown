package com.solarized.firedown.data.entity;
import android.os.Parcelable;
import com.solarized.firedown.data.Download;
/** The fields the seven tasks read and write. Parcelable: the service gets a
 *  copy, as it does through a real Intent. */
public class DownloadEntity implements Parcelable {
    private int id;
    private String fileName, filePath, mimeType, originUrl, img, durationFormatted, resolution;
    private long fileSize, fileDate, duration, thumbnailDuration;
    private int fileStatus = Download.FINISHED;
    private boolean fileSafe, encrypted, thumbnailUnavailable;
    public DownloadEntity() {}
    public DownloadEntity(DownloadEntity o) {
        id = o.id; fileName = o.fileName; filePath = o.filePath; mimeType = o.mimeType; originUrl = o.originUrl; img = o.img;
        durationFormatted = o.durationFormatted; resolution = o.resolution; fileSize = o.fileSize; fileDate = o.fileDate;
        duration = o.duration; thumbnailDuration = o.thumbnailDuration; fileStatus = o.fileStatus; fileSafe = o.fileSafe;
        encrypted = o.encrypted; thumbnailUnavailable = o.thumbnailUnavailable;
    }
    @Override public Parcelable parcelCopy() { return new DownloadEntity(this); }
    public int getId() { return id; }
    public void setId(int v) { id = v; }
    public String getFileName() { return fileName; }
    public void setFileName(String v) { fileName = v; }
    public String getFilePath() { return filePath; }
    public void setFilePath(String v) { filePath = v; }
    public String getFileMimeType() { return mimeType; }
    public void setFileMimeType(String v) { mimeType = v; }
    public String getOriginUrl() { return originUrl; }
    public void setFileOriginUrl(String v) { originUrl = v; }
    public String getFileImg() { return img; }
    public void setFileImg(String v) { img = v; }
    public String getDurationFormatted() { return durationFormatted; }
    public void setFileDurationFormatted(String v) { durationFormatted = v; }
    public String getFileResolution() { return resolution; }
    public void setFileResolution(String v) { resolution = v; }
    public long getFileSize() { return fileSize; }
    public void setFileSize(long v) { fileSize = v; }
    public long getFileDate() { return fileDate; }
    public void setFileDate(long v) { fileDate = v; }
    public long getDuration() { return duration; }
    public void setFileDuration(long v) { duration = v; }
    public long getThumbnailDuration() { return thumbnailDuration; }
    public void setFileThumbnailDuration(long v) { thumbnailDuration = v; }
    public int getFileStatus() { return fileStatus; }
    public void setFileStatus(int v) { fileStatus = v; }
    public boolean isFileSafe() { return fileSafe; }
    public void setFileSafe(boolean v) { fileSafe = v; }
    public boolean isFileEncrypted() { return encrypted; }
    public void setFileEncrypted(boolean v) { encrypted = v; }
    public boolean isFileThumbnailUnavailable() { return thumbnailUnavailable; }
    public void setFileThumbnailUnavailable(boolean v) { thumbnailUnavailable = v; }
    @Override public String toString() { return "Entity#" + id + "{" + filePath + ", status=" + fileStatus + ", mime=" + mimeType + ", safe=" + fileSafe + "}"; }
}
