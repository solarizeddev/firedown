package com.solarized.firedown;

import android.content.SharedPreferences;
import android.util.Log;

import org.mozilla.geckoview.ContentBlocking;
import org.mozilla.geckoview.GeckoRuntimeSettings;


public class Preferences {

    private static final String TAG = Preferences.class.getSimpleName();

    public static final String UPDATE_APK = "firedown.apk";

    public static final String UPDATE_URL = "https://www.firedown.app/status.json";

    public static final int EXTRA_TOUCH_AREA_DP = 12;

    public static final String CLIPBOARD_LABEL = "com.solarized.firedown.clipboard.label";

    public static final String SORT_LOCAL = "com.solarized.firedown.preferences.sort.local";

    public static final String SORT_LIST = "com.solarized.firedown.preferences.sort.list";

    public static final String SORT_TABS_LIST = "com.solarized.firedown.preferences.sort.tabs.list";

    public static final String SORT_DOWNLOADS_LIST = "com.solarized.firedown.preferences.sort.downloads.list";

    public static final String SORT_VAULT_LIST = "com.solarized.firedown.preferences.sort.vault.list";

    public static final String UNIQUE_ID = "com.solarized.firedown.id";

    public static final String KEY_ID = "com.solarized.firedown.key";

    public static final String SETTINGS_AUTOFILL = "com.solarized.firedown.preferences.browser.password";

    public static final String SETTINGS_BLOCK_LOCATION = "com.solarized.firedown.preferences.browser.block.location";

    public static final String SETTINGS_THEME = "com.solarized.firedown.preferences.theme";

    public static final String SETTINGS_THEME_DEFAULT = "com.solarized.firedown.preferences.theme.default";

    public static final String SETTINGS_THEME_DARK = "com.solarized.firedown.preferences.theme.dark";

    public static final String SETTINGS_THEME_LIGHT = "com.solarized.firedown.preferences.theme.light";

    public static final String SETTINGS_ENABLE_JIT = "com.solarized.firedown.preferences.browser.enable.jit";

    public static final String SETTINGS_DISABLE_WEBGL = "com.solarized.firedown.preferences.browser.disable.webgl";

    public static final String SETTINGS_ENABLE_RESIST_FINGERPRINTING = "com.solarized.firedown.preferences.browser.enable.resist.fingerprinting";

    public static final String SETTINGS_ENABLE_WEBRTC = "com.solarized.firedown.preferences.browser.enable.webrtc";
    
    public static final String SETTINGS_ANTI_TRACKING = "com.solarized.firedown.preferences.browser.tracking";

    public static final String SETTINGS_ANTI_TRACKING_DEFAULT = "com.solarized.firedown.preferences.browser.tracking.default";

    public static final String SETTINGS_ANTI_TRACKING_STRICT = "com.solarized.firedown.preferences.browser.tracking.strict";

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

    public static final int SHORTCUTS_LIST_LIMIT = 8;


    public static boolean getJavascriptEnabled(SharedPreferences sharedPreferences){
        Log.d(TAG, "getJavascriptEnabled : " + !sharedPreferences.getBoolean(SETTINGS_BLOCK_JAVASCRIPT, false));
        return !sharedPreferences.getBoolean(SETTINGS_BLOCK_JAVASCRIPT, false);
    }

    public static boolean getSaveToGallery(SharedPreferences sharedPreferences){
        return sharedPreferences.getBoolean(SETTINGS_GALLERY, false);
    }

    public static int getAntiTrackingCategories(SharedPreferences sharedPreferences){
        if(sharedPreferences.getBoolean(SETTINGS_ANTI_TRACKING_DEFAULT, true)){
            return ContentBlocking.AntiTracking.DEFAULT;
        }else if(sharedPreferences.getBoolean(SETTINGS_ANTI_TRACKING_STRICT, false)){
            return ContentBlocking.AntiTracking.STRICT;
        }else{
            return ContentBlocking.AntiTracking.NONE;
        }
    }

    public static int getEnhancedTrackingProtectionLevel(SharedPreferences sharedPreferences){
        if(sharedPreferences.getBoolean(SETTINGS_ANTI_TRACKING_DEFAULT, true)){
            return ContentBlocking.EtpLevel.DEFAULT;
        }else if(sharedPreferences.getBoolean(SETTINGS_ANTI_TRACKING_STRICT, false)){
            return ContentBlocking.EtpLevel.STRICT;
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
