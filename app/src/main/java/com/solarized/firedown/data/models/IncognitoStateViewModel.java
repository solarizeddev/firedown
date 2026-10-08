package com.solarized.firedown.data.models;


import android.graphics.Bitmap;
import androidx.annotation.Nullable;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

import com.solarized.firedown.data.entity.CertificateInfoEntity;
import com.solarized.firedown.data.entity.GeckoStateEntity;
import com.solarized.firedown.data.repository.IncognitoStateRepository;
import com.solarized.firedown.geckoview.GeckoState;
import com.solarized.firedown.geckoview.GeckoUblockHelper;

import org.mozilla.geckoview.GeckoSession;

import java.util.List;

import javax.inject.Inject;

import dagger.hilt.android.lifecycle.HiltViewModel;

@HiltViewModel
public class IncognitoStateViewModel extends ViewModel {

    private final IncognitoStateRepository mRepository;
    private final GeckoUblockHelper mGeckoUblockHelper;

    @Inject
    public IncognitoStateViewModel(IncognitoStateRepository repository,
                                   GeckoUblockHelper geckoUblockHelper) {
        this.mRepository = repository;
        this.mGeckoUblockHelper = geckoUblockHelper;
    }

    /** A fresh screenshot of an incognito tab — memory-only in the
     *  TabThumbnailStore, see IncognitoStateRepository.updateThumb. */
    public void updateThumb(GeckoState geckoState, Bitmap bitmap) {
        mRepository.updateThumb(geckoState, bitmap);
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

    public LiveData<GeckoState> getTranslationStateChanges() {
        return mRepository.getTranslationStateLiveData();
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

    /** Drag-and-drop reorder from the tab switcher; see the repository. */
    public void moveGeckoState(int fromId, int toId) {
        mRepository.moveGeckoState(fromId, toId);
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

    // ── Ads/Trackers Count ───────────────────────────────────────────

    /**
     * Incognito-scoped ads-blocked counter. Mirrors
     * {@link GeckoStateViewModel#getAdsCount()} but only reflects
     * blocking activity on incognito tabs.
     */
    public LiveData<String> getAdsCount() {
        return mGeckoUblockHelper.getAdsBlockedLiveIncognito();
    }

    /**
     * Incognito-scoped per-tab blocked-host tally. Mirrors
     * {@link GeckoStateViewModel#getPageBlocks()} but only carries
     * data for incognito tabs.
     */
    public LiveData<List<GeckoUblockHelper.HostCount>> getPageBlocks() {
        return mGeckoUblockHelper.getPageBlocksLiveIncognito();
    }
}