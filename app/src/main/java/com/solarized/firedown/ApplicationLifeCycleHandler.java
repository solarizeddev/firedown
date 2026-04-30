package com.solarized.firedown;

import android.app.Activity;
import android.app.Application;
import android.content.ComponentCallbacks2;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.os.Bundle;
import android.util.Log;
import android.view.WindowManager;

import androidx.annotation.NonNull;
import androidx.lifecycle.Observer;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;

import com.solarized.firedown.data.di.Qualifiers;
import com.solarized.firedown.data.observer.GeckoStateObserver;
import com.solarized.firedown.data.repository.GeckoStateDataRepository;
import com.solarized.firedown.data.repository.IncognitoStateRepository;
import com.solarized.firedown.geckoview.IncognitoNotificationHelper;
import com.solarized.firedown.geckoview.media.GeckoMediaController;
import com.solarized.firedown.phone.BrowserActivity;
import com.solarized.firedown.phone.DownloadsActivity;
import com.solarized.firedown.phone.LockActivity;
import com.solarized.firedown.phone.VaultActivity;
import com.solarized.firedown.data.workers.MediaListenerWorker;

import java.util.concurrent.Executor;

import javax.inject.Inject;
import javax.inject.Singleton;

import dagger.hilt.android.qualifiers.ApplicationContext;

@Singleton
public class ApplicationLifeCycleHandler implements Application.ActivityLifecycleCallbacks, ComponentCallbacks2 {

    private static final String TAG = "AppLifecycleHandler";

    private final GeckoStateDataRepository mGeckoStateRepository;
    private final IncognitoStateRepository mIncognitoStateRepository;
    private final IncognitoNotificationHelper mIncognitoNotification;
    private final GeckoMediaController mGeckoMediaController;
    private final AppLock mAppLock;
    private final GeckoStateObserver mGeckoStateObserver;
    private final Executor mDiskExecutor;
    private final Context mContext;

    // Observer for incognito tab count → notification
    private final Observer<Integer> mIncognitoCountObserver = this::onIncognitoCountChanged;

    private void onIncognitoCountChanged(Integer count) {
        if (count != null && count > 0) {
            mIncognitoNotification.show(count);
        } else {
            mIncognitoNotification.dismiss();
        }
    }


    @Inject
    public ApplicationLifeCycleHandler(
            GeckoStateDataRepository geckoStateRepository,
            IncognitoStateRepository incognitoStateRepository,
            IncognitoNotificationHelper incognitoNotification,
            GeckoMediaController geckoMediaController,
            AppLock appLock,
            GeckoStateObserver geckoStateObserver,
            @Qualifiers.DiskIO Executor diskExecutor,
            @ApplicationContext Context context){
        this.mGeckoStateRepository = geckoStateRepository;
        this.mIncognitoStateRepository = incognitoStateRepository;
        this.mIncognitoNotification = incognitoNotification;
        this.mGeckoMediaController = geckoMediaController;
        this.mGeckoStateObserver = geckoStateObserver;
        this.mAppLock = appLock;
        this.mDiskExecutor = diskExecutor;

        this.mContext = context;
    }

    @Override
    public void onActivityCreated(@NonNull Activity activity, Bundle bundle) { }

    @Override
    public void onActivityStarted(@NonNull Activity activity) {
        updateWindowSecureMode(activity);
    }

    @Override
    public void onActivityResumed(@NonNull Activity activity) {
        Log.d(TAG, "Resumed: " + activity.getClass().getSimpleName());

        updateWindowSecureMode(activity);

        if (mAppLock.isLocked() &&
                !(activity instanceof VaultActivity) &&
                !(activity instanceof LockActivity)) {
            activity.startActivity(new Intent(activity, LockActivity.class));
            return;
        }

        if (activity instanceof DownloadsActivity) {
            triggerMediaScan(activity);
        } else if (activity instanceof BrowserActivity) {
            mGeckoStateRepository.getTabsLiveData().observeForever(mGeckoStateObserver);
            mIncognitoStateRepository.getTabsLiveCount().observeForever(mIncognitoCountObserver);
        }
    }

    @Override
    public void onActivityPaused(@NonNull Activity activity) {
        if (activity instanceof BrowserActivity) {
            mGeckoStateRepository.getTabsLiveData().removeObserver(mGeckoStateObserver);
            mIncognitoStateRepository.getTabsLiveCount().removeObserver(mIncognitoCountObserver);
        }
        updateWindowSecureMode(activity);
    }

    @Override
    public void onActivityStopped(@NonNull Activity activity) { }

    @Override
    public void onActivitySaveInstanceState(@NonNull Activity activity, @NonNull Bundle bundle) { }

    @Override
    public void onActivityDestroyed(@NonNull Activity activity) {
        if (activity instanceof BrowserActivity && activity.isFinishing()) {
            stopMediaPlaybackService();
            mIncognitoStateRepository.deleteAll();
            mIncognitoNotification.dismiss();
        }
    }

    @Override
    public void onTrimMemory(int level) {
        if (level == ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) {
            Log.d(TAG, "App went to background - Securing session");

            // Clear cache on background thread using injected executor
            mDiskExecutor.execute(() -> StoragePaths.clearCacheFolder(mContext));

            mAppLock.setLockRequired(true);
            mAppLock.setLockTime();
        }
    }

    @Override
    public void onConfigurationChanged(@NonNull Configuration newConfig) { }

    @Override
    public void onLowMemory() { }


    private void updateWindowSecureMode(Activity activity) {
        if(activity instanceof VaultActivity){
            activity.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        }else{
            boolean secure = mAppLock.isEnabled();
            if (secure) {
                activity.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
            } else {
                activity.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE);
            }
        }
    }

    private void triggerMediaScan(Activity activity) {
        OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(MediaListenerWorker.class).build();
        WorkManager.getInstance(activity.getApplicationContext()).enqueue(request);
    }

    private void stopMediaPlaybackService() {
        mGeckoMediaController.stop();
        mGeckoMediaController.clearMedia();
    }
}