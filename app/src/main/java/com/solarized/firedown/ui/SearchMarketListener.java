package com.solarized.firedown.ui;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Intent;
import android.net.Uri;
import android.view.View;


import java.lang.ref.WeakReference;


public class SearchMarketListener implements View.OnClickListener{

    private final String mCurrentFileExtension;

    private final WeakReference<Activity> mWeakReference;

    public SearchMarketListener (Activity activity, String currentFileExtension){
        mWeakReference = new WeakReference<>(activity);
        mCurrentFileExtension = currentFileExtension;
    }

    @Override
    public void onClick(View v) {
        Activity activity = mWeakReference.get();
        if (activity == null)
            return;

        try {
            activity.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("market://search?q=" + mCurrentFileExtension)));
        } catch (ActivityNotFoundException anfe) {
            Uri uri = Uri.parse("https://play.google.com/store/search?q=" + mCurrentFileExtension);
            Intent browserIntent = new Intent(Intent.ACTION_VIEW, uri);
            ComponentName componentName = browserIntent.resolveActivity(activity.getPackageManager());
            if (componentName != null)
                activity.startActivity(browserIntent);
        }


    }

}
