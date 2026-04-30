package com.solarized.firedown.data.models;

import android.text.TextUtils;

import androidx.annotation.Nullable;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

import com.solarized.firedown.data.entity.CertificateInfoEntity;
import com.solarized.firedown.data.entity.GeckoStateEntity;
import com.solarized.firedown.data.repository.IncognitoStateRepository;
import com.solarized.firedown.data.repository.IncognitoTrackingPermissionRepository;
import com.solarized.firedown.geckoview.GeckoState;
import com.solarized.firedown.geckoview.GeckoUblockHelper;

import org.mozilla.geckoview.GeckoSession;

import java.util.List;

import javax.inject.Inject;

import dagger.hilt.android.lifecycle.HiltViewModel;

@HiltViewModel
public class IncognitoStateViewModel extends ViewModel {

    private final IncognitoStateRepository mRepository;
    private final IncognitoTrackingPermissionRepository mTrackingRepository;
    private final GeckoUblockHelper mGeckoUblockHelper;

    @Inject
    public IncognitoStateViewModel(IncognitoStateRepository repository,
                                   GeckoUblockHelper geckoUblockHelper) {
        this.mRepository = repository;
        this.mTrackingRepository = repository.getTrackingRepository();
        this.mGeckoUblockHelper = geckoUblockHelper;
    }

    // ── Tabs ─────────────────────────────────────────────────────────

    public LiveData<List<GeckoStateEntity>> getTabs() {
        return mRepository.getTabsLiveData();
    }

    public LiveData<Integer> getTabsCount() {
        return mRepository.getTabsLiveCount();
    }

    public GeckoState getCurrentGeckoState() {
        return mRepository.getCurrentGeckoState();
    }

    @Nullable
    public GeckoState peekCurrentGeckoState() {
        return mRepository.peekCurrentGeckoState();
    }

    public GeckoState getGeckoState(int sessionId) {
        return mRepository.getGeckoState(sessionId);
    }

    public GeckoState getGeckoState(GeckoSession geckoSession) {
        return mRepository.getGeckoState(geckoSession);
    }

    public void setGeckoState(GeckoState geckoState, boolean active) {
        mRepository.setGeckoState(geckoState, active);
    }

    public void closeGeckoState(GeckoState geckoState) {
        mRepository.closeGeckoState(geckoState);
    }

    public void deleteAll() {
        mRepository.deleteAll();
    }

    public boolean isCurrentGeckoState(GeckoState geckoState) {
        return mRepository.isCurrentGeckoState(geckoState);
    }

    public boolean isEmpty() {
        return mRepository.isEmpty();
    }

    public void notifyTabs() {
        mRepository.notifyTabs();
    }

    // ── Certificate ──────────────────────────────────────────────────

    public MutableLiveData<CertificateInfoEntity> getCertificateData() {
        return mRepository.getCertMutableLiveData();
    }

    // ── Tracking Protection ──────────────────────────────────────────

    /**
     * Checks if protection is active for the given URI.
     * Uses the ephemeral in-memory exception set — no database reads.
     *
     * <p>If the repository contains the domain, the user has disabled
     * the shield for that domain during this incognito session
     * (protection is OFF).</p>
     */
    public boolean isTrackingProtected(String uri) {
        if (TextUtils.isEmpty(uri)) {
            mTrackingRepository.setTrackingMutableData(true);
            return true;
        }

        boolean hasException = mTrackingRepository.contains(uri);
        mTrackingRepository.setTrackingMutableData(!hasException);
        return !hasException;
    }

    /**
     * Toggles tracking protection for the given GeckoState.
     * Exceptions are stored only in memory and discarded when the
     * incognito session ends.
     */
    public void toggleTrackingProtection(GeckoState state, boolean enabled) {
        if (state == null || state.getEntityUri() == null) return;

        String url = state.getEntityUri();

        if (enabled) {
            mTrackingRepository.delete(url);
            mTrackingRepository.setTrackingMutableData(true);
        } else {
            mTrackingRepository.add(url);
            mTrackingRepository.setTrackingMutableData(false);
        }

        state.setTrackingProtection(enabled);
    }

    public MutableLiveData<Boolean> getTrackingEnabled() {
        return mTrackingRepository.getTrackingMutableLiveData();
    }

    // ── Ads/Trackers Count ───────────────────────────────────────────

    /**
     * Incognito-scoped ads-blocked counter. Mirrors
     * {@link GeckoStateViewModel#getAdsCount()} but only reflects
     * blocking activity on incognito tabs.
     */
    public LiveData<String> getAdsCount() {
        return mGeckoUblockHelper.getAdsBlockedLiveIncognito();
    }
}