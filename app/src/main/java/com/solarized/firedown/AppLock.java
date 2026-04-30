package com.solarized.firedown;

import android.content.Context;
import android.content.SharedPreferences;
import androidx.biometric.BiometricManager;
import javax.inject.Inject;
import javax.inject.Singleton;
import dagger.hilt.android.qualifiers.ApplicationContext;

@Singleton // Hilt replaces the "Loader" static inner class
public class AppLock {

    private final SharedPreferences mSharedPreferences;
    private final Context mContext;

    @Inject
    public AppLock(@ApplicationContext Context context, SharedPreferences sharedPreferences) {
        this.mContext = context;
        this.mSharedPreferences = sharedPreferences;

        // Initialization logic from your old constructor
        initDefaults();
    }

    private void initDefaults() {
        mSharedPreferences.edit()
                .putLong(Preferences.SETTINGS_APP_LOCK_UPDATE_TIME, 0L)
                .putBoolean(Preferences.SETTINGS_APP_LOCK_REQUIRED, true)
                .apply();
    }

    public void setLockTime() {
        mSharedPreferences.edit()
                .putLong(Preferences.SETTINGS_APP_LOCK_UPDATE_TIME, System.currentTimeMillis())
                .apply();
    }

    public boolean isBiometricEnabled() {
        BiometricManager biometricManager = BiometricManager.from(mContext);
        int result = biometricManager.canAuthenticate(
                BiometricManager.Authenticators.BIOMETRIC_STRONG |
                        BiometricManager.Authenticators.DEVICE_CREDENTIAL
        );
        return result == BiometricManager.BIOMETRIC_SUCCESS;
    }

    public boolean isEnabled() {
        return mSharedPreferences.getBoolean(Preferences.SETTINGS_APP_LOCK, false);
    }

    private long getLockTime() {
        return mSharedPreferences.getLong(Preferences.SETTINGS_APP_LOCK_UPDATE_TIME, System.currentTimeMillis());
    }

    private boolean getLockRequired() {
        return mSharedPreferences.getBoolean(Preferences.SETTINGS_APP_LOCK_REQUIRED, true);
    }

    private long getPreferencesTime() {
        // Safe parsing to prevent NumberFormatException
        String value = mSharedPreferences.getString(Preferences.SETTINGS_APP_LOCK_TIME, "0");
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    public boolean isLocked() {
        if (!isEnabled() || !isBiometricEnabled() || !getLockRequired()) {
            return false;
        }
        long currentTime = System.currentTimeMillis();
        long diffTime = currentTime - getLockTime();
        return diffTime > getPreferencesTime();
    }

    public void setLockRequired(boolean required) {
        mSharedPreferences.edit()
                .putBoolean(Preferences.SETTINGS_APP_LOCK_REQUIRED, required)
                .apply();
    }
}
