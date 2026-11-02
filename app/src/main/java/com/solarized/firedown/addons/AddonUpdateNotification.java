package com.solarized.firedown.addons;

import android.Manifest;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
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
 * posts the same notification). Posted once per add-on and update; a tap on
 * one add-on's notification opens its page and puts the question, a tap on
 * the notification for several opens Settings → Add-ons, where each reads
 * "Update needs your review". Gone once nothing waits.
 */
public final class AddonUpdateNotification {

    private static final String TAG = "AddonUpdateNotification";

    private AddonUpdateNotification() {}

    /**
     * {@code names}: every add-on whose update waits, sorted; {@code onlyId}:
     * the add-on's id when there is exactly one.
     */
    static void show(@NonNull Context context, @NonNull List<String> names, @Nullable String onlyId) {
        if (names.isEmpty()) {
            return;
        }
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context,
                Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            return;
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
        } catch (SecurityException e) {
            if (BuildConfig.DEBUG) {
                Log.w(TAG, "not allowed to notify", e);
            }
        }
    }

    static void cancel(@NonNull Context context) {
        NotificationManagerCompat.from(context).cancel(NotificationID.ADDON_UPDATE_REVIEW);
    }
}
