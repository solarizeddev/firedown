package com.solarized.firedown.data.repository;

import androidx.lifecycle.MutableLiveData;

import com.solarized.firedown.utils.WebUtils;

import java.util.HashSet;


/**
 * In-memory tracking-permission store for incognito sessions.
 *
 * <p>Mirrors the API of {@link TrackingPermissionRepository} but
 * <b>never touches the database</b>.  Exceptions added here are lost
 * when the incognito session ends, which is the expected privacy
 * behaviour.</p>
 *
 * <p>On creation the set is empty, meaning every domain starts with
 * tracking protection <b>enabled</b> — matching the default for new
 * profiles.</p>
 */
public class IncognitoTrackingPermissionRepository {

    private final HashSet<Integer> mExceptions = new HashSet<>();
    private final MutableLiveData<Boolean> mTrackingMutableLiveData = new MutableLiveData<>();

    public void add(String url) {
        String domain = WebUtils.getDomainName(url);
        mExceptions.add(domain.hashCode());
    }

    public void delete(String url) {
        String domain = WebUtils.getDomainName(url);
        mExceptions.remove(domain.hashCode());
    }

    public boolean contains(String url) {
        if (url == null) return false;
        String domain = WebUtils.getDomainName(url);
        return mExceptions.contains(domain.hashCode());
    }

    public void setTrackingMutableData(boolean value) {
        mTrackingMutableLiveData.postValue(value);
    }

    public MutableLiveData<Boolean> getTrackingMutableLiveData() {
        return mTrackingMutableLiveData;
    }

    /**
     * Clears all exceptions.  Called when the incognito session is torn down.
     */
    public void clear() {
        mExceptions.clear();
    }
}