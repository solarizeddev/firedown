package com.solarized.firedown;


import android.app.Activity;
import android.app.ActivityManager;
import android.app.Application;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Process;
import android.os.StrictMode;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.hilt.work.HiltWorkerFactory;
import androidx.preference.PreferenceManager;
import androidx.work.Configuration;

import com.solarized.firedown.crash.CrashHandler;
import com.solarized.firedown.data.LegacyShortcutsMigrator;
import com.solarized.firedown.data.di.Qualifiers;
import com.solarized.firedown.data.repository.WebHistoryDataRepository;
import com.solarized.firedown.phone.BrowserActivity;
import com.solarized.firedown.sync.SyncManager;


import java.util.List;
import java.util.concurrent.Executor;

import javax.inject.Inject;
import dagger.hilt.android.HiltAndroidApp;

@HiltAndroidApp
public class App extends Application implements Configuration.Provider{

    private static final String TAG = App.class.getName();

    public static final String MEDIA_NOTIFICATION_ID = "firedown_notifications_media";

    public static final String DOWNLOADS_NOTIFICATION_ID = "firedown_notifications_downloads";

    // The update channel is IMPORTANCE_LOW: present in the shade / lock screen
    // but SILENT (no sound, no heads-up) — an update notice should be quietly
    // available, not disturbing. The in-app UpdateAvailableSheet is the
    // prominent surface while the app is in use. A channel's importance can't be
    // changed after creation, so reaching LOW needs a fresh id; the old ids are
    // deleted (v1 was DEFAULT+muted, v2 was a brief HIGH+sound experiment).
    public static final String UPDATES_NOTIFICATION_ID = "firedown_notifications_updates_v3";

    private static final String[] UPDATES_NOTIFICATION_IDS_OLD = {
            "firedown_notifications_updates",
            "firedown_notifications_updates_v2",
    };

    private static Context mAppContext;

    private static volatile Boolean isMainProcess;

    @Inject
    WebHistoryDataRepository mWebHistoryDataRepository;
    @Inject
    LegacyShortcutsMigrator mLegacyShortcutsMigrator;
    @Inject
    ApplicationLifeCycleHandler lifeCycleHandler;
    @Inject
    HiltWorkerFactory workerFactory;
    @Inject
    UpdateScheduler updateScheduler;
    @Inject
    SyncManager mSyncManager;
    @Inject
    @Qualifiers.DiskIO
    Executor mDiskExecutor;

    @Override
    public void onCreate() {
        Log.d(TAG, "App onCreate : " + getCurrentProcessName());
        // Install the uncaught-exception handler ASAP — before super.onCreate
        // and the isMainProcess gate — so Java crashes in any process
        // (main app, Gecko child, the :crash service itself) get
        // persisted. The handler stores to filesDir/crashes/ which is
        // shared across all processes of the app.
        mAppContext = getApplicationContext();
        // Stamp the manifest <application> theme onto the APPLICATION CONTEXT.
        // Android never does this by itself: ContextImpl.getTheme() falls back
        // to the platform default DeviceDefault theme when nothing called
        // setTheme() on the context — only Activities receive the manifest
        // theme (ActivityThread sets it on them). GeckoView resolves the
        // web-content ::selection highlight from THIS context's theme
        // (GeckoAppShell.getSystemColors reads android.R.attr.colorAccent;
        // nsLookAndFeel paints ColorID::Highlight as that accent at ~30%
        // alpha), so without this call the android:colorAccent item in
        // Theme.FireDown.SplashScreen is never read and selection renders in
        // the OEM's DeviceDefault accent — a near-invisible dark wash on
        // several OEM themes. Must run before the GeckoRuntime exists (the
        // colors are cached at first query); keep it ahead of super.onCreate()
        // so no Hilt-constructed dependency can create the runtime first.
        setTheme(R.style.Theme_FireDown_SplashScreen);
        CrashHandler.install(mAppContext);
        if (BuildConfig.DEBUG) {
            StrictMode.setThreadPolicy(new StrictMode.ThreadPolicy.Builder()
                    .detectDiskReads()
                    .detectDiskWrites()
                            .detectAll()
                    .detectNetwork()   // or .detectAll() for all detectable problems
                    .penaltyLog()
                    .build());
            StrictMode.setVmPolicy(new StrictMode.VmPolicy.Builder()
                    .detectLeakedSqlLiteObjects()
                    .detectLeakedClosableObjects()
                    .penaltyLog()
                    .build());
        }
        super.onCreate();

        if (!isMainProcess()) {
            // If this is not the main process then do not continue with the initialization here. Everything that
            // follows only needs to be done in our app's main process and should not be done in other processes like
            // a GeckoView child process or the crash handling process. Most importantly we never want to end up in a
            // situation where we create a GeckoRuntime from the Gecko child process (
            return;
        }
        setTheme();
        purgeDatabases();
        // Sweep orphaned SABR temp dirs (.sabr_<timestamp>) left behind by
        // process kills mid-download. Safe to do unconditionally here
        // because RunnableManager is a Service that died with the previous
        // process — no SABR download is in flight at this point.
        mDiskExecutor.execute(() -> StoragePaths.cleanupSabrTempDirs(mAppContext));
        // Reinstall restore is PROMPT-FIRST — there is deliberately no boot-time
        // auto-import (it would drop the user into a list of unopenable,
        // foreign-owned files on Android 11+). The user restores from the
        // Downloads empty-state button / Settings, which takes the SAF folder
        // grant and imports in one step. See DownloadBackupMirror.
        registerActivityLifecycleCallbacks(lifeCycleHandler);
        registerComponentCallbacks(lifeCycleHandler);
        createMediaNotificationChannel(mAppContext);
        createDownloadsNotificationChannel(mAppContext);
        createUpdateNotificationChannel(mAppContext);
        updateScheduler.schedulePeriodicUpdateCheck();
        updateScheduler.setupOneTimeCheck();
        // Wire bookmark-sync repository semantics + periodic job from saved state
        // (no-op when sync is off; tombstone-on-delete needs this at boot).
        mSyncManager.init();

        // One-time migration: lift legacy 'shortcuts' rows into the
        // bookmarks table as pinned entries, then drop the legacy
        // shortcuts DB. Idempotent — guarded by a SharedPreferences
        // flag and a presence check for the legacy DB file.
        mLegacyShortcutsMigrator.runIfNeeded();

        migrateDohServerPref();
    }

    /**
     * Migrate the DoH provider selection from the old positional index
     * ("0".."5" into settings_doh_servers) to the new URL-valued scheme
     * where SETTINGS_DOH stores the endpoint URL itself, or the "custom"
     * sentinel. Idempotent: a migrated value is a URL or "custom", neither
     * of which matches the numeric guard, so this no-ops on every run after
     * the first. A null value (never set) is left for the ListPreference
     * default to fill in.
     */
    private void migrateDohServerPref() {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(mAppContext);
        String value = prefs.getString(Preferences.SETTINGS_DOH, null);
        if (value == null || !value.matches("\\d+")) return;

        int index;
        try {
            index = Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return;
        }
        // The legacy scheme stored a positional index into the ORIGINAL
        // six-entry server list. Map it from a fixed historical table here,
        // NOT from the live @array/settings_doh_servers — that array has
        // since been trimmed/reordered, so reading it would remap an old
        // index onto the wrong endpoint (or the "custom" sentinel). The
        // three endpoints later dropped from the picker (Mozilla/Google/
        // AdGuard) still resolve fine as the persisted URL; they simply show
        // as an unlisted selection. legacy[0..4] are URLs, legacy[5] is the
        // "custom" sentinel, so one in-range lookup covers both.
        final String[] legacy = {
                "https://mozilla.cloudflare-dns.com/dns-query", // 0 Mozilla
                "https://cloudflare-dns.com/dns-query",         // 1 Cloudflare
                "https://dns.google/dns-query",                 // 2 Google
                "https://dns.quad9.net/dns-query",              // 3 Quad9
                "https://dns.adguard-dns.com/dns-query",        // 4 AdGuard
                Preferences.SETTINGS_DOH_CUSTOM_VALUE,          // 5 custom
        };
        String mapped = (index >= 0 && index < legacy.length)
                ? legacy[index]
                : Preferences.DEFAULT_SETTINGS_DOH;
        prefs.edit().putString(Preferences.SETTINGS_DOH, mapped).apply();
    }


    @NonNull
    @Override
    public Configuration getWorkManagerConfiguration() {
        return new Configuration.Builder()
                .setWorkerFactory(workerFactory)
                .build();
    }


    private String getCurrentProcessName() {
        String processName = "";
        int pid = Process.myPid();
        ActivityManager manager = (ActivityManager) this.getSystemService(Context.ACTIVITY_SERVICE);
        for (ActivityManager.RunningAppProcessInfo processInfo : manager.getRunningAppProcesses()) {
            if (processInfo.pid == pid) {
                processName = processInfo.processName;
                break;
            }
        }
        return processName;
    }

    public void purgeDatabases(){
        // Throttle to at most once per day. The purge only removes history
        // older than the retention window, so running it on every cold start
        // is a redundant full-table DELETE scan — gate it on a last-run
        // timestamp (mirrors the tab-archive last-run pattern).
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(mAppContext);
        long lastRun = prefs.getLong(Preferences.SETTINGS_HISTORY_PURGE_LAST_RUN, 0L);
        long now = System.currentTimeMillis();
        if (now - lastRun < Preferences.ONE_DAY_INTERVAL) {
            return;
        }
        prefs.edit().putLong(Preferences.SETTINGS_HISTORY_PURGE_LAST_RUN, now).apply();
        mWebHistoryDataRepository.purgeDatabase();
    }

    public static void triggerRebirth(Context context) {
        Intent intent = new Intent(context, BrowserActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(intent);
        if (context instanceof Activity) {
            ((Activity) context).finish();
        }
        Runtime.getRuntime().exit(0);
    }



    public static boolean isMainProcess() {
        if (isMainProcess != null) return isMainProcess;
        if (mAppContext == null) return false;

        int pid = Process.myPid();

        ActivityManager activityManager = ((ActivityManager) mAppContext.getSystemService(ACTIVITY_SERVICE));

        if (activityManager != null) {
            // getRunningAppProcesses() can return null on restricted/background contexts;
            // match by pid rather than assuming index 0 is the current process.
            List<ActivityManager.RunningAppProcessInfo> processes =
                    activityManager.getRunningAppProcesses();
            if (processes != null) {
                String packageName = mAppContext.getPackageName();
                for (ActivityManager.RunningAppProcessInfo info : processes) {
                    if (info.pid == pid) {
                        isMainProcess = packageName.equals(info.processName);
                        break;
                    }
                }
            }
        }

        return isMainProcess != null && isMainProcess;
    }

    public static void clearAndExit(){
        if(mAppContext != null){
            ((ActivityManager) mAppContext.getSystemService(ACTIVITY_SERVICE))
                    .clearApplicationUserData();
            System.exit(0);
        }
    }

    public static void quitAndRestart(){
        System.exit(0);
    }

    public static boolean isForeground() {
        final ActivityManager.RunningAppProcessInfo appProcessInfo =
                new ActivityManager.RunningAppProcessInfo();
        ActivityManager.getMyMemoryState(appProcessInfo);
        return appProcessInfo.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
                || appProcessInfo.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE;
    }

    public static Context getAppContext(){
        return mAppContext;
    }

    public static void setTheme(){
        SharedPreferences mSharedPreferences = PreferenceManager.getDefaultSharedPreferences(mAppContext);
        int preferenceTheme = mSharedPreferences.getInt(Preferences.SETTINGS_THEME, AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
        // Translate the OLED sentinel into MODE_NIGHT_YES — AppCompatDelegate
        // doesn't know about THEME_OLED. The actual true-black overlay is
        // applied per-activity in BaseActivity.onCreate, which reads the
        // raw pref value before super.onCreate runs.
        int nightMode = (preferenceTheme == Preferences.THEME_OLED)
                ? AppCompatDelegate.MODE_NIGHT_YES
                : preferenceTheme;
        int currentTheme = AppCompatDelegate.getDefaultNightMode();
        if(currentTheme != nightMode){
            if(nightMode == AppCompatDelegate.MODE_NIGHT_NO){
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO);
            }else if(nightMode == AppCompatDelegate.MODE_NIGHT_YES){
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES);
            }else{
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
            }
        }
    }


    public static String getNativeLibraryDir(Context context) {
        ApplicationInfo appInfo = context.getApplicationInfo();
        return appInfo.nativeLibraryDir;
    }

    public static String getProcessName() {
        ActivityManager manager = (ActivityManager) mAppContext.getSystemService(Context.ACTIVITY_SERVICE);
        if(manager != null){
            List<ActivityManager.RunningAppProcessInfo> listProcesses = manager.getRunningAppProcesses();
            if(listProcesses != null){
                for (ActivityManager.RunningAppProcessInfo processInfo : listProcesses) {
                    if (processInfo.pid == Process.myPid()) {
                        return processInfo.processName;
                    }
                }
            }
        }
        return "";
    }


    public static int getVersionCode() {
        PackageInfo packageInfo;
        try {
            packageInfo = mAppContext.getPackageManager()
                    .getPackageInfo(mAppContext.getPackageName(), 0);
        } catch (PackageManager.NameNotFoundException | RuntimeException e) {
            return BuildConfig.VERSION_CODE;
        }
        return packageInfo.versionCode;
    }




    public static String getDeviceModel(){
        return Build.MANUFACTURER
                + " " + Build.MODEL + " " + Build.VERSION.RELEASE
                + " " + Build.VERSION_CODES.class.getFields()[Build.VERSION.SDK_INT].getName();
    }

    public static String getVersionName() {
        PackageInfo packageInfo;
        try {
            packageInfo = mAppContext.getPackageManager()
                    .getPackageInfo(mAppContext.getPackageName(), 0);
        } catch (PackageManager.NameNotFoundException e) {
            return BuildConfig.VERSION_NAME;
        }
        return packageInfo.versionName;
    }

    public static String getInstalledSource() {
        PackageManager packageManager = mAppContext.getPackageManager();
        String source = packageManager.getInstallerPackageName(mAppContext.getPackageName());
        return source != null ? source : "";
    }


    public static String getApplicationName() {
        ApplicationInfo applicationInfo = mAppContext.getApplicationInfo();
        int stringId = applicationInfo.labelRes;
        return stringId == 0 ? applicationInfo.nonLocalizedLabel.toString() : mAppContext.getString(stringId);
    }

    private void createUpdateNotificationChannel(Context context) {
        NotificationManager notificationManager = (NotificationManager) context.getSystemService(NOTIFICATION_SERVICE);
        if (notificationManager != null) {
            // Clean up the earlier channel ids so a migrated user isn't left with
            // stale "Updates" channels (incl. the loud v2) in system settings.
            for (String oldId : UPDATES_NOTIFICATION_IDS_OLD) {
                notificationManager.deleteNotificationChannel(oldId);
            }
            // IMPORTANCE_LOW = silent + no heads-up. Quietly available, not
            // disturbing; the in-app sheet is the prominent in-use surface.
            NotificationChannel channel = new NotificationChannel(UPDATES_NOTIFICATION_ID,
                    context.getString(R.string.notifications_udpate_channel),
                    NotificationManager.IMPORTANCE_LOW);
            notificationManager.createNotificationChannel(channel);
        }
    }


    private void createMediaNotificationChannel(Context context) {
        NotificationManager notificationManager = (NotificationManager) context.getSystemService(NOTIFICATION_SERVICE);
        if (notificationManager != null) {
            NotificationChannel channel = new NotificationChannel(MEDIA_NOTIFICATION_ID,
                    context.getString(R.string.notifications_media_channel),
                    NotificationManager.IMPORTANCE_DEFAULT);
            channel.setSound(null, null);
            notificationManager.createNotificationChannel(channel);
        }
    }

    private void createDownloadsNotificationChannel(Context context) {
        NotificationManager notificationManager = (NotificationManager) context.getSystemService(NOTIFICATION_SERVICE);
        if (notificationManager != null) {
            NotificationChannel channel = new NotificationChannel(DOWNLOADS_NOTIFICATION_ID,
                    context.getString(R.string.notifications_downloads_channel),
                    NotificationManager.IMPORTANCE_DEFAULT);
            channel.setSound(null, null);
            notificationManager.createNotificationChannel(channel);
        }
    }

    @Override
    public String getPackageName() {
        try {
            StackTraceElement[] stackTrace = Thread.currentThread().getStackTrace();
            for (StackTraceElement element : stackTrace) {
                if ("org.chromium.base.BuildInfo".equalsIgnoreCase(element.getClassName())) {
                    if ("getAll".equalsIgnoreCase(element.getMethodName())) {
                        return "com.android.chrome";
                    }
                    break;
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "getPackageName", e);
        }

        return super.getPackageName();
    }




}
