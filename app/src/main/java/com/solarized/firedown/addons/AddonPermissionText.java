package com.solarized.firedown.addons;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;

import com.solarized.firedown.R;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

/** The words for {@link AddonPermissions}' lines and data-collection kinds. */
public final class AddonPermissionText {

    private AddonPermissionText() {}

    @NonNull
    public static List<String> lines(@NonNull Context context,
                                     @Nullable Collection<String> permissions,
                                     @Nullable Collection<String> origins) {
        List<String> out = new ArrayList<>();
        for (AddonPermissions.Line line : AddonPermissions.describe(permissions, origins)) {
            String text = text(context, line);
            if (text != null) {
                out.add(text);
            }
        }
        return out;
    }

    /** One optional API permission, or null when Firefox shows no words for it. */
    @Nullable
    public static String permission(@NonNull Context context, @NonNull String permission) {
        int res = apiString(permission);
        return res == 0 ? null : context.getString(res);
    }

    /** One optional site pattern: "Access your data for example.com", or all sites. */
    @Nullable
    public static String origin(@NonNull Context context, @NonNull String origin) {
        List<AddonPermissions.Line> lines = AddonPermissions.describe(null, Collections.singletonList(origin));
        return lines.isEmpty() ? null : text(context, lines.get(0));
    }

    /** One optional data-collection kind: "Share bookmarks with the developer". */
    @Nullable
    public static String data(@NonNull Context context, @NonNull String kind) {
        int res = dataString(kind);
        return res == 0 ? null : context.getString(R.string.addon_optional_data, context.getString(res));
    }

    @Nullable
    private static String text(@NonNull Context context, @NonNull AddonPermissions.Line line) {
        switch (line.kind) {
            case AddonPermissions.ALL_SITES:
                return context.getString(R.string.addon_perm_all_sites);
            case AddonPermissions.SITE:
                return context.getString(R.string.addon_perm_site, line.arg);
            case AddonPermissions.DOMAIN:
                return context.getString(R.string.addon_perm_domain, line.arg);
            case AddonPermissions.MORE_SITES:
                return context.getResources().getQuantityString(
                        R.plurals.addon_perm_more_sites, line.count, line.count);
            case AddonPermissions.MORE_DOMAINS:
                return context.getResources().getQuantityString(
                        R.plurals.addon_perm_more_domains, line.count, line.count);
            case AddonPermissions.API:
                int res = apiString(line.arg);
                return res == 0 ? null : context.getString(res);
            default:
                return null;
        }
    }

    @StringRes
    private static int apiString(@Nullable String permission) {
        if (permission == null) {
            return 0;
        }
        switch (permission) {
            case "bookmarks": return R.string.addon_perm_bookmarks;
            case "browserSettings": return R.string.addon_perm_browser_settings;
            case "browsingData": return R.string.addon_perm_browsing_data;
            case "clipboardRead": return R.string.addon_perm_clipboard_read;
            case "clipboardWrite": return R.string.addon_perm_clipboard_write;
            case "declarativeNetRequest": return R.string.addon_perm_dnr;
            case "declarativeNetRequestFeedback": return R.string.addon_perm_dnr_feedback;
            case "devtools": return R.string.addon_perm_devtools;
            case "downloads": return R.string.addon_perm_downloads;
            case "downloads.open": return R.string.addon_perm_downloads_open;
            case "find": return R.string.addon_perm_find;
            case "geolocation": return R.string.addon_perm_geolocation;
            case "history": return R.string.addon_perm_history;
            case "management": return R.string.addon_perm_management;
            case "nativeMessaging": return R.string.addon_perm_native_messaging;
            case "notifications": return R.string.addon_perm_notifications;
            case "pkcs11": return R.string.addon_perm_pkcs11;
            case "privacy": return R.string.addon_perm_privacy;
            case "proxy": return R.string.addon_perm_proxy;
            case "sessions": return R.string.addon_perm_sessions;
            case "tabHide": return R.string.addon_perm_tab_hide;
            case "tabs": return R.string.addon_perm_tabs;
            case "topSites": return R.string.addon_perm_history;
            case "trialML": return R.string.addon_perm_trial_ml;
            case "userScripts": return R.string.addon_perm_user_scripts;
            case "webNavigation": return R.string.addon_perm_web_navigation;
            default: return 0;
        }
    }

    /**
     * "The developer says it collects: X, Y" — or that it collects nothing.
     * Null when the add-on predates the declaration (nothing was said).
     */
    @Nullable
    public static String dataCollection(@NonNull Context context, @Nullable Collection<String> declared) {
        if (declared == null || declared.isEmpty()) {
            return null;
        }
        List<String> kinds = AddonPermissions.dataCollection(declared);
        if (kinds.isEmpty()) {
            return declared.contains("none")
                    ? context.getString(R.string.addon_prompt_data_none)
                    : null;
        }
        List<String> words = new ArrayList<>();
        for (String kind : kinds) {
            int res = dataString(kind);
            if (res != 0) {
                words.add(context.getString(res));
            }
        }
        if (words.isEmpty()) {
            return null;
        }
        return context.getString(R.string.addon_prompt_data, String.join(", ", words));
    }

    @StringRes
    private static int dataString(@NonNull String kind) {
        switch (kind) {
            case "authenticationInfo": return R.string.addon_data_authentication;
            case "bookmarksInfo": return R.string.addon_data_bookmarks;
            case "browsingActivity": return R.string.addon_data_browsing_activity;
            case "financialAndPaymentInfo": return R.string.addon_data_financial;
            case "healthInfo": return R.string.addon_data_health;
            case "locationInfo": return R.string.addon_data_location;
            case "personalCommunications": return R.string.addon_data_communications;
            case "personallyIdentifyingInfo": return R.string.addon_data_identifying;
            case "searchTerms": return R.string.addon_data_search_terms;
            case "technicalAndInteraction": return R.string.addon_data_technical;
            case "websiteActivity": return R.string.addon_data_website_activity;
            case "websiteContent": return R.string.addon_data_website_content;
            default: return 0;
        }
    }
}
