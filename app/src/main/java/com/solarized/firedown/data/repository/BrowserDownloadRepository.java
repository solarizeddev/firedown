package com.solarized.firedown.data.repository;


import android.util.Log;

import androidx.lifecycle.MutableLiveData;
import com.solarized.firedown.data.entity.BrowserDownloadEntity;
import com.solarized.firedown.utils.BuildUtils;
import com.solarized.firedown.utils.FileUriHelper;

import org.apache.commons.collections4.QueueUtils;
import org.apache.commons.collections4.queue.CircularFifoQueue;
import java.util.Collections;
import java.util.List;
import java.util.Queue;
import java.util.stream.Collectors;
import javax.inject.Inject;
import javax.inject.Singleton;

@Singleton
public class BrowserDownloadRepository {

    private static final String TAG = BrowserDownloadRepository.class.getSimpleName();
    private static final int INTERCEPT_SIZE = 1024;

    private final Queue<BrowserDownloadEntity> mInterceptedList;
    private final MutableLiveData<List<BrowserDownloadEntity>> mMediatorData;

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

        if(FileUriHelper.isImage(oldMimeType) && FileUriHelper.isImage(newMimeType)){
            // Canonical CDN-aware key (strips tokens, format, resize/crop dirs)
            String oldCanonical = canonicalKey(oldUrl);
            String newCanonical = canonicalKey(newUrl);
            if (!oldCanonical.isEmpty() && oldCanonical.equals(newCanonical)) return true;

            // Last-segment-stem fallback: same host + same filename without extension.
            // Catches CDNs that don't match canonicalKey's pattern set, but only
            // when the stem looks like a hash-derived identifier — generic names
            // like "hqdefault" or "thumbnail" repeat across distinct assets where
            // the unique id lives in a parent path segment.
            String oldStem = lastSegmentStem(oldUrl);
            String newStem = lastSegmentStem(newUrl);
            if (oldStem != null && oldStem.equals(newStem) && isLikelyHashStem(oldStem)) {
                return sameHost(oldUrl, newUrl);
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
        synchronized (mInterceptedList) {
            boolean exists = false;
            for (BrowserDownloadEntity entity : mInterceptedList) {
                if (isPresent(entity, browserDownloadEntity)) {
                    exists = true;
                    break;
                }
            }
            if (!exists) {
                Log.d(TAG, "addValue: " + browserDownloadEntity.getFileUrl() + " tab: " + browserDownloadEntity.getTabId() + " uid: " + browserDownloadEntity.getUid());
                mInterceptedList.add(browserDownloadEntity);
                // Note: We don't call GeckoRuntimeHelper.getTabId() here anymore
                // to avoid circular dependencies. We trigger updates via postComplete()
                // or you can pass the current tabId as a parameter if needed.
                emitData();
            }
        }
    }

    public void postComplete() {
        synchronized (mInterceptedList) {
            emitData();
        }
    }

    public void postClear() {
        synchronized (mInterceptedList) {
            mInterceptedList.clear();
            mMediatorData.postValue(null);
        }
    }

    public void trimTabs(int tabId) {
        synchronized (mInterceptedList) {
            mInterceptedList.removeIf(entity -> entity.getTabId() == tabId);
        }
    }

    private void emitData() {
        List<BrowserDownloadEntity> sortedList;
        if (BuildUtils.hasAndroid14()) {
            sortedList = mInterceptedList.stream()
                    .sorted(Collections.reverseOrder())
                    .toList();
        } else {
            sortedList = mInterceptedList.stream()
                    .sorted(Collections.reverseOrder())
                    .collect(Collectors.toList());
        }
        mMediatorData.postValue(sortedList);
    }


    private static String stripTrivial(String url) {
        if (url == null) return "";
        int hash = url.indexOf('#');
        if (hash >= 0) url = url.substring(0, hash);
        if (url.endsWith("/")) url = url.substring(0, url.length() - 1);
        return url;
    }

    private static boolean sameHost(String a, String b) {
        try {
            return new java.net.URL(a).getHost().equalsIgnoreCase(new java.net.URL(b).getHost());
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean isLikelyHashStem(String s) {
        // Treat a stem as hash-like only if it's long, alphanumeric, and mixes
        // digits with letters. Reject dictionary-style names ("hqdefault",
        // "maxresdefault", "thumbnail") that recur across unrelated assets.
        if (s == null || s.length() < 16) return false;
        boolean hasDigit = false, hasLetter = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= '0' && c <= '9') hasDigit = true;
            else if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')) hasLetter = true;
            else if (c != '-' && c != '_') return false;
        }
        return hasDigit && hasLetter;
    }

    private static String lastSegmentStem(String url) {
        try {
            String path = new java.net.URL(url).getPath();
            int slash = path.lastIndexOf('/');
            String last = slash >= 0 ? path.substring(slash + 1) : path;
            // Drop query already gone; drop extension
            return last.replaceFirst("\\.(?:jpe?g|webp|avif|png|gif|heic|heif|bmp|tiff?|mp4|webm|m3u8|mpd)$", "");
        } catch (Exception e) {
            return null;
        }
    }

    private static String canonicalKey(String url) {
        if (url == null) return "";
        try {
            java.net.URL u = new java.net.URL(url);
            String path = u.getPath();
            path = path.replaceFirst("^/[a-f0-9]{16,64}(?=/)", "");
            path = path.replaceAll("/(?:resize|crop|scale|fit|c)(?:/[0-9x.]+)+", "");
            path = path.replaceAll("/(?:f|format)/(?:jpe?g|webp|avif|png|gif)\\b", "");
            path = path.replaceFirst("\\.(?:jpe?g|webp|avif|png|gif|heic|heif|bmp|tiff?)$", "");
            return u.getHost() + path;
        } catch (Exception e) {
            return "";
        }
    }
}