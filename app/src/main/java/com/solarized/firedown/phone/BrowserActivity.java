package com.solarized.firedown.phone;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Process;
import androidx.core.app.ActivityOptionsCompat;
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
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        reopenScreenLeftOn(intent);
    }

    /* The launcher icon closed the screen the user left the app on (this
     * activity is singleTask, see ApplicationLifeCycleHandler.takeScreenLeftOn):
     * open it again, with no animation, so the app comes back where it was. */
    private void reopenScreenLeftOn(Intent intent) {
        if (!Intent.ACTION_MAIN.equals(intent.getAction())
                || !intent.hasCategory(Intent.CATEGORY_LAUNCHER)) {
            return;
        }
        Class<? extends Activity> left = mLifeCycleHandler.takeScreenLeftOn();
        if (left == null) {
            return;
        }
        startActivity(new Intent(this, left),
                ActivityOptionsCompat.makeCustomAnimation(this, 0, 0).toBundle());
    }

    @Override
    protected void onDestroy() {
        if (mKillProcessOnDestroy) {
            Process.killProcess(Process.myPid());
        }
        mGeckoRuntimeHelper.getGeckoRuntime().setDelegate(null);
        mActivityContentFrame = null;
        super.onDestroy();
    }



}