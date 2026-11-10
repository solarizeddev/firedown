package com.solarized.firedown;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.lifecycle.LiveData;
import androidx.preference.PreferenceManager;

/**
 * Whether a verified update newer than this build is ready to install. Read
 * from the preferences UpdateDownloader writes, and refreshed whenever the
 * ready record changes (a download finishing in the background, an install,
 * a cleared record), so the ⋮ menu's dot and "Update ready" row follow it
 * without polling. Preference listeners are called on the main thread.
 */
public final class UpdateReadyLiveData extends LiveData<Boolean> {

    private final Context mContext;
    private final SharedPreferences mPrefs;

    private final SharedPreferences.OnSharedPreferenceChangeListener mListener = (prefs, key) -> {
        if (key == null || Keys.UPDATE_READY.equals(key)
                || Keys.UPDATE_READY_VERSION_CODE.equals(key)
                || Keys.UPDATE_DOWNLOAD_ID.equals(key)) {
            refresh();
        }
    };

    public UpdateReadyLiveData(@NonNull Context context) {
        mContext = context.getApplicationContext();
        mPrefs = PreferenceManager.getDefaultSharedPreferences(mContext);
    }

    @Override
    protected void onActive() {
        mPrefs.registerOnSharedPreferenceChangeListener(mListener);
        refresh();
    }

    @Override
    protected void onInactive() {
        mPrefs.unregisterOnSharedPreferenceChangeListener(mListener);
    }

    private void refresh() {
        boolean ready = UpdateDownloader.isVerifiedReady(mContext, App.getVersionCode() + 1);
        Boolean current = getValue();
        if (current == null || current != ready) {
            setValue(ready);
        }
    }
}
