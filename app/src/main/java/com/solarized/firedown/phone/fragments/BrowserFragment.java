package com.solarized.firedown.phone.fragments;

import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextUtils;
import android.util.Log;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.coordinatorlayout.widget.CoordinatorLayout;
import androidx.core.app.ShareCompat;
import androidx.core.content.res.ResourcesCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleEventObserver;
import androidx.lifecycle.ViewModelProvider;
import androidx.navigation.NavDestination;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.snackbar.Snackbar;
import com.solarized.firedown.App;
import com.solarized.firedown.R;
import com.solarized.firedown.autocomplete.AutoCompleteView;
import com.solarized.firedown.data.entity.CertificateInfoEntity;
import com.solarized.firedown.data.entity.ContextElementEntity;
import com.solarized.firedown.data.entity.AutoCompleteEntity;
import com.solarized.firedown.data.entity.GeckoStateEntity;
import com.solarized.firedown.data.models.BrowserDialogViewModel;
import com.solarized.firedown.data.models.BrowserDownloadViewModel;
import com.solarized.firedown.data.models.BrowserURIViewModel;
import com.solarized.firedown.data.models.GeckoStateViewModel;
import com.solarized.firedown.data.models.IncognitoStateViewModel;
import com.solarized.firedown.data.models.ShortCutsViewModel;
import com.solarized.firedown.data.models.TaskViewModel;
import com.solarized.firedown.data.models.WebBookmarkViewModel;
import com.solarized.firedown.geckoview.GeckoComponents;
import com.solarized.firedown.geckoview.GeckoResources;
import com.solarized.firedown.geckoview.GeckoState;
import com.solarized.firedown.geckoview.GeckoSwipeRefreshLayout;
import com.solarized.firedown.geckoview.GeckoToolbarBehavior;
import com.solarized.firedown.geckoview.toolbar.BottomNavigationBehavior;
import com.solarized.firedown.geckoview.NestedGeckoView;
import com.solarized.firedown.geckoview.NestedGeckoViewBehavior;
import com.solarized.firedown.geckoview.media.GeckoMediaPlaybackService;
import com.solarized.firedown.geckoview.media.GeckoMetaData;
import com.solarized.firedown.geckoview.toolbar.BottomNavigationBar;
import com.solarized.firedown.manager.DownloadRequest;
import com.solarized.firedown.phone.BookmarkActivity;
import com.solarized.firedown.phone.DownloadsActivity;
import com.solarized.firedown.phone.HistoryActivity;
import com.solarized.firedown.phone.SettingsActivity;
import com.solarized.firedown.phone.VaultActivity;
import com.solarized.firedown.ui.IncognitoColors;
import com.solarized.firedown.ui.adapters.SearchAutocompleteAdapter;
import com.solarized.firedown.geckoview.GeckoToolbar;
import com.solarized.firedown.autocomplete.AutoCompleteViewBehavior;
import com.solarized.firedown.autocomplete.AutoCompleteEditText;
import com.solarized.firedown.ui.OnItemClickListener;
import com.solarized.firedown.ui.diffs.SearchDiffCallback;
import com.solarized.firedown.IntentActions;
import com.solarized.firedown.Preferences;
import com.solarized.firedown.utils.BuildUtils;
import com.solarized.firedown.utils.AppLinkUseCases;
import com.solarized.firedown.utils.FileUriHelper;
import com.solarized.firedown.Keys;
import com.solarized.firedown.utils.NavigationUtils;
import com.solarized.firedown.utils.UrlStringUtils;

import org.apache.commons.io.FilenameUtils;
import org.mozilla.geckoview.GeckoResult;
import org.mozilla.geckoview.GeckoRuntime;
import org.mozilla.geckoview.GeckoRuntimeSettings;
import org.mozilla.geckoview.GeckoSession;
import org.mozilla.geckoview.Image;
import org.mozilla.geckoview.MediaSession;
import org.mozilla.geckoview.WebExtensionController;
import org.mozilla.geckoview.WebResponse;

import java.util.Locale;

import javax.annotation.Nullable;


public class BrowserFragment extends BaseBrowserFragment implements OnItemClickListener {

    private static final String TAG = BrowserFragment.class.getSimpleName();

    private static final int MINIMUM_TRIGGER_Y = 10;

    // ── UI state machine ──────────────────────────────────────────────────────────────────────────
    //
    // Replaces BrowserStateViewModel + BrowserViewState + BrowserViewStateLiveData.
    //
    // Rationale: the previous approach routed all UI-state transitions through LiveData, which
    // dispatches asynchronously (next main-looper tick). This created races between synchronous
    // layout operations (expandBrowserView / collapseBrowserView) and the observer that fired
    // after them — the fullscreen black-band and gap bugs were direct consequences. A plain enum
    // field is simpler, faster, and impossible to race: every transition is a direct method call
    // that executes exactly when you expect it to.
    //
    // The ViewModel pattern is appropriate for data that survives configuration changes or is
    // shared across fragments. This state is neither: it's fragment-local, purely transient, and
    // needs to be destroyed with the fragment. Moving it here is correct.

    /** Current UI mode of the browser fragment. */
    private enum UiState {
        INIT,
        BROWSING,
        SEARCH,      // find-in-page mode
        FULL_SCREEN
    }

    private boolean mIsIncognitoThemed = false;

    /**
     * Set to true while {@link #recreateSession(GeckoState)} is in flight
     * so that the incognito tab-count observer does not treat the
     * transient "count == 0" (between closeGeckoState and setActiveSession)
     * as an external close and navigate the user to regular home.
     */
    private boolean mRecreatingSession = false;

    private UiState mUiState = UiState.INIT;

    // ── Views ─────────────────────────────────────────────────────────────────────────────────────

    private NestedGeckoView mGeckoView;
    private GeckoToolbar mGeckoToolbar;
    private BottomNavigationBar mBottomNavigationBar;
    private GeckoSwipeRefreshLayout mSwipeRefreshLayout;
    private AutoCompleteEditText mAutoCompleteEditText;

    // ── ViewModels ────────────────────────────────────────────────────────────────────────────────

    private IncognitoStateViewModel mIncognitoStateViewModel;
    private BrowserDownloadViewModel mBrowserDownloadViewModel;
    private BrowserDialogViewModel mBrowserDialogViewModel;
    private ShortCutsViewModel mShortCutsViewModel;
    private WebBookmarkViewModel mWebBookmarkViewModel;
    private BrowserURIViewModel mBrowserURIViewModel;
    private TaskViewModel mTaskViewModel;

    // ── Layout sizing ─────────────────────────────────────────────────────────────────────────────

    private int mGeckoToolbarSize;
    private int mBottomBarSize;

    // ── Activity result ───────────────────────────────────────────────────────────────────────────

    private final ActivityResultLauncher<Intent> mPromptForResult = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                Intent data = result.getData();
                GeckoState geckoState = peekCurrentGeckoState();
                if (geckoState == null) return;
                GeckoSession.PromptDelegate.FilePrompt filePrompt = geckoState.getFilePrompt();
                GeckoComponents.PromptDelegate prompt =
                        (GeckoComponents.PromptDelegate)
                                geckoState.getGeckoSession().getPromptDelegate();
                if (prompt != null) {
                    prompt.onFileCallbackResult(mActivity, result.getResultCode(), data, filePrompt);
                }
            });

    // ─────────────────────────────────────────────────────────────────────────────────────────────
    // Lifecycle
    // ─────────────────────────────────────────────────────────────────────────────────────────────

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Log.d(TAG, "onCreate");

        mGeckoToolbarSize = getResources().getDimensionPixelSize(R.dimen.address_bar_layout);
        mBottomBarSize    = getResources().getDimensionPixelSize(R.dimen.app_bar_size);

        mIncognitoStateViewModel = new ViewModelProvider(mActivity).get(IncognitoStateViewModel.class);
        mTaskViewModel          = new ViewModelProvider(this).get(TaskViewModel.class);
        mShortCutsViewModel     = new ViewModelProvider(this).get(ShortCutsViewModel.class);
        mWebBookmarkViewModel   = new ViewModelProvider(this).get(WebBookmarkViewModel.class);
        mGeckoStateViewModel    = new ViewModelProvider(mActivity).get(GeckoStateViewModel.class);
        mBrowserDialogViewModel = new ViewModelProvider(mActivity).get(BrowserDialogViewModel.class);
        mBrowserURIViewModel = new ViewModelProvider(mActivity).get(BrowserURIViewModel.class);
        mBrowserDownloadViewModel = new ViewModelProvider(mActivity).get(BrowserDownloadViewModel.class);

        OnBackPressedCallback callback = new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                GeckoState geckoState = peekCurrentGeckoState();
                if (geckoState == null) {
                    setEnabled(false);
                    mActivity.getOnBackPressedDispatcher().onBackPressed();
                    return;
                }
                Log.d(TAG, "handleBackPressed uri: " + geckoState.getEntityUri()
                        + " canBack: " + geckoState.canGoBackward());

                if (geckoState.isFullScreen()) {
                    geckoState.exitFullScreen();
                } else if (mUiState == UiState.SEARCH) {
                    exitSearch();
                } else if (mAutoCompleteView.getVisibility() == View.VISIBLE) {
                    hideKeyboard(mAutoCompleteEditText);
                    mBrowserDownloadViewModel.update();
                    if (mUiState == UiState.BROWSING) {
                        mGeckoToolbar.enableScrolling();
                    }
                    mGeckoToolbar.clearFocus();
                    mGeckoToolbar.startAnimation(false);
                    mAutoCompleteView.updateVisibility(false);
                } else if (geckoState.canGoBackward()) {
                    geckoState.goBack();
                    enterBrowsing();
                } else if (geckoState.hasPreviousSession()) {
                    int previousSessionId = geckoState.getEntityParentId();
                    boolean entityIncognito = geckoState.getGeckoStateEntity().isIncognito();
                    GeckoState previousGeckoState = entityIncognito
                            ? mIncognitoStateViewModel.getGeckoState(previousSessionId)
                            : mGeckoStateViewModel.getGeckoState(previousSessionId);
                    if (entityIncognito) {
                        mIncognitoStateViewModel.closeGeckoState(geckoState);
                    } else {
                        mGeckoStateViewModel.closeGeckoState(geckoState);
                    }
                    if (previousGeckoState != null) {
                        openSession(previousGeckoState);
                    } else {
                        popToCorrectHome(entityIncognito);
                        setEnabled(false);
                    }
                } else if (geckoState.isExternal()) {
                    if (geckoState.getGeckoStateEntity().isIncognito()) {
                        mIncognitoStateViewModel.closeGeckoState(geckoState);
                    } else {
                        mGeckoStateViewModel.closeGeckoState(geckoState);
                    }
                    setEnabled(false);
                    mActivity.finish();
                } else {
                    Log.d(TAG, "onBackPressed back to home");
                    mGeckoMediaController.stopMediaForSession(geckoState.getEntityId());
                    popToCorrectHome(geckoState.getGeckoStateEntity().isIncognito());
                    setEnabled(false);
                }

            }
        };
        mActivity.getOnBackPressedDispatcher().addCallback(this, callback);
    }

    @Override
    public void onConfigurationChanged(@NonNull Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        int nightMode = newConfig.uiMode & Configuration.UI_MODE_NIGHT_MASK;
        int colorScheme = (nightMode == Configuration.UI_MODE_NIGHT_YES)
                ? GeckoRuntimeSettings.COLOR_SCHEME_DARK
                : GeckoRuntimeSettings.COLOR_SCHEME_LIGHT;
        mGeckoRuntimeHelper.getGeckoRuntime()
                .getSettings()
                .setPreferredColorScheme(colorScheme);
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        Log.d(TAG, "onCreateView");
        View v = inflater.inflate(R.layout.fragment_browser, container, false);

        mBottomNavigationBar = v.findViewById(R.id.bottom_app_bar);
        mBottomNavigationBar.setListener(this);

        mSwipeRefreshLayout = v.findViewById(R.id.swipe);
        mAutoCompleteView   = v.findViewById(R.id.auto_complete_view);
        mGeckoView          = v.findViewById(R.id.geckoview);
        mGeckoToolbar       = v.findViewById(R.id.toolbar_layout);

        mGeckoToolbar.disableScrolling();
        mGeckoToolbar.setOnClearFocusListener(this);
        mGeckoToolbar.setListener(this);

        mAutoCompleteEditText = mGeckoToolbar.getAutoCompleteEditText();
        mAutoCompleteEditText.setOnTextChangedListener(this);
        mAutoCompleteEditText.setOnCommitListener(this);
        mAutoCompleteEditText.setOnSearchStateChangeListener(this);
        mAutoCompleteEditText.setOnFilterListener(this);
        mAutoCompleteEditText.setOnFocusChangeListener(this);

        mSwipeRefreshLayout.setProgressViewOffset(false, 0, mGeckoToolbarSize + mBottomBarSize);

        mAutoCompleteView.setClipboardCallback(new AutoCompleteView.OnClipboardListener() {
            @Override
            public void onClipboardClick(CharSequence text) {
                if (!TextUtils.isEmpty(text)) {
                    GeckoState geckoState = peekCurrentGeckoState();
                    if (geckoState == null) return;
                    geckoState.setEntityUri(mSearchRepository.parseUri(text.toString()));
                    openUri(geckoState);
                }
            }

            @Override
            public void onClipboardLongClick(CharSequence text) {
                Bundle bundle = new Bundle();
                bundle.putString(Keys.TITLE, text.toString());
                NavigationUtils.navigateSafe(mNavController, R.id.dialog_delete_clipboard, R.id.browser, bundle);
            }
        });

        mSearchAutocompleteAdapter = new SearchAutocompleteAdapter(mActivity, new SearchDiffCallback(), this);
        mAutoCompleteView.getRecyclerView().setAdapter(mSearchAutocompleteAdapter);

        mSwipeRefreshLayout.setOnRefreshListener(this);
        mSwipeRefreshLayout.setEnabled(false);
        mSwipeRefreshLayout.setColorSchemeResources(
                R.color.md_theme_primaryContainer,
                R.color.md_theme_primaryContainer,
                R.color.md_theme_primaryContainer);

        mGeckoView.coverUntilFirstPaint(ResourcesCompat.getColor(getResources(), R.color.white, null));
        mGeckoView.setActivityContextDelegate(() -> mActivity);
        mGeckoView.setDynamicToolbarMaxHeight(mGeckoToolbarSize + mBottomBarSize);
        mGeckoView.setVerticalClipping(0);

        CoordinatorLayout.LayoutParams layoutParams =
                (CoordinatorLayout.LayoutParams) mSwipeRefreshLayout.getLayoutParams();
        layoutParams.setBehavior(new NestedGeckoViewBehavior(
                mSwipeRefreshLayout.getContext(), null, mSwipeRefreshLayout,
                mGeckoToolbarSize, mBottomBarSize));

        CoordinatorLayout.LayoutParams layoutParamsSearch =
                (CoordinatorLayout.LayoutParams) mAutoCompleteView.getLayoutParams();
        layoutParamsSearch.setBehavior(new AutoCompleteViewBehavior(
                mAutoCompleteView.getContext(), null, mAutoCompleteView, mGeckoToolbarSize));

        mSwipeRefreshLayout.requestLayout();

        CoordinatorLayout.LayoutParams layoutToolbarParams =
                (CoordinatorLayout.LayoutParams) mGeckoToolbar.getLayoutParams();
        layoutToolbarParams.setBehavior(new GeckoToolbarBehavior(mGeckoToolbar.getContext(), null));
        mGeckoToolbar.requestLayout();

        mDownloadButton = v.findViewById(R.id.download_button);
        mDownloadButton.setOnClickListener(v1 -> {
            Bundle bundle = new Bundle();
            bundle.putBoolean(Keys.IS_INCOGNITO, mIsIncognitoThemed);
            NavigationUtils.navigateSafe(mNavController, R.id.dialog_browser_options, R.id.browser, bundle);
        });
        return v;
    }

    @Override
    public void onViewCreated(@NonNull View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        Log.d(TAG, "onViewCreated");

        ViewCompat.setOnApplyWindowInsetsListener(view, (v, windowInsets) -> {
            Insets insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars()
                    | WindowInsetsCompat.Type.displayCutout());
            view.setPadding(insets.left, insets.top, insets.right, insets.bottom);
            return WindowInsetsCompat.CONSUMED;
        });

        mTaskViewModel.getObservableCount().observe(getViewLifecycleOwner(),
                count -> mBottomNavigationBar.onBadgeCount(count));

        mGeckoStateViewModel.getTabsCount().observe(getViewLifecycleOwner(), count -> {
            if (!mIsIncognitoThemed) mBottomNavigationBar.onTabsCount(count);
        });

        mIncognitoStateViewModel.getTabsCount().observe(getViewLifecycleOwner(), count -> {
            if (mIsIncognitoThemed) {
                mBottomNavigationBar.onTabsCount(count);
                // If all incognito tabs were closed externally (e.g. via the
                // notification "Close all" action), navigate to regular home.
                //
                // Skip this during recreateSession: that path closes the
                // state then immediately re-adds it, so count transiently
                // hits 0 even though the tab is not really being closed.
                if (count == 0 && !mRecreatingSession) {
                    Log.d(TAG, "All incognito tabs closed externally, navigating to regular home");
                    popToCorrectHome(false);
                }
            }
        });

        mBrowserURIViewModel.getEvents().observe(getViewLifecycleOwner(), mPair -> {
            // Null guard — clearEvent() sets null, and re-subscription
            // on config change delivers the current (null) value.
            if (mPair == null) {
                Log.d(TAG, "BrowserURIViewModel event: null (cleared or no event)");
                return;
            }

            String action = mPair.second;
            GeckoStateEntity geckoStateEntity = mPair.first;
            Log.d(TAG, "BrowserURIViewModel event received:"
                    + " action=" + action
                    + " entityId=" + geckoStateEntity.getId()
                    + " uri=" + geckoStateEntity.getUri()
                    + " isHome=" + geckoStateEntity.isHome());

            // Clear immediately to prevent re-delivery on config change.
            // IntentHandler already handled navigation and tab activation;
            // we only need to wire up the GeckoView session.
            mBrowserURIViewModel.clearEvent();

            switch (action) {
                case IntentActions.OPEN_EXTERNAL_URI,
                     IntentActions.OPEN_SESSION -> {
                    GeckoState geckoState = setActiveSession(geckoStateEntity, true);
                    Log.d(TAG, "BrowserURIViewModel → openSession for id=" + geckoState.getEntityId()
                            + " uri=" + geckoState.getEntityUri()
                            + " hasGeckoSession=" + (geckoState.getGeckoSession() != null));
                    openSession(geckoState);
                }
                case IntentActions.OPEN_URI -> {
                    GeckoState geckoState = setActiveSession(geckoStateEntity, true);
                    Log.d(TAG, "BrowserURIViewModel → openSession+openUri for id=" + geckoState.getEntityId()
                            + " uri=" + geckoState.getEntityUri());
                    openSession(geckoState);
                    openUri(geckoState);
                }
                default ->
                        Log.w(TAG, "BrowserURIViewModel unhandled action: " + action);
                // OPEN_HOME is handled entirely by IntentHandler (tab activation +
                // navigation).  BrowserFragment doesn't need to act — if we're
                // being popped, we'll be destroyed.  No case needed.
            }
        });

        mBrowserDialogViewModel.getOptionsEvent().observe(getViewLifecycleOwner(), mOptionEntity -> {
            int id = mOptionEntity.getId();

            if (id == R.id.action_download) {
                DownloadRequest request = mOptionEntity.getDownloadRequest();
                if (request != null) {
                    startDownload(request, getSnackAnchorView(), R.id.anchor_view);
                }
            } else if (id == R.id.action_clear_browsing) {
                String host = mOptionEntity.getAction();
                Snackbar snackbar = makeSnackbar(getSnackAnchorView(), getString(R.string.settings_clear_browsing_success, host), mIsIncognitoThemed);
                snackbar.setAnchorView(R.id.anchor_view);
                snackbar.show();
            } else if (id == R.id.action_clear_error_browsing) {
                String host = mOptionEntity.getAction();
                Snackbar snackbar = makeSnackbar(getSnackAnchorView(), getString(R.string.settings_clear_browsing_error, host), mIsIncognitoThemed);
                snackbar.setAnchorView(R.id.anchor_view);
                snackbar.show();
            }else if (id == R.id.action_delete_clipboard) {
                mAutoCompleteView.hideClipboard();
            } else if (id == R.id.popup_vault) {
                mStartForResult.launch(new Intent(mActivity, VaultActivity.class));
            } else if (id == R.id.popup_downloads) {
                mStartForResult.launch(new Intent(mActivity, DownloadsActivity.class));
            }else if (id == R.id.popup_bookmarks) {
                mStartForResult.launch(new Intent(mActivity, BookmarkActivity.class));
            } else if (id == R.id.popup_history) {
                mStartForResult.launch(new Intent(mActivity, HistoryActivity.class));
            } else if (id == R.id.popup_settings) {
                mStartForResult.launch(new Intent(mActivity, SettingsActivity.class));
            } else if (id == R.id.popup_share) {
                GeckoState mGeckoState = peekCurrentGeckoState();
                if (mGeckoState == null) return;
                new ShareCompat.IntentBuilder(mActivity)
                        .setType("text/plain")
                        .setChooserTitle(App.getAppContext().getString(R.string.share_url))
                        .setText(mGeckoState.getEntityUri())
                        .startChooser();
            } else if (id == R.id.popup_find) {
                enterSearch();
            } else if (id == R.id.popup_go_forward) {
                GeckoState geckoState = peekCurrentGeckoState();
                if (geckoState == null) return;
                if (geckoState.canGoForward()) geckoState.goForward();
                enterBrowsing();
            } else if (id == R.id.popup_go_backward) {
                GeckoState geckoState = peekCurrentGeckoState();
                if (geckoState == null) return;
                if (geckoState.canGoBackward()) geckoState.goBack();
                enterBrowsing();
            } else if (id == R.id.popup_desktop_switch || id == R.id.popup_desktop) {
                GeckoState geckoState = peekCurrentGeckoState();
                if (geckoState == null) return;
                geckoState.setEntityDesktop(!geckoState.isDesktop());
                recreateSession(geckoState);
            } else if (id == R.id.popup_bookmark_add) {
                GeckoState mGeckoState = peekCurrentGeckoState();
                if (mGeckoState == null) return;
                mWebBookmarkViewModel.add(mGeckoState);
                Snackbar snackbar = makeSnackbar(getSnackAnchorView(), R.string.browser_bookmark_added, mIsIncognitoThemed);
                snackbar.setAnchorView(R.id.anchor_view);
                snackbar.show();
            } else if (id == R.id.popup_bookmark_edit) {
                GeckoState mGeckoState = peekCurrentGeckoState();
                if (mGeckoState == null) return;
                String url = mGeckoState.getEntityUri();
                Intent bookmarksIntent = new Intent(mActivity, BookmarkActivity.class);
                bookmarksIntent.putExtra(Keys.ITEM_ID, url.hashCode());
                bookmarksIntent.setAction(IntentActions.BOOKMARK_EDIT);
                mStartForResult.launch(bookmarksIntent);
            } else if (id == R.id.popup_reload) {
                GeckoState gs = peekCurrentGeckoState();
                if (gs != null) gs.reload();
            } else if (id == R.id.popup_pin_add) {
                if (mShortCutsViewModel.isFull()) {
                    Bundle args = new Bundle();
                    args.putBoolean(Keys.OPEN_INCOGNITO, mIsIncognitoThemed);
                    NavigationUtils.navigateSafe(mNavController, R.id.dialog_shortcuts_max, R.id.browser , args);
                } else {
                    GeckoState mGeckoState = peekCurrentGeckoState();
                    if (mGeckoState == null) return;
                    mShortCutsViewModel.add(mGeckoState);
                    Snackbar snackbar = makeSnackbar(getSnackAnchorView(), R.string.snackbar_added_to_shortcuts, mIsIncognitoThemed);
                    snackbar.setAnchorView(R.id.anchor_view);
                    snackbar.show();
                }
            } else if (id == R.id.popup_pin_edit) {
                GeckoState gs = peekCurrentGeckoState();
                if (gs != null) mShortCutsViewModel.delete(gs);
            } else if (id == R.id.popup_stop) {
                GeckoState gs = peekCurrentGeckoState();
                if (gs != null) {
                    gs.stop();
                    mGeckoToolbar.setLoading(false);
                    mBrowserDialogViewModel.setLoading(false);
                }
            } else if(id == R.id.new_tab){
                boolean isIncognito = mIsIncognitoThemed;
                GeckoStateEntity geckoStateEntity = new GeckoStateEntity(true);
                geckoStateEntity.setIncognito(isIncognito);
                // Route through setActiveSession so creation and repo-insert
                // stay consistent across the codebase and repo lookup-first
                // semantics are preserved.
                setActiveSession(geckoStateEntity, true);
                popToCorrectHome(isIncognito);
            } else if(id == R.id.new_incognito_tab){
                GeckoStateEntity geckoStateEntity = new GeckoStateEntity(true);
                geckoStateEntity.setIncognito(true);
                // Route through setActiveSession so creation and repo-insert
                // stay consistent across the codebase and repo lookup-first
                // semantics are preserved.
                setActiveSession(geckoStateEntity, true);
                popToCorrectHome(true);
            } else if (id == R.id.popup_quit) {
                quitApp();
            }
        });

        mBrowserDialogViewModel.getContextEvent().observe(getViewLifecycleOwner(), mPair -> {
            int id = mPair.second;
            ContextElementEntity mContextElementEntity = mPair.first;
            String linkUri = TextUtils.isEmpty(mContextElementEntity.getLinkUri())
                    ? mContextElementEntity.getBaseUri()
                    : mContextElementEntity.getLinkUri();
            String srcUri = mContextElementEntity.getSrcUri();

            if (id == R.string.contextmenu_copy_link) {
                mContextActions.copyToClipboard(mActivity, Preferences.CLIPBOARD_LABEL, linkUri);
            } else if (id == R.string.contextmenu_copy_image_location) {
                mContextActions.copyToClipboard(mActivity, getString(R.string.share_image), srcUri);
            } else if (id == R.string.contextmenu_share_link) {
                new ShareCompat.IntentBuilder(mActivity)
                        .setType("text/plain")
                        .setChooserTitle(getString(R.string.share_url))
                        .setText(linkUri)
                        .startChooser();
            } else if (id == R.string.contextmenu_share_image) {
                mContextActions.launchContextOption(mActivity, srcUri, id);
            } else if (id == R.string.contextmenu_copy_image) {
                mContextActions.launchContextOption(mActivity, srcUri, id);
            } else if (id == R.string.contextmenu_save_image) {
                GeckoState geckoState = peekCurrentGeckoState();
                if (geckoState == null) return;
                DownloadRequest request = new DownloadRequest.Builder(srcUri)
                        .saveToVault(mIsIncognitoThemed)
                        .name(FilenameUtils.getName(srcUri))
                        .cookieHeader(geckoState.getCookieHeader())
                        .build();
                startDownload(request, getSnackAnchorView(), R.id.anchor_view);
            } else if (id == R.string.contextmenu_download_link) {
                DownloadRequest request = new DownloadRequest.Builder(linkUri)
                        .saveToVault(mIsIncognitoThemed)
                        .name(FilenameUtils.getName(linkUri))
                        .mimeType(FileUriHelper.MIMETYPE_HTML)
                        .build();
                startDownload(request, getSnackAnchorView(), R.id.anchor_view);
            } else if (id == R.string.contextmenu_open_link_in_new_tab) {
                GeckoStateEntity geckoStateEntity = new GeckoStateEntity(false);
                geckoStateEntity.setUri(linkUri);

                GeckoState current = peekCurrentGeckoState();
                if (current != null) geckoStateEntity.setParentId(current.getEntityId());

                if (mIsIncognitoThemed) {
                    geckoStateEntity.setIncognito(true);
                }

                GeckoState geckoState = setActiveSession(geckoStateEntity, false);

                if (mIsIncognitoThemed) {
                    mIncognitoStateViewModel.notifyTabs();
                } else {
                    mGeckoStateViewModel.notifyTabs();
                }

                Snackbar snackbar = makeSnackbar(getSnackAnchorView(), R.string.contextmenu_snackbar_new_tab_opened, mIsIncognitoThemed);
                snackbar.setAction(R.string.contextmenu_snackbar_action_switch, v -> openSession(geckoState));
                snackbar.setAnchorView(R.id.anchor_view);
                snackbar.show();
            } else if (id == R.string.contextmenu_open_image_in_new_tab) {
                GeckoStateEntity geckoStateEntity = new GeckoStateEntity(false);
                geckoStateEntity.setUri(srcUri);

                GeckoState current = peekCurrentGeckoState();
                if (current != null) geckoStateEntity.setParentId(current.getEntityId());

                if (mIsIncognitoThemed) {
                    geckoStateEntity.setIncognito(true);
                }

                GeckoState geckoState = setActiveSession(geckoStateEntity, false);
                openSession(geckoState);

                Snackbar snackbar = makeSnackbar(getSnackAnchorView(), R.string.contextmenu_snackbar_new_tab_opened, mIsIncognitoThemed);
                snackbar.setAnchorView(R.id.anchor_view);
                snackbar.show();
            }
        });

        mGeckoStateViewModel.getTackingEnabled().observe(getViewLifecycleOwner(),
                active -> {
                    if (!mIsIncognitoThemed) mGeckoToolbar.setTrackingEnabled(active);
                });

        mIncognitoStateViewModel.getTrackingEnabled().observe(getViewLifecycleOwner(),
                active -> {
                    if (mIsIncognitoThemed) mGeckoToolbar.setTrackingEnabled(active);
                });

        mGeckoStateViewModel.isAdsFilterEnabled().observe(getViewLifecycleOwner(),
                active -> {
                    if (!mIsIncognitoThemed) mGeckoToolbar.setAdsEnabled(active);
                });

        mAutoCompleteViewModel.getAutoComplete().observe(getViewLifecycleOwner(), result -> {
            if (TextUtils.isEmpty(result))
                mAutoCompleteEditText.noAutocompleteResult();
            else
                mAutoCompleteEditText.applyAutocompleteResult(result);
        });

        mAutoCompleteViewModel.getWebSearch().observe(getViewLifecycleOwner(), webSearch -> {
            if (webSearch == null || webSearch.isEmpty()) {
                mAutoCompleteView.showEmpty();
            } else {
                mAutoCompleteView.hideAll();
            }
            mSearchAutocompleteAdapter.submitList(webSearch);
        });

        getViewLifecycleOwner().getLifecycle().addObserver((LifecycleEventObserver) (source, event) -> {
            if (Lifecycle.Event.ON_DESTROY.equals(event)) {
                Log.d(TAG, "onDestroy");
                mGeckoObserverRegistry.unregister(BrowserFragment.this);
            } else if (Lifecycle.Event.ON_CREATE.equals(event)) {
                Log.d(TAG, "onCreate");
                mGeckoObserverRegistry.register(BrowserFragment.this);
            } else if (Lifecycle.Event.ON_PAUSE.equals(event) || Lifecycle.Event.ON_STOP.equals(event)) {
                Log.d(TAG, "onPause/onStop");
                mStop = true;
                mSwipeRefreshLayout.setEnabled(false);
            } else if (Lifecycle.Event.ON_START.equals(event)) {
                Log.d(TAG, "onStart");
                mSwipeRefreshLayout.setEnabled(true);

            } else if (Lifecycle.Event.ON_RESUME.equals(event)) {
                Log.d(TAG, "onResume");
                mStop = false;
                mSwipeRefreshLayout.setEnabled(true);
                mBrowserDownloadViewModel.update();

                // ── FIX: Reconnect the current session after resume ──────────
                //
                // Placed in ON_RESUME (not ON_START) to avoid a double-
                // openSession() race.  The lifecycle ordering is:
                //
                //   1. Fragment ON_START
                //   2. Activity onResume → ActivityResultCallback fires →
                //      IntentHandler → BrowserURIViewModel.onEventSelected() →
                //      LiveData observer fires synchronously → openSession(newTab)
                //   3. Fragment ON_RESUME  ← we are here
                //
                // If step 2 already connected the correct session (tab switch
                // from TabsActivity), mGeckoView.getSession() now matches
                // the current GeckoState and ensureSessionConnected() is a
                // no-op.  If no result arrived (plain app resume, RESULT_CANCELED,
                // or return from Settings/Downloads), ensureSessionConnected()
                // re-attaches the session that was released in onDestroyView().
                ensureSessionConnected();
            }
        });

        // Always ensure normal (non-incognito) theme on initial view creation.
        // The actual incognito theme will be applied by openSession →
        // applyBrowserIncognitoTheme if the session is incognito.
        // This prevents stale incognito colors when navigating here from an
        // incognito destination whose onDestroyView hasn't run yet.
        // resetWindowTheme handles the window-level state (decor view
        // background, system bar colors) that persists across fragments.
        resetWindowTheme();
        mBottomNavigationBar.updateTheme(mActivity, false);
        mGeckoToolbar.updateTheme(mActivity, false);
        mAutoCompleteView.updateTheme(mActivity, false);
        mSearchAutocompleteAdapter.setIncognito(false);

        Log.d(TAG, "onViewCreated finished");
    }

    /**
     * Ensures the GeckoView is displaying the current active session.
     * <p>
     * This is the primary fix for the "dead tab after resume" bug.  It handles
     * three scenarios:
     * <ol>
     *   <li>View was destroyed and recreated (onDestroyView → onCreateView cycle):
     *       mGeckoView is a fresh inflation with no session attached.</li>
     *   <li>App was backgrounded and foregrounded without a tab switch: no
     *       BrowserURIViewModel event fires, so nothing calls openSession().</li>
     *   <li>Returned from a secondary activity that didn't produce a result
     *       (e.g. RESULT_CANCELED from TabsActivity): mStartForResult handler
     *       doesn't route to BrowserURIViewModel.</li>
     * </ol>
     */
    private void ensureSessionConnected() {
        if (mGeckoView == null) return;
        if (mBrowserURIViewModel.hasPendingEvent()) return;

        GeckoState current = peekCurrentGeckoState();

        if (current == null || current.isHome()) {
            // No active session to reconnect — go home instead of showing a black screen
            popToCorrectHome(mIsIncognitoThemed);
            return;
        }

        GeckoSession viewSession = mGeckoView.getSession();
        GeckoSession stateSession = current.getGeckoSession();

        // FIX: also reconnect when the session object exists but
        // the native side is dead (isOpen == false), or when both are null
        boolean needsReconnect = (viewSession != stateSession)
                || (viewSession == null)
                || (!stateSession.isOpen());

        if (needsReconnect) {
            openSession(current);
        } else {
            applyBrowserIncognitoTheme(
                    current.getGeckoStateEntity().isIncognito());
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        Log.d(TAG, "onDestroyView: releasing GeckoView session");
        if (mSwipeRefreshLayout != null) mSwipeRefreshLayout.setOnRefreshListener(null);
        if (mGeckoView != null) {
            Log.d(TAG, "onDestroyView: current viewSession=" + mGeckoView.getSession());
            mGeckoView.releaseSession();
        }
        mBottomNavigationBar      = null;
        mSwipeRefreshLayout       = null;
        mAutoCompleteEditText     = null;
        mAutoCompleteView         = null;
        mGeckoView                = null;
        mDownloadButton           = null;
        mGeckoToolbar             = null;
        mSearchAutocompleteAdapter = null;
    }

    // ─────────────────────────────────────────────────────────────────────────────────────────────
    // UI state machine — direct transition methods
    //
    // Each method is idempotent: calling enterBrowsing() when already BROWSING is a no-op.
    // All UI changes are synchronous — no async dispatch, no observer races.
    // ─────────────────────────────────────────────────────────────────────────────────────────────

    /**
     * Transitions to BROWSING. Safe to call when already BROWSING (early-return guard).
     * Called from: openSession(), openUri(), back-press landing, exitSearch(), exitFullScreen(),
     * popup go-forward/go-backward, onNew().
     */
    private void enterBrowsing() {
        if (mUiState == UiState.BROWSING) return;

        GeckoState geckoState = peekCurrentGeckoState();
        if (geckoState == null) return;

        mUiState = UiState.BROWSING;
        geckoState.setSearchMode(false);
        mGeckoObserverRegistry.register(this);
        mDownloadButton.setVisibility(View.VISIBLE);
        mGeckoView.setVisibility(View.VISIBLE);
        mGeckoToolbar.enableScrolling();
        mGeckoToolbar.onLocationChange(geckoState.getEntityUri());
        mBottomNavigationBar.show();
    }

    /**
     * Transitions to find-in-page SEARCH mode.
     * Called from: popup_find option.
     */
    private void enterSearch() {
        if (mUiState == UiState.SEARCH)
            return;
        GeckoState geckoState = peekCurrentGeckoState();
        if (geckoState == null)
            return;
        mUiState = UiState.SEARCH;
        geckoState.setSearchMode(true);
        mGeckoToolbar.disableScrolling();
        mGeckoToolbar.enableSearch();
        mBottomNavigationBar.setVisibility(View.GONE);
        mDownloadButton.setVisibility(View.GONE);
    }

    /**
     * Exits find-in-page SEARCH mode and returns to BROWSING.
     * Called from: back-press, onLocationChange(), onToolbarClearFocus().
     */
    private void exitSearch() {
        if (mUiState != UiState.SEARCH) return;
        mUiState = UiState.INIT;

        GeckoState geckoState = peekCurrentGeckoState();
        if (geckoState != null) {
            geckoState.getOrCreateGeckoSession().getFinder().clear();
            geckoState.setSearchMode(false);
        }

        // Always restore UI even if geckoState is null
        mGeckoToolbar.clearText();
        mGeckoToolbar.enableScrolling();
        mBottomNavigationBar.setVisibility(View.VISIBLE);
        mBottomNavigationBar.show();
        mDownloadButton.setVisibility(View.VISIBLE);

        enterBrowsing();
    }

    /**
     * Enters FULL_SCREEN UI state.
     *
     * <p><b>Must be called AFTER</b> {@link #expandBrowserView()} — that method is timing-
     * sensitive and synchronous; this one handles only the overlay UI bookkeeping (download
     * button, snackbar).
     *
     * <p>Called from {@link #onFullScreen(boolean)} and {@link #onHideBars(GeckoState)}.
     */
    private void enterFullScreen(View decorView) {
        decorView.setBackgroundColor(Color.BLACK);
        mUiState = UiState.FULL_SCREEN;
        mDownloadButton.setVisibility(View.GONE);
        makeSnackbar(mBottomNavigationBar, R.string.exit_fullscreen_with_back_button_short, mIsIncognitoThemed).show();
    }

    /**
     * Exits FULL_SCREEN and returns to BROWSING.
     *
     * <p><b>Must be called AFTER</b> {@link #collapseBrowserView()}.
     * Called from {@link #onFullScreen(boolean)}.
     */
    private void exitFullScreen(View decorView) {
        final TypedValue typedValue = new TypedValue();
        mActivity.getTheme().resolveAttribute(android.R.attr.colorBackground, typedValue, true);
        decorView.setBackgroundColor(typedValue.data);
        mUiState = UiState.INIT;
        mDownloadButton.setVisibility(View.VISIBLE);
        enterBrowsing();
    }

    // ─────────────────────────────────────────────────────────────────────────────────────────────
    // Toolbar callbacks
    // ─────────────────────────────────────────────────────────────────────────────────────────────

    @Override
    public void onToolbarButtonClick(View v, int id) {
        Log.d(TAG, "onToolbarButtonClick: " + getResources().getResourceName(id));

        if (id == R.id.tab_button) {
            Bundle args = new Bundle();
            args.putBoolean(Keys.OPEN_INCOGNITO, mIsIncognitoThemed);
            NavigationUtils.navigateSafe(mNavController, R.id.tabs, R.id.browser, args);
        } else if (id == R.id.clear_button) {
            mAutoCompleteViewModel.resetEngines();
            mAutoCompleteView.showEmpty();
            mGeckoToolbar.clearText();
        } else {
            GeckoState geckoState = peekCurrentGeckoState();
            if (geckoState == null)
                return;
            if (id == R.id.security_button) {
                Bundle bundle = new Bundle();
                bundle.putBoolean(Keys.IS_INCOGNITO, mIsIncognitoThemed);
                NavigationUtils.navigateSafe(mNavController, R.id.dialog_security_info, R.id.browser, bundle);
            } else if (id == R.id.search_up) {
                findNextResult(mGeckoToolbar.getText(), GeckoSession.FINDER_FIND_BACKWARDS);
            } else if (id == R.id.search_down) {
                findNextResult(mGeckoToolbar.getText(), 0);
            } else if (id == R.id.reload_button) {
                geckoState.reload();
            } else if (id == R.id.stop_button) {
                geckoState.stop();
                mGeckoToolbar.setLoading(false);
                mBrowserDialogViewModel.setLoading(false);
            }
        }
    }

    @Override
    public void onToolbarClearFocus() {
        super.onToolbarClearFocus();
        exitSearch();
    }

    // ─────────────────────────────────────────────────────────────────────────────────────────────
    // Bottom bar callbacks
    // ─────────────────────────────────────────────────────────────────────────────────────────────

    @Override
    public boolean onBottomBarButtonLongClick(View v, int id){
        if (id == R.id.new_tab_button) {
            NavigationUtils.navigateSafe(mNavController, R.id.dialog_new_tabs, R.id.browser);
            return true;
        }
        return false;
    }

    @Override
    public void onBottomBarButtonClick(View v, int id) {
        if (id == R.id.tab_button) {
            navigateToTabs();
        } else if (id == R.id.new_tab_button) {
            boolean isIncognito = mIsIncognitoThemed;
            GeckoStateEntity geckoStateEntity = new GeckoStateEntity(true);
            geckoStateEntity.setIncognito(isIncognito);
            // Route through setActiveSession so creation and repo-insert
            // stay consistent across the codebase and repo lookup-first
            // semantics are preserved.
            setActiveSession(geckoStateEntity, true);
            popToCorrectHome(isIncognito);
        } else if (id == R.id.downloads_button) {
            mStartForResult.launch(new Intent(mActivity, mIsIncognitoThemed ?
                    VaultActivity.class : DownloadsActivity.class));
        } else if (id == R.id.more_button) {
            Bundle bundle = new Bundle();
            GeckoState geckoState = peekCurrentGeckoState();
            bundle.putBoolean(Keys.ITEM_BOOKMARK, mWebBookmarkViewModel.contains(geckoState));
            bundle.putBoolean(Keys.ITEM_SHORTCUT, mShortCutsViewModel.contains(geckoState));
            bundle.putBoolean(Keys.IS_INCOGNITO, mIsIncognitoThemed);
            NavigationUtils.navigateSafe(mNavController, R.id.dialog_browser_popup, R.id.browser, bundle);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────────────────────
    // AutoCompleteEditText callbacks
    // ─────────────────────────────────────────────────────────────────────────────────────────────

    @Override
    public void onCommit() {
        if (mAutoCompleteEditText == null || mAutoCompleteEditText.getText() == null) return;
        if (mUiState == UiState.SEARCH) {
            findNextResult(mGeckoToolbar.getText(), 0);
        } else if (mUiState == UiState.BROWSING) {
            Editable editable = mAutoCompleteEditText.getText();
            String text = editable.toString();
            if (!TextUtils.isEmpty(text)) {
                GeckoState geckoState = peekCurrentGeckoState();
                if(geckoState == null)
                    return;
                geckoState.setEntityUri(mSearchRepository.parseUri(text));
                openUri(geckoState);
            }
        }
    }

    @Override
    public void onTextChanged(String afterText, String currentText) {
        if (mUiState == UiState.SEARCH) {
            findNextResult(currentText, 0);
        } else if (mUiState == UiState.BROWSING) {
            if (TextUtils.isEmpty(afterText)) {
                mAutoCompleteViewModel.resetEngines();
                mAutoCompleteView.showEmpty();
            }
            mAutoCompleteViewModel.search(afterText);
        }
    }

    @Override
    public void onSearchStateChanged(boolean hasFocus) {
        mGeckoToolbar.updateSearchView(hasFocus);
    }

    @Override
    public void onFocusChanged(boolean hasFocus) {
        if (mUiState == UiState.SEARCH) {
            if (hasFocus) {
                mGeckoToolbar.setProgress(0);
                mGeckoToolbar.setAutoCompleteVisible(false);
                mAutoCompleteView.updateVisibility(false);
            }
        } else if (mUiState == UiState.BROWSING) {
            GeckoState geckoState = peekCurrentGeckoState();
            if (hasFocus) {
                if (geckoState != null) geckoState.setActive(false);
                mGeckoToolbar.setProgress(0);
            } else {
                if (geckoState != null) {
                    if (geckoState.getGeckoStateEntity().isIncognito()) {
                        mIncognitoStateViewModel.setGeckoState(geckoState, true);
                    } else {
                        mGeckoStateViewModel.setGeckoState(geckoState, true);
                    }
                }
            }
            mAutoCompleteViewModel.resetEngines();
            mAutoCompleteView.showEmpty();
            mGeckoToolbar.updateViewVisibility(hasFocus);
            mGeckoToolbar.setAutoCompleteVisible(hasFocus);
            mGeckoToolbar.startAnimation(hasFocus);
            mAutoCompleteView.updateVisibility(hasFocus);
        }
    }

    @Override
    public void onRefreshAutoComplete(String stringToFind) {
        mAutoCompleteViewModel.autoComplete(stringToFind);
    }

    // ─────────────────────────────────────────────────────────────────────────────────────────────
    // GeckoObserver callbacks
    // ─────────────────────────────────────────────────────────────────────────────────────────────

    @Override
    public void onRefresh() {
        GeckoState geckoState = peekCurrentGeckoState();
        if (geckoState == null) {
            mSwipeRefreshLayout.setRefreshing(false);
            return;
        }
        mSwipeRefreshLayout.setRefreshing(true);
        geckoState.reload();
    }

    @Override
    public void updateProgress(int progress) {
        Log.d(TAG, "ToolbarViewModel progress: " + progress);
        if (mGeckoToolbar.isAutoCompleteVisible()) return;
        mGeckoToolbar.setProgress(progress);
        mGeckoToolbar.setLoading(progress > 0 && progress < 100);
        mBrowserDialogViewModel.setLoading(progress > 0 && progress < 100);
        mSwipeRefreshLayout.setProgressRefreshing(progress);
    }

    @Override
    public void onLocationChange(GeckoState geckoState) {
        mGeckoToolbar.onLocationChange(geckoState.getEntityUri());
        // A navigation while find-in-page is active dismisses the search bar.
        if (mUiState == UiState.SEARCH) {
            exitSearch();
        }
    }

    @Override
    public void onFullScreen(boolean fullScreen) {


        // System UI / immersive mode — mirrors upstream enterImmersiveMode / exitImmersiveMode.
        final Window window = mActivity.getWindow();
        final View decorView = window.getDecorView();


        // Layout operations are synchronous and timing-sensitive: expand/collapse must happen
        // before any other UI mutation (e.g. hiding the download button) to avoid racing with
        // the compositor. State bookkeeping (enterFullScreen / exitFullScreen) comes after.
        if (fullScreen) {
            expandBrowserView();
            enterFullScreen(decorView);
        } else {
            collapseBrowserView();
            exitFullScreen(decorView);
        }

        if (BuildUtils.hasAndroidR()) {
            final WindowInsetsControllerCompat controller =
                    WindowCompat.getInsetsController(window, decorView);
            if (fullScreen) {
                controller.hide(WindowInsetsCompat.Type.systemBars());
                controller.setSystemBarsBehavior(
                        WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            } else {
                controller.show(WindowInsetsCompat.Type.systemBars());
            }
        } else {
            //noinspection deprecation
            decorView.setSystemUiVisibility(fullScreen
                    ? View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    : View.SYSTEM_UI_FLAG_VISIBLE);
        }

        // Pull-to-refresh must be disabled in fullscreen.
        mSwipeRefreshLayout.setEnabled(!fullScreen);
    }

    @Override
    public void onHideBars(GeckoState geckoState) {
        final Window window = mActivity.getWindow();
        final View decorView = window.getDecorView();
        expandBrowserView();
        enterFullScreen(decorView);
    }

    @Override
    public void onShowDynamicToolbar() {
        CoordinatorLayout.LayoutParams topParams =
                (CoordinatorLayout.LayoutParams) mGeckoToolbar.getLayoutParams();
        if (topParams.getBehavior() instanceof GeckoToolbarBehavior) {
            ((GeckoToolbarBehavior) topParams.getBehavior()).forceExpand(mGeckoToolbar);
        }
        CoordinatorLayout.LayoutParams bottomParams =
                (CoordinatorLayout.LayoutParams) mBottomNavigationBar.getLayoutParams();
        if (bottomParams.getBehavior() instanceof BottomNavigationBehavior) {
            ((BottomNavigationBehavior) bottomParams.getBehavior()).forceExpand(mBottomNavigationBar);
        }
    }

    @Override
    public void onSecurityChange(GeckoState geckoState,
                                 GeckoSession.ProgressDelegate.SecurityInformation securityInfo) {
        Log.d(TAG, "onSecurityChanged");
        if (mUiState == UiState.BROWSING)
            mGeckoToolbar.setSecure(securityInfo.isSecure);
    }

    @Override
    public void onMetaViewportFitChange(String viewportFit) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return;
        WindowManager.LayoutParams layoutParams = mActivity.getWindow().getAttributes();
        if (viewportFit.equals("cover")) {
            layoutParams.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        } else if (viewportFit.equals("contain")) {
            layoutParams.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_NEVER;
        } else {
            layoutParams.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT;
        }
        mActivity.getWindow().setAttributes(layoutParams);
    }

    @Override
    public void onScrollChange(int scrollY) {
        mSwipeRefreshLayout.setEnabled(
                scrollY < MINIMUM_TRIGGER_Y && !mGeckoView.getInputResultDetail().canScrollToTop());
    }

    @Override
    public void onNew(GeckoState geckoState, String uri) {
        if (geckoState.getGeckoStateEntity().isIncognito()) {
            mIncognitoStateViewModel.setGeckoState(geckoState, true);
        } else {
            mGeckoStateViewModel.setGeckoState(geckoState, true);
        }
        openSession(geckoState);
    }

    @Override
    public void onDownload(WebResponse response) {
        Log.d(TAG, "onDownload: " + response.uri);
        GeckoState geckoState = peekCurrentGeckoState();
        if (geckoState == null)
            return;
        geckoState.setWebResponse(response);
        Bundle bundle = new Bundle();
        bundle.putInt(Keys.ITEM_ID, geckoState.getEntityId());
        bundle.putBoolean(Keys.IS_INCOGNITO, mIsIncognitoThemed);
        NavigationUtils.navigateSafe(mNavController, R.id.dialog_browser_download, R.id.browser, bundle);
    }

    @Override
    public void onThumbnail(GeckoState geckoState) {
        try {
            if (mStop)
                return;

            boolean isIncognito = geckoState.getGeckoStateEntity().isIncognito();

            if (isIncognito && !mIncognitoStateViewModel.isCurrentGeckoState(geckoState))
                return;
            if (!isIncognito && !mGeckoStateViewModel.isCurrentGeckoState(geckoState))
                return;

            mGeckoView.capturePixels().then(value -> {
                try {
                    Log.d(TAG, "onThumbnail bitmap: " + value);
                    if (value != null) {
                        if (isIncognito) {
                            Bitmap scaled = GeckoState.scaleThumbnail(value);
                            geckoState.setCachedThumb(scaled);
                            mIncognitoStateViewModel.notifyTabs();
                        } else {
                            mGeckoStateViewModel.updateThumb(geckoState, value);
                        }
                    }
                } catch (Throwable e) {
                    Log.d(TAG, "onThumbnail", e);
                }
                return null;
            });
        } catch (Exception e) {
            Log.e(TAG, "Thumbnail", e);
        }
    }

    @Override
    public void onLoadRequest(GeckoState geckoState, String uri) {
        Log.d(TAG, "onLoadRequest: " + uri);
        Intent browsableIntent = AppLinkUseCases.createBrowsableIntent(uri);
        if (browsableIntent == null
                || UrlStringUtils.isHttpOrHttps(uri)
                || UrlStringUtils.isMozExtensionLike(uri))
            return;
        try {
            Bundle bundle = new Bundle();
            bundle.putParcelable(Keys.ITEM_ID, browsableIntent);
            bundle.putBoolean(Keys.OPEN_INCOGNITO, mIsIncognitoThemed);
            NavigationUtils.navigateSafe(mNavController, R.id.dialog_browser_open_in_app, R.id.browser, bundle);
        } catch (ActivityNotFoundException e) {
            Log.e(TAG, "No Activity found: " + browsableIntent, e);
        }
    }

    @Override
    public void onContext(GeckoState geckoState, GeckoSession.ContentDelegate.ContextElement element) {
        Log.d(TAG, "onContext Element type: " + element.type
                + " sessionId: " + geckoState.getEntityId()
                + " Element url: " + element.baseUri);
        Bundle bundle = new Bundle();
        bundle.putParcelable(Keys.ITEM_ID, new ContextElementEntity(element));
        bundle.putBoolean(Keys.OPEN_INCOGNITO, mIsIncognitoThemed);
        NavigationUtils.navigateSafe(mNavController, R.id.dialog_browser_content, R.id.browser, bundle);
    }

    @Override
    public void onCrash(GeckoState geckoState) {
        stopMedia(mGeckoMediaController, geckoState);
        openSession(geckoState);
    }

    @Override
    public void onOrientation(Integer screenOrientation) {
        mActivity.setRequestedOrientation(screenOrientation);
    }

    @Override
    public void onPromptLoginSave(GeckoState geckoState,
                                  GeckoSession.PromptDelegate.AutocompleteRequest<?> request,
                                  boolean contains) {
        geckoState.setPendingAutoCompleteRequest(request);
        Bundle bundle = new Bundle();
        bundle.putInt(Keys.ITEM_ID, geckoState.getEntityId());
        bundle.putBoolean(Keys.ITEM_CONTAINS, contains);
        NavigationUtils.navigateSafe(mNavController, R.id.dialog_save_login, R.id.browser, bundle);
    }

    @Override
    public void onPromptFile(GeckoState geckoState,
                             GeckoSession.PromptDelegate.FilePrompt filePrompt, Intent intent) {
        geckoState.setPendingFilePrompt(filePrompt);
        mPromptForResult.launch(intent);
    }

    @Override
    public void onPromptUnload(GeckoState geckoState,
                               GeckoSession.PromptDelegate.BeforeUnloadPrompt prompt) {
        mGeckoPromptManager.onPromptUnload(mActivity, geckoState, mNavController, prompt);
    }

    @Override
    public void onPromptRepost(GeckoState geckoState,
                               GeckoSession.PromptDelegate.RepostConfirmPrompt prompt) {
        mGeckoPromptManager.onRepostPrompt(mActivity, geckoState, mNavController, prompt);
    }

    @Override
    public void onPromptButton(GeckoState geckoState,
                               GeckoSession.PromptDelegate.ButtonPrompt prompt) {
        mGeckoPromptManager.onButtonPrompt(mActivity, geckoState, mNavController, prompt);
    }

    @Override
    public void onPromptText(GeckoState geckoState,
                             GeckoSession.PromptDelegate.TextPrompt prompt) {
        mGeckoPromptManager.onTextPrompt(mActivity, geckoState, mNavController, prompt);
    }

    @Override
    public void onPromptAlert(GeckoState geckoState,
                              GeckoSession.PromptDelegate.AlertPrompt prompt) {
        mGeckoPromptManager.onAlertPrompt(mActivity, geckoState, mNavController, prompt);
    }

    @Override
    public void onPromptChoice(GeckoState geckoState,
                               GeckoSession.PromptDelegate.ChoicePrompt prompt) {
        mGeckoPromptManager.onChoicePrompt(mActivity, geckoState, mNavController, prompt);
    }

    @Override
    public void onPromptAuth(GeckoState geckoState,
                             GeckoSession.PromptDelegate.AuthPrompt prompt) {
        mGeckoPromptManager.onAuthPrompt(mActivity, geckoState, mNavController, prompt);
    }

    @Override
    public void onPromptDate(GeckoState geckoState,
                             GeckoSession.PromptDelegate.DateTimePrompt prompt) {
        mGeckoPromptManager.onDatePrompt(mActivity, geckoState, mNavController, prompt);
    }

    @Override
    public void onPromptColor(GeckoState geckoState,
                              GeckoSession.PromptDelegate.ColorPrompt prompt) {
        mGeckoPromptManager.onColorPrompt(mActivity, geckoState, mNavController, prompt);
    }

    @Override
    public void onContentPermission(GeckoState geckoState,
                                    GeckoSession.PermissionDelegate.ContentPermission permission,
                                    int messageId) {
        String message = String.format(getString(messageId),
                Uri.parse(permission.uri).getAuthority());
        mGeckoPromptManager.onContentPermission(mActivity, geckoState, mNavController, message, permission);
    }

    // ─────────────────────────────────────────────────────────────────────────────────────────────
    // Media callbacks
    // ─────────────────────────────────────────────────────────────────────────────────────────────

    @Override
    public void onMediaActivated(GeckoState geckoState, MediaSession mediaSession) {
        super.onMediaActivated(geckoState, mediaSession);
        Log.d(TAG, "onMediaActivated");
        mActivity.startService(new Intent(mActivity, GeckoMediaPlaybackService.class));
    }

    @Override
    public void onMediaPlay(GeckoState geckoState, MediaSession mediaSession) {
        super.onMediaPlay(geckoState, mediaSession);
        MediaSession.PositionState pos = mGeckoMediaController.getPositionState();
        Log.d(TAG, "onMediaPlay: id=" + geckoState.getEntityId()
                + " pos=" + (pos != null ? pos.position : "null")
                + " dur=" + (pos != null ? pos.duration : "null"));
        Intent intent = new Intent(mActivity, GeckoMediaPlaybackService.class);
        intent.setAction(IntentActions.MEDIA_PLAY);
        if (pos != null) {
            intent.putExtra(Keys.MEDIA_POSITION, pos.position);
            intent.putExtra(Keys.MEDIA_DURATION, pos.duration);
            intent.putExtra(Keys.MEDIA_RATE, (float) pos.playbackRate);
        }
        mActivity.startService(intent);
    }

    @Override
    public void onMediaPause(GeckoState geckoState, MediaSession mediaSession) {
        super.onMediaPause(geckoState, mediaSession);
        MediaSession.PositionState pos = mGeckoMediaController.getPositionState();
        Log.d(TAG, "onMediaPause: id=" + geckoState.getEntityId()
                + " pos=" + (pos != null ? pos.position : "null")
                + " dur=" + (pos != null ? pos.duration : "null"));
        Intent intent = new Intent(mActivity, GeckoMediaPlaybackService.class);
        intent.setAction(IntentActions.MEDIA_PAUSE);
        if (pos != null) {
            intent.putExtra(Keys.MEDIA_POSITION, pos.position);
            intent.putExtra(Keys.MEDIA_DURATION, pos.duration);
            intent.putExtra(Keys.MEDIA_RATE, (float) pos.playbackRate);
        }
        mActivity.startService(intent);
    }

    @Override
    public void onMediaPosition(GeckoState geckoState, MediaSession mediaSession,
                                MediaSession.PositionState positionState) {
        super.onMediaPosition(geckoState, mediaSession, positionState);
        Log.d(TAG, "onMediaPosition: id=" + geckoState.getEntityId()
                + " pos=" + positionState.position
                + " dur=" + positionState.duration
                + " rate=" + positionState.playbackRate
                + " serviceRunning=" + GeckoMediaPlaybackService.isRunning());

        if (!GeckoMediaPlaybackService.isRunning())
            return;

        Intent intent = new Intent(mActivity, GeckoMediaPlaybackService.class);
        intent.setAction(IntentActions.MEDIA_POSITION);
        intent.putExtra(Keys.MEDIA_POSITION, positionState.position);
        intent.putExtra(Keys.MEDIA_DURATION, positionState.duration);
        intent.putExtra(Keys.MEDIA_RATE, (float) positionState.playbackRate);
        mActivity.startService(intent);
    }

    @Override
    public void onMediaMetadata(GeckoState geckoState, MediaSession mediaSession,
                                MediaSession.Metadata metadata) {
        super.onMediaMetadata(geckoState, mediaSession, metadata);
        Log.d(TAG, "onMediaMetadata");
        Image image = metadata.artwork;
        if (image != null) {
            try {
                image.getBitmap(GeckoMetaData.ARTWORK_SIZE).then(value -> {
                    mGeckoMediaController.setBitmap(value, geckoState);
                    Intent intent = new Intent(mActivity, GeckoMediaPlaybackService.class);
                    intent.setAction(IntentActions.MEDIA_METADATA);
                    mActivity.startService(intent);
                    return null;
                });
            } catch (Image.ImageProcessingException e) {
                Log.e(TAG, "onMediaMetadata", e);
            }
        }
    }

    @Override
    public void onMediaDeactivated(GeckoState geckoState, MediaSession mediaSession) {
        super.onMediaDeactivated(geckoState, mediaSession);
        Log.d(TAG, "onMediaDeactivated");
        mGeckoMediaController.onDeactivated(geckoState);
    }

    @Override
    public void onClose(GeckoState geckoState) {
        super.onClose(geckoState);
        stopMedia(mGeckoMediaController, geckoState);
    }

    @Override
    public void onKill(GeckoState geckoState) {
        super.onKill(geckoState);
        stopMedia(mGeckoMediaController, geckoState);
    }

    // ─────────────────────────────────────────────────────────────────────────────────────────────
    // RecyclerView item callbacks
    // ─────────────────────────────────────────────────────────────────────────────────────────────

    @Override
    public void onItemClick(int position, int resId) {
        if (position == RecyclerView.NO_POSITION) return;
        if (resId == R.id.item_search) {
            AutoCompleteEntity searchEntity = mSearchAutocompleteAdapter.getCurrentList().get(position);
            int type = searchEntity.getType();
            if (type == AutoCompleteEntity.TAB) {
                int sessionId = searchEntity.getSessionId();
                // Check both repos — the tab could be regular or incognito.
                // Prefer the current-theme's repo first for consistency,
                // then fall back to the other repo.
                GeckoState geckoState;
                if (mIsIncognitoThemed) {
                    geckoState = mIncognitoStateViewModel.getGeckoState(sessionId);
                    if (geckoState == null) {
                        geckoState = mGeckoStateViewModel.getGeckoState(sessionId);
                    }
                } else {
                    geckoState = mGeckoStateViewModel.getGeckoState(sessionId);
                    if (geckoState == null) {
                        geckoState = mIncognitoStateViewModel.getGeckoState(sessionId);
                    }
                }
                if (geckoState != null) {
                    switchSession(geckoState);
                }
            } else {
                GeckoState geckoState = peekCurrentGeckoState();
                if (geckoState == null)
                    return;
                geckoState.setEntityUri(mSearchRepository.parseUri(searchEntity.getSubText()));
                openUri(geckoState);
            }
        }
    }

    @Override
    public void onLongClick(int position, int resId) { }

    @Override
    public void onItemVariantClick(int position, int variant, int resId) { }

    // ─────────────────────────────────────────────────────────────────────────────────────────────
    // Session / navigation helpers
    // ─────────────────────────────────────────────────────────────────────────────────────────────

    private GeckoState setActiveSession(GeckoStateEntity geckoStateEntity, boolean active) {
        boolean isIncognito = geckoStateEntity.isIncognito();

        GeckoState geckoState = isIncognito
                ? mIncognitoStateViewModel.getGeckoState(geckoStateEntity.getId())
                : mGeckoStateViewModel.getGeckoState(geckoStateEntity.getId());

        boolean isNew = (geckoState == null);
        if (isNew) geckoState = new GeckoState(geckoStateEntity);

        Log.d(TAG, "setActiveSession: entityId=" + geckoStateEntity.getId()
                + " uri=" + geckoStateEntity.getUri()
                + " isHome=" + geckoStateEntity.isHome()
                + " isIncognito=" + isIncognito
                + " foundInRepo=" + !isNew
                + " active=" + active);

        if (isIncognito) {
            mIncognitoStateViewModel.setGeckoState(geckoState, active);
        } else {
            mGeckoStateViewModel.setGeckoState(geckoState, active);
        }

        return geckoState;
    }

    private void connectSession(GeckoSession session) {
        Log.d(TAG, "ConnectSession");
        session.setContentDelegate(mGeckoComponents.getContentDelegate());
        session.setProgressDelegate(mGeckoComponents.getProgressDelegate());
        session.setNavigationDelegate(mGeckoComponents.getNavigationDelegate());
        session.setHistoryDelegate(mGeckoComponents.getHistoryDelegate());
        session.setMediaSessionDelegate(mGeckoComponents.getMediaSessionDelegate());
        session.setScrollDelegate(mGeckoComponents.getScrollDelegate());
        session.setPromptDelegate(mGeckoComponents.getPromptDelegate());
        session.setContentBlockingDelegate(mGeckoComponents.getContentBlockingDelegate());
        session.setPermissionDelegate(mGeckoComponents.getPermissionDelegate());
        mGeckoRuntimeHelper.registerSession(session);
    }

    private void setGeckoViewSession(GeckoState geckoState) {
        Log.d(TAG, "setGeckoViewSession: id=" + geckoState.getEntityId()
                + " uri=" + geckoState.getEntityUri());

        final WebExtensionController controller =
                mGeckoRuntimeHelper.getGeckoRuntime().getWebExtensionController();
        final GeckoSession previousSession = mGeckoView.getSession();
        final GeckoSession newSession = geckoState.getOrCreateGeckoSession();

        if (newSession == null) {
            Log.e(TAG, "setGeckoViewSession: session is null, falling back to home");
            popToCorrectHome(geckoState.getGeckoStateEntity().isIncognito());
            return;
        }

        Log.d(TAG, "setGeckoViewSession: previousSession=" + previousSession
                + " newSession=" + newSession
                + " isOpen=" + newSession.isOpen());

        // Deactivate the previous session if we're switching
        if (previousSession != null && previousSession != newSession) {
            Log.d(TAG, "setGeckoViewSession: deactivating previousSession");
            controller.setTabActive(previousSession, false);
            previousSession.setActive(false);
        }

        mAutoCompleteEditText.setEnabled(true);

        if (!newSession.isOpen()) {
            Log.d(TAG, "setGeckoViewSession: session not open, opening + loading URI");
            newSession.open(mGeckoRuntimeHelper.getGeckoRuntime());
            newSession.setActive(true);
            String uri = mSearchRepository.parseUri(geckoState.getEntityUri());
            geckoState.setEntityUri(uri);
            openUri(geckoState);
        }

        final CertificateInfoEntity certificateInfoEntity = geckoState.getCertificateState();
        mGeckoToolbar.setSecure(certificateInfoEntity != null && certificateInfoEntity.isSecure);

        if (mGeckoView.getSession() != newSession) {
            Log.d(TAG, "setGeckoViewSession: calling mGeckoView.setSession()");
            mGeckoView.setSession(newSession);
        } else {
            Log.d(TAG, "setGeckoViewSession: GeckoView already has this session");
        }

        controller.setTabActive(newSession, true);

        if (geckoState.getGeckoStateEntity().isIncognito()) {
            mIncognitoStateViewModel.setGeckoState(geckoState, true);
        } else {
            mGeckoStateViewModel.setGeckoState(geckoState, true);
        }
    }


    private void applyBrowserIncognitoTheme(boolean incognito) {
        if (incognito == mIsIncognitoThemed)
            return;
        mIsIncognitoThemed = incognito;
        if (mActivity == null || mGeckoToolbar == null || mBottomNavigationBar == null)
            return;

        mGeckoToolbar.updateTheme(mActivity, incognito);
        mBottomNavigationBar.updateTheme(mActivity, incognito);
        mAutoCompleteView.updateTheme(mActivity, incognito);
        mAutoCompleteViewModel.setIncognito(incognito);
        mSearchAutocompleteAdapter.setIncognito(incognito);

        int nightMode = getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK;
        boolean lightBars = !incognito && nightMode != Configuration.UI_MODE_NIGHT_YES;

        Window window = mActivity.getWindow();
        window.getDecorView().setBackgroundColor(
                IncognitoColors.getSurface(mActivity, incognito));

        // Block recents thumbnail / screen capture for incognito browsing.
        // When leaving incognito we only clear the flag if AppLock isn't also
        // holding it; ApplicationLifeCycleHandler#updateWindowSecureMode
        // delegates the BrowserActivity FLAG_SECURE to this fragment so the
        // two don't fight.
        if (incognito) {
            window.addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        } else if (!mAppLock.isEnabled()) {
            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE);
        }

        WindowInsetsControllerCompat insetsController =
                WindowCompat.getInsetsController(window, window.getDecorView());
        insetsController.setAppearanceLightStatusBars(lightBars);
        insetsController.setAppearanceLightNavigationBars(lightBars);

        Integer count = incognito
                ? mIncognitoStateViewModel.getTabsCount().getValue()
                : mGeckoStateViewModel.getTabsCount().getValue();
        if (count != null) mBottomNavigationBar.onTabsCount(count);
    }

    private void recreateSession(GeckoState geckoState) {
        boolean isIncognito = geckoState.getGeckoStateEntity().isIncognito();
        final GeckoRuntime geckoRuntime = mGeckoRuntimeHelper.getGeckoRuntime();
        final WebExtensionController controller = geckoRuntime.getWebExtensionController();
        mGeckoMediaController.stopMediaForSession(geckoState.getEntityId());

        // Close the old GeckoSession before discarding the GeckoState
        GeckoSession oldSession = geckoState.getGeckoSession();
        if (oldSession != null) {
            controller.setTabActive(oldSession, false);
            oldSession.setActive(false);
            oldSession.close();
        }

        // Suppress the "all incognito tabs closed" observer while we
        // close-then-recreate this state. Without this guard, the
        // observer sees count briefly drop to 0 and navigates to
        // regular home, stranding the user off the incognito tab they
        // were just trying to reload with a new UA.
        mRecreatingSession = true;
        try {
            if (isIncognito) {
                mIncognitoStateViewModel.closeGeckoState(geckoState);
            } else {
                mGeckoStateViewModel.closeGeckoState(geckoState);
            }

            // setActiveSession routes to the correct repo based on entity.isIncognito().
            GeckoState newGeckoState = setActiveSession(geckoState.getGeckoStateEntity(), true);
            // Ensure the new GeckoSession is open before handing off to
            // openSession (which expects the session attachment machinery
            // to work against an open session).
            GeckoSession geckoSession = newGeckoState.getOrCreateGeckoSession();
            if (!geckoSession.isOpen()) {
                geckoSession.open(geckoRuntime);
            }

            // Reuse the same attachment path as every other tab-switch —
            // this calls setGeckoViewSession (correct-repo setGeckoState),
            // applyBrowserIncognitoTheme, and toolbar/tracking sync, all of
            // which were previously bypassed by recreateSession.
            openSession(newGeckoState);
            openUri(newGeckoState);
        } finally {
            mRecreatingSession = false;
        }
    }


    public void switchSession(GeckoState geckoState) {
        Log.d(TAG, "switchSession");
        openSession(geckoState);
        mAutoCompleteEditText.clearFocus();
        hideKeyboard(mAutoCompleteEditText);
    }

    public void openSession(GeckoState geckoState) {
        Log.d(TAG, "openSession: id=" + geckoState.getEntityId()
                + " uri=" + geckoState.getEntityUri()
                + " isHome=" + geckoState.isHome()
                + " hasGeckoSession=" + (geckoState.getGeckoSession() != null)
                + " isOpen=" + (geckoState.getGeckoSession() != null && geckoState.getGeckoSession().isOpen()));
        if (geckoState.getGeckoStateEntity().isIncognito()) {
            mIncognitoStateViewModel.isTrackingProtected(geckoState.getEntityUri());
        } else {
            mGeckoStateViewModel.isTrackingProtected(geckoState.getEntityUri());
        }
        mGeckoToolbar.onLocationChange(geckoState.getEntityUri());
        connectSession(geckoState.getOrCreateGeckoSession());
        setGeckoViewSession(geckoState);
        updateProgress(0);
        // Apply incognito theme BEFORE enterBrowsing so peekCurrentGeckoState works
        applyBrowserIncognitoTheme(geckoState.getGeckoStateEntity().isIncognito());
        enterBrowsing(geckoState);
        Log.d(TAG, "openSession end: id=" + geckoState.getEntityId());
    }

    private void openUri(GeckoState geckoState) {
        Log.d(TAG, "openUri: " + geckoState.getEntityUri());
        String currentUri = geckoState.getEntityUri();
        if (GeckoResources.isAboutOnboarding(currentUri)) {
            currentUri = GeckoResources.createFiredownTab(mActivity);
            geckoState.setEntityUri(currentUri);
        }
        enterBrowsing(geckoState);
        if (geckoState.getGeckoStateEntity().isIncognito()) {
            mIncognitoStateViewModel.isTrackingProtected(currentUri);
        } else {
            mGeckoStateViewModel.isTrackingProtected(currentUri);
        }
        geckoState.getOrCreateGeckoSession().loadUri(currentUri);
        mAutoCompleteEditText.clearFocus();
        mGeckoToolbar.setUri(currentUri, false);
        hideKeyboard(mAutoCompleteEditText);
        resetIcon(geckoState);
    }


    private void enterBrowsing(@Nullable GeckoState explicitState) {
        if (mUiState == UiState.BROWSING) return;

        GeckoState geckoState = explicitState != null ? explicitState : peekCurrentGeckoState();
        if (geckoState == null) return;

        mUiState = UiState.BROWSING;
        geckoState.setSearchMode(false);
        mGeckoObserverRegistry.register(this);
        mDownloadButton.setVisibility(View.VISIBLE);
        mGeckoView.setVisibility(View.VISIBLE);
        mGeckoToolbar.enableScrolling();
        mGeckoToolbar.onLocationChange(geckoState.getEntityUri());
        mBottomNavigationBar.show();
    }


    // ─────────────────────────────────────────────────────────────────────────────────────────────
    // Fullscreen layout helpers
    // ─────────────────────────────────────────────────────────────────────────────────────────────

    private void expandBrowserView() {
        CoordinatorLayout.LayoutParams topParams =
                (CoordinatorLayout.LayoutParams) mGeckoToolbar.getLayoutParams();
        if (topParams.getBehavior() instanceof GeckoToolbarBehavior) {
            ((GeckoToolbarBehavior) topParams.getBehavior()).forceCollapse(mGeckoToolbar);
        }
        mGeckoToolbar.setVisibility(View.GONE);

        CoordinatorLayout.LayoutParams bottomParams =
                (CoordinatorLayout.LayoutParams) mBottomNavigationBar.getLayoutParams();
        if (bottomParams.getBehavior() instanceof BottomNavigationBehavior) {
            ((BottomNavigationBehavior) bottomParams.getBehavior()).forceCollapse(mBottomNavigationBar);
        }
        mBottomNavigationBar.setVisibility(View.GONE);

        CoordinatorLayout.LayoutParams srlParams =
                (CoordinatorLayout.LayoutParams) mSwipeRefreshLayout.getLayoutParams();
        srlParams.setBehavior(null);
        srlParams.topMargin    = 0;
        srlParams.bottomMargin = 0;

        mSwipeRefreshLayout.setTranslationY(0f);
        mSwipeRefreshLayout.requestLayout();

        mGeckoView.setDynamicToolbarMaxHeight(0);
        mGeckoView.setVerticalClipping(0);
    }

    private void collapseBrowserView() {
        mGeckoToolbar.setVisibility(View.VISIBLE);
        CoordinatorLayout.LayoutParams topParams =
                (CoordinatorLayout.LayoutParams) mGeckoToolbar.getLayoutParams();
        if (topParams.getBehavior() instanceof GeckoToolbarBehavior) {
            ((GeckoToolbarBehavior) topParams.getBehavior()).forceExpand(mGeckoToolbar);
        }

        mBottomNavigationBar.setVisibility(View.VISIBLE);
        CoordinatorLayout.LayoutParams bottomParams =
                (CoordinatorLayout.LayoutParams) mBottomNavigationBar.getLayoutParams();
        if (bottomParams.getBehavior() instanceof BottomNavigationBehavior) {
            ((BottomNavigationBehavior) bottomParams.getBehavior()).forceExpand(mBottomNavigationBar);
        }

        CoordinatorLayout.LayoutParams srlParams =
                (CoordinatorLayout.LayoutParams) mSwipeRefreshLayout.getLayoutParams();
        srlParams.setBehavior(new NestedGeckoViewBehavior(
                mSwipeRefreshLayout.getContext(), null,
                mSwipeRefreshLayout, mGeckoToolbarSize, mBottomBarSize));
        mSwipeRefreshLayout.requestLayout();

        mGeckoView.setDynamicToolbarMaxHeight(mGeckoToolbarSize + mBottomBarSize);
    }

    // ─────────────────────────────────────────────────────────────────────────────────────────────
    // Misc helpers
    // ─────────────────────────────────────────────────────────────────────────────────────────────

    /**
     * Returns the current GeckoState from the correct repo (incognito or regular)
     * without creating a new tab. Returns null if no active tab exists.
     */
    private GeckoState peekCurrentGeckoState() {
        return mIsIncognitoThemed
                ? mIncognitoStateViewModel.peekCurrentGeckoState()
                : mGeckoStateViewModel.peekCurrentGeckoState();
    }

    /**
     * Pops BrowserFragment off the back stack and ensures the user lands
     * on the correct home destination (regular or incognito).
     *
     * <p>After popping, the back stack reveals whatever home destination
     * was underneath. This might not match the tab's mode — e.g. a regular
     * tab opened from {@code home_incognito}. We check and swap if needed.</p>
     */
    private void popToCorrectHome(boolean isIncognito) {
        NavigationUtils.popBackStackSafe(mNavController, R.id.browser);

        NavDestination dest = mNavController.getCurrentDestination();
        if (dest == null) return;

        int currentId = dest.getId();
        int targetHome = isIncognito ? R.id.home_incognito : R.id.home;

        if (currentId == targetHome) return;

        // Swap home destinations
        if (currentId == R.id.home && isIncognito) {
            NavigationUtils.navigateSafe(mNavController, R.id.action_home_to_home_incognito);
        } else if (currentId == R.id.home_incognito && !isIncognito) {
            NavigationUtils.navigateSafe(mNavController, R.id.action_home_incognito_to_home);
        } else {
            // Fallback — shouldn't happen but safe
            NavigationUtils.navigateSafe(mNavController, targetHome);
        }
    }

    private void findNextResult(String currentText, int flags) {
        GeckoState geckoState = peekCurrentGeckoState();
        if (geckoState == null)
            return;
        geckoState.getOrCreateGeckoSession().getFinder().find(currentText, flags).then(result -> {
            if (mStop || mGeckoToolbar == null) {
                Log.w(TAG, "onValue Stopped Search");
                return null;
            }
            mGeckoToolbar.post(() -> {
                if (result != null && result.total > 0) {
                    mGeckoToolbar.setSearchText(String.format(Locale.getDefault(),
                            "%d/%d", result.current, result.total));
                } else {
                    mGeckoToolbar.setSearchErrorText("0/0");
                }
            });
            return null;
        });
    }

    private void resetIcon(GeckoState geckoState) {
        geckoState.setEntityIcon(null);
    }

    /**
     * Captures the current tab's thumbnail and navigates to TabsFragment.
     *
     * <h3>P1 Migration</h3>
     * <p>Previously this launched {@code TabsActivity} via {@code mStartForResult}.
     * Now TabsFragment is a destination in BrowserActivity's nav graph, so we
     * navigate directly.  No Binder, no activity result, no parceling.</p>
     *
     * <p>The thumbnail capture via {@code GeckoView.capturePixels()} is preserved
     * so the tab grid shows an up-to-date screenshot.</p>
     */
    private void navigateToTabs() {
        boolean isIncognito = mIsIncognitoThemed;

        GeckoState currentState = peekCurrentGeckoState();

        boolean canCapture = currentState != null
                && currentState.getGeckoSession() != null
                && !TextUtils.isEmpty(currentState.getEntityUri())
                && !currentState.isHome();

        Bundle args = new Bundle();
        args.putBoolean(Keys.OPEN_INCOGNITO, isIncognito);

        if (!canCapture) {
            NavigationUtils.navigateSafe(mNavController, R.id.tabs, R.id.browser, args);
            return;
        }

        // Capture thumbnail from the correct ViewModel
        mGeckoView.capturePixels().then(bitmap -> {
            if (bitmap != null) {
                if (!isIncognito) {
                    mGeckoStateViewModel.updateThumb(currentState, bitmap);
                }else{
                    Bitmap scaled = GeckoState.scaleThumbnail(bitmap);
                    currentState.setCachedThumb(scaled);
                    mIncognitoStateViewModel.notifyTabs();
                }
            }
            NavigationUtils.navigateSafe(mNavController, R.id.tabs, R.id.browser, args);
            return GeckoResult.fromValue(null);
        }, error -> {
            NavigationUtils.navigateSafe(mNavController, R.id.tabs, R.id.browser, args);
            return GeckoResult.fromValue(null);
        });
    }

    // ─────────────────────────────────────────────────────────────────────────────────────────────
    // Incognito-aware state helpers
    // ─────────────────────────────────────────────────────────────────────────────────────────────

}