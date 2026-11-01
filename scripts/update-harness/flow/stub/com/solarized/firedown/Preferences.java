package com.solarized.firedown;
public class Preferences {
    public static final String UPDATE_APK = "firedown.apk";
    public static java.io.File getUpdateApkFile(android.content.Context c) { return new java.io.File(c.getExternalFilesDir(null), UPDATE_APK); }
}
