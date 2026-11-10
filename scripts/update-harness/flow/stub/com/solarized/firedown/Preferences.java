package com.solarized.firedown;
public class Preferences {
    public static final String UPDATE_APK = "firedown.apk";
    public static java.io.File getUpdateApkFile(android.content.Context c) {
        java.io.File dir = c.getExternalFilesDir(null);
        if (dir == null) {
            dir = c.getFilesDir();
        }
        return new java.io.File(dir, UPDATE_APK);
    }
}
