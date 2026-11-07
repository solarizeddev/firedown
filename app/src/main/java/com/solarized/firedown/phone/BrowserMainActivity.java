package com.solarized.firedown.phone;

import android.app.PictureInPictureParams;
import android.content.Context;
import android.content.Intent;
import android.content.IntentSender;
import android.content.res.Configuration;
import android.os.Bundle;
import android.os.PowerManager;
import android.os.Process;
import android.util.Rational;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.splashscreen.SplashScreen;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.Lifecycle;
import androidx.navigation.fragment.NavHostFragment;

import com.solarized.firedown.BaseActivity;
import com.solarized.firedown.R;
import com.solarized.firedown.UpdatePromptPolicy;
import com.solarized.firedown.phone.fragments.BrowserFragment;

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

    // ── Picture-in-picture (PipPolicy) ───────────────────────────────────────
    //
    // Leaving the app from a fullscreen web video that plays puts the page in
    // a PiP window (Firefox for Android's rule); any other page keeps playing
    // its audio in the background, as before. Closing the window ends the
    // video (paused, page out of fullscreen); expanding it returns to the
    // fullscreen video.

    /**
     * The app itself started another screen or app since the last resume:
     * onUserLeaveHint fires for that too ("Open in app" from a fullscreen
     * page, the file picker), and it must not put the page in PiP.
     */
    private boolean mLeavingForOwnStart;

    /** In a PiP window since the last resume, and not yet dismissed. */
    private boolean mInPipStay;

    /**
     * True from onPictureInPictureModeChanged(false) until the next
     * onResume: an expand ends in onResume, a dismissal in onStop.
     */
    private boolean mPipTeardown;

    /** The user closed the PiP window (see wasClosedFromPip). */
    private boolean mClosedFromPip;

    /** values-sw600dp/sw720dp bucket the activity was created with. */
    private int mWidthBucket;


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

        mWidthBucket = PipPolicy.widthBucket(
                getResources().getConfiguration().smallestScreenWidthDp);

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
    public void startActivityForResult(@NonNull Intent intent, int requestCode,
                                       @Nullable Bundle options) {
        mLeavingForOwnStart = true;
        try {
            super.startActivityForResult(intent, requestCode, options);
        } catch (RuntimeException e) {
            // Nothing started (no app for it): the next Home is the user's.
            mLeavingForOwnStart = false;
            throw e;
        }
    }

    @Override
    public void startIntentSenderForResult(@NonNull IntentSender intent, int requestCode,
                                           @Nullable Intent fillInIntent, int flagsMask,
                                           int flagsValues, int extraFlags,
                                           @Nullable Bundle options)
            throws IntentSender.SendIntentException {
        mLeavingForOwnStart = true;
        try {
            super.startIntentSenderForResult(intent, requestCode, fillInIntent, flagsMask,
                    flagsValues, extraFlags, options);
        } catch (IntentSender.SendIntentException | RuntimeException e) {
            mLeavingForOwnStart = false;
            throw e;
        }
    }

    @Override
    protected void onUserLeaveHint() {
        super.onUserLeaveHint();
        BrowserFragment fragment = browserFragment();
        if (fragment == null
                || !fragment.wantsPictureInPicture(mLeavingForOwnStart, isInPictureInPictureMode())) {
            return;
        }
        int[] aspect = fragment.pictureInPictureAspect();
        PictureInPictureParams params = new PictureInPictureParams.Builder()
                .setAspectRatio(new Rational(aspect[0], aspect[1]))
                .build();
        try {
            enterPictureInPictureMode(params);
        } catch (IllegalStateException | IllegalArgumentException ignored) {
            // No PiP on this device or configuration, or an OEM narrowed the
            // accepted aspect range: the audio keeps playing as before.
        }
    }

    @Override
    public void onPictureInPictureModeChanged(boolean isInPictureInPictureMode,
                                              @NonNull Configuration newConfig) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig);
        if (isInPictureInPictureMode) {
            mInPipStay = true;
            mPipTeardown = false;
            BrowserFragment fragment = browserFragment();
            if (fragment != null) {
                fragment.onPictureInPictureEntered();
            }
            return;
        }
        Lifecycle.State state = getLifecycle().getCurrentState();
        boolean stopped = !state.isAtLeast(Lifecycle.State.STARTED);
        if (PipPolicy.dismissedAtModeExit(stopped)) {
            pipDismissed();
        } else if (!state.isAtLeast(Lifecycle.State.RESUMED)) {
            // Expand (onResume follows) or a close on its way to onStop. A
            // ROM that resumed before reporting the mode change has expanded.
            mPipTeardown = true;
        }
        BrowserFragment fragment = browserFragment();
        if (fragment != null) {
            fragment.onPictureInPictureLeft();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        mLeavingForOwnStart = false;
        // Back on screen: an expanded PiP window ends here. No resource
        // bucket check here: the resources can still carry the PiP window's
        // size at this point, and a recreate mid-expand loses the fullscreen
        // page (a bucket change while in PiP, an unfold, is the residual).
        mInPipStay = false;
        mPipTeardown = false;
        mClosedFromPip = false;
    }

    @Override
    protected void onStart() {
        super.onStart();
        // Visible again: a later finish (swiped from recents) is the user
        // leaving the browser, not a closed PiP window.
        mClosedFromPip = false;
    }

    @Override
    protected void onPause() {
        super.onPause();
        // onUserLeaveHint runs before onPause: an own start is one leave.
        mLeavingForOwnStart = false;
    }

    /**
     * The PiP window was closed: on most devices the system then removes the
     * task, which FINISHES the browser. That is not the user leaving private
     * browsing (the finishing rule in ApplicationLifeCycleHandler), so the
     * private tabs stay.
     */
    public boolean wasClosedFromPip() {
        return mClosedFromPip;
    }

    @Override
    protected void onStop() {
        if (mInPipStay) {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            boolean interactive = pm == null || pm.isInteractive();
            if (PipPolicy.dismissedAtStop(mPipTeardown, isInPictureInPictureMode(), interactive)) {
                pipDismissed();
            }
        }
        super.onStop();
    }

    @Override
    public void onConfigurationChanged(@NonNull Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        recreateIfWidthBucketChanged(newConfig);
    }

    /**
     * The recreate Android did itself before the browser handled
     * smallestScreenSize: only for a real change of resource bucket, never
     * for a PiP window or its way in or out.
     */
    private void recreateIfWidthBucketChanged(Configuration config) {
        if (isInPictureInPictureMode() || mInPipStay || mPipTeardown) {
            return;
        }
        if (PipPolicy.widthBucket(config.smallestScreenWidthDp) != mWidthBucket) {
            recreate();
        }
    }

    private void pipDismissed() {
        if (!mInPipStay) {
            return;
        }
        mInPipStay = false;
        mPipTeardown = false;
        mClosedFromPip = true;
        BrowserFragment fragment = browserFragment();
        if (fragment != null) {
            fragment.onPictureInPictureDismissed();
        }
    }

    /** The browser page fragment when it is the navigation's primary one. */
    @Nullable
    private BrowserFragment browserFragment() {
        if (mActivityContentFrame == null) {
            return null;
        }
        Fragment host = mActivityContentFrame.getFragment();
        if (!(host instanceof NavHostFragment)) {
            return null;
        }
        Fragment primary = host.getChildFragmentManager().getPrimaryNavigationFragment();
        return primary instanceof BrowserFragment ? (BrowserFragment) primary : null;
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