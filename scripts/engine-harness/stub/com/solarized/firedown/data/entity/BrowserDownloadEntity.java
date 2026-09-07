package com.solarized.firedown.data.entity;
/** The legacy intent payload; the engine only ever converts it via DownloadRequest.from. */
public class BrowserDownloadEntity {
    public String url, name, mimeType;
    public BrowserDownloadEntity(String url, String name, String mimeType) { this.url = url; this.name = name; this.mimeType = mimeType; }
}
