package com.solarized.firedown.addons;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns an add-on's permission names into the lines a prompt shows, the
 * way Firefox for Android words them: site access first (all sites, or up
 * to four sites and four domains, then "other sites"), then the API
 * permissions that need the user's consent. Permissions Firefox installs
 * without asking (storage, alarms, webRequest…) produce no line.
 *
 * <p>Pure: {@link AddonPermissionText} maps each {@link Line} to a string.
 */
public final class AddonPermissions {

    public static final int ALL_SITES = 1;
    public static final int SITE = 2;
    public static final int DOMAIN = 3;
    public static final int MORE_SITES = 4;
    public static final int MORE_DOMAINS = 5;
    public static final int API = 6;

    /** Firefox lists four sites and four domains, then counts the rest. */
    static final int MAX_LISTED = 4;

    /** The API permissions Firefox asks about, in the order it lists them. */
    static final List<String> PROMPTED_API = Collections.unmodifiableList(Arrays.asList(
            "bookmarks", "browserSettings", "browsingData", "clipboardRead",
            "clipboardWrite", "declarativeNetRequest", "declarativeNetRequestFeedback",
            "devtools", "downloads", "downloads.open", "find", "geolocation",
            "history", "management", "nativeMessaging", "notifications", "pkcs11",
            "privacy", "proxy", "sessions", "tabHide", "tabs", "topSites",
            "trialML", "userScripts", "webNavigation"));

    /** Data a developer can declare the add-on collects (Firefox's list). */
    static final List<String> DATA_COLLECTION = Collections.unmodifiableList(Arrays.asList(
            "authenticationInfo", "bookmarksInfo", "browsingActivity",
            "financialAndPaymentInfo", "healthInfo", "locationInfo",
            "personalCommunications", "personallyIdentifyingInfo", "searchTerms",
            "technicalAndInteraction", "websiteActivity", "websiteContent"));

    private static final Pattern ORIGIN = Pattern.compile("^([a-z*]+)://([^/]*)(/.*)?$");
    private static final Set<String> WEB_SCHEMES =
            new HashSet<>(Arrays.asList("*", "http", "https", "ws", "wss"));

    private AddonPermissions() {}

    public static final class Line {
        public final int kind;
        /** The permission or host for API / SITE / DOMAIN, else null. */
        @Nullable public final String arg;
        /** How many more sites or domains, for MORE_*; else 0. */
        public final int count;

        Line(int kind, @Nullable String arg, int count) {
            this.kind = kind;
            this.arg = arg;
            this.count = count;
        }

        @Override
        public String toString() {
            return kind + ":" + arg + ":" + count;
        }
    }

    /**
     * {@code permissions} may mix API names and host patterns (a manifest v2
     * permission list, or the catalog's); {@code origins} are host patterns.
     */
    @NonNull
    public static List<Line> describe(@Nullable Collection<String> permissions,
                                      @Nullable Collection<String> origins) {
        Set<String> apis = new HashSet<>();
        Set<String> hosts = new LinkedHashSet<>();
        if (permissions != null) {
            for (String p : permissions) {
                if (p == null) {
                    continue;
                }
                if (isOriginPattern(p)) {
                    hosts.add(p);
                } else {
                    apis.add(p);
                }
            }
        }
        if (origins != null) {
            for (String o : origins) {
                if (o != null) {
                    hosts.add(o);
                }
            }
        }
        List<Line> lines = new ArrayList<>(siteLines(hosts));
        for (String api : PROMPTED_API) {
            if (apis.contains(api)) {
                lines.add(new Line(API, api, 0));
            }
        }
        return lines;
    }

    /** True when the add-on can read every site. */
    public static boolean grantsAllSites(@Nullable Collection<String> permissions,
                                         @Nullable Collection<String> origins) {
        for (Line line : describe(permissions, origins)) {
            if (line.kind == ALL_SITES) {
                return true;
            }
        }
        return false;
    }

    /**
     * The declared data-collection kinds Firefox knows, in its order. A
     * developer declaring "none" yields an empty list; so does a list of
     * nothing Firefox knows.
     */
    @NonNull
    public static List<String> dataCollection(@Nullable Collection<String> declared) {
        List<String> out = new ArrayList<>();
        if (declared == null) {
            return out;
        }
        for (String kind : DATA_COLLECTION) {
            if (declared.contains(kind)) {
                out.add(kind);
            }
        }
        return out;
    }

    /**
     * True when a prompt for these permissions would show the user nothing:
     * no site, no API Firefox asks about, no declared data collection. An
     * update like that is installed without asking, as in Firefox (an
     * update adding only {@code publicSuffix} reached us with lists that
     * read as nothing).
     */
    public static boolean asksNothing(@Nullable Collection<String> permissions,
                                      @Nullable Collection<String> origins,
                                      @Nullable Collection<String> dataCollection) {
        return describe(permissions, origins).isEmpty() && dataCollection(dataCollection).isEmpty();
    }

    /** One switch of an add-on's site access: a host and the patterns behind it. */
    public static final class Site {
        /** The host the switch shows ("example.com"), or the pattern when it names none. */
        @NonNull public final String label;
        /** Every pattern with this label; the switch grants or takes back all of them. */
        @NonNull public final List<String> patterns;
        /** True when any of them is granted: the add-on can reach the site. */
        public final boolean granted;

        Site(@NonNull String label, @NonNull List<String> patterns, boolean granted) {
            this.label = label;
            this.patterns = Collections.unmodifiableList(patterns);
            this.granted = granted;
        }
    }

    /** The site switches of an add-on's page. */
    public static final class SiteAccess {
        /** The all-sites patterns ({@code <all_urls>}, host "*"), declared or granted. */
        @NonNull public final List<String> allSitesPatterns;
        /** True when any of them is granted. */
        public final boolean allSitesGranted;
        /** Every other site, one per host, sorted by host. */
        @NonNull public final List<Site> sites;

        SiteAccess(@NonNull List<String> allSitesPatterns, boolean allSitesGranted,
                   @NonNull List<Site> sites) {
            this.allSitesPatterns = Collections.unmodifiableList(allSitesPatterns);
            this.allSitesGranted = allSitesGranted;
            this.sites = Collections.unmodifiableList(sites);
        }
    }

    /**
     * An add-on's optional site access: the patterns its manifest declares
     * PLUS the ones it was granted while running. An add-on that declares
     * all sites as optional asks for one site at a time, and those grants
     * are in no manifest: listing the declared patterns alone left them
     * with no switch to take them back. Firefox for Android lists the same
     * union. Patterns that name one host (http and https, a domain and its
     * bare host) share one switch.
     */
    @NonNull
    public static SiteAccess siteAccess(@Nullable Collection<String> declared,
                                        @Nullable Collection<String> granted) {
        Set<String> grantedSet = new HashSet<>();
        Set<String> patterns = new LinkedHashSet<>();
        addTrimmed(patterns, declared);
        addTrimmed(patterns, granted);
        addTrimmed(grantedSet, granted);
        List<String> allSites = new ArrayList<>();
        boolean allGranted = false;
        Map<String, String> labels = new HashMap<>();
        Map<String, List<String>> byHost = new HashMap<>();
        Map<String, Boolean> grantedByHost = new HashMap<>();
        for (String pattern : patterns) {
            boolean isGranted = grantedSet.contains(pattern);
            if (isAllSitesPattern(pattern)) {
                allSites.add(pattern);
                allGranted |= isGranted;
                continue;
            }
            String host = hostOf(pattern);
            String label = host == null || "*".equals(host) ? pattern : stripWildcard(host);
            String key = label.toLowerCase(Locale.ROOT);
            if (!byHost.containsKey(key)) {
                labels.put(key, label);
                byHost.put(key, new ArrayList<>());
                grantedByHost.put(key, false);
            }
            byHost.get(key).add(pattern);
            if (isGranted) {
                grantedByHost.put(key, true);
            }
        }
        List<String> keys = new ArrayList<>(byHost.keySet());
        Collections.sort(keys);
        List<Site> sites = new ArrayList<>();
        for (String key : keys) {
            sites.add(new Site(labels.get(key), byHost.get(key), Boolean.TRUE.equals(grantedByHost.get(key))));
        }
        return new SiteAccess(allSites, allGranted, sites);
    }

    private static void addTrimmed(@NonNull Set<String> into, @Nullable Collection<String> values) {
        if (values == null) {
            return;
        }
        for (String v : values) {
            if (v != null && !v.trim().isEmpty()) {
                into.add(v.trim());
            }
        }
    }

    /**
     * Gecko's all-sites rule (Extension.isAllSitesPermission): {@code
     * <all_urls>}, or host "*" under a web scheme. Gecko widens a grant or
     * revoke of one of these to every one the manifest declares, so they
     * are one switch. An ftp: pattern with host "*" is not all sites.
     */
    static boolean isAllSitesPattern(@NonNull String pattern) {
        String p = pattern.trim();
        if ("<all_urls>".equals(p)) {
            return true;
        }
        Matcher m = ORIGIN.matcher(p);
        return m.matches() && "*".equals(m.group(2)) && WEB_SCHEMES.contains(m.group(1));
    }

    @NonNull
    private static String stripWildcard(@NonNull String host) {
        return host.startsWith("*.") ? host.substring(2) : host;
    }

    /**
     * "*" for host "*" (the prompt lines read it as all sites, whatever the
     * scheme), the host of one site or domain ("*.example.com" keeps its
     * wildcard, a port is dropped, an IPv6 literal kept whole), null when
     * the pattern names no host (a file: pattern, a malformed one).
     */
    @Nullable
    static String hostOf(@NonNull String pattern) {
        String p = pattern.trim();
        if ("<all_urls>".equals(p)) {
            return "*";
        }
        Matcher m = ORIGIN.matcher(p);
        if (!m.matches()) {
            return null;
        }
        String host = m.group(2) == null ? "" : m.group(2).toLowerCase(Locale.ROOT);
        String path = m.group(3);
        if ("*".equals(host)) {
            return "*";
        }
        if (host.isEmpty() || path == null || path.isEmpty()) {
            return null;
        }
        if (host.startsWith("[")) {
            // An IPv6 literal: its colons are not a port.
            int close = host.indexOf(']');
            host = close > 0 ? host.substring(0, close + 1) : host;
        } else {
            int colon = host.indexOf(':');
            if (colon >= 0) {
                host = host.substring(0, colon);
            }
        }
        return host.isEmpty() ? null : host;
    }

    static boolean isOriginPattern(@NonNull String value) {
        return "<all_urls>".equals(value) || value.contains("://");
    }

    @NonNull
    private static List<Line> siteLines(@NonNull Set<String> hosts) {
        Set<String> sites = new LinkedHashSet<>();
        Set<String> domains = new LinkedHashSet<>();
        boolean all = false;
        for (String pattern : hosts) {
            String host = hostOf(pattern);
            if (host == null) {
                continue;
            }
            if ("*".equals(host)) {
                all = true;
                continue;
            }
            if (host.startsWith("*.")) {
                domains.add(host.substring(2));
            } else {
                sites.add(host);
            }
        }
        List<Line> lines = new ArrayList<>();
        if (all) {
            lines.add(new Line(ALL_SITES, null, 0));
            return lines;
        }
        int listed = 0;
        for (String domain : domains) {
            if (listed == MAX_LISTED) {
                break;
            }
            lines.add(new Line(DOMAIN, domain, 0));
            listed++;
        }
        if (domains.size() > MAX_LISTED) {
            lines.add(new Line(MORE_DOMAINS, null, domains.size() - MAX_LISTED));
        }
        listed = 0;
        for (String site : sites) {
            if (listed == MAX_LISTED) {
                break;
            }
            lines.add(new Line(SITE, site, 0));
            listed++;
        }
        if (sites.size() > MAX_LISTED) {
            lines.add(new Line(MORE_SITES, null, sites.size() - MAX_LISTED));
        }
        return lines;
    }
}
