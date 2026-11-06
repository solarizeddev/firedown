package com.solarized.firedown.phone;

import android.os.Bundle;
import android.os.Process;

import androidx.annotation.NonNull;
import androidx.core.splashscreen.SplashScreen;

import com.solarized.firedown.BaseActivity;
import com.solarized.firedown.R;
import com.solarized.firedown.UpdatePromptPolicy;

import org.mozilla.geckoview.GeckoRuntime;


/**
 * The browser. singleTask, so a link from another app reuses the one browser
 * (and its GeckoView) and closes the screens above it. It is NOT the app
 * icon's activity: that is {@link LauncherActivity}, behind the
 * {@code .phone.BrowserActivity} alias (this class's old name, which home
 * screen icons point at). A singleTask launcher activity closes every screen
 * above it each time the icon is tapped.
 */
public class BrowserMainActivity extends BaseActivity {

    private static final String TAG = BrowserMainActivity.class.getSimpleName();

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



    /**
     * The home screen (regular or private) is where the update sheet may
     * open; over a web page it waits (UpdatePromptPolicy), except as the
     * first screen after the app starts.
     */
    @NonNull
    @Override
    protected UpdatePromptPolicy.Place updatePromptPlace() {
        if (showsDestination(R.id.home) || showsDestination(R.id.home_incognito)) {
            return UpdatePromptPolicy.Place.HOME;
        }
        return UpdatePromptPolicy.Place.PAGE;
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