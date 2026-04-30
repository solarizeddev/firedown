package com.solarized.firedown.data.models;

import android.content.SharedPreferences;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;
import com.solarized.firedown.AppLock;
import com.solarized.firedown.Preferences;

import javax.inject.Inject;
import dagger.hilt.android.lifecycle.HiltViewModel;


@HiltViewModel
public class LockViewModel extends ViewModel {

    private final AppLock mAppLock;
    private final SharedPreferences mPrefs;

    // Used by LockActivity to track the current session's auth state
    private final MutableLiveData<Boolean> mAuthenticated = new MutableLiveData<>(false);

    @Inject
    public LockViewModel(AppLock appLock, SharedPreferences sharedPreferences) {
        this.mAppLock = appLock;
        this.mPrefs = sharedPreferences;
    }

    // --- ENFORCEMENT LOGIC (For LockActivity) ---

    public void onAuthenticationSuccess() {
        mAppLock.setLockTime();
        mAppLock.setLockRequired(false);
        mAuthenticated.setValue(true);
    }

    public LiveData<Boolean> getAuthenticatedStatus() {
        return mAuthenticated;
    }

    // --- CONFIGURATION LOGIC (For LockFragment) ---

    public boolean isAppLockEnabled() {
        return mAppLock.isEnabled();
    }

    public boolean isBiometricAvailable() {
        return mAppLock.isBiometricEnabled();
    }

    public void setLockEnabled(boolean enabled) {
        mPrefs.edit().putBoolean(Preferences.SETTINGS_APP_LOCK, enabled).apply();
    }

    public String getLockTimeInterval() {
        return mPrefs.getString(Preferences.SETTINGS_APP_LOCK_TIME, "0");
    }
}