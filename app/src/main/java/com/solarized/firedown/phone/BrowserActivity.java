package com.solarized.firedown.phone;

import android.os.Bundle;
import androidx.core.splashscreen.SplashScreen;

import com.solarized.firedown.BaseActivity;
import com.solarized.firedown.R;

import org.mozilla.geckoview.GeckoRuntime;


public class BrowserActivity extends BaseActivity {

    private static final String TAG = BrowserActivity.class.getSimpleName();

    private boolean mKillProcessOnDestroy;


    @Override
    public void onCreate(Bundle savedInstanceState) {
        SplashScreen splashScreen = SplashScreen.installSplashScreen(this);
        super.onCreate(savedInstanceState);

        // Keep the splash screen visible until the cold-start intent has
        // been fully processed and the destination fragment has drawn.
        // mColdStartNavigated is posted one frame after navigation, so the
        // splash covers both the repo init wait AND the navigation transition.
        splashScreen.setKeepOnScreenCondition(() -> !mColdStartNavigated);

        GeckoRuntime sGeckoRuntime = mGeckoRuntimeHelper.getGeckoRuntime();
        sGeckoRuntime.setDelegate(() -> mKillProcessOnDestroy = true);

        setContentView(R.layout.actvity_main);
        mActivityContentFrame = findViewById(R.id.content_frame);

        requestPermissions();
        requestNotificationPermission();
    }



    @Override
    protected void onDestroy() {
        if (mKillProcessOnDestroy) {
            android.os.Process.killProcess(android.os.Process.myPid());
        }
        mGeckoRuntimeHelper.getGeckoRuntime().setDelegate(null);
        mActivityContentFrame = null;
        super.onDestroy();
    }



}