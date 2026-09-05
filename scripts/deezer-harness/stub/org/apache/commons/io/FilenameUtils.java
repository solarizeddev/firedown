package org.apache.commons.io;
public class FilenameUtils {
    public static String getExtension(String n){ int i = n.lastIndexOf('.'); return i < 0 ? "" : n.substring(i + 1); }
    public static String getBaseName(String n){ int i = n.lastIndexOf('.'); return i < 0 ? n : n.substring(0, i); }
}
