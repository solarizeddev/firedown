package com.solarized.firedown;

import android.Manifest;
import android.app.Notification;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.text.TextUtils;

import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.preference.PreferenceManager;

import com.solarized.firedown.phone.LauncherActivity;
import com.solarized.firedown.utils.BuildUtils;
import com.solarized.firedown.utils.NotificationID;

public class UpdateNotification {

    public static void showNotificationFailed(Context context){
        NotificationManagerCompat mNotificationManager = NotificationManagerCompat.from(context);
        // Opens the app like its icon: as it was left, closing no screen.
        Intent intent = LauncherActivity.openAppIntent(context);
        PendingIntent contentIntent = PendingIntent.getActivity(context, NotificationID.APK_UPDATE_FAILED_INSTALL,
                intent, BuildUtils.hasAndroidS() ? PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT : PendingIntent.FLAG_IMMUTABLE);
        NotificationCompat.Builder mBuilder = new NotificationCompat.Builder(context, App.UPDATES_NOTIFICATION_ID);
        mBuilder.setSmallIcon(R.drawable.ic_firedown_notification);
        mBuilder.setOnlyAlertOnce(true);
        mBuilder.setAutoCancel(true);
        mBuilder.setWhen(System.currentTimeMillis());
        mBuilder.setContentTitle(context.getText(R.string.update_failed_general_title));
        mBuilder.setContentText(context.getText(R.string.update_failed_general_body));
        mBuilder.setContentIntent(contentIntent);
        mBuilder.setOngoing(false);
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        mNotificationManager.cancel(NotificationID.APK_UPDATE_PROMPT_INSTALL);
        mNotificationManager.notify(NotificationID.APK_UPDATE_FAILED_INSTALL, mBuilder.build());
    }

    public static void showNotificationSuccess(Context context, String updateName){
        String contentTitle = context.getString(R.string.update_auto_update_success_title);
        String contentText = TextUtils.isEmpty(updateName) ? context.getString(R.string.update_auto_update_success_default_body) :
                context.getString(R.string.update_auto_update_success_body, updateName);
        NotificationManagerCompat mNotificationManager = NotificationManagerCompat.from(context);
        // Opens the app like its icon: as it was left, closing no screen.
        Intent intent = LauncherActivity.openAppIntent(context);
        PendingIntent contentIntent = PendingIntent.getActivity(context, NotificationID.APK_UPDATE_SUCCESSFUL_INSTALL,
                intent, BuildUtils.hasAndroidS() ? PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT : PendingIntent.FLAG_IMMUTABLE);
        NotificationCompat.Builder mBuilder = new NotificationCompat.Builder(context, App.UPDATES_NOTIFICATION_ID);
        mBuilder.setSmallIcon(R.drawable.ic_firedown_notification);
        mBuilder.setOnlyAlertOnce(true);
        mBuilder.setAutoCancel(true);
        mBuilder.setWhen(System.currentTimeMillis());
        mBuilder.setContentTitle(contentTitle);
        mBuilder.setContentText(contentText);
        mBuilder.setContentIntent(contentIntent);
        mBuilder.setOngoing(false);
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        mNotificationManager.cancel(NotificationID.APK_UPDATE_PROMPT_INSTALL);
        mNotificationManager.notify(NotificationID.APK_UPDATE_SUCCESSFUL_INSTALL, mBuilder.build());
    }


    /**
     * The install the user started from the "Tap to update" notification, shown
     * on that same notification (same id), so nothing new stacks in the shade
     * and the failed / updated notifications replace it. {@code percent} is the
     * copy into Android's installer; -1 once Android has it ("Installing…").
     * Silent and ongoing; posted at most twice a second by the caller.
     */
    public static void showInstallProgress(Context context, int percent) {
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        NotificationManagerCompat.from(context).notify(NotificationID.APK_UPDATE_PROMPT_INSTALL,
                buildInstallProgress(context, percent));
    }

    /**
     * The progress notification itself, also the foreground notice of
     * {@link UpdateInstallWorker} where the system needs one (same id, so the
     * progress posts replace it).
     */
    public static Notification buildInstallProgress(Context context, int percent) {
        boolean installing = percent < 0;
        NotificationCompat.Builder mBuilder = new NotificationCompat.Builder(context, App.UPDATES_NOTIFICATION_ID);
        mBuilder.setSmallIcon(R.drawable.ic_firedown_notification);
        mBuilder.setOnlyAlertOnce(true);
        mBuilder.setSilent(true);
        mBuilder.setOngoing(true);
        mBuilder.setAutoCancel(false);
        mBuilder.setShowWhen(false);
        mBuilder.setCategory(NotificationCompat.CATEGORY_PROGRESS);
        mBuilder.setContentTitle(context.getText(R.string.update_prompt_install_title));
        mBuilder.setContentText(context.getText(installing
                ? R.string.update_install_installing : R.string.update_install_preparing));
        mBuilder.setProgress(100, installing ? 0 : percent, installing);
        return mBuilder.build();
    }

    /**
     * Android needs the user to confirm the update (the first self-update of
     * an install another app put on the phone, or any update before Android
     * 12) and Firedown, in the background, could not open that dialog: a
     * notification whose tap opens it. Removed by the install's outcome.
     */
    public static void showConfirmInstall(Context context, Intent confirmationIntent) {
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        Intent intent = new Intent(confirmationIntent);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent contentIntent = PendingIntent.getActivity(context,
                NotificationID.APK_UPDATE_CONFIRM_INSTALL, intent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        String name = PreferenceManager.getDefaultSharedPreferences(context)
                .getString(Keys.UPDATE_META, null);
        CharSequence body = TextUtils.isEmpty(name)
                ? context.getText(R.string.update_prompt_install_body)
                : context.getString(R.string.update_prompt_install_body_versioned, name);
        NotificationCompat.Builder mBuilder = new NotificationCompat.Builder(context, App.UPDATES_NOTIFICATION_ID);
        mBuilder.setSmallIcon(R.drawable.ic_firedown_notification);
        mBuilder.setOnlyAlertOnce(true);
        mBuilder.setAutoCancel(true);
        mBuilder.setWhen(System.currentTimeMillis());
        mBuilder.setContentTitle(context.getText(R.string.update_prompt_install_title));
        mBuilder.setContentText(body);
        mBuilder.setContentIntent(contentIntent);
        mBuilder.setOngoing(false);
        NotificationManagerCompat.from(context).notify(NotificationID.APK_UPDATE_CONFIRM_INSTALL, mBuilder.build());
    }

    /** The install has an outcome: the confirmation has nothing left to open. */
    public static void cancelConfirmInstall(Context context) {
        NotificationManagerCompat.from(context).cancel(NotificationID.APK_UPDATE_CONFIRM_INSTALL);
    }

    /** The foreground notice of an update check run as an expedited job on Android 11 and older. */
    public static Notification buildCheckRunning(Context context) {
        NotificationCompat.Builder mBuilder = new NotificationCompat.Builder(context, App.UPDATES_NOTIFICATION_ID);
        mBuilder.setSmallIcon(R.drawable.ic_firedown_notification);
        mBuilder.setOnlyAlertOnce(true);
        mBuilder.setSilent(true);
        mBuilder.setOngoing(true);
        mBuilder.setShowWhen(false);
        mBuilder.setContentTitle(context.getText(R.string.update_check_running));
        return mBuilder.build();
    }

    /** Android answered (or never did): the progress has nothing left to say. */
    public static void cancelInstallProgress(Context context) {
        NotificationManagerCompat.from(context).cancel(NotificationID.APK_UPDATE_PROMPT_INSTALL);
    }

    public static void showInstallPrompt(Context context, String versionName) {
        NotificationManagerCompat mNotificationManager = NotificationManagerCompat.from(context);

        // Target the receiver directly — no Activity launch on tap
        Intent intent = new Intent(context, UpdateInstallReceiver.class);
        intent.setAction(UpdateInstallReceiver.ACTION_INSTALL_UPDATE);
        intent.putExtra(Keys.UPDATE_NAME, versionName);

        // FLAG_IMMUTABLE is safe here — all extras are set at creation time.
        // Only the PackageInstaller session callback PendingIntent needs
        // FLAG_MUTABLE (system writes EXTRA_STATUS into it).
        PendingIntent contentIntent = PendingIntent.getBroadcast(
                context,
                NotificationID.APK_UPDATE_PROMPT_INSTALL,
                intent,
                BuildUtils.hasAndroidS()
                        ? PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
                        : PendingIntent.FLAG_UPDATE_CURRENT
        );

        NotificationCompat.Builder mBuilder = new NotificationCompat.Builder(context, App.UPDATES_NOTIFICATION_ID);
        mBuilder.setSmallIcon(R.drawable.ic_firedown_notification);
        mBuilder.setOnlyAlertOnce(true);
        mBuilder.setAutoCancel(true);
        mBuilder.setWhen(System.currentTimeMillis());
        mBuilder.setContentTitle(context.getText(R.string.update_prompt_install_title));
        // Show the version when we have it, generic text otherwise.
        CharSequence body = TextUtils.isEmpty(versionName)
                ? context.getText(R.string.update_prompt_install_body)
                : context.getString(R.string.update_prompt_install_body_versioned, versionName);
        mBuilder.setContentText(body);
        mBuilder.setContentIntent(contentIntent);
        mBuilder.setOngoing(false);
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        mNotificationManager.cancel(NotificationID.APK_UPDATE_FAILED_INSTALL);
        mNotificationManager.notify(NotificationID.APK_UPDATE_PROMPT_INSTALL, mBuilder.build());
    }

}