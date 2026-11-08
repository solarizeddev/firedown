package com.solarized.firedown.addons;

import android.Manifest;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.service.notification.StatusBarNotification;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;

import com.solarized.firedown.App;
import com.solarized.firedown.BuildConfig;
import com.solarized.firedown.R;
import com.solarized.firedown.phone.SettingsActivity;
import com.solarized.firedown.utils.NotificationID;

import java.util.List;

/**
 * "An add-on update needs your OK": the daily check found an update that asks
 * for new permissions, which only the user can grant (Firefox for Android
 * posts the same notification). Posted once per add-on and update, or
 * later when it could not be posted then; a tap on one add-on's
 * notification opens its page and puts the question, a tap on the
 * notification for several opens Settings → Add-ons, where each reads
 * "Update needs your review". Gone once the user has seen every question
 * (AddonManager.refreshReviewNotification decides).
 */
public final class AddonUpdateNotification {

    private static final String TAG = "AddonUpdateNotification";

    private AddonUpdateNotification() {}

    /**
     * {@code names}: every add-on whose update waits, sorted; {@code onlyId}:
     * the add-on's id when there is exactly one. Returns whether the user
     * can see it: false without the permission, with the app's
     * notifications off or the Updates channel off, so it is tried again.
     */
    static boolean show(@NonNull Context context, @NonNull List<String> names, @Nullable String onlyId) {
        if (names.isEmpty() || !canPost(context)) {
            return false;
        }
        Intent intent = new Intent(context, SettingsActivity.class);
        if (onlyId != null) {
            intent.putExtra(SettingsActivity.EXTRA_REVIEW_ADDON_UPDATE, onlyId);
        } else {
            intent.putExtra(SettingsActivity.EXTRA_OPEN_ADDONS, true);
        }
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent tap = PendingIntent.getActivity(context, NotificationID.ADDON_UPDATE_REVIEW, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        String title = names.size() == 1
                ? context.getString(R.string.addons_update_review_notification_one, names.get(0))
                : context.getResources().getQuantityString(R.plurals.addons_update_review_notification_many,
                        names.size(), names.size());
        String text = names.size() == 1
                ? context.getString(R.string.addons_update_review_notification_text)
                : String.join(", ", names);

        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, App.UPDATES_NOTIFICATION_ID)
                .setSmallIcon(R.drawable.ic_firedown_notification)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(text))
                .setContentIntent(tap)
                .setAutoCancel(true)
                .setOnlyAlertOnce(true);
        try {
            NotificationManagerCompat.from(context).notify(NotificationID.ADDON_UPDATE_REVIEW, builder.build());
            return true;
        } catch (SecurityException e) {
            if (BuildConfig.DEBUG) {
                Log.w(TAG, "not allowed to notify", e);
            }
            return false;
        }
    }

    private static boolean canPost(@NonNull Context context) {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context,
                Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            return false;
        }
        NotificationManagerCompat manager = NotificationManagerCompat.from(context);
        if (!manager.areNotificationsEnabled()) {
            return false;
        }
        NotificationChannel channel = manager.getNotificationChannel(App.UPDATES_NOTIFICATION_ID);
        return channel == null || channel.getImportance() != NotificationManager.IMPORTANCE_NONE;
    }

    /** The notification is in the shade now (not swiped away, not tapped). */
    static boolean isShowing(@NonNull Context context) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager == null) {
            return false;
        }
        try {
            for (StatusBarNotification posted : manager.getActiveNotifications()) {
                if (posted.getId() == NotificationID.ADDON_UPDATE_REVIEW) {
                    return true;
                }
            }
        } catch (RuntimeException e) {
            // A system service hiccup: treat as not showing.
            if (BuildConfig.DEBUG) {
                Log.w(TAG, "active notifications unreadable", e);
            }
        }
        return false;
    }

    static void cancel(@NonNull Context context) {
        NotificationManagerCompat.from(context).cancel(NotificationID.ADDON_UPDATE_REVIEW);
    }
}
