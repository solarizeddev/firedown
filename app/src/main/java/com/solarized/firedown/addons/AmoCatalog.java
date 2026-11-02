package com.solarized.firedown.addons;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The official list of add-ons that work on Android: Mozilla's
 * "Extensions-for-Android" collection on addons.mozilla.org, the list
 * Firefox for Android shows in its add-ons manager. Every entry there is
 * reviewed by Mozilla and marked compatible with Firefox for Android, so the
 * app never has to judge compatibility itself.
 *
 * <p>Pure parsing and URL building, no network: {@link AmoCatalogClient}
 * fetches, this reads the AMO API's collection response
 * (api/v4/accounts/account/{user}/collections/{name}/addons/).
 */
public final class AmoCatalog {

    public static final String SERVER = "https://services.addons.mozilla.org";
    public static final String COLLECTION_USER = "mozilla";
    public static final String COLLECTION_NAME = "Extensions-for-Android";
    public static final int PAGE_SIZE = 50;
    /** A collection longer than this is cut: the screen lists, it doesn't page. */
    public static final int MAX_ENTRIES = 200;

    private AmoCatalog() {}

    /** One add-on of the collection, as the screen needs it. */
    public static final class Entry {
        @NonNull public final String id;
        @NonNull public final String name;
        @NonNull public final String summary;
        @NonNull public final String author;
        @Nullable public final String iconUrl;
        @Nullable public final String detailUrl;
        @NonNull public final String downloadUrl;
        @NonNull public final String version;
        public final double rating;
        public final int reviews;
        public final int users;
        @NonNull public final List<String> permissions;

        Entry(@NonNull String id, @NonNull String name, @NonNull String summary,
              @NonNull String author, @Nullable String iconUrl, @Nullable String detailUrl,
              @NonNull String downloadUrl, @NonNull String version, double rating,
              int reviews, int users, @NonNull List<String> permissions) {
            this.id = id;
            this.name = name;
            this.summary = summary;
            this.author = author;
            this.iconUrl = iconUrl;
            this.detailUrl = detailUrl;
            this.downloadUrl = downloadUrl;
            this.version = version;
            this.rating = rating;
            this.reviews = reviews;
            this.users = users;
            this.permissions = permissions;
        }
    }

    /** One page of the response: its entries and the next page's URL, if any. */
    public static final class Page {
        @NonNull public final List<Entry> entries;
        @Nullable public final String next;

        Page(@NonNull List<Entry> entries, @Nullable String next) {
            this.entries = entries;
            this.next = next;
        }
    }

    /**
     * The collection's first page in {@code language} (an ISO code such as
     * "es", or null for every translation). Sorted by popularity, the order
     * Firefox shows.
     */
    @NonNull
    public static String collectionUrl(@Nullable String language) {
        StringBuilder url = new StringBuilder(SERVER)
                .append("/api/v4/accounts/account/").append(COLLECTION_USER)
                .append("/collections/").append(COLLECTION_NAME)
                .append("/addons/?page_size=").append(PAGE_SIZE)
                .append("&sort=-popularity");
        if (language != null && !language.isEmpty()) {
            url.append("&lang=").append(language);
        }
        return url.toString();
    }

    /** A next-page URL is followed only when it stays on the API server. */
    public static boolean isTrustedPageUrl(@Nullable String url) {
        return url != null && url.startsWith(SERVER + "/");
    }

    /**
     * Parses one page. An entry missing its ID or download file, or one that
     * is not an extension (a theme, a dictionary), is skipped; a malformed
     * page throws.
     */
    @NonNull
    public static Page parse(@NonNull String body, @Nullable String language) throws JSONException {
        JSONObject root = new JSONObject(body);
        JSONArray results = root.getJSONArray("results");
        List<Entry> entries = new ArrayList<>();
        for (int i = 0; i < results.length(); i++) {
            JSONObject item = results.optJSONObject(i);
            if (item == null) {
                continue;
            }
            // A collection wraps each add-on as {"addon": {...}, "notes": ...};
            // the search endpoint returns the add-on itself.
            JSONObject addon = item.optJSONObject("addon");
            if (addon == null) {
                addon = item;
            }
            Entry entry = toEntry(addon, language);
            if (entry != null) {
                entries.add(entry);
            }
        }
        String next = root.isNull("next") ? null : root.optString("next", null);
        if (next != null && next.isEmpty()) {
            next = null;
        }
        return new Page(entries, next);
    }

    /** Joins pages in order, keeping the first copy of an ID and at most {@link #MAX_ENTRIES}. */
    @NonNull
    public static List<Entry> merge(@NonNull List<Page> pages) {
        List<Entry> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Page page : pages) {
            for (Entry entry : page.entries) {
                if (out.size() >= MAX_ENTRIES) {
                    return Collections.unmodifiableList(out);
                }
                String key = entry.id.toLowerCase(Locale.ROOT);
                if (seen.add(key)) {
                    out.add(entry);
                }
            }
        }
        return Collections.unmodifiableList(out);
    }

    @Nullable
    private static Entry toEntry(@NonNull JSONObject addon, @Nullable String language) {
        String type = optString(addon, "type");
        if (!type.isEmpty() && !"extension".equals(type)) {
            return null;
        }
        if (addon.optBoolean("is_disabled", false)) {
            return null;
        }
        String id = optString(addon, "guid");
        if (id.isEmpty()) {
            return null;
        }
        JSONObject version = addon.optJSONObject("current_version");
        if (version == null) {
            return null;
        }
        JSONObject file = null;
        JSONArray files = version.optJSONArray("files");
        if (files != null) {
            file = files.optJSONObject(0);
        }
        if (file == null) {
            // Newer API versions give the version a single "file".
            file = version.optJSONObject("file");
        }
        if (file == null) {
            return null;
        }
        String downloadUrl = optString(file, "url");
        if (!downloadUrl.startsWith("https://")) {
            return null;
        }
        String name = translated(addon, "name", language);
        if (name.isEmpty()) {
            name = id;
        }
        String summary = translated(addon, "summary", language);
        String author = "";
        JSONArray authors = addon.optJSONArray("authors");
        if (authors != null) {
            JSONObject first = authors.optJSONObject(0);
            if (first != null) {
                author = optString(first, "name");
            }
        }
        String iconUrl = iconUrl(addon);
        String detailUrl = optString(addon, "url");
        double rating = 0;
        int reviews = 0;
        JSONObject ratings = addon.optJSONObject("ratings");
        if (ratings != null) {
            rating = ratings.optDouble("average", 0);
            if (Double.isNaN(rating)) {
                rating = 0;
            }
            reviews = ratings.optInt("count", 0);
        }
        int users = addon.optInt("average_daily_users", 0);
        List<String> permissions = new ArrayList<>();
        JSONArray perms = file.optJSONArray("permissions");
        if (perms != null) {
            for (int i = 0; i < perms.length(); i++) {
                String p = perms.optString(i, "");
                if (!p.isEmpty()) {
                    permissions.add(p);
                }
            }
        }
        // MV3 lists host permissions apart from API permissions.
        JSONArray hosts = file.optJSONArray("host_permissions");
        if (hosts != null) {
            for (int i = 0; i < hosts.length(); i++) {
                String p = hosts.optString(i, "");
                if (!p.isEmpty()) {
                    permissions.add(p);
                }
            }
        }
        return new Entry(id, name, summary, author,
                iconUrl, detailUrl.isEmpty() ? null : detailUrl,
                downloadUrl, optString(version, "version"),
                rating, reviews, users, Collections.unmodifiableList(permissions));
    }

    /** The 64px icon when the response lists sizes, else "icon_url". */
    @Nullable
    private static String iconUrl(@NonNull JSONObject addon) {
        JSONObject icons = addon.optJSONObject("icons");
        if (icons != null) {
            String sized = optString(icons, "64");
            if (sized.isEmpty()) {
                sized = optString(icons, "128");
            }
            if (sized.startsWith("https://")) {
                return sized;
            }
        }
        String icon = optString(addon, "icon_url");
        return icon.startsWith("https://") ? icon : null;
    }

    /**
     * A translatable field is a plain string when the request named a
     * language, else an object of every translation. From the object: the
     * requested language, then the add-on's default locale, then English,
     * then whatever comes first.
     */
    @NonNull
    static String translated(@NonNull JSONObject addon, @NonNull String key, @Nullable String language) {
        if (addon.isNull(key)) {
            return "";
        }
        Object value = addon.opt(key);
        if (value instanceof String) {
            return ((String) value).trim();
        }
        if (!(value instanceof JSONObject)) {
            return "";
        }
        JSONObject map = (JSONObject) value;
        String hit = pick(map, language);
        if (!hit.isEmpty()) {
            return hit;
        }
        hit = pick(map, optString(addon, "default_locale"));
        if (!hit.isEmpty()) {
            return hit;
        }
        hit = pick(map, "en-US");
        if (!hit.isEmpty()) {
            return hit;
        }
        Iterator<String> keys = map.keys();
        while (keys.hasNext()) {
            String any = optString(map, keys.next());
            if (!any.isEmpty()) {
                return any;
            }
        }
        return "";
    }

    /** Exact key first, then any key with the same language subtag ("pt" matches "pt-BR"). */
    @NonNull
    private static String pick(@NonNull JSONObject map, @Nullable String language) {
        if (language == null || language.isEmpty()) {
            return "";
        }
        String exact = optString(map, language);
        if (!exact.isEmpty()) {
            return exact;
        }
        String want = subtag(language);
        Iterator<String> keys = map.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            if (subtag(key).equals(want)) {
                String value = optString(map, key);
                if (!value.isEmpty()) {
                    return value;
                }
            }
        }
        return "";
    }

    @NonNull
    private static String subtag(@NonNull String tag) {
        int cut = tag.indexOf('-');
        if (cut < 0) {
            cut = tag.indexOf('_');
        }
        String lang = cut < 0 ? tag : tag.substring(0, cut);
        return lang.toLowerCase(Locale.ROOT);
    }

    @NonNull
    private static String optString(@NonNull JSONObject object, @NonNull String key) {
        if (object.isNull(key)) {
            return "";
        }
        Object value = object.opt(key);
        if (value instanceof String) {
            return ((String) value).trim();
        }
        return "";
    }
}
