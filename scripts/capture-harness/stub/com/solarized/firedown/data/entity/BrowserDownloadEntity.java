package com.solarized.firedown.data.entity;
import com.solarized.firedown.ffmpegutils.FFmpegEntity;
import java.util.ArrayList;
/** Test stand-in: just the accessors the Captured-sheet logic reads. */
public class BrowserDownloadEntity {
  public int uid; public String mime, url, origin, name = "n"; public boolean audio, incognito, variants; public long fileLength, durationUs;
  public ArrayList<FFmpegEntity> streams = new ArrayList<>(); public ArrayList<FFmpegTagEntity> tags = new ArrayList<>();
  public BrowserDownloadEntity(int uid, String mime, String url, String origin) { this.uid = uid; this.mime = mime; this.url = url; this.origin = origin; }
  public int getUid() { return uid; } public String getMimeType() { return mime; } public String getFileUrl() { return url; }
  public String getFileOrigin() { return origin; } public boolean isAudio() { return audio; } public boolean isIncognito() { return incognito; }
  public ArrayList<FFmpegEntity> getStreams() { return streams; } public ArrayList<FFmpegTagEntity> getTags() { return tags; }
  public String getFileName() { return name; } public String getFileDuration() { return null; } public long getDurationTime() { return durationUs; }
  public long getFileLength() { return fileLength; }
  public String getFileHeaders() { return null; } public boolean getHasVariants() { return variants; }
}
