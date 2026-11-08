package com.solarized.firedown.phone;

import android.app.PictureInPictureParams;
import android.content.Context;
import android.content.Intent;
import android.content.IntentSender;
import android.content.res.Configuration;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.Process;
import android.os.SystemClock;
import android.util.Rational;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.splashscreen.SplashScreen;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.Lifecycle;
import androidx.navigation.fragment.NavHostFragment;

import com.solarized.firedown.ApplicationLifeCycleHandler;
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

    /** PiP bookkeeping across the lifecycle; every ordering is in PipSessionTest. */
    private final PipSession mPip = new PipSession();

    /** Decides a mode exit that arrived while stopped (see PipSession). */
    private final Handler mPipHandler = new Handler(Looper.getMainLooper());
    private final Runnable mSettleStoppedExit = () -> {
        if (!isDestroyed() && mPip.settleStoppedExit(
                ApplicationLifeCycleHandler.otherScreenStarted(), SystemClock.uptimeMillis())) {
            pipDismissed();
        }
    };

    /** values-sw600dp/sw720dp bucket the activity was created with. */
    private int mWidthBucket;

    private static final String STATE_IN_PIP_STAY = "firedown.pip.stay";

    /** The task the browser's PiP window shows, or -1 (main thread). */
    private static int sPipTaskId = -1;

    /**
     * Whether a task is the one in the browser's PiP window. Another of the
     * app's screens opened from outside (a notification) lands in that task,
     * inside the small window, and must bring it back to full screen.
     */
    public static boolean isPipTask(int taskId) {
        return taskId != -1 && taskId == sPipTaskId;
    }

    /** Created inside a PiP window: the bucket is read at the first resume. */
    private static final int WIDTH_BUCKET_UNKNOWN = -1;


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

        // Recreated while in a PiP window (dark mode, locale): the stay goes
        // on, and the resource bucket is taken once out of the window. The
        // saved flag carries it whether or not the framework reports the
        // mode before the first callback.
        boolean recreatedInPip = savedInstanceState != null
                && savedInstanceState.getBoolean(STATE_IN_PIP_STAY, false);
        if (recreatedInPip || isInPictureInPictureMode()) {
            mPip.recreatedInPip();
            sPipTaskId = getTaskId();
            mWidthBucket = WIDTH_BUCKET_UNKNOWN;
        } else {
            mWidthBucket = PipPolicy.widthBucket(
                    getResources().getConfiguration().smallestScreenWidthDp);
        }

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
        mPip.ownStartBegan();
        try {
            super.startActivityForResult(intent, requestCode, options);
        } catch (RuntimeException e) {
            // Nothing started (no app for it): the next Home is the user's.
            mPip.ownStartFailed();
            throw e;
        }
    }

    @Override
    public void startIntentSenderForResult(@NonNull IntentSender intent, int requestCode,
                                           @Nullable Intent fillInIntent, int flagsMask,
                                           int flagsValues, int extraFlags,
                                           @Nullable Bundle options)
            throws IntentSender.SendIntentException {
        mPip.ownStartBegan();
        try {
            super.startIntentSenderForResult(intent, requestCode, fillInIntent, flagsMask,
                    flagsValues, extraFlags, options);
        } catch (IntentSender.SendIntentException | RuntimeException e) {
            mPip.ownStartFailed();
            throw e;
        }
    }

    @Override
    protected void onUserLeaveHint() {
        super.onUserLeaveHint();
        // Never a video floating beside the app's lock screen.
        if (mAppLock.isLocked()) {
            return;
        }
        BrowserFragment fragment = browserFragment();
        if (fragment == null
                || !fragment.wantsPictureInPicture(mPip.leavingForOwnStart(), isInPictureInPictureMode())) {
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
        BrowserFragment fragment = browserFragment();
        if (isInPictureInPictureMode) {
            mPipHandler.removeCallbacks(mSettleStoppedExit);
            sPipTaskId = getTaskId();
            mPip.enteredPip();
            if (fragment != null) {
                fragment.onPictureInPictureEntered();
            }
            return;
        }
        sPipTaskId = -1;
        if (mPip.leftPip(lifecycleState())) {
            mPipHandler.removeCallbacks(mSettleStoppedExit);
            mPipHandler.postDelayed(mSettleStoppedExit, PipSession.STOPPED_EXIT_SETTLE_MS);
        }
        if (fragment != null) {
            fragment.onPictureInPictureLeft();
        }
    }

    private PipSession.State lifecycleState() {
        Lifecycle.State state = getLifecycle().getCurrentState();
        if (state.isAtLeast(Lifecycle.State.RESUMED)) {
            return PipSession.State.RESUMED;
        }
        if (state.isAtLeast(Lifecycle.State.STARTED)) {
            return PipSession.State.STARTED;
        }
        return PipSession.State.STOPPED;
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Back on screen: an expanded PiP window ends here. No resource
        // bucket check here: the resources can still carry the PiP window's
        // size at this point, and a recreate mid-expand loses the fullscreen
        // page (a bucket change while in PiP, an unfold, is the residual).
        mPipHandler.removeCallbacks(mSettleStoppedExit);
        mPip.onResume();
        if (mWidthBucket == WIDTH_BUCKET_UNKNOWN && !isInPictureInPictureMode()) {
            mWidthBucket = PipPolicy.widthBucket(
                    getResources().getConfiguration().smallestScreenWidthDp);
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        // Visible again: a mode exit that arrived while stopped was the
        // window brought back, and a later finish (swiped from recents) is
        // the user leaving the browser, not a closed PiP window.
        mPipHandler.removeCallbacks(mSettleStoppedExit);
        mPip.onStart();
    }

    @Override
    protected void onPause() {
        super.onPause();
        mPip.onPause();
    }

    /**
     * The PiP window was just closed: on most devices the system then removes
     * the task, which FINISHES the browser. That is not the user leaving
     * private browsing (the finishing rule in ApplicationLifeCycleHandler),
     * so the private tabs stay.
     */
    public boolean wasClosedFromPip() {
        return mPip.wasClosedFromPip(SystemClock.uptimeMillis());
    }

    @Override
    protected void onStop() {
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        boolean interactive = pm == null || pm.isInteractive();
        // A recreate in the window (dark mode) stops it with the screen on,
        // like Samsung's drag-to-dismiss: isChangingConfigurations tells them apart.
        if (mPip.onStop(isInPictureInPictureMode(), interactive,
                ApplicationLifeCycleHandler.otherScreenStarted(), isChangingConfigurations(),
                SystemClock.uptimeMillis())) {
            pipDismissed();
        }
        super.onStop();
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        // The window itself, not the stay: a stay can outlive the window
        // (a mode exit not yet settled), and the new instance must not
        // think it floats.
        outState.putBoolean(STATE_IN_PIP_STAY, isInPictureInPictureMode());
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
        if (mPip.holdsRecreate(isInPictureInPictureMode())) {
            return;
        }
        if (mWidthBucket != WIDTH_BUCKET_UNKNOWN
                && PipPolicy.widthBucket(config.smallestScreenWidthDp) != mWidthBucket) {
            recreate();
        }
    }

    /** The user threw the PiP window away: the video ends. */
    private void pipDismissed() {
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
        mPipHandler.removeCallbacks(mSettleStoppedExit);
        // A mode exit still settling: the ROMs that stop before reporting a
        // close destroy right after it.
        if (mPip.onDestroy(isFinishing(), ApplicationLifeCycleHandler.otherScreenStarted(),
                isChangingConfigurations(), SystemClock.uptimeMillis())) {
            pipDismissed();
        }
        if (sPipTaskId == getTaskId()) {
            sPipTaskId = -1;
        }
        if (mKillProcessOnDestroy) {
            Process.killProcess(Process.myPid());
        }
        mGeckoRuntimeHelper.getGeckoRuntime().setDelegate(null);
        mActivityContentFrame = null;
        super.onDestroy();
    }



}