package com.solarized.firedown.ui;

import android.Manifest;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.ActivityCompat;
import androidx.fragment.app.DialogFragment;
import androidx.fragment.app.FragmentActivity;

import com.solarized.firedown.App;
import com.solarized.firedown.R;
import com.solarized.firedown.utils.BuildUtils;
import com.solarized.firedown.utils.NotificationID;

public class NotificationsManagerDialog extends DialogFragment implements View.OnClickListener {

    private static final String TAG = NotificationsManagerDialog.class.getName();

    protected FragmentActivity mActivity;

    @Override
    public void onAttach(@NonNull Context context) {
        super.onAttach(context);
        if (context instanceof Activity)
            mActivity = (FragmentActivity) context;
    }

    @Override
    public void onDetach() {
        super.onDetach();
        mActivity = null;
    }

    @Override
    public void onResume() {
        super.onResume();
        if (getDialog() != null && getDialog().getWindow() != null) {
            Resources resources = getResources();
            int width = Math.min((int)(resources.getDisplayMetrics().widthPixels*0.90), resources.getDimensionPixelOffset(R.dimen.max_dialog_width));
            getDialog().getWindow().setLayout(width, WindowManager.LayoutParams.WRAP_CONTENT);
        }
    }

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container,

                             Bundle savedInstanceState) {


        if (getDialog() != null && getDialog().getWindow() != null) {
            getDialog().getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            getDialog().getWindow().requestFeature(Window.FEATURE_NO_TITLE);
        }

        return inflater.inflate(R.layout.fragment_dialog_manager_notifications, container);
    }



    @Override

    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {

        super.onViewCreated(view, savedInstanceState);


        View okButton = view.findViewById(R.id.button);

        View declineButton = view.findViewById(R.id.button_cancel);

        okButton.setOnClickListener(this);

        declineButton.setOnClickListener(this);

    }


    @Override
    public void onClick(View view) {

        int id = view.getId();
        if (id == R.id.button) {
            try {
                Intent settingsIntent = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, App.getAppContext().getPackageName())
                        .putExtra(Settings.EXTRA_CHANNEL_ID, App.UPDATES_NOTIFICATION_ID)
                        .putExtra(Settings.EXTRA_CHANNEL_ID, App.DOWNLOADS_NOTIFICATION_ID);
                startActivity(settingsIntent);
            } catch (ActivityNotFoundException e) {
                Log.e(TAG, "OnClick", e);
                if (BuildUtils.hasAndroidTiramisu()) {
                    String[] permission = new String[]{Manifest.permission.POST_NOTIFICATIONS};
                    ActivityCompat.requestPermissions(mActivity, permission, NotificationID.PERMISSIONS);
                }
            }
        }
        dismiss();
    }
}
