package com.solarized.firedown.applinks;

import static android.provider.Browser.EXTRA_APPLICATION_ID;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.solarized.firedown.BuildConfig;
import com.solarized.firedown.utils.UrlStringUtils;

import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Finds where an app link leads on this phone: the app that opens it (never
 * Firedown, never another browser for a web address), the app's name, and
 * the link's web version. Modelled on Firefox's AppLinksUseCases.
 *
 * <p>Only links the NavigationDelegate denied reach here: non-web schemes
 * ({@code intent://}, {@code market://}, {@code tiktok://}, {@code mailto:}…)
 * and Play Store listings.</p>
 */
public final class AppLinkResolver {

    private static final String TAG = "AppLinkResolver";

    public static final String PLAY_STORE_PACKAGE = "com.android.vending";

    private static final String MARKET_DETAILS = "market://details?id=";
    private static final String PLAY_LISTING = "https://play.google.com/store/apps/details?id=";
    private static final String EXTRA_BROWSER_FALLBACK_URL = "browser_fallback_url";
    /** The system's "open with" chooser answers resolveActivity when no app is the default. */
    private static final String ANDROID_RESOLVER_PACKAGE = "android";

    /** Schemes no page may hand to an app (Firefox's list). */
    private static final Set<String> DENIED_SCHEMES = new HashSet<>(Arrays.asList(
            "jar", "file", "javascript", "data", "about", "content", "fido"));

    private AppLinkResolver() {
    }

    /** Null when the link can't be read as an app link at all. */
    @Nullable
    public static AppLinkTarget resolve(@NonNull Context context, @NonNull String uri) {
        PackageManager pm = context.getPackageManager();
        String self = context.getPackageName();
        boolean comms = UrlStringUtils.isUserCommsScheme(uri);

        if (UrlStringUtils.isHttpOrHttps(uri)) {
            // A Play Store listing (every other web link loads in the tab).
            Intent store = browsable(new Intent(Intent.ACTION_VIEW, Uri.parse(uri)), self);
            store.setPackage(PLAY_STORE_PACKAGE);
            return storeTarget(pm, store, uri);
        }

        Intent intent = parse(uri, self);
        if (intent == null) {
            return null;
        }
        String fallback = webOnly(intent.getStringExtra(EXTRA_BROWSER_FALLBACK_URL));
        String pkg = intent.getPackage();

        if (pkg != null && !isInstalled(pm, pkg)) {
            // The app the link names isn't here. Its own web page wins; with
            // none, it leads to the app's store listing.
            if (fallback != null && !UrlStringUtils.isPlayStoreUrl(fallback)) {
                return new AppLinkTarget(null, null, null, pkg, fallback, false, comms);
            }
            Intent store = browsable(new Intent(Intent.ACTION_VIEW, Uri.parse(MARKET_DETAILS + pkg)), self);
            store.setPackage(PLAY_STORE_PACKAGE);
            return storeTarget(pm, store, PLAY_LISTING + pkg);
        }

        Uri data = intent.getData();
        String scheme = data != null ? data.getScheme() : null;
        if (data == null || scheme == null || DENIED_SCHEMES.contains(scheme.toLowerCase(Locale.ROOT))) {
            String web = fallback != null && !UrlStringUtils.isPlayStoreUrl(fallback) ? fallback : null;
            String uriScheme = Uri.parse(uri).getScheme();
            String key = pkg != null ? pkg : String.valueOf(uriScheme).toLowerCase(Locale.ROOT);
            return new AppLinkTarget(null, null, null, key, web, false, comms);
        }
        String dataString = data.toString();
        boolean webData = UrlStringUtils.isHttpOrHttps(dataString);
        boolean toStore = "market".equalsIgnoreCase(scheme) || UrlStringUtils.isPlayStoreUrl(dataString);
        String webUrl = webVersion(fallback, data, webData);
        String key = pkg != null ? pkg : scheme.toLowerCase(Locale.ROOT);

        browsable(intent, self);
        ResolveInfo chosen = null;
        boolean several = false;
        ResolveInfo ri = resolveDefault(pm, intent);
        String riPackage = packageOf(ri);
        if (ANDROID_RESOLVER_PACKAGE.equals(riPackage)) {
            // No default: keep the apps that aren't Firedown or (for a web
            // address) a browser. One left is the app; several, Android asks.
            List<ResolveInfo> apps = candidates(pm, intent, self, webData);
            if (apps.size() == 1) {
                chosen = apps.get(0);
                ActivityInfo ai = chosen.activityInfo;
                intent.setComponent(new ComponentName(ai.packageName, ai.name));
            } else if (apps.size() > 1) {
                several = true;
            }
        } else if (ri != null && !self.equals(riPackage) && !(webData && isBrowser(pm, riPackage))) {
            chosen = ri;
        }

        if (chosen == null && !several) {
            return new AppLinkTarget(null, null, null, key, webUrl, toStore, comms);
        }
        String appName = chosen != null ? labelOf(pm, chosen) : null;
        String appPackage = chosen != null ? packageOf(chosen) : null;
        if (appPackage != null) {
            key = appPackage;
        }
        if (PLAY_STORE_PACKAGE.equals(appPackage)) {
            toStore = true;
        }
        return new AppLinkTarget(intent, appName, appPackage, key, webUrl, toStore, comms);
    }

    /**
     * The page a link stands for: the fallback the page declared, else the
     * link's own address when it is a web one ({@code intent://host#Intent;
     * scheme=https;…}), else a {@code market://} link's store listing.
     */
    @Nullable
    private static String webVersion(@Nullable String fallback, @NonNull Uri data, boolean webData) {
        if (fallback != null && !UrlStringUtils.isPlayStoreUrl(fallback)) {
            return fallback;
        }
        if (webData) {
            return data.toString();
        }
        if ("market".equalsIgnoreCase(data.getScheme())) {
            String id = data.getQueryParameter("id");
            if (!TextUtils.isEmpty(id)) {
                return PLAY_LISTING + Uri.encode(id);
            }
        }
        return null;
    }

    @NonNull
    private static AppLinkTarget storeTarget(@NonNull PackageManager pm, @NonNull Intent store,
                                             @NonNull String listingUrl) {
        ResolveInfo ri = resolveDefault(pm, store);
        if (ri == null) {
            return new AppLinkTarget(null, null, null, PLAY_STORE_PACKAGE, listingUrl, true, false);
        }
        return new AppLinkTarget(store, labelOf(pm, ri), PLAY_STORE_PACKAGE, PLAY_STORE_PACKAGE,
                listingUrl, true, false);
    }

    /** Category BROWSABLE, no component, no way back into Firedown. */
    @NonNull
    private static Intent browsable(@NonNull Intent intent, @NonNull String self) {
        intent.addCategory(Intent.CATEGORY_BROWSABLE);
        intent.setComponent(null);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        Intent selector = intent.getSelector();
        if (selector != null) {
            selector.addCategory(Intent.CATEGORY_BROWSABLE);
            selector.setComponent(null);
        }
        intent.putExtra(EXTRA_APPLICATION_ID, self);
        return intent;
    }

    @Nullable
    private static Intent parse(@NonNull String uri, @NonNull String self) {
        try {
            Intent intent = Intent.parseUri(uri, Intent.URI_INTENT_SCHEME);
            if (self.equals(intent.getPackage())) {
                return null;
            }
            return intent;
        } catch (URISyntaxException | RuntimeException e) {
            if (BuildConfig.DEBUG) {
                Log.d(TAG, "not an app link", e);
            }
            return null;
        }
    }

    @Nullable
    private static String webOnly(@Nullable String url) {
        return UrlStringUtils.isHttpOrHttps(url) ? url : null;
    }

    private static boolean isInstalled(@NonNull PackageManager pm, @NonNull String pkg) {
        try {
            pm.getPackageInfo(pkg, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    @Nullable
    private static ResolveInfo resolveDefault(@NonNull PackageManager pm, @NonNull Intent intent) {
        try {
            return pm.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY);
        } catch (RuntimeException e) {
            return null;
        }
    }

    @NonNull
    private static List<ResolveInfo> candidates(@NonNull PackageManager pm, @NonNull Intent intent,
                                                @NonNull String self, boolean webData) {
        List<ResolveInfo> out = new ArrayList<>();
        List<ResolveInfo> all;
        try {
            all = pm.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY);
        } catch (RuntimeException e) {
            return out;
        }
        for (ResolveInfo info : all) {
            String pkg = packageOf(info);
            if (pkg == null || pkg.equals(self) || (webData && isBrowser(pm, pkg))) {
                continue;
            }
            out.add(info);
        }
        return out;
    }

    /** A browser opens any web address, so it answers for one it has never seen. */
    private static boolean isBrowser(@NonNull PackageManager pm, @Nullable String pkg) {
        if (pkg == null) {
            return false;
        }
        Intent probe = new Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com/"));
        probe.addCategory(Intent.CATEGORY_BROWSABLE);
        probe.setPackage(pkg);
        try {
            return !pm.queryIntentActivities(probe, 0).isEmpty();
        } catch (RuntimeException e) {
            return false;
        }
    }

    @Nullable
    private static String packageOf(@Nullable ResolveInfo info) {
        if (info == null || info.activityInfo == null) {
            return null;
        }
        return info.activityInfo.packageName;
    }

    @Nullable
    private static String labelOf(@NonNull PackageManager pm, @NonNull ResolveInfo info) {
        if (info.activityInfo == null || info.activityInfo.applicationInfo == null) {
            return null;
        }
        CharSequence label = pm.getApplicationLabel(info.activityInfo.applicationInfo);
        return TextUtils.isEmpty(label) ? null : label.toString();
    }
}
