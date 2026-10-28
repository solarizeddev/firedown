package org.apache.commons.io;
/** Fallback when no commons-io jar is on disk: the four methods the code
 *  under test calls, with commons-io's semantics (getPath drops the prefix,
 *  "/a/b/c.txt" -> "a/b/"). */
public class FilenameUtils {
    private static int lastSep(String f) { return Math.max(f.lastIndexOf('/'), f.lastIndexOf('\\')); }
    private static int prefixLength(String f) { return f.startsWith("/") ? 1 : 0; }
    public static String getName(String f) { if (f == null) return null; return f.substring(lastSep(f) + 1); }
    public static String getBaseName(String f) { String n = getName(f); if (n == null) return null; int d = n.lastIndexOf('.'); return d < 0 ? n : n.substring(0, d); }
    public static String getExtension(String f) { String n = getName(f); if (n == null) return null; int d = n.lastIndexOf('.'); return d < 0 ? "" : n.substring(d + 1); }
    public static String getPath(String f) { if (f == null) return null; int p = prefixLength(f); int s = lastSep(f); return s < p ? "" : f.substring(p, s + 1); }
    public static String getFullPath(String f) { if (f == null) return null; int s = lastSep(f); return s < 0 ? "" : f.substring(0, s + 1); }
}
