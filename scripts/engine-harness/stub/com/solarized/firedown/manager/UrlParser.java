package com.solarized.firedown.manager;
/** parseFilePath must yield a NEW path each call so the collision loop terminates. */
public class UrlParser {
    public static String parseFilePath(String filePath) {
        int dot = filePath.lastIndexOf('.');
        int slash = filePath.lastIndexOf('/');
        if (dot > slash) return filePath.substring(0, dot) + "-1" + filePath.substring(dot);
        return filePath + "-1";
    }
    public static String decodeUrl(String url) { return url; }
}
