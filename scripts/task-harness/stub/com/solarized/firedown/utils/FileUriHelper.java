package com.solarized.firedown.utils;
/** The constants the tasks use, same values as the app's; the mime lookup
 *  is a reduced table (DecompressTask labels extracted files with it). */
public class FileUriHelper {
    public static final String MIMETYPE_JPEG = "image/jpeg";
    public static final String MIMETYPE_GIF = "image/gif";
    public static final String AUDIO_AAC = "audio/aac";
    public static final String MIMETYPE_ZIP_2 = "application/zip";
    public static final String MIMETYPE_BINARY = "application/octet-stream";
    public static String getMimeTypeFromFile(String url) {
        String u = url.toLowerCase();
        if (u.endsWith(".mp4")) return "video/mp4";
        if (u.endsWith(".txt")) return "text/plain";
        if (u.endsWith(".jpg")) return MIMETYPE_JPEG;
        return MIMETYPE_BINARY;
    }
}
