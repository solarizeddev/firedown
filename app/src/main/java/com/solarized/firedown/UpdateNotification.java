package com.solarized.firedown;

import android.Manifest;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.text.TextUtils;

import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import com.solarized.firedown.phone.BrowserActivity;
import com.solarized.firedown.utils.BuildUtils;
import com.solarized.firedown.utils.NotificationID;

public class UpdateNotification {

    public static void showNotificationFailed(Context context){
        NotificationManagerCompat mNotificationManager = NotificationManagerCompat.from(context);
        Intent intent = new Intent(context, BrowserActivity.class);
        intent.setAction(Intent.ACTION_MAIN);
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
        Intent intent = new Intent(context, BrowserActivity.class);
        intent.setAction(Intent.ACTION_MAIN);
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
        mBuilder.setContentText(context.getText(R.string.update_prompt_install_body));
        mBuilder.setContentIntent(contentIntent);
        mBuilder.setOngoing(false);
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        mNotificationManager.cancel(NotificationID.APK_UPDATE_FAILED_INSTALL);
        mNotificationManager.notify(NotificationID.APK_UPDATE_PROMPT_INSTALL, mBuilder.build());
    }

}