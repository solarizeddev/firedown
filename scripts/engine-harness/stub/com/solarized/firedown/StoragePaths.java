package com.solarized.firedown;
import android.content.Context;
public class StoragePaths {
    /** Set by the harness to a scratch directory. */
    public static volatile String downloadDir = System.getProperty("java.io.tmpdir");
    public static String getDownloadPath(Context context) { return downloadDir; }
    /** The app-private safe folder a vault download is written into. */
    public static volatile String safeDir = System.getProperty("java.io.tmpdir") + "/safe";
    public static String getSafePath(Context context) { return safeDir; }
}
