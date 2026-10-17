package com.solarized.firedown.ffmpegutils;
/** Test stand-in: the accessors the Captured-sheet logic and CaptureFacts read. */
public class FFmpegEntity {
  private final String url;
  public String info, videoCodec; public int width, height, bitrate = -1; public long size; public boolean audioOnly;
  public FFmpegEntity(String url) { this.url = url; }
  public FFmpegEntity(String info, int width, int height) { this(""); this.info = info; this.width = width; this.height = height; }
  public String getStreamUrl() { return url; }
  public String getInfo() { return info; } public int getWidth() { return width; } public int getHeight() { return height; }
  public int getBitrate() { return bitrate; } public long getByteSize() { return size; }
  public String getVideoCodec() { return videoCodec; } public boolean isAudioOnly() { return audioOnly; }
}
