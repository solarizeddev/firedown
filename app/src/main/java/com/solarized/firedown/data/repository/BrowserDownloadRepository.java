package com.solarized.firedown.data.repository;


import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import androidx.lifecycle.MutableLiveData;
import com.solarized.firedown.data.entity.BrowserDownloadEntity;
import com.solarized.firedown.ffmpegutils.FFmpegEntity;
import com.solarized.firedown.utils.BuildUtils;
import com.solarized.firedown.utils.FileUriHelper;

import org.apache.commons.collections4.QueueUtils;
import org.apache.commons.collections4.queue.CircularFifoQueue;
import java.util.Collections;
import java.util.List;
import java.util.Queue;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import javax.inject.Inject;
import javax.inject.Singleton;

@Singleton
public class BrowserDownloadRepository {

    private static final String TAG = BrowserDownloadRepository.class.getSimpleName();
    private static final int INTERCEPT_SIZE = 1024;

    /** Max Hamming distance (out of 64 bits) between two image perceptual
     *  hashes for them to count as the same picture. Small enough to avoid
     *  folding distinct images, loose enough to absorb scaling artefacts
     *  between sizes of the same image. */
    private static final int PHASH_MAX_DISTANCE = 8;

    private final Queue<BrowserDownloadEntity> mInterceptedList;
    private final MutableLiveData<List<BrowserDownloadEntity>> mMediatorData;

    /**
     * Coalesces list emissions. A media-heavy page (twitch/kick) can capture
     * 200+ items in a burst, and each {@code addValue} would otherwise re-sort
     * the whole list and dispatch a DiffUtil pass. We emit on the leading edge
     * (the first capture shows instantly) then throttle the rest to one
     * emission per {@link #EMIT_THROTTLE_MS} window (a single trailing flush),
     * collapsing the burst into a handful of updates. The capture sheet's
     * "scanning" spinner already signals ongoing work, so the small batching
     * latency is invisible.
     */
    private static final long EMIT_THROTTLE_MS = 175L;
    private final Object mEmitLock = new Object();
    private final Handler mEmitHandler = new Handler(Looper.getMainLooper());
    private long mLastEmit;
    private boolean mEmitScheduled;
    private final Runnable mEmitRunnable = () -> {
        synchronized (mEmitLock) {
            mEmitScheduled = false;
            mLastEmit = SystemClock.uptimeMillis();
        }
        doEmit();
    };

    @Inject
    public BrowserDownloadRepository() {
        mInterceptedList = QueueUtils.synchronizedQueue(new CircularFifoQueue<>(INTERCEPT_SIZE));
        mMediatorData = new MutableLiveData<>();
    }

    public MutableLiveData<List<BrowserDownloadEntity>> getData() {
        return mMediatorData;
    }

    private boolean isPresent(BrowserDownloadEntity oldEntity, BrowserDownloadEntity newEntity) {
        // Different tab → never the same entry
        if (oldEntity.getTabId() != newEntity.getTabId()) return false;

        // Same uid → exact dup, fast path
        if (oldEntity.getUid() == newEntity.getUid()) return true;

        String oldUrl = oldEntity.getFileUrl();
        String newUrl = newEntity.getFileUrl();
        if (oldUrl == null || newUrl == null) return false;

        // Identical URLs (uid hash collision possible but rare; still a dup)
        if (oldUrl.equals(newUrl)) return true;

        // URLs that differ only in fragment or trailing slash
        if (stripTrivial(oldUrl).equals(stripTrivial(newUrl))) return true;

        String oldMimeType = oldEntity.getMimeType();
        String newMimeType = newEntity.getMimeType();

        if (FileUriHelper.isImage(oldMimeType) && FileUriHelper.isImage(newMimeType)) {
            // Content-based de-dup. The native metadata reader stamps each image
            // with a perceptual hash (dHash) of its pixels; two URLs are the
            // same picture when those hashes are within a small Hamming distance
            // — independent of size, host or CDN, and with no URL-pattern rules.
            // 0 means "not hashed" (flat image / decode skipped), in which case
            // we rely on the exact-URL checks already done above.
            long a = oldEntity.getPHash();
            long b = newEntity.getPHash();
            if (a != 0 && b != 0 && Long.bitCount(a ^ b) <= PHASH_MAX_DISTANCE) {
                return true;
            }
        }

        return false;
    }

    public boolean isEmpty() {
        return mInterceptedList.isEmpty();
    }

    public boolean contains(BrowserDownloadEntity browserDownloadEntity) {
        synchronized (mInterceptedList) {
            for (BrowserDownloadEntity entity : mInterceptedList) {
                if (isPresent(entity, browserDownloadEntity))
                    return true;
            }
            return false;
        }
    }

    public void addValue(BrowserDownloadEntity browserDownloadEntity) {
        boolean added = false;
        synchronized (mInterceptedList) {
            BrowserDownloadEntity match = null;
            for (BrowserDownloadEntity entity : mInterceptedList) {
                if (isPresent(entity, browserDownloadEntity)) {
                    match = entity;
                    break;
                }
            }
            if (match == null) {
                Log.d(TAG, "addValue: " + browserDownloadEntity.getFileUrl() + " tab: " + browserDownloadEntity.getTabId() + " uid: " + browserDownloadEntity.getUid());
                mInterceptedList.add(browserDownloadEntity);
                added = true;
            } else if (imagePixels(browserDownloadEntity) > imagePixels(match)) {
                // Same PICTURE, materially larger rendition — upgrade in place
                // instead of dropping the newcomer. The pHash dedup collapses
                // the same photo across URLs (by design), but first-wins
                // inverted quality on gallery pages: Google Maps loads a place
                // photo's THUMBNAIL first and the full-size copy only when the
                // user opens it, so the hero arrived, probed fine, and was
                // discarded as a "duplicate" of its own thumb — the Captured
                // sheet kept the small copy and the user "never got the image".
                // Pixels come from the probed stream info ("WxH"), so a
                // URL-level dup (same bytes, equal pixels) never churns here,
                // and non-image matches compare 0 > 0 and keep the old
                // behavior. The pre-probe contains() gate only matches by
                // url/uid (pHash is 0 before the probe), so the larger copy is
                // always probed and reaches this comparison.
                Log.d(TAG, "addValue: upgrading same-image capture to larger rendition: "
                        + browserDownloadEntity.getFileUrl());
                mInterceptedList.remove(match);
                mInterceptedList.add(browserDownloadEntity);
                added = true;
            }
        }
        // Throttled emit, outside the list lock (see scheduleEmit / mEmitRunnable).
        if (added) {
            scheduleEmit();
        }
    }

    /** Resolution WxH pattern in a probed image stream's info string
     *  (FFmpegMetaDataReader formats "1024x768"; SVG carries a prefix). */
    private static final Pattern RESOLUTION_RE = Pattern.compile("(\\d{1,5})\\s*x\\s*(\\d{1,5})");

    /**
     * Pixel area of a captured IMAGE entity, parsed from its probed stream
     * info. 0 = unknown/not an image — callers compare with {@code >}, so an
     * unknown never displaces anything and is never displaced by another
     * unknown.
     */
    private static long imagePixels(BrowserDownloadEntity entity) {
        if (!FileUriHelper.isImage(entity.getMimeType())) {
            return 0L;
        }
        List<FFmpegEntity> streams = entity.getStreams();
        if (streams == null || streams.isEmpty()) {
            return 0L;
        }
        String info = streams.get(0).getInfo();
        if (info == null) {
            return 0L;
        }
        Matcher m = RESOLUTION_RE.matcher(info);
        if (!m.find()) {
            return 0L;
        }
        try {
            return Long.parseLong(m.group(1)) * Long.parseLong(m.group(2));
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    public void postComplete() {
        // Force an immediate emit (e.g. a download finished) and reset the
        // throttle window so a following capture burst still gets its leading edge.
        synchronized (mEmitLock) {
            mEmitHandler.removeCallbacks(mEmitRunnable);
            mEmitScheduled = false;
            mLastEmit = SystemClock.uptimeMillis();
        }
        doEmit();
    }

    public void postClear() {
        synchronized (mEmitLock) {
            mEmitHandler.removeCallbacks(mEmitRunnable);
            mEmitScheduled = false;
            mLastEmit = SystemClock.uptimeMillis();
        }
        synchronized (mInterceptedList) {
            mInterceptedList.clear();
        }
        mMediatorData.postValue(null);
    }

    public void trimTabs(int tabId) {
        synchronized (mInterceptedList) {
            mInterceptedList.removeIf(entity -> entity.getTabId() == tabId);
        }
    }

    /**
     * Leading + trailing throttle. The first call after a quiet period emits
     * immediately; calls within the window schedule a single trailing flush so
     * a capture burst collapses into one emission per window.
     */
    private void scheduleEmit() {
        synchronized (mEmitLock) {
            long now = SystemClock.uptimeMillis();
            long sinceLast = now - mLastEmit;
            if (sinceLast < EMIT_THROTTLE_MS) {
                if (!mEmitScheduled) {
                    mEmitScheduled = true;
                    mEmitHandler.postDelayed(mEmitRunnable, EMIT_THROTTLE_MS - sinceLast);
                }
                return;
            }
            mEmitHandler.removeCallbacks(mEmitRunnable);
            mEmitScheduled = false;
            mLastEmit = now;
            // leading edge — emit below, outside the lock
        }
        doEmit();
    }

    private void doEmit() {
        List<BrowserDownloadEntity> sortedList;
        // Snapshot+sort under the list lock — the trailing flush runs on the
        // main thread, so it can't rely on a caller already holding it.
        synchronized (mInterceptedList) {
            if (BuildUtils.hasAndroid14()) {
                sortedList = mInterceptedList.stream()
                        .sorted(Collections.reverseOrder())
                        .toList();
            } else {
                sortedList = mInterceptedList.stream()
                        .sorted(Collections.reverseOrder())
                        .collect(Collectors.toList());
            }
        }
        mMediatorData.postValue(sortedList);
    }


    private static String stripTrivial(String url) {
        if (url == null) return "";
        int hash = url.indexOf('#');
        if (hash >= 0) url = url.substring(0, hash);
        if (url.endsWith("/")) url = url.substring(0, url.length() - 1);
        // Drop a leading "www." on the host. The same media file is routinely
        // reachable from both the apex and the www host, and a page can mix the
        // two for one video: its <source>/relative URL (and the actual on-wire
        // fetch) resolve to whatever host the page loaded from — e.g. the apex —
        // while its og:video / JSON-LD contentUrl hardcode the www host. Those
        // two URLs differ only by the "www." label, so uid (= url.hashCode())
        // differs and the capture lands twice. Folding the prefix here collapses
        // them to one entry; an honest cross-host duplicate is not a case worth
        // preserving on the capture sheet.
        int scheme = url.indexOf("://");
        if (scheme >= 0) {
            int hostStart = scheme + 3;
            if (url.regionMatches(true, hostStart, "www.", 0, 4)) {
                url = url.substring(0, hostStart) + url.substring(hostStart + 4);
            }
        }
        return url;
    }

}