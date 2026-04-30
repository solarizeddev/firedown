package com.solarized.firedown.geckoview;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import com.solarized.firedown.data.repository.IncognitoStateRepository;

import javax.inject.Inject;

import dagger.hilt.android.AndroidEntryPoint;

@AndroidEntryPoint
public class IncognitoCloseReceiver extends BroadcastReceiver {

    @Inject
    IncognitoStateRepository incognitoStateRepository;

    @Inject
    IncognitoNotificationHelper notificationHelper;

    @Override
    public void onReceive(Context context, Intent intent) {
        if (IncognitoNotificationHelper.ACTION_CLOSE_ALL_INCOGNITO
                .equals(intent.getAction())) {
            incognitoStateRepository.deleteAll();
            notificationHelper.dismiss();
        }
    }
}