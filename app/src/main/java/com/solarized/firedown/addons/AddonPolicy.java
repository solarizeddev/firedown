package com.solarized.firedown.addons;

import androidx.annotation.Nullable;

import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Rules for add-ons the user installs, kept free of Android and GeckoView
 * so the add-ons harness can run them on a plain JVM.
 */
public final class AddonPolicy {

    /**
     * IDs of extensions Firedown ships. An add-on claiming one of these is
     * refused before Gecko installs it: installing uBlock Origin from the
     * store (same ID as the bundled, patched build) would replace the build
     * the native bridge talks to, and our own IDs are not registered on
     * addons.mozilla.org, so Mozilla would sign an unlisted add-on that
     * claims one.
     */
    private static final Set<String> BUILT_IN_IDS;

    /** Every ID in this domain is ours, including ones a later build adds. */
    private static final String OWN_ID_SUFFIX = "@solarized.dev";
    private static final String UBLOCK_ID = "uBlock0@raymondhill.net";

    static {
        Set<String> ids = new HashSet<>();
        ids.add(UBLOCK_ID.toLowerCase(Locale.ROOT));
        ids.add("icons@mozac.org");
        BUILT_IN_IDS = Collections.unmodifiableSet(ids);
    }

    private AddonPolicy() {}

    /** True for an ID Firedown ships itself; case-insensitive. */
    public static boolean isBuiltInId(@Nullable String id) {
        if (id == null) {
            return false;
        }
        String lower = id.trim().toLowerCase(Locale.ROOT);
        if (lower.isEmpty()) {
            return false;
        }
        if (lower.endsWith(OWN_ID_SUFFIX)) {
            return true;
        }
        return BUILT_IN_IDS.contains(lower);
    }

    /** The bundled uBlock, whose badge is the blocked count. */
    public static boolean isUblockId(@Nullable String id) {
        return id != null && UBLOCK_ID.equalsIgnoreCase(id.trim());
    }

    /**
     * Whether {@code tabs.update} from a user add-on may load {@code url} in
     * a tab: web pages, and the add-on's own pages ({@code baseUrl} is the
     * add-on's moz-extension:// root). Another extension's pages are refused,
     * so an add-on cannot load uBlock's or the capture extension's pages.
     */
    public static boolean mayLoadInTab(@Nullable String url, @Nullable String baseUrl) {
        if (url == null) {
            return false;
        }
        String lower = url.toLowerCase(Locale.ROOT);
        if (lower.startsWith("http://") || lower.startsWith("https://")) {
            return true;
        }
        if (lower.equals("about:blank")) {
            return true;
        }
        if (baseUrl != null && !baseUrl.isEmpty()
                && lower.startsWith("moz-extension://")
                && url.startsWith(baseUrl)) {
            return true;
        }
        return false;
    }
}
