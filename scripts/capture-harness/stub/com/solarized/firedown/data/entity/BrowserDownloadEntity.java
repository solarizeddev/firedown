package com.solarized.firedown.data.entity;
import com.solarized.firedown.ffmpegutils.FFmpegEntity;
import java.util.ArrayList;
/** Test stand-in: just the accessors the Captured-sheet logic and the capture
 *  repository read and write. */
public class BrowserDownloadEntity implements Comparable<BrowserDownloadEntity> {
  static long clock;
  public int uid, tabId, visitId, pendingLoadSeq, type; public String mime, url, origin, name = "n", description; public boolean audio, incognito, variants, namedByPage, forced; public long fileLength, durationUs, pHash;
  public long creationTime = ++clock;
  public ArrayList<FFmpegEntity> streams = new ArrayList<>(); public ArrayList<FFmpegTagEntity> tags = new ArrayList<>();
  public BrowserDownloadEntity(int uid, String mime, String url, String origin) { this.uid = uid; this.mime = mime; this.url = url; this.origin = origin; }
  public BrowserDownloadEntity(BrowserDownloadEntity o) {
    uid = o.uid; tabId = o.tabId; visitId = o.visitId; pendingLoadSeq = o.pendingLoadSeq; type = o.type; mime = o.mime; url = o.url;
    origin = o.origin; name = o.name; description = o.description; namedByPage = o.namedByPage; forced = o.forced; audio = o.audio; incognito = o.incognito; variants = o.variants; fileLength = o.fileLength;
    durationUs = o.durationUs; pHash = o.pHash; creationTime = o.creationTime; streams = new ArrayList<>(o.streams); tags = o.tags;
  }
  public int getUid() { return uid; } public String getMimeType() { return mime; } public String getFileUrl() { return url; }
  public String getFileOrigin() { return origin; } public boolean isAudio() { return audio; } public boolean isIncognito() { return incognito; }
  public ArrayList<FFmpegEntity> getStreams() { return streams; } public ArrayList<FFmpegTagEntity> getTags() { return tags; }
  public String getFileName() { return name; } public String getFileDuration() { return null; } public long getDurationTime() { return durationUs; }
  public long getFileLength() { return fileLength; }
  public String getFileHeaders() { return null; } public boolean getHasVariants() { return variants; }
  public int getTabId() { return tabId; } public int getType() { return type; } public long getPHash() { return pHash; }
  public int getVisitId() { return visitId; } public void setVisitId(int v) { visitId = v; } public int getPendingLoadSeq() { return pendingLoadSeq; }
  public long getCreationTime() { return creationTime; }
  public void setFileName(String n) { name = n; } public String getFileDescription() { return description; }
  public void setFileDescription(String d) { description = d; } public boolean isNamedByPage() { return namedByPage; }
  public void setNamedByPage(boolean b) { namedByPage = b; } public boolean isFileNameForced() { return forced; }
  public void setStreams(ArrayList<FFmpegEntity> s) { streams = s; } public void setHasVariants(boolean v) { variants = v; }
  public void setFileLength(long l) { fileLength = l; } public void setTags(ArrayList<FFmpegTagEntity> t) { tags = t; }
  @Override public int compareTo(BrowserDownloadEntity o) { return Long.compare(creationTime, o.creationTime); }
}
