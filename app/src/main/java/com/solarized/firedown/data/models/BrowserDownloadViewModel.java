package com.solarized.firedown.data.models;

import androidx.annotation.Nullable;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.Transformations;
import androidx.lifecycle.ViewModel;

import com.solarized.firedown.BuildConfig;
import com.solarized.firedown.Sorting;
import com.solarized.firedown.data.entity.BrowserDownloadEntity;
import com.solarized.firedown.data.repository.BrowserDownloadRepository;
import com.solarized.firedown.data.repository.GeckoStateDataRepository;
import com.solarized.firedown.geckoview.GeckoRuntimeHelper;
import com.solarized.firedown.geckoview.GeckoState;
import com.solarized.firedown.geckoview.PriorityTaskThreadPoolExecutor;
import com.solarized.firedown.utils.BuildUtils;
import com.solarized.firedown.utils.DebugLog;
import com.solarized.firedown.utils.FileUriHelper;

import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import javax.inject.Inject;

import dagger.hilt.android.lifecycle.HiltViewModel;

@HiltViewModel
public class BrowserDownloadViewModel extends ViewModel {

    private final BrowserDownloadRepository mBrowserDownloadRepository;
    private final GeckoRuntimeHelper mGeckoRuntimeHelper;
    private final GeckoStateDataRepository mGeckoStateDataRepository;
    private final Sorting mSorting;
    private final PriorityTaskThreadPoolExecutor mPriorityExecutor;
    private final LiveData<List<BrowserDownloadEntity>> mObservableBrowser;
    private final MutableLiveData<Integer> mObservableBrowserType = new MutableLiveData<>();

    @Inject
    public BrowserDownloadViewModel(
            BrowserDownloadRepository repository,
            GeckoRuntimeHelper geckoRuntimeHelper,
            GeckoStateDataRepository geckoStateDataRepository,
            PriorityTaskThreadPoolExecutor priorityExecutor,
            Sorting sorting) {

        this.mBrowserDownloadRepository = repository;
        this.mGeckoRuntimeHelper = geckoRuntimeHelper;
        this.mGeckoStateDataRepository = geckoStateDataRepository;
        this.mPriorityExecutor = priorityExecutor;
        this.mSorting = sorting;

        // Use Transformations.switchMap to react to limit changes
        mObservableBrowser = Transformations.switchMap(mObservableBrowserType, limit ->
                Transformations.map(mBrowserDownloadRepository.getData(), entities ->
                        filter(entities, limit)
                )
        );
    }

    public LiveData<List<BrowserDownloadEntity>> getBrowserDownloads(int limit) {
        mObservableBrowserType.postValue(limit);
        return mObservableBrowser;
    }

    /**
     * In-flight inspect-task count (queued + running). The capture sheet uses
     * this to show a "scanning…" indicator while a capture is still being
     * processed (e.g. an HLS-master fetch that takes a few seconds).
     */
    public LiveData<Integer> getInflight() {
        return mPriorityExecutor.getInFlight();
    }

    private List<BrowserDownloadEntity> filter(List<BrowserDownloadEntity> entities, int limit) {
        if (entities == null) return null;

        // Note: Using the injected mGeckoRuntimeHelper instead of static call
        int currentTabId = mGeckoRuntimeHelper.getTabId();
        final int currentVisitId = currentVisitId();

        // Sort BEFORE limiting so the current page's media always floats to
        // the top (and never gets truncated by the limit). Order:
        //   1. captures from the page-visit you're on now, first
        //   2. within that group, by type: video, audio, subtitle, then rest
        //   3. then most-recent first (descending creationTime)
        // Step 1 is the session anchor. Within one tab the repo accumulates
        // captures across navigations; each is stamped with the navigation
        // visit id that was active when it was captured (GeckoState#getVisitId),
        // so "this page" is identified by a browser navigation boundary rather
        // than by matching origin URL strings — which different extensions
        // spell inconsistently (m./www., feed vs deep-link).
        // Step 2 surfaces the actual video + captions above the page's
        // thumbnails (which are captured later, so recency alone would bury the
        // video). It applies ONLY inside the current-page group; everything
        // below keeps pure recency.
        Comparator<BrowserDownloadEntity> order = Comparator
                .comparingInt((BrowserDownloadEntity e) ->
                        isCurrentPage(e, currentVisitId) ? 0 : 1)
                .thenComparingInt((BrowserDownloadEntity e) ->
                        isCurrentPage(e, currentVisitId) ? typeRank(e) : 0)
                .thenComparing(Comparator.reverseOrder());

        var stream = entities.stream()
                .filter(entity -> mSorting.getPredicateBrowser(entity) && entity.getTabId() == currentTabId)
                .sorted(order);

        if (limit > 0) {
            stream = stream.limit(limit);
        }

        List<BrowserDownloadEntity> result = BuildUtils.hasAndroid14()
                ? stream.toList()
                : stream.collect(Collectors.toList());
        logVisitTrace(currentTabId, currentVisitId, result);
        return result;
    }

    /**
     * VisitTrace: the READ side of the Captured pin — the tab's current anchor
     * plus each shown entity's stamped visit id, in render order. Pairs with
     * GeckoState.updateVisit's id-move lines and the capture stamps
     * (GeckoRuntimeHelper), so one repro of "the video isn't pinned first"
     * names the cause in logcat: entities whose {@code v=} trails the anchor
     * were stamped under a page key the tab has since respelled (e.g. a
     * YouTube Mix's list=/index= params). {@code adb logcat -s VisitTrace:*}.
     * Debug builds only (DebugLog no-ops in release); names are previews per
     * the truncate-user-text logging rule.
     */
    private static void logVisitTrace(int currentTabId, int currentVisitId,
                                      List<BrowserDownloadEntity> result) {
        if (!BuildConfig.DEBUG) {
            return; // skip the string-building work entirely in release
        }
        StringBuilder sb = new StringBuilder("sheet tab=").append(currentTabId)
                .append(" anchor=").append(currentVisitId);
        for (BrowserDownloadEntity e : result) {
            sb.append("\n  v=").append(e.getVisitId())
                    .append(e.getVisitId() == currentVisitId ? " *" : "  ")
                    .append(' ').append(DebugLog.preview(e.getFileName()));
        }
        DebugLog.d("VisitTrace", sb.toString());
    }

    /** True when {@code e} belongs to the page currently shown in the active
     *  tab. {@code currentVisitId == 0} means no anchor (home / unavailable),
     *  in which case nothing is "this page" and the list is pure recency. */
    private static boolean isCurrentPage(BrowserDownloadEntity e, int currentVisitId) {
        return currentVisitId > 0 && e.getVisitId() == currentVisitId;
    }

    /** Ordering rank by media kind, used only within the current-page group:
     *  the downloadable video first, then audio, then subtitles/CC, then
     *  everything else (thumbnails, etc.). Mirrors the grid's mime-based
     *  VIDEO/AUDIO/SUBTITLE labelling so the badge and the order agree. */
    private static int typeRank(BrowserDownloadEntity e) {
        String mime = e.getMimeType();
        if (FileUriHelper.isVideo(mime)) return 0;
        if (e.isAudio() || FileUriHelper.isAudio(mime)) return 1;
        if (FileUriHelper.isSubtitle(mime)) return 2;
        return 3;
    }

    /** Navigation-visit id of the page currently shown in the active tab, or
     *  0 when there's no active page (home / state unavailable). The session
     *  anchor: entities stamped with this id are "this page". */
    private int currentVisitId() {
        GeckoState state = mGeckoStateDataRepository.peekCurrentGeckoState();
        if (state == null || state.isHome()) return 0;
        return state.getVisitId();
    }

    /**
     * Count of subtitle entities per video origin, computed from the FULL
     * (unfiltered) repository list — not the chip-filtered adapter list, so
     * the CC badge on a video row stays correct even when the Subtitle chip
     * has filtered the caption siblings out of view. Keyed by origin; only
     * origins with at least one subtitle appear. Restricted to the current
     * tab to match the list the adapter shows.
     */
    public Map<String, Integer> subtitleCountsByOrigin() {
        List<BrowserDownloadEntity> entities = mBrowserDownloadRepository.getData().getValue();
        if (entities == null) return Collections.emptyMap();
        int currentTabId = mGeckoRuntimeHelper.getTabId();
        Map<String, Integer> counts = new HashMap<>();
        for (BrowserDownloadEntity e : entities) {
            if (e == null || e.getTabId() != currentTabId) continue;
            if (!FileUriHelper.isSubtitle(e.getMimeType())) continue;
            String origin = e.getFileOrigin();
            if (origin == null || origin.isEmpty()) continue;
            counts.merge(origin, 1, Integer::sum);
        }
        return counts;
    }

    /**
     * Subtitle entities captured for the given video origin, current tab only.
     * Used by the variant picker to render its captions multi-select section.
     * Returns an empty list if the origin is unknown or has no captions.
     */
    public List<BrowserDownloadEntity> subtitlesForOrigin(@Nullable String origin) {
        if (origin == null || origin.isEmpty()) return Collections.emptyList();
        List<BrowserDownloadEntity> entities = mBrowserDownloadRepository.getData().getValue();
        if (entities == null) return Collections.emptyList();
        int currentTabId = mGeckoRuntimeHelper.getTabId();
        return entities.stream()
                .filter(e -> e != null
                        && e.getTabId() == currentTabId
                        && FileUriHelper.isSubtitle(e.getMimeType())
                        && origin.equals(e.getFileOrigin()))
                .collect(Collectors.toList());
    }

    public int getCurrentSortBrowserId(){
        return mSorting.getCurrentSortBrowserId();
    }

    public String getCurrentSortForIds(int selectedIds){
        return mSorting.getCurrentSortForIds(selectedIds);
    }

    public void setCurrentSortBrowser(int type){
        mSorting.setCurrentSortBrowser(type);
    }

    public void setCurrentSortBrowser(String type){
        mSorting.setCurrentSortBrowser(type);
    }

    public void clearBrowserDownloads() {
        mBrowserDownloadRepository.postClear();
    }

    public void sortBrowserDownloads(String sorting) {
        mSorting.setCurrentSortBrowser(sorting);
        mBrowserDownloadRepository.postComplete();
    }

    public void update() {
        mBrowserDownloadRepository.postComplete();
    }

    public void loadMore(int limit) {
        mObservableBrowserType.postValue(limit);
    }
}