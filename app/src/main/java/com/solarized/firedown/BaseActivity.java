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

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.IntentSenderRequest;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentContainerView;
import androidx.lifecycle.ViewModelProvider;
import androidx.navigation.NavController;
import androidx.navigation.fragment.NavHostFragment;

import androidx.preference.PreferenceManager;
import com.google.android.material.snackbar.Snackbar;
import com.solarized.firedown.crash.CrashReportSheet;
import com.solarized.firedown.data.di.Qualifiers;
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
import com.solarized.firedown.utils.IntentSanitizer;
import com.solarized.firedown.utils.NavigationUtils;
import com.solarized.firedown.utils.NotificationID;
import com.solarized.firedown.utils.Utils;

import org.mozilla.geckoview.GeckoResult;

import java.util.ArrayDeque;
import java.util.concurrent.Executor;

import javax.inject.Inject;

import dagger.hilt.android.AndroidEntryPoint;


@AndroidEntryPoint
public abstract class BaseActivity extends AppCompatActivity implements IntentHandler.Callback {

    private static final String TAG = BaseActivity.class.getSimpleName();

    /** Install-local prefs file, EXCLUDED from Auto Backup (see DownloadBackupMirror). */
    private static final String LOCAL_PREFS = "backup_local";
    /** Set once the notifications priming sheet has been shown on this install. */
    private static final String KEY_NOTIFICATION_PRIME_SHOWN = "notification_prime_shown";

    protected boolean mPaused = false;

    protected FragmentContainerView mActivityContentFrame;

    protected BrowserURIViewModel mBrowserURIViewModel;

    protected GeckoStateViewModel mGeckoStateViewModel;

    protected IncognitoStateViewModel mIncognitoStateViewModel;

    @Inject
    protected GeckoRuntimeHelper mGeckoRuntimeHelper;

    @Inject
    protected GeckoStateDataRepository mGeckoStateDataRepository;

    /** Background executor for the crash-sheet's disk scan (keeps it off the main thread). */
    @Inject
    @Qualifiers.DiskIO
    protected Executor mDiskExecutor;

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

    // ── WebAuthn / passkeys ──────────────────────────────────────────────────
    //
    // GeckoView implements the Web Authentication API (navigator.credentials)
    // on top of Google Play Services FIDO2 plus — on Android 14+ — the platform
    // Credential Manager, which is what surfaces passkeys stored in third-party
    // password managers (1Password, Bitwarden, …) to web pages. GeckoView can't
    // launch that credential UI itself: for the FIDO2 paths (security keys,
    // non-discoverable credentials, and the fallback when a passkey isn't
    // resolved purely through Credential Manager) it builds a PendingIntent and
    // hands it to GeckoRuntime.startActivityForResult, which forwards it to the
    // runtime's ActivityDelegate. With no delegate attached that call returns
    // immediately with IllegalStateException("No delegate attached"), so every
    // such WebAuthn/passkey login silently fails. Wiring this delegate is the
    // required app-side integration step (see the geckoview_example app).
    //
    // Credential prompts are modal and user-driven (one at a time), so launch
    // order == completion order; a FIFO queue is enough to pair each Activity
    // result back with the GeckoResult Gecko is awaiting. (The runtime is an
    // app-wide singleton but the result is delivered through this activity's
    // launcher, so the queue lives on the activity instance.)
    private final ArrayDeque<GeckoResult<Intent>> mWebAuthnPending = new ArrayDeque<>();

    private final ActivityResultLauncher<IntentSenderRequest> mWebAuthnLauncher =
            registerForActivityResult(
                    new ActivityResultContracts.StartIntentSenderForResult(),
                    result -> {
                        GeckoResult<Intent> pending = mWebAuthnPending.poll();
                        if (pending == null) {
                            return;
                        }
                        if (result.getResultCode() == RESULT_OK) {
                            pending.complete(result.getData());
                        } else {
                            // Gecko's contract: any non-OK result (user cancelled,
                            // credential UI failed) must complete the GeckoResult
                            // exceptionally so the page sees a WebAuthn abort/error
                            // rather than hanging on an unresolved promise.
                            pending.completeExceptionally(
                                    new RuntimeException("WebAuthn request was cancelled"));
                        }
                    });

    /**
     * Tracks which activity instance currently owns the runtime's
     * {@code ActivityDelegate}. The runtime is an app-wide singleton, but the
     * delegate must launch from — and return its result to — the foreground
     * activity, so each activity claims ownership in {@link #onResume()} and
     * only the current owner relinquishes it in {@link #onDestroy()}. Without
     * the ownership guard a finishing background activity (destroyed after the
     * next one has already resumed and re-claimed the delegate) would wipe the
     * foreground activity's delegate.
     */
    private static BaseActivity sActivityDelegateOwner;


    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        // BEFORE super.onCreate, and before any ViewModel exists:
        // ComponentActivity.getDefaultViewModelCreationExtras() hands
        // intent.extras to every SavedStateHandle built from this Activity,
        // and saving one runs its contents through bundleOf — which has no
        // case for the SparseArray that CustomTabsIntent puts in
        // EXTRA_COLOR_SCHEME_PARAMS. As a registered browser we receive
        // those extras from any app opening a link, and the throw landed in
        // onSaveInstanceState: follow a link, press home, crash.
        IntentSanitizer.stripUnsavableExtras(getIntent());

        // Apply the AMOLED true-black overlay BEFORE super.onCreate so the
        // window background, status bar, and view inflation in super pick
        // up the overridden surface tokens. App.setTheme already coerced
        // the night mode to YES for the OLED sentinel; this layers the
        // pure-black surfaces on top of the dark theme that just came up.
        int themePref = PreferenceManager
                .getDefaultSharedPreferences(this)
                .getInt(Preferences.SETTINGS_THEME,
                        AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
        if (themePref == Preferences.THEME_OLED && !isIncognitoTheme()) {
            getTheme().applyStyle(R.style.ThemeOverlay_App_OLED, true);
        }

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
        // Claim the runtime's WebAuthn ActivityDelegate for the foreground
        // activity so passkey/FIDO credential prompts launch from (and return
        // to) whichever activity is currently visible.
        installWebAuthnDelegate();
        handleIntent(getIntent());
        mPaused = false;
        // Crash-report sheet — surfaces here rather than in any single
        // fragment so it fires regardless of which activity Android
        // restarts to after a crash.
        //
        // Defer to the next main-thread tick so any pending fragment
        // transactions (e.g. NavHostFragment committing HomeFragment
        // on a fresh launch) finish before we add the dialog — without
        // this, BrowserActivity.onResume fires the dialog and Home
        // commits over the top, hiding the sheet until the user
        // navigates to a Browser tab.
        getWindow().getDecorView().post(() -> {
            // mPaused covers the common case (user navigated away
            // before the post ran). The other two catch the rarer
            // 'activity dying' cases — if we showed a sheet on an
            // isFinishing()/isDestroyed() activity we'd hit
            // IllegalStateException from FragmentManager.
            if (mPaused || isFinishing() || isDestroyed()) return;
            CrashReportSheet.showIfPending(
                    this, getSupportFragmentManager(), mDiskExecutor);
            // In-app fallback for a ready update when notifications are denied;
            // self-bails if the crash sheet above claimed the slot.
            UpdateAvailableSheet.showIfReady(
                    this, getSupportFragmentManager());
        });
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // Same reason as onCreate — and this is the path that actually fires
        // for a browser: we are singleTask, so a link followed from another
        // app while Firedown is already running arrives here, not through a
        // fresh onCreate. Must run before setIntent, which is what makes it
        // the intent the saved-state path will later read.
        IntentSanitizer.stripUnsavableExtras(intent);
        Log.d(TAG, "onNewIntent: " + Utils.bundleToString(intent.getExtras()));
        setIntent(intent);
        handleIntent(intent);
    }

    @Override
    protected void onDestroy(){
        // Only the current owner clears the delegate. By the time a finishing
        // activity is destroyed, the next activity has already resumed and
        // re-claimed ownership, so this no-ops for it and leaves the foreground
        // delegate intact; it only fires for the genuinely-last activity.
        if (sActivityDelegateOwner == this) {
            mGeckoRuntimeHelper.getGeckoRuntime().setActivityDelegate(null);
            sActivityDelegateOwner = null;
        }
        mActivityContentFrame = null;
        super.onDestroy();
    }

    // ── WebAuthn / passkeys ──────────────────────────────────────────────────

    /**
     * Installs this activity as the runtime's WebAuthn {@code ActivityDelegate}.
     * Called from {@link #onResume()} so the foreground activity always owns the
     * delegate. When Gecko needs to show a FIDO2/passkey credential UI it passes
     * us a {@link android.app.PendingIntent}; we launch it via the IntentSender
     * result launcher and return a {@link GeckoResult} that the launcher
     * callback resolves with the credential intent (or an exception on cancel).
     */
    private void installWebAuthnDelegate() {
        sActivityDelegateOwner = this;
        mGeckoRuntimeHelper.getGeckoRuntime().setActivityDelegate(pendingIntent -> {
            // Diagnostic: if this never logs when you pick "Use your passkey",
            // Gecko isn't routing WebAuthn to the Android FIDO2 backend at all
            // (the pref didn't take or this GeckoView build lacks the module) —
            // i.e. not a launch-side problem.
            Log.d(TAG, "WebAuthn ActivityDelegate invoked — launching credential UI");
            GeckoResult<Intent> result = new GeckoResult<>();
            try {
                mWebAuthnPending.add(result);
                mWebAuthnLauncher.launch(
                        new IntentSenderRequest.Builder(pendingIntent.getIntentSender()).build());
            } catch (Exception e) {
                // Launcher already unregistered (activity tearing down) or the
                // IntentSender was rejected — fail this request instead of
                // leaving Gecko waiting on a promise that never resolves.
                Log.w(TAG, "WebAuthn launch failed", e);
                mWebAuthnPending.remove(result);
                result.completeExceptionally(e);
            }
            return result;
        });
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
        if (!BuildUtils.hasAndroidTiramisu()) return;
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED) return;

        // Prime ONCE, before the system prompt. On a fresh install
        // shouldShowRequestPermissionRationale is false, so firing the OS dialog
        // here would ask with no context and leave the explanation for the
        // post-denial path — too late, since a denied POST_NOTIFICATIONS is
        // sticky and the OS won't re-prompt. Instead show the onboarding sheet
        // (which explains why, then launches the OS prompt on "Enable"); never
        // nag on later launches.
        if (notificationPrimeShown()) return;

        // Called from onCreate: only commit (mark + navigate) once the
        // NavHostFragment's graph is live, so a not-yet-ready controller doesn't
        // burn the one-shot flag without ever showing the sheet — it simply
        // retries on the next launch.
        NavController navController = getNavController();
        if (navController == null || navController.getCurrentDestination() == null) return;
        markNotificationPrimeShown();
        NavigationUtils.navigateSafe(navController, R.id.dialog_notifications_priming);
    }

    /**
     * Whether the notifications priming sheet has already been shown on this
     * install. Stored in {@code backup_local.xml} — EXCLUDED from Auto Backup
     * — so a genuine reinstall (which resets the OS permission grant) primes
     * again, rather than reading a backed-up "already shown" flag and never
     * asking. Same install-local-state pattern as the restore sentinels.
     */
    private boolean notificationPrimeShown() {
        return getSharedPreferences(LOCAL_PREFS, MODE_PRIVATE)
                .getBoolean(KEY_NOTIFICATION_PRIME_SHOWN, false);
    }

    private void markNotificationPrimeShown() {
        getSharedPreferences(LOCAL_PREFS, MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_NOTIFICATION_PRIME_SHOWN, true)
                .apply();
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

    /**
     * True when the activity's resolved theme advertises
     * {@code ?attr/isIncognitoTheme} (set on {@code Theme.FireDown.Vault}).
     * Used to skip the AMOLED true-black overlay for incognito-themed
     * activities — without this guard the overlay's pure-black
     * surfaces would clobber the incognito purple set by the vault
     * theme.
     */
    private boolean isIncognitoTheme() {
        TypedValue tv = new TypedValue();
        if (!getTheme().resolveAttribute(R.attr.isIncognitoTheme, tv, true)) {
            return false;
        }
        return tv.type == TypedValue.TYPE_INT_BOOLEAN && tv.data != 0;
    }
}