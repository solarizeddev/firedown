package com.solarized.firedown.ffmpegutils;
/** Test stand-in: the accessors the Captured-sheet logic, CaptureFacts and the
 *  rendition fold read and write. */
public class FFmpegEntity {
  private String url;
  public String info, videoCodec; public int width, height, bitrate = -1; public long size; public boolean audioOnly;
  public FFmpegEntity(String url) { this.url = url; }
  public FFmpegEntity(String info, int width, int height) { this(""); this.info = info; this.width = width; this.height = height; }
  public FFmpegEntity(FFmpegEntity o) { url = o.url; info = o.info; videoCodec = o.videoCodec; width = o.width; height = o.height;
    bitrate = o.bitrate; size = o.size; audioOnly = o.audioOnly; }
  public String getStreamUrl() { return url; } public void setStreamUrl(String u) { url = u; }
  public String getInfo() { return info; } public int getWidth() { return width; } public int getHeight() { return height; }
  public int getBitrate() { return bitrate; } public long getByteSize() { return size; } public void setByteSize(long s) { size = s; }
  public String getVideoCodec() { return videoCodec; } public boolean isAudioOnly() { return audioOnly; }
}
