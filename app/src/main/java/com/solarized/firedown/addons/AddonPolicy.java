package com.solarized.firedown.addons;

import androidx.annotation.Nullable;

import java.util.Collection;
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
     * IDs of extensions Firedown ships as built-ins. An add-on claiming one
     * of these is refused before Gecko installs it: our own IDs are not
     * registered on addons.mozilla.org, so Mozilla would sign an unlisted
     * add-on that claims one, and it would replace the built-in the native
     * bridge talks to. uBlock Origin is NOT one: Firedown installs the
     * official, Mozilla-signed build as an ordinary add-on.
     */
    private static final Set<String> BUILT_IN_IDS;

    /** Every ID in this domain is ours, including ones a later build adds. */
    private static final String OWN_ID_SUFFIX = "@solarized.dev";
    public static final String UBLOCK_ID = "uBlock0@raymondhill.net";

    static {
        Set<String> ids = new HashSet<>();
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

    /** uBlock Origin, the ad blocker Firedown installs (always listed first behind the shield). */
    public static boolean isUblockId(@Nullable String id) {
        return id != null && UBLOCK_ID.equalsIgnoreCase(id.trim());
    }

    /**
     * Whether {@code tabs.update} from a user add-on may load {@code url} in
     * a tab: web pages, and the add-on's own pages ({@code baseUrl} is the
     * add-on's moz-extension:// root). Another extension's pages are refused,
     * so an add-on cannot load another add-on's or the capture extension's
     * pages. (uBlock's strict-block page, document-blocked.html, is its own.)
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

    // ── Blockers behind the shield ──────────────────────────────────────

    /**
     * Whether an add-on's permissions make it a blocker: it can refuse
     * requests on every site. {@code webRequestBlocking} with access to all
     * sites, or {@code declarativeNetRequest} (which blocks without host
     * access). Read from the add-on's own manifest, so there is no list of
     * blocker names to keep; the few non-blockers holding the same
     * permissions are corrected by the user's "Show behind the shield"
     * switch ({@link #isBlocker}).
     */
    public static boolean blocksRequests(@Nullable Collection<String> permissions,
                                         @Nullable Collection<String> origins) {
        if (permissions == null) {
            return false;
        }
        if (permissions.contains("declarativeNetRequest")) {
            return true;
        }
        boolean blocking = permissions.contains("webRequestBlocking");
        boolean dnrWithHosts = permissions.contains("declarativeNetRequestWithHostAccess");
        if (!blocking && !dnrWithHosts) {
            return false;
        }
        return reachesAllSites(permissions) || reachesAllSites(origins);
    }

    private static boolean reachesAllSites(@Nullable Collection<String> patterns) {
        if (patterns == null) {
            return false;
        }
        boolean http = false;
        boolean https = false;
        for (String p : patterns) {
            if (p == null) {
                continue;
            }
            switch (p) {
                case "<all_urls>":
                case "*://*/*":
                    return true;
                case "http://*/*":
                    http = true;
                    break;
                case "https://*/*":
                    https = true;
                    break;
                default:
                    break;
            }
        }
        return http && https;
    }

    /**
     * The add-on gets a row behind the shield: uBlock Origin always; any
     * other add-on that has a toolbar button and either the user said so
     * ({@code override} true) or its permissions say it blocks and the
     * user did not hide it ({@code override} false).
     */
    public static boolean isBlocker(@Nullable String id, boolean hasButton, boolean blocksRequests,
                                    @Nullable Boolean override) {
        if (isUblockId(id)) {
            return true;
        }
        if (!hasButton) {
            return false;
        }
        if (override != null) {
            return override;
        }
        return blocksRequests;
    }

    /**
     * uBlock Origin titles its toolbar button per tab: "uBlock Origin (23)"
     * while it filters there, "uBlock Origin (off)" once the user turned it
     * off for the site (vapi-background.js, setTitle). The word is not
     * translated by uBlock. A title with no parenthesis (the default, a tab
     * uBlock has not titled yet) reads as on.
     */
    public static boolean saysOffForSite(@Nullable String id, @Nullable String title) {
        if (!isUblockId(id) || title == null) {
            return false;
        }
        return title.trim().endsWith("(off)");
    }

    /** What the shield shows for a tab. */
    public static final int SHIELD_PROTECTED = 0;
    /** A blocker runs here but one of them is off for this site. */
    public static final int SHIELD_OFF_FOR_SITE = 1;
    /** No blocker runs in this tab (none installed, turned off, or not allowed in private tabs). */
    public static final int SHIELD_NO_BLOCKER = 2;

    /**
     * The rule: protection is on for a tab when at least one blocker runs
     * there (enabled, and allowed in private tabs for a private tab) and none
     * of those says it is off for the site.
     *
     * @param running the blockers that run in this tab, each as whether it says off for the site
     */
    public static int shieldState(@Nullable Collection<Boolean> running) {
        if (running == null || running.isEmpty()) {
            return SHIELD_NO_BLOCKER;
        }
        for (Boolean off : running) {
            if (Boolean.TRUE.equals(off)) {
                return SHIELD_OFF_FOR_SITE;
            }
        }
        return SHIELD_PROTECTED;
    }
}
