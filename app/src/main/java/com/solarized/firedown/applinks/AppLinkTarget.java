package com.solarized.firedown.applinks;

import android.content.Intent;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Where an app link leads on this phone, as {@link AppLinkResolver} found it:
 * the app that opens it, its name, the web version Firedown can load
 * instead, and the facts {@link AppLinkPolicy} decides on.
 */
public final class AppLinkTarget {

    /** Opens the app; null when nothing on the phone (but a browser) opens the link. */
    @Nullable
    public final Intent appIntent;

    /** The app's name; null when several apps can open it (Android asks which). */
    @Nullable
    public final String appName;

    /** The app's package, for its icon; null when several apps can open it. */
    @Nullable
    public final String packageName;

    /** What a tab remembers this app by: its package, else the link's scheme. */
    @NonNull
    public final String key;

    /** An http(s) page that stands for the link; null when there is none. */
    @Nullable
    public final String webUrl;

    /** The link leads to the Play Store. */
    public final boolean toStore;

    /** mailto:, tel:, sms:, geo: and the like. */
    public final boolean commsScheme;

    AppLinkTarget(@Nullable Intent appIntent, @Nullable String appName, @Nullable String packageName,
                  @NonNull String key, @Nullable String webUrl, boolean toStore, boolean commsScheme) {
        this.appIntent = appIntent;
        this.appName = appName;
        this.packageName = packageName;
        this.key = key;
        this.webUrl = webUrl;
        this.toStore = toStore;
        this.commsScheme = commsScheme;
    }

    public boolean hasApp() {
        return appIntent != null;
    }
}
