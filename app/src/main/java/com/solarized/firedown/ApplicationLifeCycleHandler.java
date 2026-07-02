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
import androidx.work.ExistingWorkPolicy;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;

import com.solarized.firedown.data.DownloadBackupMirror;
import com.solarized.firedown.data.DownloadDatabase;
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

import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
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
    private final Executor mHeavyExecutor;
    private final Context mContext;
    private final DownloadDatabase mDownloadDatabase;
    // Observer for incognito tab count → notification
    private final Observer<Integer> mIncognitoCountObserver = this::onIncognitoCountChanged;

    /**
     * A holder of RE-DERIVABLE memory caches (decoded bitmaps etc.) that should
     * be dropped under memory pressure. Screens register for exactly their view
     * lifetime; this handler is the app's ONE registered
     * {@link ComponentCallbacks2}, so the drop policy (which trim levels count)
     * lives here instead of being re-implemented per screen — fragments never
     * receive {@code onTrimMemory} themselves. Dropping must always be safe:
     * a listener's caches refill lazily.
     */
    public interface TrimMemoryListener {
        void onTrimMemory();
    }

    /** All callbacks run on the main thread today; CopyOnWrite so a listener
     *  that unregisters itself mid-dispatch can never throw a CME. */
    private final Set<TrimMemoryListener> mTrimListeners = new CopyOnWriteArraySet<>();

    public void addTrimListener(TrimMemoryListener listener) {
        mTrimListeners.add(listener);
    }

    public void removeTrimListener(TrimMemoryListener listener) {
        mTrimListeners.remove(listener);
    }

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
            @Qualifiers.HeavyIO Executor heavyExecutor,
            DownloadDatabase downloadDatabase,
            @ApplicationContext Context context){
        this.mGeckoStateRepository = geckoStateRepository;
        this.mIncognitoStateRepository = incognitoStateRepository;
        this.mIncognitoNotification = incognitoNotification;
        this.mGeckoMediaController = geckoMediaController;
        this.mGeckoStateObserver = geckoStateObserver;
        this.mAppLock = appLock;
        this.mDiskExecutor = diskExecutor;
        this.mHeavyExecutor = heavyExecutor;
        this.mDownloadDatabase = downloadDatabase;

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
        if (activity instanceof BrowserActivity) {
            // Belt-and-suspenders: if the activity is destroyed without a paired
            // onPause (e.g. process death, configuration crash), make sure the
            // singleton observers are detached so they don't keep firing.
            mGeckoStateRepository.getTabsLiveData().removeObserver(mGeckoStateObserver);
            mIncognitoStateRepository.getTabsLiveCount().removeObserver(mIncognitoCountObserver);

            if (activity.isFinishing()) {
                stopMediaPlaybackService();
                mIncognitoStateRepository.deleteAll();
                mIncognitoNotification.dismiss();
            }
        }
    }

    @Override
    public void onTrimMemory(int level) {
        // Cache-drop fan-out: UI_HIDDEN and beyond = the app left the
        // foreground (a re-derivable cache costs only a lazy refill on
        // return); RUNNING_CRITICAL = pressure while still foreground (the
        // pre-API-34 signal, below UI_HIDDEN numerically so it needs its own
        // test). Deliberately broader than the exact-UI_HIDDEN gate below —
        // the mirror/lock work is a backgrounding TRANSITION action, cache
        // dropping is a pressure response.
        if (level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN
                || level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL) {
            for (TrimMemoryListener listener : mTrimListeners) {
                listener.onTrimMemory();
            }
        }
        if (level == ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) {
            Log.d(TAG, "App went to background - Securing session");

            // Both run on the HEAVY executor, not @DiskIO: a recursive cache
            // sweep (and the full mirror rewrite + encrypt) can take a long
            // time, and the @DiskIO thread is the single serial lane every
            // short DB mutation rides on. Parking these two there made a
            // Downloads delete queue invisibly for minutes — the rows only
            // vanished after leaving and reopening the fragment, because
            // Room invalidation can't fire before the DELETE actually runs.
            mHeavyExecutor.execute(() -> StoragePaths.clearCacheFolder(mContext));

            // Refresh the Auto Backup mirror (non-safe, finished download rows
            // only — see DownloadBackupMirror). Backgrounding is also the
            // moment the system considers the app for an Auto Backup pass, so
            // the mirror is at its freshest exactly when it matters.
            mHeavyExecutor.execute(() -> DownloadBackupMirror.writeMirror(mContext, mDownloadDatabase));

            mAppLock.setLockRequired(true);
            mAppLock.setLockTime();
        }
    }

    @Override
    public void onConfigurationChanged(@NonNull Configuration newConfig) { }

    @Override
    public void onLowMemory() {
        // Pre-ICS equivalent of TRIM_MEMORY_COMPLETE — drop every cache.
        for (TrimMemoryListener listener : mTrimListeners) {
            listener.onTrimMemory();
        }
    }


    private void updateWindowSecureMode(Activity activity) {
        if (activity instanceof VaultActivity) {
            activity.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
            return;
        }

        boolean secure = mAppLock.isEnabled();
        if (secure) {
            activity.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        } else if (!(activity instanceof BrowserActivity)) {
            // BrowserFragment toggles FLAG_SECURE based on incognito theme;
            // don't clear it here or we'd race with that signal.
            activity.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE);
        }
    }

    private void triggerMediaScan(Activity activity) {
        // Unique + KEEP: this fires on EVERY DownloadsActivity resume, and
        // rapid resume cycles (rotation, app-switch ping-pong, lock/unlock)
        // would otherwise stack concurrent full-table sweeps, each statting
        // every file. A sweep already queued or running absorbs the trigger.
        OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(MediaListenerWorker.class).build();
        WorkManager.getInstance(activity.getApplicationContext())
                .enqueueUniqueWork("media-existence-check", ExistingWorkPolicy.KEEP, request);
    }

    private void stopMediaPlaybackService() {
        mGeckoMediaController.stop();
        mGeckoMediaController.clearMedia();
    }
}