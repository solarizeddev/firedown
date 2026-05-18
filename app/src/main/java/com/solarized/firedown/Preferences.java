package com.solarized.firedown;

import android.content.SharedPreferences;
import android.util.Log;

import org.mozilla.geckoview.ContentBlocking;
import org.mozilla.geckoview.GeckoRuntimeSettings;


public class Preferences {

    private static final String TAG = Preferences.class.getSimpleName();

    public static final String UPDATE_APK = "firedown.apk";

    /**
     * Primary update-check endpoint. Sits behind Cloudflare. UpdateWorker
     * tries each URL in {@link #UPDATE_URL_FALLBACKS} until one succeeds.
     */
    public static final String UPDATE_URL = "https://www.firedown.app/status.json";

    /**
     * Fallback endpoints, tried in order after {@link #UPDATE_URL} fails.
     *
     * Spain's LaLiga court orders force major ISPs to block large blocks
     * of Cloudflare IPs during match windows (the Cloudflare-front blast
     * radius affects every Cloudflare-hosted service whether or not it's
     * related to piracy). The primary firedown.app endpoint becomes
     * unreachable for those users — TCP SYN is dropped, DNS-over-HTTPS
     * can't help because the block is IP-level, not DNS-level.
     *
     * The GitHub Raw mirror is hosted on Microsoft Azure IPs, not on
     * Cloudflare's, so the block doesn't catch it. The file is a
     * straight copy of status.json committed to the repo's main branch
     * — keep it updated alongside the firedown.app one.
     */
    public static final String[] UPDATE_URL_FALLBACKS = new String[]{
            UPDATE_URL,
            "https://raw.githubusercontent.com/solarizeddev/firedown/main/status.json",
    };

    public static final int EXTRA_TOUCH_AREA_DP = 12;

    public static final String CLIPBOARD_LABEL = "com.solarized.firedown.clipboard.label";

    public static final String SORT_LOCAL = "com.solarized.firedown.preferences.sort.local";

    public static final String SORT_LIST = "com.solarized.firedown.preferences.sort.list";

    public static final String SORT_TABS_LIST = "com.solarized.firedown.preferences.sort.tabs.list";

    public static final String SORT_DOWNLOADS_LIST = "com.solarized.firedown.preferences.sort.downloads.list";

    public static final String SORT_VAULT_LIST = "com.solarized.firedown.preferences.sort.vault.list";

    public static final String SETTINGS_AUTOFILL = "com.solarized.firedown.preferences.browser.password";

    public static final String SETTINGS_BLOCK_LOCATION = "com.solarized.firedown.preferences.browser.block.location";

    public static final boolean DEFAULT_BLOCK_LOCATION = true;

    public static final String SETTINGS_THEME = "com.solarized.firedown.preferences.theme";

    public static final String SETTINGS_THEME_DEFAULT = "com.solarized.firedown.preferences.theme.default";

    public static final String SETTINGS_THEME_DARK = "com.solarized.firedown.preferences.theme.dark";

    public static final String SETTINGS_THEME_LIGHT = "com.solarized.firedown.preferences.theme.light";

    public static final String SETTINGS_THEME_OLED = "com.solarized.firedown.preferences.theme.oled";

    /**
     * Sentinel value stored in {@link #SETTINGS_THEME} when the user picks
     * AMOLED mode. Distinct from {@link androidx.appcompat.app.AppCompatDelegate}
     * MODE_NIGHT_* constants (which are -1, 1, 2, 3) so it survives a
     * round-trip through getInt without colliding. App.onCreate translates
     * this to MODE_NIGHT_YES + the OLED theme overlay.
     */
    public static final int THEME_OLED = -100;

    public static final String SETTINGS_ENABLE_JIT = "com.solarized.firedown.preferences.browser.enable.jit";

    public static final boolean DEFAULT_ENABLE_JIT = false;

    public static final String SETTINGS_DISABLE_WEBGL = "com.solarized.firedown.preferences.browser.disable.webgl";

    public static final boolean DEFAULT_DISABLE_WEBGL = false;

    public static final String SETTINGS_ENABLE_RESIST_FINGERPRINTING = "com.solarized.firedown.preferences.browser.enable.resist.fingerprinting";

    public static final boolean DEFAULT_RESIST_FINGERPRINTING = false;

    public static final String SETTINGS_ENABLE_WEBRTC = "com.solarized.firedown.preferences.browser.enable.webrtc";

    public static final boolean DEFAULT_ENABLE_WEBRTC = false;

    public static final String SETTINGS_ENABLE_WEBASSEMBLY = "com.solarized.firedown.preferences.browser.enable.webassembly";

    public static final boolean DEFAULT_ENABLE_WEBASSEMBLY = false;

    public static final String SETTINGS_ENABLE_DRM = "com.solarized.firedown.preferences.browser.enable.drm";

    /**
     * HTTPS-only mode — refuse plaintext HTTP loads, show a warning page
     * with a per-site override option. Default ON: the privacy gain is
     * substantial and modern sites are nearly all HTTPS; the warning page
     * makes the rare HTTP-only site one click to allow.
     */
    public static final String SETTINGS_HTTPS_ONLY = "com.solarized.firedown.preferences.browser.https.only";
    public static final boolean DEFAULT_HTTPS_ONLY = true;

    /**
     * Disk cache toggle. Off = on-disk caching (default), On = in-memory
     * cache only. Disabling defeats cross-site cache fingerprinting and
     * leaves no cached content on disk to recover, at the cost of slower
     * repeat visits. Default OFF (= disk cache enabled) since the perf
     * cost is the more visible of the two effects. Naming follows the
     * "Disable X" convention used by SETTINGS_DISABLE_WEBGL — the switch
     * ON means the privacy-preferring action.
     */
    public static final String SETTINGS_DISABLE_DISK_CACHE = "com.solarized.firedown.preferences.browser.disable.disk.cache";
    public static final boolean DEFAULT_DISABLE_DISK_CACHE = true;

    /**
     * Disable Google Safe Browsing — its blocklist of malware / phishing
     * URLs sends URL hash prefixes to Google for matching. The switch ON
     * stops those network calls but loses warnings on known-bad sites.
     * Default OFF (= Safe Browsing on) because the security benefit is
     * concrete and the privacy leak is hash-prefixes not full URLs.
     * Naming follows the "Disable X" convention.
     */
    public static final String SETTINGS_DISABLE_SAFE_BROWSING = "com.solarized.firedown.preferences.browser.disable.safebrowsing";
    public static final boolean DEFAULT_DISABLE_SAFE_BROWSING = false;

    public static final String SETTINGS_ANTI_TRACKING = "com.solarized.firedown.preferences.browser.tracking";

    public static final String SETTINGS_ANTI_TRACKING_DEFAULT = "com.solarized.firedown.preferences.browser.tracking.default";

    public static final String SETTINGS_ANTI_TRACKING_STRICT = "com.solarized.firedown.preferences.browser.tracking.strict";

    public static final String SETTINGS_ANTI_TRACKING_CUSTOM = "com.solarized.firedown.preferences.browser.tracking.custom";

    public static final String SETTINGS_ANTI_TRACKING_STRIP_LIST = "com.solarized.firedown.preferences.browser.tracking.strip.list";

    public static final String SETTINGS_ANTI_TRACKING_USER_PARAMS = "com.solarized.firedown.preferences.browser.tracking.strip.user.params";

    // Default tracking query parameter strip list. Whitespace-separated.
    // Union of Brave's curated kSimpleQueryStringTrackers
    // (brave-core/components/query_filter/browser/utils.cc), Firefox's
    // privacy.query_stripping.strip_list defaults, and standard utm_*
    // campaign parameters. Conditional / host-scoped entries (mkt_tok,
    // igsh on instagram, si on youtube, ...) are intentionally excluded
    // — GeckoView's flat strip list cannot evaluate URL context, and
    // stripping them globally would break legitimate flows like
    // unsubscribe links.
    public static final String DEFAULT_QUERY_STRIP_LIST =
            "_kx _openstat at_recipient_id at_recipient_list bbeml bsft_clkid bsft_uid " +
                    "dclid epik et_rid fb_action_ids fb_comment_id fbclid gbraid gclid " +
                    "guce_referrer guce_referrer_sig hsCtaTracking igshid irclickid mc_cid mc_eid " +
                    "mkcid mkevt mkwid ml_subscriber ml_subscriber_hash msclkid mtm_cid oft_c " +
                    "oft_ck oft_d oft_id oft_ids oft_k oft_lk oft_sk oly_anon_id oly_enc_id pcrid " +
                    "pk_cid rb_clickid s_cid s_kwcid sc_customer sc_eh sc_uid sfmc_activityid " +
                    "sfmc_id sms_click sms_source sms_uph srsltid ss_email_id syclid ttclid " +
                    "twclid unicorn_click_id utm_campaign utm_content utm_medium utm_source " +
                    "utm_term vero_conv vero_id vgo_ee wbraid wickedid yclid ymclid ysclid";

    public static final String SETTINGS_CLEAR_DATA = "com.solarized.firedown.preferences.browser.clear";

    public static final String SETTINGS_DOWNLOADS = "com.solarized.firedown.preferences.downloads.location";

    public static final String SETTINGS_SAVE_ASK = "com.solarized.firedown.preferences.downloads.save.ask";

    public static final String SETTINGS_GALLERY = "com.solarized.firedown.preferences.downloads.gallery";

    public static final boolean DEFAULT_SETTINGS_SAVE_ASK = false;

    public static final String SETTINGS_DOH = "com.solarized.firedown.preferences.browser.doh";

    public static final String SETTINGS_DOH_SWITCH = "com.solarized.firedown.preferences.browser.doh.switch";

    public static final String SETTINGS_DOH_PREF = "com.solarized.firedown.preferences.browser.doh.pref";

    public static final String SETTINGS_DOH_CUSTOM = "com.solarized.firedown.preferences.browser.doh.custom";

    public static final int SETTINGS_DOH_CUSOTM_INT = 5;

    public static final String SETTINGS_TABS = "com.solarized.firedown.preferences.browser.tabs";
    public static final String SETTINGS_QUIT = "com.solarized.firedown.preferences.browser.quit";

    public static final String SETTINGS_QUIT_PREF = "com.solarized.firedown.preferences.browser.quit.pref";

    public static final String SETTINGS_QUIT_PREF_TABS = "com.solarized.firedown.preferences.browser.quit.pref.tabs";

    public static final String SETTINGS_QUIT_PREF_HISTORY = "com.solarized.firedown.preferences.browser.quit.pref.history";

    public static final String SETTINGS_QUIT_PREF_COOKIES = "com.solarized.firedown.preferences.browser.quit.pref.cookies";

    public static final String SETTINGS_QUIT_PREF_CACHE = "com.solarized.firedown.preferences.browser.quit.pref.cache";

    public static final String SETTINGS_COOKIES = "com.solarized.firedown.preferences.browser.cookies";

    public static final String DEFAULT_SETTINGS_DOH = String.valueOf(GeckoRuntimeSettings.TRR_MODE_OFF);

    public static final String DEFAULT_SETTINGS_COOKIES = String.valueOf(ContentBlocking.CookieBehavior.ACCEPT_NON_TRACKERS);

    public static final String SETTINGS_VERSION = "com.solarized.firedown.preferences.about.version";

    public static final String SETTINGS_GECKO = "com.solarized.firedown.preferences.about.gecko";

    public static final String SETTINGS_CONTACT = "com.solarized.firedown.preferences.about.contact";

    public static final String SETTINGS_WEBSITE = "com.solarized.firedown.preferences.about.website";

    public static final String SETTINGS_BLOCK_JAVASCRIPT = "com.solarized.firedown.preferences.browser.block.javascript";

    public static final String SETTINGS_APP_LOCK_MAIN = "com.solarized.firedown.preferences.lock.main";

    public static final String SETTINGS_APP_LOCK = "com.solarized.firedown.preferences.lock";

    public static final String SETTINGS_APP_LOCK_TIME = "com.solarized.firedown.preferences.lock.time";

    public static final String SETTINGS_APP_LOCK_UPDATE_TIME = "com.solarized.firedown.preferences.lock.update.time";

    public static final String SETTINGS_APP_LOCK_REQUIRED = "com.solarized.firedown.preferences.lock.required";

    public static final String SETTINGS_DONATE = "com.solarized.firedown.preferences.donate";

    public static final String SETTINGS_SEARCH_ENGINE = "com.solarized.firedown.preferences.search.engine";

    public static final String SETTINGS_ABOUT = "com.solarized.firedown.preferences.about";

    public static final String SETTINGS_LICENSE = "com.solarized.firedown.preferences.license";

    public static final String SETTINGS_SUPPORT = "com.solarized.firedown.preferences.support";

    public static final String SETTINGS_TABS_ARCHIVE = "com.solarized.firedown.preferences.tabs.archive";

    public static final String SETTINGS_TABS_ARCHIVE_LAST_RUN = "com.solarized.firedown.preferences.tabs.archive.last.run";
    public static final String SETTINGS_TABS_ARCHIVE_INTERVAL = "com.solarized.firedown.preferences.tabs.archive.interval";

    /**
     * Snapshot of the archived-tab count at the moment the user last
     * dismissed the archive banner. The banner re-appears when the live
     * archived count exceeds this snapshot; tapping dismiss writes the
     * current count back so the banner stays gone until *more* tabs land
     * in the archive. Matches the count-driven inactive-tabs UX in
     * Fennec / Chrome / Edge.
     */
    public static final String SETTINGS_TABS_ARCHIVE_BANNER_DISMISSED_AT = "com.solarized.firedown.preferences.tabs.archive.banner.dismissed.at";

    /**
     * Cached archived-tab count windowed by {@link #SETTINGS_TABS_ARCHIVE_BANNER_LAST_INTERVAL}.
     * Written by the banner observer in TabsFragment whenever the
     * archived-count LiveData fires; read synchronously on the next
     * fragment open so we can decide whether to show the banner row
     * without waiting on the Room count query. Removes the ~250 ms
     * spinner gap between "open tabs page" and "tabs visible". Matches
     * how Chrome / Fenix avoid spinners on tab-switcher open — both
     * keep their tab + chrome state in an already-warm in-memory
     * store; for our archive count which lives in Room a small
     * SharedPreferences cache is the equivalent.
     *
     * <p>Default {@code -1} means "no cache, fall back to async wait".
     * The interval is stored alongside so a settings change
     * (day / week / month) invalidates the cache.</p>
     */
    public static final String SETTINGS_TABS_ARCHIVE_BANNER_LAST_COUNT = "com.solarized.firedown.preferences.tabs.archive.banner.last.count";
    public static final String SETTINGS_TABS_ARCHIVE_BANNER_LAST_INTERVAL = "com.solarized.firedown.preferences.tabs.archive.banner.last.interval";

    public static final String SETTINGS_BLOCK_COOKIE_NOTICES = "com.solarized.firedown.preferences.ublock.block.cookie.notices";
    public static final boolean DEFAULT_BLOCK_COOKIE_NOTICES = false;

    public static final String DEFAULT_SEARCH_ENGINE = "StartPage";

    public static final String DEFAULT_SEARCH_AUTOCOMPLETE = "https://www.startpage.com/suggestions?q=%s&format=opensearch&segment=startpage.defaultffx";

    public static final String DEFAULT_SEARCH_FORMAT = "https://www.startpage.com/do/dsearch?q=%s&cat=we";

    public static final int DEFAULT_DOWNLOADS = 0;

    public static final String ONBOARDING_INFO = "com.solarized.firedown.preferences.onboarding.info";

    public static final long FIVE_MINUTES_INTERVAL = 300_000L;
    public static final long FIFTEEN_MINUTES_INTERVAL = 900_000L;
    public static final long ONE_HOUR_INTERVAL = 3_600_000L;
    public static final long ONE_DAY_INTERVAL = 86_400_000L;
    public static final long ONE_WEEK_INTERVAL = 604_800_000L;
    public static final long THIRTY_DAYS_INTERVAL = 2_592_000_000L;
    public static final long NEVER_INTERVAL     = -1L;

    public static final int LIST_LIMIT = 25;



    public static boolean getJavascriptEnabled(SharedPreferences sharedPreferences){
        Log.d(TAG, "getJavascriptEnabled : " + !sharedPreferences.getBoolean(SETTINGS_BLOCK_JAVASCRIPT, false));
        return !sharedPreferences.getBoolean(SETTINGS_BLOCK_JAVASCRIPT, false);
    }

    public static boolean getSaveToGallery(SharedPreferences sharedPreferences){
        return sharedPreferences.getBoolean(SETTINGS_GALLERY, false);
    }

    public static int getAntiTrackingCategories(SharedPreferences sharedPreferences){
        if(sharedPreferences.getBoolean(SETTINGS_ANTI_TRACKING_STRICT, false)){
            return ContentBlocking.AntiTracking.STRICT;
        }else if(sharedPreferences.getBoolean(SETTINGS_ANTI_TRACKING_DEFAULT, true)
                || sharedPreferences.getBoolean(SETTINGS_ANTI_TRACKING_CUSTOM, false)){
            return ContentBlocking.AntiTracking.DEFAULT;
        }else{
            return ContentBlocking.AntiTracking.NONE;
        }
    }

    public static int getEnhancedTrackingProtectionLevel(SharedPreferences sharedPreferences){
        if(sharedPreferences.getBoolean(SETTINGS_ANTI_TRACKING_STRICT, false)){
            return ContentBlocking.EtpLevel.STRICT;
        }else if(sharedPreferences.getBoolean(SETTINGS_ANTI_TRACKING_DEFAULT, true)
                || sharedPreferences.getBoolean(SETTINGS_ANTI_TRACKING_CUSTOM, false)){
            return ContentBlocking.EtpLevel.DEFAULT;
        }else{
            return ContentBlocking.EtpLevel.NONE;
        }
    }

    public static int getEnhancedTrackingProtectionCategories(SharedPreferences sharedPreferences){
        if(sharedPreferences.getBoolean(SETTINGS_ANTI_TRACKING_STRICT, false)){
            return ContentBlocking.EtpCategory.STRICT;
        }else{
            return ContentBlocking.EtpCategory.STANDARD;
        }
    }

    public static boolean getQueryParameterStrippingEnabled(SharedPreferences sharedPreferences){
        return sharedPreferences.getBoolean(SETTINGS_ANTI_TRACKING_CUSTOM, false);
    }

    public static String[] getQueryParameterStripList(SharedPreferences sharedPreferences){
        String raw = sharedPreferences.getString(SETTINGS_ANTI_TRACKING_STRIP_LIST, DEFAULT_QUERY_STRIP_LIST);
        if(raw == null) return new String[0];
        String trimmed = raw.trim();
        if(trimmed.isEmpty()) return new String[0];
        return trimmed.split("\\s+");
    }

    /**
     * DRM defaults to off. Firedown can't download DRM-protected content,
     * so the request_media_key_system_access prompt is dead-end noise for
     * the few sites that still gate playback on it. Users who want DRM
     * playback flip the toggle in settings.
     */
    public static boolean getDRMEnabled(SharedPreferences sharedPreferences){
        return sharedPreferences.getBoolean(SETTINGS_ENABLE_DRM, false);
    }


    public static int getCookieBehavior(SharedPreferences sharedPreferences){

        String cookieValue = sharedPreferences.getString(SETTINGS_COOKIES, DEFAULT_SETTINGS_COOKIES);

        return switch (cookieValue) {
            case "3" -> ContentBlocking.CookieBehavior.ACCEPT_VISITED;
            case "1" -> ContentBlocking.CookieBehavior.ACCEPT_FIRST_PARTY;
            case "4" -> ContentBlocking.CookieBehavior.ACCEPT_NON_TRACKERS;
            case "5" -> ContentBlocking.CookieBehavior.ACCEPT_FIRST_PARTY_AND_ISOLATE_OTHERS;
            case "2" -> ContentBlocking.CookieBehavior.ACCEPT_NONE;
            default -> ContentBlocking.CookieBehavior.ACCEPT_NON_TRACKERS;
        };
    }



}
