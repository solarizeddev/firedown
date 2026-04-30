package com.solarized.firedown.geckoview;


import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;


import androidx.core.app.NotificationCompat;

import com.solarized.firedown.IntentActions;
import com.solarized.firedown.R;
import com.solarized.firedown.phone.BrowserActivity;

import javax.inject.Singleton;

@Singleton
public class IncognitoNotificationHelper {
    private static final String CHANNEL_ID = "incognito_tabs";
    private static final int NOTIFICATION_ID = 9001;

    public static final String ACTION_CLOSE_ALL_INCOGNITO =
            "com.solarized.firedown.ACTION_CLOSE_ALL_INCOGNITO";

    private final Context mContext;
    private final NotificationManager mNotificationManager;

    public IncognitoNotificationHelper(Context context) {
        mContext = context.getApplicationContext();
        mNotificationManager = (NotificationManager)
                mContext.getSystemService(Context.NOTIFICATION_SERVICE);
        createChannel();
    }

    private void createChannel() {
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                mContext.getString(R.string.incognito_notification_channel),
                NotificationManager.IMPORTANCE_LOW);
        channel.setDescription(
                mContext.getString(R.string.incognito_notification_channel_desc));
        channel.setShowBadge(false);
        channel.enableLights(false);
        channel.enableVibration(false);
        mNotificationManager.createNotificationChannel(channel);
    }

    /**
     * Shows or updates the persistent notification.
     * Call when an incognito tab is opened.
     */
    public void show(int tabCount) {
        if (tabCount <= 0) {
            dismiss();
            return;
        }

        // Tapping the notification opens the app to the incognito tab switcher
        Intent openIntent = new Intent(mContext, BrowserActivity.class);
        openIntent.setAction(IntentActions.OPEN_INCOGNITO);
        openIntent.addCategory(Intent.CATEGORY_LAUNCHER);
        openIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent openPendingIntent = PendingIntent.getActivity(
                mContext, 0, openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        // "Close all" action button uses broadcast
        Intent closeIntent = new Intent(ACTION_CLOSE_ALL_INCOGNITO);
        closeIntent.setPackage(mContext.getPackageName());
        PendingIntent closePendingIntent = PendingIntent.getBroadcast(
                mContext, 0, closeIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        String title = mContext.getResources().getQuantityString(
                R.plurals.incognito_notification_title, tabCount, tabCount);

        Notification notification = new NotificationCompat.Builder(mContext, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_tabs_private_24)
                .setContentTitle(title)
                .setContentText(mContext.getString(R.string.incognito_notification_tap_to_open))
                .setContentIntent(openPendingIntent)
                .setOngoing(true)
                .setSilent(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setVisibility(NotificationCompat.VISIBILITY_SECRET)
                .addAction(0,
                        mContext.getString(R.string.incognito_notification_close_action),
                        closePendingIntent)
                .build();

        mNotificationManager.notify(NOTIFICATION_ID, notification);
    }

    /**
     * Dismisses the notification.
     * Call when all incognito tabs are closed.
     */
    public void dismiss() {
        mNotificationManager.cancel(NOTIFICATION_ID);
    }
}