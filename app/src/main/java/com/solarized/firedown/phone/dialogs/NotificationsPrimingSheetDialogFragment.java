package com.solarized.firedown.phone.dialogs;

import android.Manifest;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.ActivityCompat;

import com.solarized.firedown.R;
import com.solarized.firedown.utils.BuildUtils;
import com.solarized.firedown.utils.NotificationID;

/**
 * In-context "why we want notifications" pre-prompt, shown ONCE on first run
 * BEFORE the system POST_NOTIFICATIONS dialog (Android 13+) — see
 * {@code BaseActivity.requestNotificationPermission}.
 *
 * <p>Rationale: on a fresh install {@code shouldShowRequestPermissionRationale}
 * is {@code false}, so the old flow fired the OS permission dialog directly with
 * no context, and only showed the explanation ({@code NotificationsManagerDialog})
 * <i>after</i> a denial — too late, because a denied POST_NOTIFICATIONS is sticky
 * and the OS won't re-prompt. This sheet inverts that: explain first, then let
 * "Enable" trigger the system prompt. "Not now" dismisses without spending the
 * one-shot OS prompt.</p>
 *
 * <p>Drains no new strings — the body and buttons reuse already-localized
 * resources (see the layout header).</p>
 */
public class NotificationsPrimingSheetDialogFragment extends BaseBottomSheetDialogFragment {

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        mView = inflater.inflate(R.layout.fragment_dialog_notifications_priming, container, false);

        mView.findViewById(R.id.notifications_priming_enable).setOnClickListener(v -> {
            // The OS prompt itself is the consent surface; this just launches it.
            // Guarded by hasAndroidTiramisu because POST_NOTIFICATIONS is a no-op
            // permission below API 33 (the sheet is never shown there anyway).
            if (mActivity != null && BuildUtils.hasAndroidTiramisu()) {
                ActivityCompat.requestPermissions(mActivity,
                        new String[]{Manifest.permission.POST_NOTIFICATIONS},
                        NotificationID.PERMISSIONS);
            }
            dismiss();
        });

        mView.findViewById(R.id.notifications_priming_decline)
                .setOnClickListener(v -> dismiss());

        return mView;
    }
}
