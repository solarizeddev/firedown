package com.solarized.firedown.manager;
import com.solarized.firedown.data.entity.BrowserDownloadEntity;
public class DownloadRequest {
    private final String url, name, mimeType;
    private final boolean saveToVault;
    public DownloadRequest(String url, String name, String mimeType, boolean saveToVault) {
        this.url = url; this.name = name; this.mimeType = mimeType; this.saveToVault = saveToVault;
    }
    public static DownloadRequest from(BrowserDownloadEntity e) { return new DownloadRequest(e.url, e.name, e.mimeType, false); }
    public String getUrl() { return url; }
    public String getName() { return name; }
    public String getMimeType() { return mimeType; }
    public boolean isFileNameForced() { return false; }
    public boolean isSaveToVault() { return saveToVault; }
    public int getFileType() { return 0; }
}
