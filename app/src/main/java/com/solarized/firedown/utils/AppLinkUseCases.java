package com.solarized.firedown.utils;

import static android.provider.Browser.EXTRA_APPLICATION_ID;

import android.app.SearchManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.text.TextUtils;
import android.util.Log;

import com.solarized.firedown.App;

import java.net.URISyntaxException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Utilities for Intents.
 */
public class AppLinkUseCases {

    private static final String TAG = AppLinkUseCases.class.getSimpleName();

    private final static Set<String> HTTP_SUPPORTED_SCHEMES = new HashSet<>(Arrays.asList(
             "http", "https"
    ));

    private final static Set<String> ENGINE_SUPPORTED_SCHEMES = new HashSet<>(Arrays.asList(
            "about", "data", "file", "ftp", "http", "https", "moz-extension", "moz-safe-about", "resource", "view-source", "ws", "wss", "blob"
    ));

    private final static Set<String> ALWAYS_DENY_SCHEMES = new HashSet<>(Arrays.asList(
            "jar", "file", "javascript", "data", "about"
    ));


    private static final String EXTRA_BROWSER_FALLBACK_URL = "browser_fallback_url";

    private static final String MARKET_INTENT_URI_PACKAGE_PREFIX = "market://details?id=";

    private AppLinkUseCases() {
    }


    public static String getIntentUrl(Intent intent){


        String url = null;

        String intentAction = intent.getAction();

        if (Intent.ACTION_SEND.equals(intentAction)) {
            url = intent.getStringExtra(Intent.EXTRA_TEXT);
        } else if (Intent.ACTION_VIEW.equals(intentAction)) {
            url = intent.getDataString();
        } else if (Intent.ACTION_PROCESS_TEXT.equals(intentAction)) {
            url = intent.getStringExtra(Intent.EXTRA_PROCESS_TEXT);
        } else if(Intent.ACTION_WEB_SEARCH.equals(intentAction)){
            String query = intent.getStringExtra(SearchManager.QUERY);
            if(!TextUtils.isEmpty(query)){
                url = query;
            }
        }

        if(TextUtils.isEmpty(url)){
            return null;
        }

        if(!UrlStringUtils.isURLLike(url)){
            url = UrlStringUtils.extractUrlFromText(url);
        }else if(!isEngineSupported(url) && !ContentUriUtils.isContentUri(Uri.parse(url))){
            return null;
        }



        return url;
    }

    public static Intent createBrowsableIntent(String url) {
        Intent intent = safeParseUri(url, Intent.URI_INTENT_SCHEME);

        if (intent == null)
            return null;

        Intent fallbackIntent = safeParseUri(intent.getStringExtra(EXTRA_BROWSER_FALLBACK_URL), 0);

        Intent marketplaceIntent = !isPackageInstalled(App.getAppContext(), intent.getPackage()) ? safeParseUri(MARKET_INTENT_URI_PACKAGE_PREFIX + intent.getPackage(), 0) : null;

        if (marketplaceIntent != null) {
            marketplaceIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            return fallbackIntent != null ? fallbackIntent : marketplaceIntent;
        }

        Intent appIntent = intent.getData() == null || ALWAYS_DENY_SCHEMES.contains(intent.getData().getScheme()) ? null : intent;

        if (appIntent != null) {
            appIntent.addCategory(Intent.CATEGORY_BROWSABLE);
            appIntent.setComponent(null);
            appIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            if (appIntent.getSelector() != null) {
                appIntent.getSelector().addCategory(Intent.CATEGORY_BROWSABLE);
                appIntent.getSelector().setComponent(null);
            }
            appIntent.putExtra(EXTRA_APPLICATION_ID, App.getAppContext().getPackageName());
        }

        return appIntent;

    }

    private static ResolveInfo findDefaultActivity(Intent intent) {
        return App.getAppContext().getPackageManager().resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY);
    }

    public static boolean isPackageInstalled(Context context, String packageName) {
        final PackageManager packageManager = context.getPackageManager();
        Intent intent = packageManager.getLaunchIntentForPackage(packageName);
        if (intent == null) {
            return false;
        }
        List<ResolveInfo> list = packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY);
        return !list.isEmpty();
    }

    private static Intent safeParseUri(String uri, int flags) {
        if(uri == null)
            return null;
        try {
            Intent intent = Intent.parseUri(uri, flags);
            if (App.getAppContext().getPackageName() != null && Objects.equals(App.getAppContext().getPackageName(), intent.getPackage())) {
                // Ignore intents that would open in the browser itself
                return null;
            } else {
                return intent;
            }
        } catch (URISyntaxException | NumberFormatException e) {
            Log.e(TAG, "failed to parse URI", e);
            return null;
        }
    }


    // We create a separate method to better encapsulate the @TargetApi use.
    private static void nullIntentSelector(final Intent intent) {
        intent.setSelector(null);
    }

    public static boolean isEngineSupported(String url) {
        if(TextUtils.isEmpty(url))
            return false;
        return ENGINE_SUPPORTED_SCHEMES.contains(Uri.parse(url).getScheme());
    }

    public static boolean isEngineDenied(String url){
        if(TextUtils.isEmpty(url))
            return true;
        return ALWAYS_DENY_SCHEMES.contains(Uri.parse(url).getScheme());
    }

    public static boolean isHttpSupported(String url){
        if(TextUtils.isEmpty(url))
            return false;
        return HTTP_SUPPORTED_SCHEMES.contains(Uri.parse(url).getScheme());
    }
}
