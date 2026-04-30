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
import android.os.StrictMode;
import android.util.Base64;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.hilt.work.HiltWorkerFactory;
import androidx.preference.PreferenceManager;
import androidx.work.Configuration;

import com.solarized.firedown.data.repository.WebHistoryDataRepository;
import com.solarized.firedown.phone.BrowserActivity;


import java.security.SecureRandom;
import java.util.List;
import java.util.UUID;

import javax.inject.Inject;
import dagger.hilt.android.HiltAndroidApp;

@HiltAndroidApp
public class App extends Application implements Configuration.Provider{

    private static final String TAG = App.class.getName();

    public static final String MEDIA_NOTIFICATION_ID = "firedown_notifications_media";

    public static final String DOWNLOADS_NOTIFICATION_ID = "firedown_notifications_downloads";

    public static final String UPDATES_NOTIFICATION_ID = "firedown_notifications_updates";

    private static Context mAppContext;

    private static Boolean isMainProcess;

    @Inject
    WebHistoryDataRepository mWebHistoryDataRepository;
    @Inject
    ApplicationLifeCycleHandler lifeCycleHandler;
    @Inject
    HiltWorkerFactory workerFactory;
    @Inject
    UpdateScheduler updateScheduler;

    @Override
    public void onCreate() {
        Log.d(TAG, "App onCreate : " + getCurrentProcessName());
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

        mAppContext = getApplicationContext();

        if (!isMainProcess()) {
            // If this is not the main process then do not continue with the initialization here. Everything that
            // follows only needs to be done in our app's main process and should not be done in other processes like
            // a GeckoView child process or the crash handling process. Most importantly we never want to end up in a
            // situation where we create a GeckoRuntime from the Gecko child process (
            return;
        }
        generateId();
        setTheme();
        purgeDatabases();
        registerActivityLifecycleCallbacks(lifeCycleHandler);
        registerComponentCallbacks(lifeCycleHandler);
        createMediaNotificationChannel(mAppContext);
        createDownloadsNotificationChannel(mAppContext);
        createUpdateNotificationChannel(mAppContext);
        updateScheduler.schedulePeriodicUpdateCheck();
        updateScheduler.setupOneTimeCheck();
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
        int pid = android.os.Process.myPid();
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

        int pid = android.os.Process.myPid();

        ActivityManager activityManager = ((ActivityManager) mAppContext.getSystemService(ACTIVITY_SERVICE));

        if (activityManager != null) {
            // getRunningAppProcesses() can return null on restricted/background contexts;
            // match by pid rather than assuming index 0 is the current process.
            java.util.List<ActivityManager.RunningAppProcessInfo> processes =
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
        int currentTheme = AppCompatDelegate.getDefaultNightMode();
        if(currentTheme != preferenceTheme){
            if(preferenceTheme == AppCompatDelegate.MODE_NIGHT_NO){
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO);
            }else if(preferenceTheme == AppCompatDelegate.MODE_NIGHT_YES){
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES);
            }else{
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
            }
        }
    }

    public synchronized static void generateId() {

        SharedPreferences mSharedPreferences = PreferenceManager.getDefaultSharedPreferences(mAppContext);

        String uniqueId = mSharedPreferences.getString(Preferences.UNIQUE_ID, null);

        if(uniqueId == null){
            uniqueId = UUID.randomUUID().toString();
            mSharedPreferences.edit().putString(Preferences.UNIQUE_ID, uniqueId).apply();
        }

        String key = mSharedPreferences.getString(Preferences.KEY_ID, null);

        if(key == null){
            byte[] result = new byte[32];
            SecureRandom secureRandom = new SecureRandom();
            secureRandom.nextBytes(result);
            String encoded = Base64.encodeToString(result, Base64.NO_WRAP);
            mSharedPreferences.edit().putString(Preferences.KEY_ID, encoded).apply();
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
                    if (processInfo.pid == android.os.Process.myPid()) {
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
                + " " + Build.VERSION_CODES.class.getFields()[android.os.Build.VERSION.SDK_INT].getName();
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
            NotificationChannel channel = new NotificationChannel(UPDATES_NOTIFICATION_ID,
                    context.getString(R.string.notifications_udpate_channel),
                    NotificationManager.IMPORTANCE_DEFAULT);
            channel.setSound(null, null);
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
