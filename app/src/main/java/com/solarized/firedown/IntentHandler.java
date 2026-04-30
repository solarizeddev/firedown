package com.solarized.firedown;

import android.content.Intent;
import android.util.Log;

import androidx.navigation.NavController;
import androidx.navigation.NavDestination;

import com.solarized.firedown.data.entity.GeckoStateEntity;
import com.solarized.firedown.data.models.BrowserURIViewModel;
import com.solarized.firedown.data.models.GeckoStateViewModel;
import com.solarized.firedown.data.models.IncognitoStateViewModel;
import com.solarized.firedown.geckoview.GeckoState;
import com.solarized.firedown.utils.AppLinkUseCases;
import com.solarized.firedown.utils.NavigationUtils;

/**
 * Interprets inbound intents and dispatches the corresponding ViewModel events
 * and navigation actions.
 *
 * <h3>Responsibility contract</h3>
 * <p>IntentHandler owns <b>all</b> navigation (navigateSafe / popBackStack)
 * and <b>all</b> tab activation (setGeckoState).  It fires events on
 * {@link BrowserURIViewModel} only to pass the entity + action to
 * BrowserFragment so it can call openSession() / openUri().  Fragments
 * never navigate in response to ViewModel events — they only wire up
 * the GeckoView session.</p>
 *
 * <h3>Binder safety</h3>
 * <p>Activity results from TabsActivity, BookmarkActivity, etc. pass only
 * the tab ID (int) and isHome (boolean) — never the full GeckoStateEntity
 * which can exceed the 1MB Binder transaction limit due to session state.
 * IntentHandler looks up the live GeckoState from the singleton repository.</p>
 */
public class IntentHandler {

    private static final String TAG = IntentHandler.class.getSimpleName();

    public interface Callback {
        NavController getNavController();
        void startEncryptionService(Intent intent);
        void finishActivity();
    }

    private final BrowserURIViewModel browserURIViewModel;
    private final GeckoStateViewModel geckoStateViewModel;
    private final IncognitoStateViewModel incognitoStateViewModel;
    private final Callback callback;

    public IntentHandler(BrowserURIViewModel browserURIViewModel,
                         GeckoStateViewModel geckoStateViewModel,
                         IncognitoStateViewModel incognitoStateViewModel,
                         Callback callback) {
        this.incognitoStateViewModel = incognitoStateViewModel;
        this.browserURIViewModel = browserURIViewModel;
        this.geckoStateViewModel = geckoStateViewModel;
        this.callback = callback;
    }

    public void handle(Intent intent) {
        final NavController navController = callback.getNavController();
        final String intentAction = intent.getAction();

        if (Intent.ACTION_MAIN.equals(intentAction)) {
            handleActionMain(intent, navController);
        } else if (isExternalViewAction(intentAction)) {
            handleExternalUri(intent, navController);
        } else if (IntentActions.OPEN_URI.equals(intentAction)) {
            handleOpenUri(intent, navController);
        } else if (IntentActions.OPEN_SESSION.equals(intentAction)) {
            handleOpenSession(intent, navController);
        } else if (IntentActions.OPEN_INCOGNITO.equals(intentAction)) {
            handleOpenIncognito(intent, navController);
        } else if (IntentActions.START_ENCRYPTION.equals(intentAction)) {
            callback.startEncryptionService(intent);
            intent.setAction("");
        } else if (IntentActions.MAIN_MEDIA.equals(intentAction)) {
            handleMainMedia(intent, navController);
        } else if (IntentActions.ACTIVITY_FINISH.equals(intentAction)) {
            callback.finishActivity();
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private static boolean isExternalViewAction(String action) {
        return Intent.ACTION_SEND.equals(action)
                || Intent.ACTION_VIEW.equals(action)
                || Intent.ACTION_WEB_SEARCH.equals(action);
    }


    private void handleOpenIncognito(Intent intent, NavController navController) {
        GeckoState incognitoState = incognitoStateViewModel.peekCurrentGeckoState();

        if (incognitoState == null || incognitoStateViewModel.isEmpty()) {
            // No incognito tabs — shouldn't happen but safe fallback
            intent.setAction("");
            return;
        }

        if (incognitoState.isHome()) {
            navigateToHomeIfNeeded(navController, true);
        } else {
            browserURIViewModel.onEventSelected(
                    incognitoState.getGeckoStateEntity(), IntentActions.OPEN_SESSION);
            navigateToBrowserIfNeeded(navController);
        }

        intent.setAction("");
    }

    /**
     * ACTION_MAIN — cold start only (BaseActivity filters out warm resumes).
     *
     * <p>On cold start the nav graph inflates {@code home} as the start
     * destination. If the last active tab was a non-home page, we navigate
     * to BrowserFragment. If it was an incognito home, we swap to
     * {@code home_incognito}. If regular home, we do nothing — the
     * startDestination is already correct.</p>
     */
    private void handleActionMain(Intent intent, NavController navController) {

        // Check regular tabs first
        GeckoState geckoState = geckoStateViewModel.peekCurrentGeckoState();

        // If regular is null or home, check incognito
        if (geckoState == null || geckoState.isHome()) {
            GeckoState incognitoState = incognitoStateViewModel.peekCurrentGeckoState();
            if (incognitoState != null && !incognitoStateViewModel.isEmpty()) {
                geckoState = incognitoState;
            }
        }

        if (geckoState == null) {
            intent.setAction("");
            return;
        }

        GeckoStateEntity entity = geckoState.getGeckoStateEntity();

        if (entity.isHome()) {
            // Active tab is home — make sure we're on the right home destination
            if (entity.isIncognito()) {
                navigateToHomeIfNeeded(navController, true);
            }
            // If regular home, startDestination handles it — no-op
        } else {
            // Active tab has a URL — resume in browser
            browserURIViewModel.onEventSelected(entity, IntentActions.OPEN_SESSION);
            navigateToBrowserIfNeeded(navController);
        }

        intent.setAction("");
    }

    private void handleExternalUri(Intent intent, NavController navController) {
        String url = AppLinkUseCases.getIntentUrl(intent);
        GeckoStateEntity geckoStateEntity = new GeckoStateEntity(false, url);
        geckoStateEntity.setExternal(true);
        browserURIViewModel.onEventSelected(geckoStateEntity, IntentActions.OPEN_EXTERNAL_URI);
        navigateToBrowserIfNeeded(navController);
        intent.setAction("");
    }

    /**
     * OPEN_URI — from openSourceUrl() in DownloadsActivity/VaultActivity via
     * setSessionResult(), or from HomeFragment's search bar.
     *
     * <p>setSessionResult() now passes the tab ID as an int (Binder-safe),
     * so we read the int first. If the ID resolves to a live GeckoState in
     * the repository, we use that entity. Otherwise we create a fresh home tab.
     * This prevents phantom tab creation that occurred when getParcelableExtra()
     * returned null after the Binder fix changed the extra to an int.</p>
     */
    private void handleOpenUri(Intent intent, NavController navController) {
        String url = intent.getStringExtra(Keys.ITEM_URL);
        int tabId = intent.getIntExtra(Keys.ITEM_ID, 0);
        boolean isHome = intent.getBooleanExtra(Keys.ITEM_IS_HOME, true);

        Log.d(TAG, "handleOpenUri: tabId=" + tabId + " url=" + url + " isHome=" + isHome);

        GeckoStateEntity geckoStateEntity;
        if (url != null && !url.isEmpty()) {
            // New tab with URL (from openSourceUrl in Downloads/Vault).
            // Creates a fresh non-home entity — no existing tab to look up.
            geckoStateEntity = new GeckoStateEntity(false);
            geckoStateEntity.setUri(url);
            Log.d(TAG, "handleOpenUri: new tab from URL");
        } else if (tabId != 0) {
            // Existing tab by ID (from setSessionResult)
            GeckoState geckoState = geckoStateViewModel.getGeckoState(tabId);
            if (geckoState != null) {
                geckoStateEntity = geckoState.getGeckoStateEntity();
                Log.d(TAG, "handleOpenUri: found in repo, id=" + geckoStateEntity.getId()
                        + " uri=" + geckoStateEntity.getUri());
            } else {
                Log.w(TAG, "handleOpenUri: tabId=" + tabId + " not found in repo, creating home tab");
                geckoStateEntity = new GeckoStateEntity(true);
            }
        } else {
            Log.d(TAG, "handleOpenUri: no tabId or url, creating home tab");
            geckoStateEntity = new GeckoStateEntity(true);
        }

        browserURIViewModel.onEventSelected(geckoStateEntity, IntentActions.OPEN_URI);

        NavDestination navDestination = navController.getCurrentDestination();
        if (navDestination != null) {
            int navId = navDestination.getId();
            if (navId == R.id.dialog_browser_options) {
                NavigationUtils.popBackStackSafe(navController, R.id.dialog_browser_options);
            } else if (navId != R.id.browser) {
                // Only navigate if NOT already on BrowserFragment.
                // If already there, the existing fragment picks up the event
                // via its LiveData observer — no need to push a duplicate.
                NavigationUtils.navigateSafe(navController, R.id.browser);
            }
            // If already on BrowserFragment: no-op. The event is already set
            // on the ViewModel and the active fragment will observe it.
        }
        intent.setAction("");
    }

    /**
     * OPEN_SESSION — from TabsActivity, BookmarkActivity, etc.
     *
     * <p><b>Key design:</b> IntentHandler activates the tab AND navigates.
     * For non-home tabs, it fires an event so BrowserFragment can call
     * openSession(). For home tabs, it pops BrowserFragment to reveal
     * HomeFragment without firing any event.</p>
     *
     * <p>Reads only the tab ID (int) from the intent — never the full
     * GeckoStateEntity — to stay under the Binder transaction limit.</p>
     */
    private void handleOpenSession(Intent intent, NavController navController) {
        int tabId = intent.getIntExtra(Keys.ITEM_ID, 0);
        boolean isHome = intent.getBooleanExtra(Keys.ITEM_IS_HOME, true);

        Log.d(TAG, "handleOpenSession: tabId=" + tabId + " isHome=" + isHome);

        GeckoState geckoState = geckoStateViewModel.getGeckoState(tabId);
        if (geckoState == null) {
            Log.w(TAG, "handleOpenSession: tabId=" + tabId + " not found, creating home tab");
            geckoState = new GeckoState(new GeckoStateEntity(true));
        }
        geckoStateViewModel.setGeckoState(geckoState, true);

        if (isHome) {
            boolean isIncognito = geckoState.getGeckoStateEntity().isIncognito();
            navigateToHomeIfNeeded(navController, isIncognito);
        } else {
            GeckoStateEntity geckoStateEntity = geckoState.getGeckoStateEntity();
            browserURIViewModel.onEventSelected(geckoStateEntity, IntentActions.OPEN_SESSION);
            navigateToBrowserIfNeeded(navController);
        }
        intent.setAction("");
    }


    private void navigateToHomeIfNeeded(NavController navController, boolean incognito) {
        NavDestination dest = navController.getCurrentDestination();
        if (dest == null) return;

        int targetHome = incognito ? R.id.home_incognito : R.id.home;
        int currentId = dest.getId();

        if (currentId == targetHome) return;

        // Pop browser if on it
        if (currentId == R.id.browser) {
            NavigationUtils.popBackStackSafe(navController, R.id.browser);
            dest = navController.getCurrentDestination();
            if (dest == null) return;
            currentId = dest.getId();
        }

        if (currentId == targetHome) return;

        // Use nav actions to swap home destinations cleanly
        if (currentId == R.id.home) {
            NavigationUtils.navigateSafe(navController, R.id.action_home_to_home_incognito);
        } else if (currentId == R.id.home_incognito) {
            NavigationUtils.navigateSafe(navController, R.id.action_home_incognito_to_home);
        } else {
            // Fallback — shouldn't happen but safe
            NavigationUtils.navigateSafe(navController, targetHome);
        }
    }

    /**
     * Navigates to BrowserFragment only if it's not already the current destination.
     * Prevents stacking duplicate BrowserFragments on the back stack, which causes
     * "new tab" pop to reveal a stale BrowserFragment instead of HomeFragment.
     */
    private void navigateToBrowserIfNeeded(NavController navController) {
        NavDestination dest = navController.getCurrentDestination();
        if (dest == null || dest.getId() != R.id.browser) {
            NavigationUtils.navigateSafe(navController, R.id.browser);
        } else {
            Log.d(TAG, "navigateToBrowserIfNeeded: already on BrowserFragment, skipping");
        }
    }

    /**
     * MAIN_MEDIA — from media notification tap to bring the playing tab to foreground.
     */
    private void handleMainMedia(Intent intent, NavController navController) {
        int sessionId = intent.getIntExtra(Keys.ITEM_ID, 0);
        GeckoState geckoState = geckoStateViewModel.getGeckoState(sessionId);
        if (geckoState == null) return;

        // Activate the tab in the repository.
        geckoStateViewModel.setGeckoState(geckoState, true);

        GeckoStateEntity geckoStateEntity = geckoState.getGeckoStateEntity();
        browserURIViewModel.onEventSelected(geckoStateEntity, IntentActions.OPEN_SESSION);

        NavDestination navDestination = navController.getCurrentDestination();
        int currentId = navDestination != null ? navDestination.getId() : R.id.browser;
        if (currentId != R.id.browser) {
            NavigationUtils.navigateSafe(navController, R.id.browser);
        }
        intent.removeExtra(IntentActions.MAIN_MEDIA);
        intent.setAction("");
    }
}