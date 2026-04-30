package com.solarized.firedown;

import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;

import androidx.appcompat.app.AppCompatActivity;

import com.solarized.firedown.phone.BrowserActivity;
import com.solarized.firedown.utils.AppLinkUseCases;
import com.solarized.firedown.utils.BuildUtils;
import com.solarized.firedown.utils.Utils;

public class SearchIntentActivity extends AppCompatActivity {


    private static final String TAG = SearchIntentActivity.class.getName();

    @Override
    public void onCreate(Bundle savedInstanceState) {

        super.onCreate(savedInstanceState);

        Intent intent = getIntent();

        Log.d(TAG, "handleIntent: " + Utils.bundleToString(intent.getExtras()));

        String url = AppLinkUseCases.getIntentUrl(intent);

        if(!TextUtils.isEmpty(url)){

            Log.d(TAG, "Intent data: " + url);

            Intent resultIntent = new Intent(this, BrowserActivity.class);

            resultIntent.putExtra(Intent.EXTRA_TEXT, url);

            resultIntent.setAction(Intent.ACTION_SEND);

            resultIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);

            startActivity(resultIntent);
        }

        finish();
    }
}
