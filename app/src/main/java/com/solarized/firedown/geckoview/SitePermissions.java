package com.solarized.firedown.geckoview;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * What the shield sheet's "Site permissions" row lists for a site: the
 * answers Gecko stored for it, of the kinds Firedown asks about (location,
 * keeping data on the phone, an embedded site's cookies). Pure Java, so the
 * rules run without Android ({@code SitePermissionsTest}).
 *
 * <p>Gecko keeps more than that for a site: every answer GeckoView ever
 * returned, a PROMPT row included (it stores whatever the delegate answers),
 * DRM rows from pages that asked while DRM was off, autoplay. None of those
 * is a decision the user made here, and listing them would put rows on
 * every site, so only an ALLOW or a DENY of the three kinds is shown, and
 * only one the user gave (not a cookie grant Gecko made by itself).
 *
 * @param <T> the caller's handle on a stored answer (GeckoView's
 *            ContentPermission), kept so Reset can forget it
 */
public final class SitePermissions<T> {

    // GeckoView's GeckoSession.PermissionDelegate values (api.txt).
    public static final int TYPE_LOCATION = 0;
    public static final int TYPE_PERSISTENT_STORAGE = 2;
    public static final int TYPE_STORAGE_ACCESS = 8;
    public static final int VALUE_ALLOW = 1;
    public static final int VALUE_DENY = 2;

    /**
     * Gecko's key for cookie access it granted by itself (its login-popup
     * heuristics), not by an answer to a dialog. Not listed: the user never
     * answered it, and GeckoView's setPermission writes the
     * {@code 3rdPartyFrameStorage^} key, so a Reset could not forget it.
     */
    static final String AUTOMATIC_STORAGE_KEY_PREFIX = "3rdPartyStorage^";

    /** One stored answer as Gecko reports it. */
    public static final class Stored<T> {
        final int type;
        final int value;
        final String thirdPartyOrigin;
        /** Gecko's own key for the answer ("geolocation", "3rdPartyFrameStorage^…"), when known. */
        final String key;
        final T handle;

        public Stored(int type, int value, String thirdPartyOrigin, String key, T handle) {
            this.type = type;
            this.value = value;
            this.thirdPartyOrigin = thirdPartyOrigin;
            this.key = key;
            this.handle = handle;
        }
    }

    /** One row of the list: a kind, the answer, and every stored answer it stands for. */
    public static final class Row<T> {
        public final int type;
        public final boolean allowed;
        /** The embedded site, for a storage-access row; null otherwise or when unknown. */
        public final String thirdPartyHost;
        /** The stored answers this row resets (Gecko can hold duplicates). */
        public final List<T> handles;

        Row(int type, boolean allowed, String thirdPartyHost, List<T> handles) {
            this.type = type;
            this.allowed = allowed;
            this.thirdPartyHost = thirdPartyHost;
            this.handles = handles;
        }

        public String key() {
            return type + "|" + (thirdPartyHost == null ? "" : thirdPartyHost);
        }
    }

    public final List<Row<T>> rows;
    public final int allowedCount;
    public final int blockedCount;

    private SitePermissions(List<Row<T>> rows) {
        this.rows = Collections.unmodifiableList(rows);
        int allowed = 0;
        for (Row<T> row : rows) {
            if (row.allowed) {
                allowed++;
            }
        }
        this.allowedCount = allowed;
        this.blockedCount = rows.size() - allowed;
    }

    public boolean isEmpty() {
        return rows.isEmpty();
    }

    public static boolean isListedType(int type) {
        return type == TYPE_LOCATION || type == TYPE_PERSISTENT_STORAGE || type == TYPE_STORAGE_ACCESS;
    }

    /**
     * The rows for these stored answers: listed kinds only, ALLOW or DENY
     * only, one row per kind (per embedded site for storage access),
     * location first, then data, then embedded sites by name. When Gecko
     * holds two answers for one row the latest wins, and Reset forgets both.
     */
    public static <T> SitePermissions<T> of(List<Stored<T>> stored) {
        Map<String, List<Stored<T>>> byKey = new LinkedHashMap<>();
        if (stored != null) {
            for (Stored<T> s : stored) {
                if (s == null || !isListedType(s.type)) {
                    continue;
                }
                if (s.value != VALUE_ALLOW && s.value != VALUE_DENY) {
                    continue;
                }
                if (s.key != null && s.key.startsWith(AUTOMATIC_STORAGE_KEY_PREFIX)) {
                    continue;
                }
                String host = s.type == TYPE_STORAGE_ACCESS ? hostOf(s.thirdPartyOrigin) : null;
                String key = s.type + "|" + (host == null ? "" : host);
                List<Stored<T>> group = byKey.get(key);
                if (group == null) {
                    group = new ArrayList<>();
                    byKey.put(key, group);
                }
                group.add(s);
            }
        }
        List<Row<T>> rows = new ArrayList<>(byKey.size());
        for (List<Stored<T>> group : byKey.values()) {
            Stored<T> last = group.get(group.size() - 1);
            List<T> handles = new ArrayList<>(group.size());
            for (Stored<T> s : group) {
                handles.add(s.handle);
            }
            String host = last.type == TYPE_STORAGE_ACCESS ? hostOf(last.thirdPartyOrigin) : null;
            rows.add(new Row<>(last.type, last.value == VALUE_ALLOW, host, handles));
        }
        rows.sort(ORDER);
        return new SitePermissions<>(rows);
    }

    private static final Comparator<Row<?>> ORDER = (a, b) -> {
        int byType = Integer.compare(rank(a.type), rank(b.type));
        if (byType != 0) {
            return byType;
        }
        String ha = a.thirdPartyHost == null ? "" : a.thirdPartyHost;
        String hb = b.thirdPartyHost == null ? "" : b.thirdPartyHost;
        return ha.compareTo(hb);
    };

    private static int rank(int type) {
        if (type == TYPE_LOCATION) {
            return 0;
        }
        if (type == TYPE_PERSISTENT_STORAGE) {
            return 1;
        }
        return 2;
    }

    /**
     * The host of an origin as Gecko spells it: {@code https://a.example},
     * {@code https://a.example:8443}, with origin attributes after a
     * {@code ^} ({@code https://a.example^privateBrowsingId=1}), or
     * {@code [::1]} for an IPv6 literal. A leading {@code www.} is dropped,
     * as the address bar does. Null when there is no host.
     */
    public static String hostOf(String origin) {
        if (origin == null) {
            return null;
        }
        String s = origin.trim();
        int caret = s.indexOf('^');
        if (caret >= 0) {
            s = s.substring(0, caret);
        }
        int scheme = s.indexOf("://");
        if (scheme >= 0) {
            s = s.substring(scheme + 3);
        }
        int slash = s.indexOf('/');
        if (slash >= 0) {
            s = s.substring(0, slash);
        }
        int at = s.lastIndexOf('@');
        if (at >= 0) {
            s = s.substring(at + 1);
        }
        if (s.startsWith("[")) {
            int close = s.indexOf(']');
            s = close > 0 ? s.substring(0, close + 1) : s;
        } else {
            int colon = s.indexOf(':');
            if (colon >= 0) {
                s = s.substring(0, colon);
            }
        }
        s = s.toLowerCase(Locale.ROOT);
        if (s.startsWith("www.") && s.length() > 4) {
            s = s.substring(4);
        }
        return s.isEmpty() ? null : s;
    }
}
