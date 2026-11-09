package com.solarized.firedown;
public class UpdateNotification {
    public static int prompts;
    /** Times the "Tap to update" notification (id 666) was taken down. */
    public static int cancelled;
    public static void showInstallPrompt(android.content.Context c, String v) { prompts++; }
    public static void cancelInstallProgress(android.content.Context c) { cancelled++; }
}
