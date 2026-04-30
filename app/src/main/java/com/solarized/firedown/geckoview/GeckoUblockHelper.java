package com.solarized.firedown.geckoview;

import android.text.TextUtils;
import android.util.Log;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Helper class to manage uBlock Origin state and communication.
 * Refactored to use Hilt and LiveData for reactive UI updates.
 */
@Singleton
public class GeckoUblockHelper {

    private static final String TAG = GeckoUblockHelper.class.getSimpleName();
    // LiveData streams for the UI to observe.
    //
    // Ads-blocked count is per-mode so the incognito toolbar never
    // reflects counts from regular browsing and vice versa. Callers
    // that know the session's incognito-ness should call
    // onAdsCount(count, isIncognito); the no-arg overload exists for
    // legacy callers and routes to the regular stream.
    private final MutableLiveData<String> mAdsBlockedLive = new MutableLiveData<>("0");
    private final MutableLiveData<String> mAdsBlockedLiveIncognito = new MutableLiveData<>("0");

    // Firewall activation is a global user preference — not per-mode.
    private final MutableLiveData<Boolean> mFirewallActiveLive = new MutableLiveData<>();

    // Internal state variables — written from extension callbacks (potentially
    // background) and read from UI getters; volatile guarantees visibility.
    private volatile boolean mJavascriptDisabled;
    private volatile boolean mFontsDisabled;
    private volatile boolean mMediaDisabled;

    @Inject
    public GeckoUblockHelper() {

    }


    /**
     * @return LiveData containing the current ads/trackers blocked count string
     * for regular browsing.
     */
    public LiveData<String> getAdsBlockedLive() {
        return mAdsBlockedLive;
    }

    /**
     * @return LiveData containing the current ads/trackers blocked count string
     * for incognito browsing.
     */
    public LiveData<String> getAdsBlockedLiveIncognito() {
        return mAdsBlockedLiveIncognito;
    }

    /**
     * @return LiveData containing the current activation status of the uBlock firewall.
     */
    public LiveData<Boolean> getFirewallActiveLive() {
        return mFirewallActiveLive;
    }


    // Cookie-notice blocking is a global filter-list selection, not per-hostname.
    // Reflects whether fanboy-cookiemonster is in µb.selectedFilterLists.
    private final MutableLiveData<Boolean> mCookieNoticesBlockedLive = new MutableLiveData<>(false);

    // --- State Management ---

    /**
     * Session-aware ads count. Routes to the correct per-mode stream.
     */
    public void onAdsCount(String count, boolean isIncognito) {
        String value = TextUtils.isEmpty(count) ? "0" : count;
        if (isIncognito) {
            mAdsBlockedLiveIncognito.postValue(value);
        } else {
            mAdsBlockedLive.postValue(value);
        }
    }

    /**
     * @return LiveData containing the current state of cookie-notice list
     * selection. True when fanboy-cookiemonster is selected.
     */
    public LiveData<Boolean> getCookieNoticesBlockedLive() {
        return mCookieNoticesBlockedLive;
    }


    /**
     * Called when the uBlock firewall settings change.
     */
    public void onFirewallChanged(boolean activated, boolean javascriptDisabled,
                                  boolean mediaDisabled, boolean fontsDisabled,
                                  boolean cookieNoticesBlocked) {

        Log.d(TAG, "onFirewallChanged: " + activated);
        mFirewallActiveLive.postValue(activated);
        this.mJavascriptDisabled = javascriptDisabled;
        this.mMediaDisabled = mediaDisabled;
        this.mFontsDisabled = fontsDisabled;
        mCookieNoticesBlockedLive.postValue(cookieNoticesBlocked);
    }

    // --- Standard Getters & Setters ---


    public boolean isActivated() {
        Boolean active = mFirewallActiveLive.getValue();
        return active != null && active;
    }

    public boolean isJavascriptDisabled() {
        return mJavascriptDisabled;
    }

    public void setJavascriptDisabled(boolean javascriptDisabled) {
        this.mJavascriptDisabled = javascriptDisabled;
    }

    public boolean isFontsDisabled() {
        return mFontsDisabled;
    }

    public void setFontsDisabled(boolean fontsDisabled) {
        this.mFontsDisabled = fontsDisabled;
    }

    public boolean isMediaDisabled() {
        return mMediaDisabled;
    }

    public void setMediaDisabled(boolean mediaDisabled) {
        this.mMediaDisabled = mediaDisabled;
    }

    /**
     * Resets the helper state (e.g., when a new session starts or engine is cleared).
     */
    public void clear() {
        Log.d(TAG, "onFirewallChanged clear");
        mAdsBlockedLive.postValue("0");
        mAdsBlockedLiveIncognito.postValue("0");
        mFirewallActiveLive.postValue(false);
        mJavascriptDisabled = false;
        mFontsDisabled = false;
        mMediaDisabled = false;
        mCookieNoticesBlockedLive.postValue(false);
    }
}