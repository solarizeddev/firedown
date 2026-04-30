package com.solarized.firedown;


import android.Manifest;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Log;
import android.util.TypedValue;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentContainerView;
import androidx.lifecycle.ViewModelProvider;
import androidx.navigation.NavController;
import androidx.navigation.fragment.NavHostFragment;

import com.google.android.material.snackbar.Snackbar;
import com.solarized.firedown.data.entity.BrowserDownloadEntity;
import com.solarized.firedown.data.models.BrowserURIViewModel;
import com.solarized.firedown.data.models.GeckoStateViewModel;
import com.solarized.firedown.data.models.IncognitoStateViewModel;
import com.solarized.firedown.data.repository.GeckoStateDataRepository;
import com.solarized.firedown.geckoview.GeckoRuntimeHelper;
import com.solarized.firedown.manager.RunnableManager;
import com.solarized.firedown.manager.tasks.TaskManager;
import com.solarized.firedown.phone.DownloadsActivity;
import com.solarized.firedown.phone.fragments.BaseFocusFragment;
import com.solarized.firedown.utils.BuildUtils;
import com.solarized.firedown.utils.NavigationUtils;
import com.solarized.firedown.utils.NotificationID;
import com.solarized.firedown.utils.Utils;

import javax.inject.Inject;

import dagger.hilt.android.AndroidEntryPoint;


@AndroidEntryPoint
public abstract class BaseActivity extends AppCompatActivity implements IntentHandler.Callback {

    private static final String TAG = BaseActivity.class.getSimpleName();

    protected boolean mPaused = false;

    protected FragmentContainerView mActivityContentFrame;

    protected BrowserURIViewModel mBrowserURIViewModel;

    protected GeckoStateViewModel mGeckoStateViewModel;

    protected IncognitoStateViewModel mIncognitoStateViewModel;

    @Inject
    protected GeckoRuntimeHelper mGeckoRuntimeHelper;

    @Inject
    protected GeckoStateDataRepository mGeckoStateDataRepository;

    /** Intent waiting to be processed until the repository finishes loading. */
    private Intent mPendingIntent;

    private IntentHandler mIntentHandler;

    /** Whether the repository has finished its initial tab load. */
    protected boolean mRepoInitialized;

    /**
     * Whether the cold-start ACTION_MAIN intent has already been handled.
     * Subsequent ACTION_MAIN intents (warm resume from recents) are no-ops —
     * the user is already on their chosen destination.
     */
    private boolean mColdStartHandled;

    /**
     * Set to true once the cold-start intent has been fully processed
     * (navigation complete). The splash screen stays visible until this
     * is true, preventing the HomeFragment flash.
     */
    protected boolean mColdStartNavigated;


    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if(BuildUtils.hasAndroidQ()){
            getWindow().setNavigationBarContrastEnforced(false);
        }

        WindowCompat.enableEdgeToEdge(getWindow());

        mBrowserURIViewModel = new ViewModelProvider(this).get(BrowserURIViewModel.class);

        mGeckoStateViewModel = new ViewModelProvider(this).get(GeckoStateViewModel.class);

        mIncognitoStateViewModel = new ViewModelProvider(this).get(IncognitoStateViewModel.class);

        mIntentHandler = new IntentHandler(mBrowserURIViewModel, mGeckoStateViewModel, mIncognitoStateViewModel,this);

        setStatusBarIconAppearance();

        setNavigationBarAppearance();

        requestPermissions();

        // Gate intent handling on repository initialization
        mGeckoStateDataRepository.isInitializedLiveData().observe(this, initialized -> {
            if (!Boolean.TRUE.equals(initialized)) return;

            mRepoInitialized = true;

            // Process any intent that arrived before init completed.
            if (mPendingIntent != null) {
                Intent intent = mPendingIntent;
                mPendingIntent = null;
                mIntentHandler.handle(intent);
            }

            dismissSplashAfterNavigation();
        });
    }

    // ── System bar appearance ────────────────────────────────────────────────

    private boolean isDarkThemeActive() {
        // Check the activity's actual resolved background color, not just system night mode.
        // This handles theme overrides like Theme.FireDown.Vault (always dark).
        TypedValue tv = new TypedValue();
        getTheme().resolveAttribute(android.R.attr.windowLightStatusBar, tv, true);
        if (tv.type == TypedValue.TYPE_INT_BOOLEAN) {
            return tv.data == 0; // false = dark theme (light icons)
        }
        // Fallback to system night mode
        int currentNightMode = getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        return currentNightMode == Configuration.UI_MODE_NIGHT_YES;
    }

    private void setStatusBarIconAppearance() {
        // Only override if the theme doesn't declare windowLightStatusBar.
        // Themes like Theme.FireDown.Vault set it explicitly and shouldn't be overridden.
        TypedValue tv = new TypedValue();
        if (getTheme().resolveAttribute(android.R.attr.windowLightStatusBar, tv, true)
                && tv.type == TypedValue.TYPE_INT_BOOLEAN) {
            // Theme declares it — respect it
            return;
        }
        // No declaration — derive from system night mode
        WindowInsetsControllerCompat controller =
                WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
        controller.setAppearanceLightStatusBars(!isDarkThemeActive());
    }

    private void setNavigationBarAppearance() {
        TypedValue tv = new TypedValue();
        if (getTheme().resolveAttribute(android.R.attr.windowLightNavigationBar, tv, true)
                && tv.type == TypedValue.TYPE_INT_BOOLEAN) {
            return;
        }
        WindowInsetsControllerCompat controller =
                WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
        controller.setAppearanceLightNavigationBars(!isDarkThemeActive());
    }

    // ── Lifecycle ────────────────────────────────────────────────────────────

    @Override
    protected void onResume(){
        super.onResume();
        Log.d(TAG, "onResume");
        handleIntent(getIntent());
        mPaused = false;
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        Log.d(TAG, "onNewIntent: " + Utils.bundleToString(intent.getExtras()));
        setIntent(intent);
        handleIntent(intent);
    }

    @Override
    protected void onDestroy(){
        mActivityContentFrame = null;
        super.onDestroy();
    }

    // ── Intent handling ──────────────────────────────────────────────────────

    /**
     * Public entry point for intent handling. Called from onResume, onNewIntent,
     * and externally from activity-result callbacks (e.g. BaseFocusFragment).
     *
     * <p>If the repository hasn't finished loading tabs from disk yet, the
     * intent is queued and will be processed as soon as init completes.
     * This prevents the race condition where handleActionMain sees an empty
     * repository and incorrectly routes to the home screen.</p>
     */
    public void handleIntent(Intent intent) {
        Log.d(TAG, "handleIntent: " + Utils.bundleToString(intent.getExtras())
                + " Intent: " + Utils.intentToString(intent)
                + " activity: " + getClass().getName()
                + " action: " + intent.getAction());

        // ACTION_MAIN is delivered on cold start AND on every warm resume
        // from recents (for singleTask activities). Only the cold-start
        // delivery should trigger navigation — subsequent ones are just
        // task-to-front signals and the user is already on their destination.
        if (Intent.ACTION_MAIN.equals(intent.getAction())) {
            if (mColdStartHandled) {
                Log.d(TAG, "handleIntent: ACTION_MAIN already handled, ignoring");
                return;
            }
            mColdStartHandled = true;
        }

        if (mRepoInitialized) {
            mIntentHandler.handle(intent);
            dismissSplashAfterNavigation();
        } else {
            Log.d(TAG, "handleIntent: repo not initialized, queuing intent");
            mPendingIntent = intent;
        }
    }

    /**
     * Posts the splash dismissal flag one frame after intent processing,
     * giving any triggered navigation time to draw before the splash
     * reveals the content. Only runs once — subsequent calls are no-ops.
     */
    private void dismissSplashAfterNavigation() {
        if (mColdStartNavigated) return;
        if (mActivityContentFrame != null) {
            mActivityContentFrame.post(() -> mColdStartNavigated = true);
        } else {
            mColdStartNavigated = true;
        }
    }



    // ── IntentHandler.Callback ───────────────────────────────────────────────

    @Override
    public NavController getNavController(){
        if(mActivityContentFrame == null)
            return null;

        NavHostFragment fragment = mActivityContentFrame.getFragment();
        if(fragment != null) {
            return fragment.getNavController();
        }

        return null;
    }

    @Override
    public void startEncryptionService(Intent intent) {
        Intent serviceIntent = new Intent(this, TaskManager.class);
        serviceIntent.putParcelableArrayListExtra(Keys.ITEM_LIST_ID,
                intent.getParcelableArrayListExtra(Keys.ITEM_LIST_ID));
        serviceIntent.setAction(intent.getAction());
        startService(serviceIntent);
    }

    @Override
    public void finishActivity() {
        finish();
    }

    // ── Downloads ────────────────────────────────────────────────────────────

    public void startDownload(BrowserDownloadEntity browserDownloadEntity, View anchorView, int anchorId){

        Intent intent = new Intent(this, RunnableManager.class);

        intent.setAction(IntentActions.DOWNLOAD_START);

        intent.putExtra(Keys.ITEM_ID, browserDownloadEntity);

        startService(intent);

        Snackbar snackbar = Snackbar.make(anchorView, R.string.downloading, Snackbar.LENGTH_LONG);

        snackbar.setAction(R.string.file_view, view -> {
            NavHostFragment navHostFragment = mActivityContentFrame.getFragment();
            Fragment fragment = navHostFragment.getChildFragmentManager().getFragments().get(0);
            if(fragment instanceof BaseFocusFragment){
                Intent downloadsIntent = new Intent(BaseActivity.this, DownloadsActivity.class);
                ((BaseFocusFragment) fragment).getActivityResultLauncher().launch(downloadsIntent);
            }
        });
        snackbar.setAnchorView(anchorId);
        snackbar.show();


    }

    public void startDownload(BrowserDownloadEntity browserDownloadEntity, View anchorView){

        Intent intent = new Intent(this, RunnableManager.class);

        intent.setAction(IntentActions.DOWNLOAD_START);

        intent.putExtra(Keys.ITEM_ID, browserDownloadEntity);

        startService(intent);

        Snackbar snackbar = Snackbar.make(anchorView, R.string.downloading, Snackbar.LENGTH_LONG);

        snackbar.setAction(R.string.file_view, view -> {
            NavHostFragment navHostFragment = mActivityContentFrame.getFragment();
            Fragment fragment = navHostFragment.getChildFragmentManager().getFragments().get(0);
            if(fragment instanceof BaseFocusFragment){
                Intent downloadsIntent = new Intent(BaseActivity.this, DownloadsActivity.class);
                ((BaseFocusFragment) fragment).getActivityResultLauncher().launch(downloadsIntent);
            }
        });

        snackbar.show();


    }

    // ── Utility ──────────────────────────────────────────────────────────────

    public View getSnackAnchorView() {
        return mActivityContentFrame;
    }

    // ── Permissions ──────────────────────────────────────────────────────────

    public void requestNotificationPermission(){
        if(BuildUtils.hasAndroidTiramisu() && (ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)){
            String[] permission = new String[]{android.Manifest.permission.POST_NOTIFICATIONS};
            if(shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)){
                NavigationUtils.navigateSafe(getNavController(), R.id.dialog_notifications_manager);
            }else{
                ActivityCompat.requestPermissions(this, permission, NotificationID.PERMISSIONS);
            }
        }
    }


    protected void requestPermissions() {
        String[] permission = new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE, Manifest.permission.READ_EXTERNAL_STORAGE};
        if (!BuildUtils.hasAndroidR()) {
            if (ContextCompat.checkSelfPermission(this,Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED ||
                    ContextCompat.checkSelfPermission(this,Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, permission, StoragePaths.PERMISSIONS_REQUESTS);
            }
        }
    }


    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode != StoragePaths.PERMISSIONS_REQUESTS || BuildUtils.hasAndroidR()) return;

        boolean allGranted = grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED
                && grantResults[1] == PackageManager.PERMISSION_GRANTED;

        if (allGranted) return;

        Snackbar snackbar = Snackbar.make(mActivityContentFrame, R.string.permission_global_phone, Snackbar.LENGTH_LONG);
        snackbar.setAction(R.string.permission_retry_phone, view -> {
            try {
                Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.fromParts("package", getPackageName(), null));
                startActivity(intent);
            } catch (ActivityNotFoundException e) {
                Log.e(TAG, "Activity not found", e);
            }
        });
        snackbar.show();
    }





}