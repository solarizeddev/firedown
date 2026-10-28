package com.solarized.firedown;
import android.content.Context;
import java.io.File;
/** The download folder and the app-private safe folder, pointed at the
 *  harness's scratch directories. ensure* make them, like forceMkdir. */
public class StoragePaths {
    public static volatile String downloadDir = System.getProperty("java.io.tmpdir") + "/task-harness-dl";
    public static volatile String safeDir = System.getProperty("java.io.tmpdir") + "/task-harness-safe";
    public static String getDownloadPath(Context context) { return downloadDir; }
    public static String getSafePath(Context context) { return safeDir; }
    public static void ensureDownloadPath(Context context) { new File(downloadDir).mkdirs(); }
    public static void ensureSafePath(Context context) { new File(safeDir).mkdirs(); }
}
