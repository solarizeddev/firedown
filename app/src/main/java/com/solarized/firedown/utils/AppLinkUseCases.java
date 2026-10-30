package com.solarized.firedown.utils;

import android.app.SearchManager;
import android.content.Intent;
import android.net.Uri;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.Nullable;

import com.solarized.firedown.App;

import java.net.URISyntaxException;
import java.util.Arrays;
import java.util.HashSet;
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

    /**
     * The web page an {@code intent://} "open in app" link stands for, or
     * {@code null} when it stands for none. A tab can never load an
     * {@code intent:} URL — the NavigationDelegate denies every non-web scheme
     * — so "open link in new tab" on one used to create a tab that sat on
     * about:blank forever (Play Store's own "Open in app" banner is such a
     * link). Chrome resolves the same gesture to the link's web form, in this
     * order: the {@code S.browser_fallback_url} the page declared for
     * app-less browsers, else the data URI the intent itself carries when its
     * {@code scheme=} is http(s) (an {@code intent://host/path#Intent;scheme=
     * https;…;end} IS {@code https://host/path}). Anything else — a custom
     * scheme with no fallback — has no page to show and returns null.
     */
    @Nullable
    public static String webUrlForAppLink(String url) {
        Intent intent = safeParseUri(url, Intent.URI_INTENT_SCHEME);
        if (intent == null) {
            return null;
        }
        String fallback = intent.getStringExtra(EXTRA_BROWSER_FALLBACK_URL);
        if (UrlStringUtils.isHttpOrHttps(fallback)) {
            return fallback;
        }
        Uri data = intent.getData();
        if (data != null && UrlStringUtils.isHttpOrHttps(data.toString())) {
            return data.toString();
        }
        return null;
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
