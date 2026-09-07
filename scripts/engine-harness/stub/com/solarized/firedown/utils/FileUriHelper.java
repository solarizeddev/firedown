package com.solarized.firedown.utils;
/** Identity transforms: filename shaping has its own trace; the harness tests the queue. */
public class FileUriHelper {
    public static String decodeName(String name) { return name; }
    public static String sanitizeFileName(String name) { return name; }
    public static String checkFileExtension(String name, String mime) { return name; }
}
