package com.solarized.firedown.data.entity;
import com.solarized.firedown.data.Download;
/** The fields the engine and the fake task read/write. Snapshot-copyable like the Room entity. */
public class DownloadEntity {
    private int id;
    private String fileUrl = "", fileName = "", filePath, mimeType = "";
    private int fileStatus = Download.PROGRESS, fileErrorType, fileProgress;
    private boolean fileSafe;
    public DownloadEntity() {}
    public DownloadEntity(DownloadEntity o) { parseDownload(o); }
    public void parseDownload(DownloadEntity o) {
        id = o.id; fileUrl = o.fileUrl; fileName = o.fileName; filePath = o.filePath; mimeType = o.mimeType;
        fileStatus = o.fileStatus; fileErrorType = o.fileErrorType; fileProgress = o.fileProgress; fileSafe = o.fileSafe;
    }
    public int getId() { return id; }
    public void setId(int id) { this.id = id; }
    public String getFileUrl() { return fileUrl; }
    public void setFileUrl(String u) { fileUrl = u; }
    public String getFileName() { return fileName; }
    public void setFileName(String n) { fileName = n; }
    public String getFilePath() { return filePath; }
    public void setFilePath(String p) { filePath = p; }
    public String getFileMimeType() { return mimeType; }
    public void setFileMimeType(String m) { mimeType = m; }
    public int getFileStatus() { return fileStatus; }
    public void setFileStatus(int s) { fileStatus = s; }
    public int getFileErrorType() { return fileErrorType; }
    public void setFileErrorType(int t) { fileErrorType = t; }
    public int getFileProgress() { return fileProgress; }
    public void setFileProgress(int p) { fileProgress = p; }
    public boolean isFileSafe() { return fileSafe; }
    public void setFileSafe(boolean s) { fileSafe = s; }
    @Override public String toString() { return "Entity#" + id + "{status=" + fileStatus + ",err=" + fileErrorType + ",path=" + filePath + "}"; }
}
