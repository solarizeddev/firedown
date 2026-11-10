package com.solarized.firedown;
/** The install slot, as UpdateDownloader asks about it. */
public final class UpdateInstaller {
    public static boolean installing;
    public static boolean isInstalling() { return installing; }
}
