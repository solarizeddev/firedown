package com.solarized.firedown.data.models;

import android.content.Context;
import android.graphics.Bitmap;
import android.text.TextUtils;

import androidx.annotation.Nullable;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

import com.solarized.firedown.StoragePaths;
import com.solarized.firedown.data.di.Qualifiers;
import com.solarized.firedown.data.entity.CertificateInfoEntity;
import com.solarized.firedown.data.entity.GeckoStateEntity;
import com.solarized.firedown.data.repository.GeckoStateDataRepository;
import com.solarized.firedown.data.repository.TrackingPermissionRepository;
import com.solarized.firedown.geckoview.GeckoState;
import com.solarized.firedown.geckoview.GeckoUblockHelper;
import org.mozilla.geckoview.GeckoSession;

import java.util.List;
import java.util.concurrent.Executor;

import javax.inject.Inject;

import dagger.hilt.android.lifecycle.HiltViewModel;
import dagger.hilt.android.qualifiers.ApplicationContext;

@HiltViewModel
public class GeckoStateViewModel extends ViewModel {

    private static final String TAG = GeckoStateViewModel.class.getSimpleName();

    private final MutableLiveData<Integer> mArchivedCountEvent = new MutableLiveData<>();
    private final GeckoStateDataRepository mRepository;
    private final GeckoUblockHelper mGeckoUblockHelper;
    private final TrackingPermissionRepository mTrackingRepository;
    private final Executor mDiskIOExecutor;
    private final Context mContext;

    @Inject
    public GeckoStateViewModel(GeckoUblockHelper geckoUblockHelper,
                               GeckoStateDataRepository repository,
                               TrackingPermissionRepository trackingRepository,
                               @Qualifiers.DiskIO Executor diskExecutor,
                               @ApplicationContext Context context) {
        this.mGeckoUblockHelper = geckoUblockHelper;
        this.mRepository = repository;
        this.mTrackingRepository = trackingRepository;
        this.mDiskIOExecutor = diskExecutor;
        this.mContext = context;
    }

    /**
     * Checks if protection is active.
     * If the repository CONTAINS the URL, it's an exception (Protection is OFF).
     */
    public boolean isTrackingProtected(String uri) {
        if (TextUtils.isEmpty(uri)) {
            mTrackingRepository.setTrackingMutableData(true);
            return true;
        }

        // If the repository contains the domain, the user has "allowed" trackers (disabled shield)
        boolean hasException = mTrackingRepository.contains(uri);

        mTrackingRepository.setTrackingMutableData(!hasException);

        return !hasException;
    }

    public void toggleTrackingProtection(GeckoState state, boolean enabled) {
        if (state == null || state.getEntityUri() == null) return;

        String url = state.getEntityUri();

        if (enabled) {
            // Turning protection ON: delete the exception from the database
            mTrackingRepository.delete(url);
            mTrackingRepository.setTrackingMutableData(true);
        } else {
            // Turning protection OFF: add the exception to the database
            mTrackingRepository.add(url);
            mTrackingRepository.setTrackingMutableData(false);
        }

        // Apply to the GeckoSession live
        state.setTrackingProtection(enabled);
    }

    public MutableLiveData<Boolean> getTackingEnabled(){
        return mTrackingRepository.getTrackingMutableLiveData();
    }

    public MutableLiveData<CertificateInfoEntity> getCertificateData(){
        return mRepository.getCertMutableLiveData();
    }

    public LiveData<String> getAdsCount() {
        return mGeckoUblockHelper.getAdsBlockedLive();
    }

    public LiveData<Boolean> isAdsFilterEnabled() {
        return mGeckoUblockHelper.getFirewallActiveLive();
    }

    /**
     * Exposes the LiveData from the repository so the Fragment can
     * react to tab changes (new tabs, closed tabs, etc.)
     */
    public LiveData<List<GeckoStateEntity>> getTabs() {
        return mRepository.getTabsLiveData();
    }

    /**
     * Helper to get the current active state
     */
    public GeckoState getCurrentState() {
        return mRepository.getCurrentGeckoState();
    }

    public GeckoState getGeckoState(int sessionId) {
        return mRepository.getGeckoState(sessionId);
    }

    public LiveData<Integer> getTabsCount() {
        return mRepository.getTabsLiveCount();
    }


    public GeckoState getGeckoState(GeckoSession geckoSession) {
        return mRepository.getGeckoState(geckoSession);
    }

    /**
     * Returns the current active GeckoState, creating a new home tab if
     * none exists.  Use this only in code paths where a tab <b>must</b>
     * exist (user actions, intent handling, new-tab creation).
     *
     * @return the active GeckoState, never {@code null}
     * @see #peekCurrentGeckoState()
     */
    public GeckoState getCurrentGeckoState(){
        return mRepository.getCurrentGeckoState();
    }

    /**
     * Returns the current active GeckoState without side effects, or
     * {@code null} if no active tab exists.
     *
     * <p>Use this in read-only / guard contexts — session reconnection
     * checks, toolbar UI updates, observer callbacks — where accidentally
     * creating a tab would be incorrect.</p>
     *
     * @return the active GeckoState or {@code null}
     * @see #getCurrentGeckoState()
     */
    @Nullable
    public GeckoState peekCurrentGeckoState() {
        return mRepository.peekCurrentGeckoState();
    }

    public void closeGeckoState(GeckoState geckoState){
        mRepository.closeGeckoState(geckoState);
    }

    public void setGeckoState(GeckoState geckoState, boolean active){
        mRepository.setGeckoState(geckoState, active);
    }

    public void deleteAll(){
        mRepository.deleteAll();
    }

    public void updateThumb(GeckoState geckoState, Bitmap bitmap){
        mRepository.updateThumb(geckoState, bitmap);
    }

    public void notifyTabs(){
        mRepository.notifyTabs();
    }

    public boolean isCurrentGeckoState(GeckoState geckoState){
        return mRepository.isCurrentGeckoState(geckoState);
    }

    public void clearStorage(){
        //Delete cache Files
        mDiskIOExecutor.execute(() -> StoragePaths.clearCacheFolder(mContext));
    }

    /**
     * Returns a LiveData that emits the number of tabs archived by the
     * most recent {@link #archiveInactiveTabs} call.  Observed by
     * TabsFragment to show the info banner.
     */
    public LiveData<Integer> getArchivedCountEvent() {
        return mArchivedCountEvent;
    }

    /**
     * Clears the archived-count event so the banner is dismissed.
     */
    public void clearArchivedCountEvent() {
        mArchivedCountEvent.setValue(null);
    }

    /**
     * Archives tabs inactive for longer than {@code maxInactiveMillis}
     * and posts the result count to {@link #getArchivedCountEvent()}.
     *
     * <p>Runs on the disk I/O executor so it never blocks the main thread.</p>
     *
     * @param maxInactiveMillis inactivity threshold in milliseconds
     */
    public void archiveInactiveTabs(long maxInactiveMillis) {
        mDiskIOExecutor.execute(() -> {
            int count = mRepository.archiveInactiveTabs(maxInactiveMillis);
            mArchivedCountEvent.postValue(count);
        });
    }


    public boolean isEmpty() {
        return mRepository.isEmpty();
    }
}