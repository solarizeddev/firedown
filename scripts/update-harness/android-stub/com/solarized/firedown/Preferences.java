package com.solarized.firedown;
public class Preferences {
    public static final String UPDATE_APK = "firedown.apk";
    public static final String UPDATE_URL = "https://www.firedown.app/status.json";
    public static final String[] UPDATE_URL_FALLBACKS = {UPDATE_URL};
    public static java.io.File getUpdateApkFile(android.content.Context c) { return null; }
}
