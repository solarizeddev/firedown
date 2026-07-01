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
import android.view.accessibility.AccessibilityManager;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.coordinatorlayout.widget.CoordinatorLayout;
import androidx.core.app.ShareCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleEventObserver;
import androidx.lifecycle.ViewModelProvider;
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
import com.solarized.firedown.data.models.TaskViewModel;
import com.solarized.firedown.data.models.WebBookmarkViewModel;
import com.solarized.firedown.geckoview.GeckoComponents;
import com.solarized.firedown.geckoview.GeckoState;
import com.solarized.firedown.geckoview.GeckoSwipeRefreshLayout;
import com.solarized.firedown.geckoview.GeckoToolbarBehavior;
import com.solarized.firedown.geckoview.NestedGeckoView;
import com.solarized.firedown.geckoview.NestedGeckoViewBehavior;
import com.solarized.firedown.geckoview.media.GeckoMediaPlaybackService;
import com.solarized.firedown.geckoview.media.GeckoMetaData;
import com.solarized.firedown.geckoview.toolbar.BottomNavigationBar;
import com.solarized.firedown.data.entity.BrowserDownloadEntity;
import com.solarized.firedown.manager.DownloadRequest;
import com.solarized.firedown.manager.RunnableManager;
import com.solarized.firedown.utils.BrowserHeaders;
import com.solarized.firedown.phone.DownloadsActivity;
import com.solarized.firedown.phone.dialogs.BrowserAppDialogFragment;
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
import com.solarized.firedown.data.repository.WebBookmarkDataRepository;
import com.solarized.firedown.utils.BuildUtils;
import com.solarized.firedown.utils.AppLinkUseCases;
import com.solarized.firedown.utils.FileUriHelper;
import com.solarized.firedown.Keys;
import com.solarized.firedown.utils.NavigationUtils;
import com.solarized.firedown.utils.UrlStringUtils;
import com.solarized.firedown.utils.WebUtils;

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


public class BrowserFragment extends BaseBrowserFragment
        implements OnItemClickListener {

    private static final String TAG = BrowserFragment.class.getSimpleName();

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

    // ── Toolbar scroll policy (Fenix ToolbarBehaviorController equivalent) ───────────────────────
    //
    // Fenix forces the dynamic toolbar visible and disables scroll-to-hide in a handful of
    // states; we mirror them through one decision point, applyToolbarScrollPolicy(), fed by
    // these flags (plus mUiState). See that method for the policy itself.

    /** True between onStart (page load begins) and onStop (load finished/halted). */
    private boolean mPageLoading = false;

    /** True while the soft keyboard is visible (tracked via the root window-insets listener). */
    private boolean mImeVisible = false;

    /**
     * True while TalkBack-style touch exploration is active. Scroll-to-hide chrome is hostile
     * to screen-reader users (Fenix pins the toolbar via shouldUseFixedTopToolbar), so the
     * bars are pinned visible for the duration.
     */
    private boolean mTouchExplorationEnabled = false;

    private AccessibilityManager mAccessibilityManager;

    private final AccessibilityManager.TouchExplorationStateChangeListener
            mTouchExplorationListener = enabled -> {
        mTouchExplorationEnabled = enabled;
        expandBarsAndApplyPolicy();
    };

    // ── Views ─────────────────────────────────────────────────────────────────────────────────────

    private NestedGeckoView mGeckoView;
    private GeckoToolbar mGeckoToolbar;
    private BottomNavigationBar mBottomNavigationBar;
    private GeckoSwipeRefreshLayout mSwipeRefreshLayout;
    private AutoCompleteEditText mAutoCompleteEditText;

    // ── Save snapshot ───────────────────────────────────────────────────────────────────────────────
    // The in-progress "Saving snapshot…" snackbar (flipped to "Snapshot saved ·
    // View" on completion), and a flag marking THIS fragment as the snapshot's
    // initiator so the (fan-out) download callback only acts once.
    private Snackbar mSnapshotSnackbar;
    private boolean mSnapshotPending;
    private static final long SNAPSHOT_SAVE_TIMEOUT_MS = 90_000L;
    private static final long SNAPSHOT_SAVED_LINGER_MS = 5_000L;

    // ── ViewModels ────────────────────────────────────────────────────────────────────────────────

    private IncognitoStateViewModel mIncognitoStateViewModel;
    private BrowserDownloadViewModel mBrowserDownloadViewModel;
    private BrowserDialogViewModel mBrowserDialogViewModel;
    private WebBookmarkViewModel mWebBookmarkViewModel;
    private BrowserURIViewModel mBrowserURIViewModel;
    private TaskViewModel mTaskViewModel;

    // ── Layout sizing ─────────────────────────────────────────────────────────────────────────────

    /** The bars' content row height (app_bar_size), without any inset. */
    private int mChromeBarBaseSize;
    /**
     * Dynamic-toolbar heights handed to Gecko — CONTENT-ONLY (= base, no
     * system inset). The dynamic toolbar reserves space that becomes page
     * content when a bar hides; system bars aren't reclaimable, so their
     * insets are kept OUT of these and reserved via the FRAMED root's
     * all-sides safe-area padding instead (the root insets listener). In
     * the framed model the bars are also a true base tall, so these
     * heights now match the real bar heights exactly. Constant after init;
     * the fields remain (rather than inlining base) so the fullscreen
     * exit's behavior rebuild reads one source.
     */
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

        // Restore incognito-mode flag across fragment recreation
        // (config change, process death). Without this, the recreated
        // BrowserFragment defaults mIsIncognitoThemed to false, and
        // peekCurrentGeckoState() then resolves to the regular
        // ViewModel — onResume's ensureSessionConnected opens a
        // regular tab and the user lands on regular browser even
        // though they were on an incognito tab before the recreation.
        if (savedInstanceState != null) {
            mIsIncognitoThemed = savedInstanceState.getBoolean(Keys.IS_INCOGNITO, false);
        }

        mChromeBarBaseSize = getResources().getDimensionPixelSize(R.dimen.app_bar_size);
        mGeckoToolbarSize  = mChromeBarBaseSize;
        mBottomBarSize     = mChromeBarBaseSize;

        mIncognitoStateViewModel = new ViewModelProvider(mActivity).get(IncognitoStateViewModel.class);
        mTaskViewModel          = new ViewModelProvider(this).get(TaskViewModel.class);
        mWebBookmarkViewModel   = new ViewModelProvider(this).get(WebBookmarkViewModel.class);
        mGeckoStateViewModel    = new ViewModelProvider(mActivity).get(GeckoStateViewModel.class);
        mBrowserDialogViewModel = new ViewModelProvider(mActivity).get(BrowserDialogViewModel.class);
        mBrowserURIViewModel = new ViewModelProvider(mActivity).get(BrowserURIViewModel.class);
        mBrowserDownloadViewModel = new ViewModelProvider(mActivity).get(BrowserDownloadViewModel.class);

        OnBackPressedCallback callback = new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (dismissAutocompleteOverlayIfVisible()) return;

                GeckoState geckoState = peekCurrentGeckoState();
                if (geckoState == null) {
                    setEnabled(false);
                    mActivity.getOnBackPressedDispatcher().onBackPressed();
                    return;
                }
                final boolean incognito = geckoState.getGeckoStateEntity().isIncognito();
                Log.d(TAG, "handleBackPressed uri: " + geckoState.getEntityUri()
                        + " canBack: " + geckoState.canGoBackward());

                if (geckoState.isFullScreen()) {
                    geckoState.exitFullScreen();
                    return;
                }
                // onHideDynamicToolbar enters the fullscreen UI WITHOUT DOM
                // fullscreen (entityFullScreen stays false), so the branch
                // above has nothing to exit and back used to fall through to
                // history navigation, breaking the snackbar's "exit fullscreen
                // with back" promise. Restore the chrome directly.
                if (mUiState == UiState.FULL_SCREEN) {
                    collapseBrowserView();
                    exitFullScreen(mActivity.getWindow().getDecorView());
                    return;
                }
                if (mUiState == UiState.SEARCH) {
                    exitSearch();
                    return;
                }
                if (geckoState.canGoBackward()) {
                    geckoState.goBack();
                    enterBrowsing();
                    return;
                }
                if (geckoState.hasPreviousSession()) {
                    int previousSessionId = geckoState.getEntityParentId();
                    GeckoState previousGeckoState = incognito
                            ? mIncognitoStateViewModel.getGeckoState(previousSessionId)
                            : mGeckoStateViewModel.getGeckoState(previousSessionId);
                    closeSession(geckoState, incognito);
                    if (previousGeckoState != null) {
                        openSession(previousGeckoState);
                    } else {
                        popToCorrectHome(incognito);
                        setEnabled(false);
                    }
                    return;
                }
                if (geckoState.isExternal()) {
                    closeSession(geckoState, incognito);
                    setEnabled(false);
                    mActivity.finish();
                    return;
                }

                Log.d(TAG, "onBackPressed back to home");
                mGeckoMediaController.stopMediaForSession(geckoState.getEntityId());
                popToCorrectHome(incognito);
                setEnabled(false);
            }
        };
        mActivity.getOnBackPressedDispatcher().addCallback(this, callback);
    }

    private void closeSession(@NonNull GeckoState state, boolean incognito) {
        if (incognito) {
            mIncognitoStateViewModel.closeGeckoState(state);
        } else {
            mGeckoStateViewModel.closeGeckoState(state);
        }
        // The repo's closeGeckoState only drops the entity from the list —
        // it doesn't release the underlying GeckoSession (the TabsFragment
        // swipe-close path defers that via the undo-snackbar dismissal).
        // The back-press flows that land here have no undo, so we close
        // the session immediately to free its content process — without
        // this, every back-pressed popup tab or external-intent tab leaks
        // a Gecko content process until app death.
        state.closeGeckoSession();
    }

    /**
     * Closes the URL-bar autocomplete overlay if it's currently up, and
     * returns true to short-circuit the back-press handler. The overlay
     * can outlive the current Gecko session (last tab closed, transient
     * null between tab swaps, fragment restored before its session
     * re-attaches), so this runs before any geckoState checks — back
     * has to dismiss the overlay even when there's no session to
     * navigate.
     */
    private boolean dismissAutocompleteOverlayIfVisible() {
        if (mAutoCompleteView == null || mAutoCompleteView.getVisibility() != View.VISIBLE) {
            return false;
        }
        hideKeyboard(mAutoCompleteEditText);
        mBrowserDownloadViewModel.update();
        applyToolbarScrollPolicy();
        mGeckoToolbar.clearFocus();
        mGeckoToolbar.startAnimation(false);
        mAutoCompleteView.updateVisibility(false);
        return true;
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

        // NOTE: no disableScrolling() here — at this point the toolbar has no behavior yet
        // (installed below), so the old call was a silent no-op. GeckoToolbarBehavior now
        // starts with isScrollEnabled=false (upstream default); applyToolbarScrollPolicy()
        // flips it once browsing actually starts.
        mGeckoToolbar.setOnClearFocusListener(this);
        mGeckoToolbar.setListener(this);

        mAutoCompleteEditText = mGeckoToolbar.getAutoCompleteEditText();
        mAutoCompleteEditText.setOnTextChangedListener(this);
        mAutoCompleteEditText.setOnCommitListener(this);
        mAutoCompleteEditText.setOnSearchStateChangeListener(this);
        mAutoCompleteEditText.setOnFilterListener(this);
        mAutoCompleteEditText.setOnFocusChangeListener(this);

        mSwipeRefreshLayout.setProgressViewOffset(false, 0, mGeckoToolbarSize + mBottomBarSize);

        // Pull-to-refresh gesture-time veto — Fenix SwipeRefreshFeature.canChildScrollUp
        // parity. Stock SwipeRefreshLayout decides "may I start the drag?" by asking
        // child.canScrollVertically(-1), which a GeckoView always answers false ("cannot
        // scroll up"), so to the stock logic EVERY downward drag looks like a refresh
        // candidate whenever the layout is enabled. Polling the LIVE InputResultDetail
        // instead makes the decision per-event from the CURRENT gesture's APZ answer:
        // content not at top, or the site consumed the touch → "child can scroll up" →
        // no spinner. This is the second, independent veto beside NestedGeckoView's
        // disallow-intercept arbitration (which can only be lifted once APZ reports
        // canOverscrollTop for the gesture) — defense in depth, exactly Fenix's shape.
        // Null guard: the lambda outlives onDestroyView's field nulling by
        // one teardown hop; null → veto (true = "child can scroll up"), the
        // safe answer.
        mSwipeRefreshLayout.setOnChildScrollUpCallback((parent, child) ->
                mGeckoView == null || !mGeckoView.getInputResultDetail().canOverscrollTop());

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

        // Most-visited tile tap → open the URL in the current tab (same as a
        // history-suggestion tap).
        mAutoCompleteView.setMostVisitedClickListener(url -> {
            GeckoState geckoState = peekCurrentGeckoState();
            if (geckoState == null) return;
            geckoState.setEntityUri(mSearchRepository.parseUri(url));
            openUri(geckoState);
        });
        // Long-press a tile → confirm dialog (in AutoCompleteView) → hide the
        // site from the strip (blocklist; history untouched), then refresh.
        mAutoCompleteView.setMostVisitedRemoveListener(
                url -> mAutoCompleteViewModel.hideFromMostVisited(url));

        mSwipeRefreshLayout.setOnRefreshListener(this);
        mSwipeRefreshLayout.setEnabled(false);
        mSwipeRefreshLayout.setColorSchemeResources(
                R.color.md_theme_primaryContainer,
                R.color.md_theme_primaryContainer,
                R.color.md_theme_primaryContainer);

        // GeckoView paints this colour over its surface until the compositor's
        // first frame — and re-shows it whenever the surface is recreated, which
        // the find-in-page relayout (bottom bar GONE→VISIBLE, dynamic-toolbar
        // resize) can trigger. White there flashes over the dark chrome on
        // find-mode exit; use the chrome surface so any cover matches instead.
        mGeckoView.coverUntilFirstPaint(IncognitoColors.getSurface(mActivity, false));
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
                mAutoCompleteView.getContext(), null, mAutoCompleteView));

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

        // Dock the FAB: bottomMargin = (bar height − the 64dp content row)
        // + app_bar_fab_margin. In the FRAMED model the bar no longer
        // self-pads, so bar height == the 64dp content row and this reduces
        // to a plain app_bar_fab_margin (16dp) above the bar — the nav strip
        // is already reserved by the root's safe-area padding. The formula
        // is kept (not hardcoded 16dp) so it still self-corrects if the bar
        // ever grows. Recomputed on every bar layout pass.
        // BottomNavigationFABBehavior remains purely the scroll-follower.
        mBottomNavigationBar.addOnLayoutChangeListener(
                (bar, l, t, r, b, ol, ot, or, ob) -> {
                    int barHeight = b - t;
                    if (barHeight <= 0 || mDownloadButton == null) {
                        return;
                    }
                    int margin = Math.max(0, barHeight - mChromeBarBaseSize)
                            + getResources().getDimensionPixelOffset(R.dimen.app_bar_fab_margin);
                    ViewGroup.MarginLayoutParams params =
                            (ViewGroup.MarginLayoutParams) mDownloadButton.getLayoutParams();
                    if (params.bottomMargin != margin) {
                        params.bottomMargin = margin;
                        mDownloadButton.setLayoutParams(params);
                    }
                });
        return v;
    }

    @Override
    public void onViewCreated(@NonNull View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        Log.d(TAG, "onViewCreated");

        // FRAMED chrome: the root pads itself on ALL FOUR sides by the
        // system-bar (+ cutout) insets, so every child lives inside the
        // safe area and the status/nav strips are this view's own
        // background showing through the padding (an opaque frame; painted
        // in resetWindowTheme / applyIncognitoTheme). clipToPadding (default
        // true) clips a scroll-hiding bar clean at the frame edge. This
        // REPLACES the old split where the root padded l/r only and each
        // bar self-padded its strip (which created the dead-zone — see the
        // layout comment): now the bars are a true app_bar_size tall and
        // need no inset of their own. Insets are CONSUMED — no descendant
        // self-pads any more, so nothing downstream needs them.
        ViewCompat.setOnApplyWindowInsetsListener(view, (v, windowInsets) -> {
            Insets insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars()
                    | WindowInsetsCompat.Type.displayCutout());
            v.setPadding(insets.left, insets.top, insets.right, insets.bottom);

            // Keyboard tracking for the toolbar scroll policy (Fenix parity): while the IME
            // is up the bars must not be able to scroll away mid-typing, and when it closes
            // the bars are brought back (Fenix's setupShowingToolbarsAfterKeyboardHidden).
            // Fenix doesn't force-expand on IME *open* — a form field at the bottom of a
            // scrolled page shouldn't lose more height to chrome — so neither do we.
            boolean imeVisible = windowInsets.isVisible(WindowInsetsCompat.Type.ime());
            if (imeVisible != mImeVisible) {
                mImeVisible = imeVisible;
                if (imeVisible) {
                    applyToolbarScrollPolicy();
                } else {
                    expandBarsAndApplyPolicy();
                }
            }
            return WindowInsetsCompat.CONSUMED;
        });

        // Pin the bars while touch exploration (TalkBack et al.) is on — a toolbar that
        // hides on scroll is unusable under a screen reader (same rationale as Fenix's
        // shouldUseFixedTopToolbar). Listener unregistered in the ON_DESTROY block below.
        mAccessibilityManager = mActivity.getSystemService(AccessibilityManager.class);
        if (mAccessibilityManager != null) {
            mTouchExplorationEnabled = mAccessibilityManager.isTouchExplorationEnabled();
            mAccessibilityManager.addTouchExplorationStateChangeListener(mTouchExplorationListener);
        }

        // Download badge: subscribe to both count streams and gate on
        // mIsIncognitoThemed (same pattern as the tab-count observers
        // below). Without this, an incognito-tab download would surface
        // its badge in the regular BrowserFragment chrome and vice
        // versa — the bottom bar is a single instance shared across
        // both modes.
        mTaskViewModel.getRegularCount().observe(getViewLifecycleOwner(), count -> {
            if (!mIsIncognitoThemed) mBottomNavigationBar.onBadgeCount(count);
        });
        mTaskViewModel.getSafeCount().observe(getViewLifecycleOwner(), count -> {
            if (mIsIncognitoThemed) mBottomNavigationBar.onBadgeCount(count);
        });

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

        // WebAssembly per-site allowlist. The content-script bridge in
        // the webrequests extension reports when a page tried to use WASM
        // while it's disabled. We surface a one-tap "Enable for {host}?"
        // snackbar. Filter by mIsIncognitoThemed so the regular and
        // incognito fragments don't both fire for the same event — each
        // VM is wired to its own repo (persistent vs in-memory).
        mGeckoStateViewModel.getNeedsWasmLive().observe(getViewLifecycleOwner(), url -> {
            if (mIsIncognitoThemed || url == null) return;
            showEnableWasmSnackbar(url, false);
        });
        mIncognitoStateViewModel.getNeedsWasmLive().observe(getViewLifecycleOwner(), url -> {
            if (!mIsIncognitoThemed || url == null) return;
            showEnableWasmSnackbar(url, true);
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
                    GeckoSession existing = geckoState.getGeckoSession();
                    boolean wasAlreadyOpen = existing != null && existing.isOpen();
                    Log.d(TAG, "BrowserURIViewModel → openSession+openUri for id=" + geckoState.getEntityId()
                            + " uri=" + geckoState.getEntityUri()
                            + " wasAlreadyOpen=" + wasAlreadyOpen);
                    openSession(geckoState);
                    // When the session wasn't open yet, setGeckoViewSession's
                    // !isOpen branch already drove the load (either via
                    // restoreState's auto-navigation or openUri). Calling
                    // openUri again here queues a second loadUri that races
                    // the first and stalls — same shape as the TabsFragment
                    // stuck-progress bug. Only fire openUri when the session
                    // was already attached and setGeckoViewSession had no
                    // load to dispatch.
                    if (wasAlreadyOpen) {
                        openUri(geckoState);
                    }
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
                makeAnchoredSnackbar(getString(R.string.settings_clear_browsing_success, host)).show();
            } else if (id == R.id.action_clear_error_browsing) {
                String host = mOptionEntity.getAction();
                makeAnchoredSnackbar(getString(R.string.settings_clear_browsing_error, host)).show();
            }else if (id == R.id.action_delete_clipboard) {
                mAutoCompleteView.hideClipboard();
            } else if (id == R.id.popup_vault) {
                mStartForResult.launch(new Intent(mActivity, VaultActivity.class));
            } else if (id == R.id.popup_downloads) {
                mStartForResult.launch(new Intent(mActivity, DownloadsActivity.class));
            }else if (id == R.id.popup_bookmarks) {
                // Carry the incognito flag through so the list paints
                // in incognito tones and tapping a bookmark opens an
                // incognito tab + unwinds to home_incognito on back.
                Bundle args = new Bundle();
                args.putBoolean(Keys.IS_INCOGNITO, mIsIncognitoThemed);
                NavigationUtils.navigateSafe(mNavController, R.id.action_browser_to_bookmarks, args);
            } else if (id == R.id.popup_history) {
                Bundle args = new Bundle();
                args.putBoolean(Keys.IS_INCOGNITO, mIsIncognitoThemed);
                NavigationUtils.navigateSafe(mNavController, R.id.action_browser_to_history, args);
            } else if (id == R.id.popup_sync) {
                Intent syncIntent = new Intent(mActivity, SettingsActivity.class);
                syncIntent.putExtra(SettingsActivity.EXTRA_OPEN_SYNC, true);
                mStartForResult.launch(syncIntent);
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
            } else if (id == R.id.popup_save_snapshot) {
                // Archive the current page to a self-contained .html. The
                // serializer runs in the downloader@ extension's snapshot.js
                // content script and delivers the file through GeckoView's
                // normal download funnel (onExternalResponse); we just kick it
                // off and confirm. A page must be loaded for there to be a DOM
                // to capture.
                GeckoState geckoState = peekCurrentGeckoState();
                if (geckoState == null) return;
                // Safeguard: the serializer is the downloader@ snapshot.js
                // CONTENT script, which never runs on moz-extension (e.g. the
                // uBlock page-blocked interstitial), about:, resource: or data:
                // pages. On those the capture message reaches no listener, so no
                // download fires and the "Saving snapshot…" snackbar would hang
                // until it times out. Refuse up front instead.
                if (!UrlStringUtils.isSnapshotSupported(geckoState.getEntityUri())) {
                    makeAnchoredSnackbar(R.string.browser_snapshot_unsupported).show();
                    return;
                }
                mGeckoRuntimeHelper.captureSnapshot();
                // Mark this fragment as the one that started the snapshot (the
                // download callback fans out to both regular + incognito
                // fragments — only the initiator should act) and show the
                // in-progress snackbar; it flips to "Snapshot saved · View" in
                // onDownload, with no confirm dialog in between.
                mSnapshotPending = true;
                showSnapshotSavingSnackbar();
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
                makeAnchoredSnackbar(R.string.browser_bookmark_added).show();
            } else if (id == R.id.popup_bookmark_edit) {
                GeckoState mGeckoState = peekCurrentGeckoState();
                if (mGeckoState == null) return;
                String url = mGeckoState.getEntityUri();
                Bundle editArgs = new Bundle();
                // Repository's canonical id so the edit fragment looks
                // up the right row even when the GeckoSession URL has a
                // trailing slash / mixed-case host that doesn't match
                // the user-typed save string verbatim.
                editArgs.putInt(Keys.ITEM_ID,
                        WebBookmarkDataRepository.bookmarkIdFor(url));
                // Carry incognito through so the edit form paints in
                // matching tones and 'Open in browser' from there opens
                // an incognito tab.
                editArgs.putBoolean(Keys.IS_INCOGNITO, mIsIncognitoThemed);
                NavigationUtils.navigateSafe(mNavController,
                        R.id.action_browser_to_bookmark_edit, R.id.browser, editArgs);
            } else if (id == R.id.popup_reload) {
                GeckoState gs = peekCurrentGeckoState();
                if (gs != null) gs.reload();
            } else if (id == R.id.popup_stop) {
                GeckoState gs = peekCurrentGeckoState();
                if (gs != null) {
                    gs.stop();
                    mGeckoToolbar.setLoading(false);
                    mBrowserDialogViewModel.setLoading(false);
                }
            } else if(id == R.id.new_tab){
                // Fixed meaning: New tab always opens a REGULAR tab,
                // even from incognito chrome (where it acts as the
                // explicit exit-to-normal-tab action). The incognito
                // counterpart is R.id.new_incognito_tab below. Matches
                // the new-tab picker, which labels this option with the
                // regular-web icon alongside the incognito one.
                GeckoStateEntity geckoStateEntity = new GeckoStateEntity(true);
                geckoStateEntity.setIncognito(false);
                // Route through setActiveSession so creation and repo-insert
                // stay consistent across the codebase and repo lookup-first
                // semantics are preserved.
                setActiveSession(geckoStateEntity, true);
                popToCorrectHome(false);
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

                Snackbar snackbar = makeAnchoredSnackbar(R.string.contextmenu_snackbar_new_tab_opened);
                snackbar.setAction(R.string.contextmenu_snackbar_action_switch, v -> openSession(geckoState));
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

                makeAnchoredSnackbar(R.string.contextmenu_snackbar_new_tab_opened).show();
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

        // Empty-focus most-visited strip — its own view, populated here and
        // toggled by showEmpty()/hideAll() (visibility only, no list diff).
        mAutoCompleteViewModel.getMostVisited().observe(getViewLifecycleOwner(),
                list -> mAutoCompleteView.setMostVisited(list));

        getViewLifecycleOwner().getLifecycle().addObserver((LifecycleEventObserver) (source, event) -> {
            if (Lifecycle.Event.ON_DESTROY.equals(event)) {
                Log.d(TAG, "onDestroy");
                mGeckoObserverRegistry.unregister(BrowserFragment.this);
                if (mAccessibilityManager != null) {
                    mAccessibilityManager
                            .removeTouchExplorationStateChangeListener(mTouchExplorationListener);
                }
            } else if (Lifecycle.Event.ON_CREATE.equals(event)) {
                Log.d(TAG, "onCreate");
                mGeckoObserverRegistry.register(BrowserFragment.this);
            } else if (Lifecycle.Event.ON_PAUSE.equals(event) || Lifecycle.Event.ON_STOP.equals(event)) {
                Log.d(TAG, "onPause/onStop");
                mStop = true;
                mSwipeRefreshLayout.setEnabled(false);
            } else if (Lifecycle.Event.ON_START.equals(event)) {
                Log.d(TAG, "onStart");
                // FULL_SCREEN-aware: a blanket enable resurrected
                // pull-to-refresh over fullscreen video after a home-button
                // round-trip (nothing re-disables until fullscreen exit now
                // that the per-scroll heuristic is gone).
                mSwipeRefreshLayout.setEnabled(mUiState != UiState.FULL_SCREEN);

            } else if (Lifecycle.Event.ON_RESUME.equals(event)) {
                Log.d(TAG, "onResume");
                mStop = false;
                mSwipeRefreshLayout.setEnabled(mUiState != UiState.FULL_SCREEN);
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

                // Returning to the app always brings the bars back (Fenix dispatches
                // showToolbarAsExpanded in onResume) — half-hidden chrome from the
                // previous session's last scroll shouldn't greet the user.
                expandBarsAndApplyPolicy();
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
        // FRAMED chrome: the status/nav strips are the root view's own
        // background showing through its all-sides safe-area padding, so
        // paint that frame here in the chrome tone. paintSystemBars still
        // sets the WINDOW bar colours for Android <= 14 (same tone, so the
        // opaque window bar and the frame behind it are seamless); on 15+
        // the window bars are transparent and this root frame is what shows.
        // Flat SURFACE chrome (Firefox-parity): the whole frame - status strip,
        // sides and nav strip - is surface, matching the surface toolbar and
        // bottom bar, so the browser reads as one surface plane (the bar's
        // hairline carries the division). The window layer below mirrors it for
        // Android <= 14.
        paintFrameBackground(IncognitoColors.getSurface(mActivity, false));
        paintSystemBars(
                IncognitoColors.getSurface(mActivity, false),
                IncognitoColors.getSurface(mActivity, false));
        // tonalHolder=false: surface holder (the quiet Home spec), not the old
        // tonal surfaceContainer chrome.
        mGeckoToolbar.updateTheme(mActivity, false, false);
        mAutoCompleteView.updateTheme(mActivity, false, false);
        mSearchAutocompleteAdapter.setIncognito(false);

        // BrowserAppDialogFragment reports a blocked Play Store "open in
        // app" redirect here — the user tapped Open but the target app
        // isn't installed and the block-redirects pref is on, so instead of
        // launching Google Play we surface the same snackbar the direct
        // anti-nag path uses, so the no-op isn't invisible.
        getParentFragmentManager().setFragmentResultListener(
                BrowserAppDialogFragment.RESULT_KEY,
                getViewLifecycleOwner(),
                (requestKey, result) -> {
                    if (result.getBoolean(BrowserAppDialogFragment.RESULT_BLOCKED, false)) {
                        makeAnchoredSnackbar(getString(R.string.block_redirect_snackbar)).show();
                    }
                });

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
            // Binding intact: re-theme but do NOT re-arm the first-paint
            // cover — the page is already painted, so a re-armed cover would
            // sit on top of the live surface (the "stuck cover colour after
            // returning from Settings" bug) until the next forced repaint.
            applyBrowserIncognitoTheme(
                    current.getGeckoStateEntity().isIncognito(), false);
            // The view↔session binding is intact, but the session itself
            // may have been deactivated out-of-band (URL-bar focus,
            // backgrounding, …). Re-assert active so the surface resumes
            // rendering. setActive is idempotent when already active.
            current.setActive(true);
        }
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        // Pair with the onCreate restore — keeps incognito mode pinned
        // across config changes and process death.
        outState.putBoolean(Keys.IS_INCOGNITO, mIsIncognitoThemed);
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        Log.d(TAG, "onDestroyView: releasing GeckoView session");
        dismissSnapshotSnackbar();
        if (mSwipeRefreshLayout != null) {
            mSwipeRefreshLayout.setOnRefreshListener(null);
            mSwipeRefreshLayout.setOnChildScrollUpCallback(null);
        }
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
        // Delegates to the explicit-state overload — the two used to be
        // byte-identical clones and every BROWSING-transition tweak had to be
        // made twice (and was once missed). One body, one truth.
        enterBrowsing(null);
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
        // The toolbar IS the find bar — it must be fully on-screen before search starts
        // (it may be half-hidden from the scroll that preceded opening the menu).
        expandBars();
        mUiState = UiState.SEARCH;
        geckoState.setSearchMode(true);
        applyToolbarScrollPolicy();
        mGeckoToolbar.enableSearch();
        mBottomNavigationBar.setVisibility(View.GONE);
        mDownloadButton.hide();
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
            // Non-creating getter: if mGeckoSession is null (tab whose
            // session was never instantiated, or killed and discarded via
            // discardGeckoSession), there's no finder state to clear and
            // we don't want to spawn a fresh content process for it.
            GeckoSession session = geckoState.getGeckoSession();
            if (session != null) session.getFinder().clear();
            geckoState.setSearchMode(false);
        }

        // Always restore UI even if geckoState is null.
        mGeckoToolbar.clearText();
        mBottomNavigationBar.setVisibility(View.VISIBLE);
        mBottomNavigationBar.show();
        mDownloadButton.show();

        enterBrowsing();
        // enterBrowsing applies the scroll policy only when it actually
        // transitions — with a null current state (find-in-page exit racing a
        // tab swap/restore, the case the always-restore block above exists
        // for) it early-returns and mUiState stays INIT. Apply unconditionally
        // so this path never exits without a policy decision (idempotent when
        // enterBrowsing already ran).
        applyToolbarScrollPolicy();
    }

    /**
     * Shows the "Enable WebAssembly for {host}?" snackbar in response to a
     * wasm-unavailable event from the content-script bridge.
     *
     * <p>The snackbar is scoped to the host of {@code reportedUrl}; only
     * fires if that host matches the currently active tab — sites in
     * background tabs shouldn't be able to grab the user's attention on
     * a foreground tab they're not looking at. Tapping "Enable" adds the
     * host to the appropriate allowlist (persistent or incognito-only)
     * and asks {@link GeckoRuntimeHelper#enableWasmAndReload} to flip
     * the global pref and reload — the pref change is async, the reload
     * waits for it.</p>
     */
    /**
     * Build a Snackbar parented to {@link #getSnackAnchorView()}, anchored
     * above the bottom navigation bar ({@code R.id.anchor_view}) and tinted
     * for the current theme ({@code mIsIncognitoThemed}). Collapses the
     * repeated {@code makeSnackbar(getSnackAnchorView(), …)} +
     * {@code setAnchorView(R.id.anchor_view)} pairing used across this
     * fragment. Returns the Snackbar so callers can still chain
     * {@code setAction(...)} before {@code show()}.
     *
     * <p>Deliberately not pushed down into {@link BaseFocusFragment}: the
     * base {@code makeSnackbar} is also used by the fullscreen hint (parented
     * to the bottom bar, not anchored) and by the download fragments, which
     * don't anchor to {@code R.id.anchor_view}.
     */
    private Snackbar makeAnchoredSnackbar(String text) {
        Snackbar snackbar = makeSnackbar(getSnackAnchorView(), text, mIsIncognitoThemed);
        snackbar.setAnchorView(R.id.anchor_view);
        return snackbar;
    }

    private Snackbar makeAnchoredSnackbar(int textResId) {
        return makeAnchoredSnackbar(getString(textResId));
    }

    private void showEnableWasmSnackbar(String reportedUrl, boolean incognito) {
        GeckoState current = peekCurrentGeckoState();
        if (current == null || current.getEntityUri() == null) {
            Log.d(TAG, "showEnableWasmSnackbar skip: no current tab. reportedUrl=" + reportedUrl);
            return;
        }

        String currentHost = WebUtils.getDomainName(current.getEntityUri());
        String reportedHost = WebUtils.getDomainName(reportedUrl);
        if (!reportedHost.equals(currentHost)) {
            Log.d(TAG, "showEnableWasmSnackbar skip: host mismatch. current=" + currentHost
                    + " reported=" + reportedHost);
            return;
        }

        View anchor = getSnackAnchorView();
        if (anchor == null) {
            Log.d(TAG, "showEnableWasmSnackbar skip: no anchor view");
            return;
        }
        Log.d(TAG, "showEnableWasmSnackbar showing for " + reportedHost
                + " incognito=" + incognito);

        Snackbar snackbar = makeAnchoredSnackbar(
                getString(R.string.wasm_snackbar_message, reportedHost));
        snackbar.setAction(R.string.wasm_snackbar_action_enable, v -> {
            if (incognito) {
                mIncognitoStateViewModel.allowWasmFor(reportedUrl);
            } else {
                mGeckoStateViewModel.allowWasmFor(reportedUrl);
            }
            GeckoState state = peekCurrentGeckoState();
            if (state != null) {
                mGeckoRuntimeHelper.enableWasmAndReload(state.getGeckoSession());
            }
        });
        snackbar.show();
    }

    /**
     * Enters FULL_SCREEN UI state.
     *
     * <p><b>Must be called AFTER</b> {@link #expandBrowserView()} — that method is timing-
     * sensitive and synchronous; this one handles only the overlay UI bookkeeping (download
     * button, snackbar).
     *
     * <p>Called from {@link #onFullScreen(GeckoState, boolean)} and {@link #onHideBars(GeckoState)}.
     */
    private void enterFullScreen(View decorView) {
        decorView.setBackgroundColor(Color.BLACK);
        mUiState = UiState.FULL_SCREEN;
        // The one mUiState transition that previously skipped the policy:
        // without this, isScrollEnabled stays true for the whole fullscreen
        // stay and the gesture detector keeps translating the GONE toolbar
        // while the bottom-bar follower (not VISIBLE) refuses to sync.
        applyToolbarScrollPolicy();
        // Owned here (not only in onFullScreen) so the onHideBars pseudo-
        // fullscreen disables pull-to-refresh too — its SwipeRefreshLayout
        // behavior is detached and both bars are GONE, exactly the state a
        // spinner must not appear over.
        mSwipeRefreshLayout.setEnabled(false);
        mDownloadButton.hide();
        makeSnackbar(mBottomNavigationBar, R.string.exit_fullscreen_with_back_button_short, mIsIncognitoThemed).show();
    }

    /**
     * Exits FULL_SCREEN and returns to BROWSING.
     *
     * <p><b>Must be called AFTER</b> {@link #collapseBrowserView()}.
     * Called from {@link #onFullScreen(GeckoState, boolean)}.
     */
    private void exitFullScreen(View decorView) {
        final TypedValue typedValue = new TypedValue();
        mActivity.getTheme().resolveAttribute(android.R.attr.colorBackground, typedValue, true);
        decorView.setBackgroundColor(typedValue.data);
        mUiState = UiState.INIT;
        mSwipeRefreshLayout.setEnabled(true);
        mDownloadButton.show();
        enterBrowsing();
        // Same null-current-state backstop as exitSearch: enterBrowsing
        // early-returns when peekCurrentGeckoState() is null (kill-on-trim /
        // tab-swap race), which would leave this path without any policy
        // decision and the bars latched. Idempotent when enterBrowsing ran.
        applyToolbarScrollPolicy();
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
            // Field empty again → most-visited strip (its observer fills the
            // strip; showEmpty reveals it when it has tiles).
            mAutoCompleteViewModel.loadMostVisited();
            mAutoCompleteView.showEmpty();
            mGeckoToolbar.clearText();
        } else {
            // resolveActiveGeckoState (not peek): stop/reload must act on the
            // session actually shown in the GeckoView even if mCurrentId has
            // drifted (kill-on-trim → resume), otherwise the controls no-op on
            // a visibly-loading tab.
            GeckoState geckoState = resolveActiveGeckoState();
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
                reloadOrReopen(geckoState);
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
                // resolveActiveGeckoState so typing a new URL works on the
                // visible tab even if mCurrentId drifted (otherwise the commit
                // silently no-ops on a stuck tab).
                GeckoState geckoState = resolveActiveGeckoState();
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
                mAutoCompleteViewModel.loadMostVisited();
                mAutoCompleteView.showEmpty();
            } else {
                mAutoCompleteViewModel.search(afterText);
            }
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
                    // Symmetric to the setActive(false) above: the toolbar
                    // deactivates the session on focus-gain (so the surface
                    // can be backgrounded behind the autocomplete sheet).
                    // Without re-activating on focus-loss, dismissing the
                    // sheet via back leaves the session inactive and the
                    // GeckoView keeps rendering a blank surface. The
                    // viewmodel.setGeckoState() call below only flips the
                    // repo flag; it does NOT call session.setActive().
                    geckoState.setActive(true);
                    if (geckoState.getGeckoStateEntity().isIncognito()) {
                        mIncognitoStateViewModel.setGeckoState(geckoState, true);
                    } else {
                        mGeckoStateViewModel.setGeckoState(geckoState, true);
                    }
                }
            }
            // Focus + empty field → most-visited strip (its observer fills the
            // strip; showEmpty reveals it when it has tiles); blur → clear.
            if (hasFocus) {
                mAutoCompleteViewModel.loadMostVisited();
            } else {
                mAutoCompleteViewModel.resetEngines();
            }
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
        // resolveActiveGeckoState, not peek: pull-to-refresh is the same user
        // intent as the toolbar reload button (which already resolves) — it
        // must act on the session actually shown in the GeckoView even if
        // mCurrentId drifted (kill-on-trim → resume), or the pull reloads a
        // non-visible tab while the spinner waits on the wrong one.
        GeckoState geckoState = resolveActiveGeckoState();
        if (geckoState == null) {
            mSwipeRefreshLayout.setRefreshing(false);
            return;
        }
        mSwipeRefreshLayout.setRefreshing(true);
        reloadOrReopen(geckoState);
    }

    @Override
    public void updateProgress(GeckoState geckoState, int progress) {
        Log.d(TAG, "ToolbarViewModel progress: " + progress);
        // Per-repo gating leak (see onStart) — the other mode's tab must not
        // repaint this fragment's progress UI or clear its refresh spinner.
        if (geckoState.isIncognito() != mIsIncognitoThemed) return;
        // Spinner bookkeeping BEFORE the autocomplete early-return: the gate
        // below is about the progress UI, but a pull-to-refresh whose 100%
        // arrived while the URL-bar overlay was up used to keep mRefreshing
        // true forever — and stock SwipeRefreshLayout fail-fasts on
        // mRefreshing, silently disabling all future pull-to-refresh.
        mSwipeRefreshLayout.setProgressRefreshing(progress);
        if (mGeckoToolbar.isAutoCompleteVisible()) return;
        mGeckoToolbar.setProgress(progress);
        mGeckoToolbar.setLoading(progress > 0 && progress < 100);
        mBrowserDialogViewModel.setLoading(progress > 0 && progress < 100);
    }

    @Override
    public void onStart(GeckoState geckoState) {
        // isCurrentGeckoState in GeckoComponents is PER-REPO, so a background
        // REGULAR tab's load events still reach the visible INCOGNITO fragment
        // (and vice versa) — each fragment instance must filter for its own
        // mode or the other mode's tab drives this UI's scroll policy.
        if (geckoState.isIncognito() != mIsIncognitoThemed) return;
        // Page load started on this mode's current tab. Mirror Fenix's
        // ToolbarBehaviorController: expand the bars and keep them pinned for
        // the whole load, so the user always has the URL/progress in view and a
        // half-hidden toolbar never carries across a navigation. Scrolling is
        // re-enabled in onStop.
        mPageLoading = true;
        expandBarsAndApplyPolicy();
    }

    @Override
    public void onStop(GeckoState geckoState) {
        // Same per-repo gating leak as onStart — filter for this fragment's mode.
        if (geckoState.isIncognito() != mIsIncognitoThemed) return;
        // Load finished — scroll-to-hide may resume (Fenix enables scrolling only when
        // content.loading flips false). Runs before the autocomplete early-return below:
        // that gate is about the *progress UI*, not the scroll policy, and the policy
        // itself refuses to enable while the keyboard is up.
        mPageLoading = false;
        applyToolbarScrollPolicy();
        // Spinner bookkeeping before the autocomplete gate — same stuck-
        // mRefreshing reasoning as updateProgress.
        mSwipeRefreshLayout.setProgressRefreshing(100);

        // Page finished (or was halted by the engine). The loading indicator
        // is otherwise cleared only by onProgressChange(100); if a page stalls
        // mid-load or its final progress tick never arrives, the stop button
        // would stay up forever. onPageStop is the authoritative "no longer
        // loading" signal, so clear the loading UI here regardless of progress.
        if (mGeckoToolbar.isAutoCompleteVisible()) return;
        mGeckoToolbar.setProgress(100);
        mGeckoToolbar.setLoading(false);
        mBrowserDialogViewModel.setLoading(false);
    }

    @Override
    public void onLocationChange(GeckoState geckoState, String url) {
        // Per-repo gating leak (see onStart): without this, a background
        // regular tab's commit paints its URL into the visible incognito
        // toolbar and vice versa.
        if (geckoState.isIncognito() != mIsIncognitoThemed) return;
        // Paint the toolbar from the event's url, NOT geckoState.getEntityUri():
        // the entity URI is mutable shared state, and re-reading it here is how a
        // late commit of an abandoned load used to revert the toolbar to the old
        // URL after the user had already typed a new one (the value read back was
        // whatever the last NavigationDelegate write left, not this event's).
        mGeckoToolbar.onLocationChange(url);
        // A navigation while find-in-page is active dismisses the search bar.
        if (mUiState == UiState.SEARCH) {
            exitSearch();
        }
    }

    @Override
    public void onFullScreen(GeckoState geckoState, boolean fullScreen) {
        // Per-repo gating leak (see onStart): the other mode's tab must not
        // flip this fragment's chrome.
        if (geckoState.isIncognito() != mIsIncognitoThemed) return;

        // System UI / immersive mode — mirrors upstream enterImmersiveMode / exitImmersiveMode.
        final Window window = mActivity.getWindow();
        final View decorView = window.getDecorView();


        // Layout operations are synchronous and timing-sensitive: expand/collapse must happen
        // before any other UI mutation (e.g. hiding the download button) to avoid racing with
        // the compositor. State bookkeeping (enterFullScreen / exitFullScreen) comes after.
        if (fullScreen) {
            // Fullscreen entered while find-in-page is up: close the find bar
            // first or the toolbar comes back from fullscreen still wearing
            // the find UI while mUiState says BROWSING — onCommit would then
            // load the find text as a URL and exitSearch could never run
            // again (its SEARCH guard fails). Fenix closes find-in-page on
            // fullscreen for the same reason.
            if (mUiState == UiState.SEARCH) {
                exitSearch();
            }
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
        // Same mode filter as the other observers; GeckoComponents also gates
        // HIDE_BARS on isCurrentGeckoState now.
        if (geckoState.isIncognito() != mIsIncognitoThemed) return;
        final Window window = mActivity.getWindow();
        final View decorView = window.getDecorView();
        if (mUiState == UiState.SEARCH) {
            exitSearch(); // same hybrid-toolbar hazard as DOM fullscreen
        }
        expandBrowserView();
        enterFullScreen(decorView);
    }

    @Override
    public void onShowDynamicToolbar() {
        // onHideDynamicToolbar's paired exit. That path enters the fullscreen
        // UI WITHOUT DOM fullscreen (entityFullScreen stays false), so nothing
        // else ever restores the chrome — the old expandBars() call was
        // self-locked-out by its own FULL_SCREEN guard and the user was wedged
        // chrome-less until a real DOM fullscreen cycle. A genuine DOM
        // fullscreen (entityFullScreen true) is left alone: its exit is
        // onFullScreen(false).
        if (mUiState == UiState.FULL_SCREEN) {
            GeckoState geckoState = peekCurrentGeckoState();
            if (geckoState == null || !geckoState.isFullScreen()) {
                collapseBrowserView();
                exitFullScreen(mActivity.getWindow().getDecorView());
            }
            return;
        }
        // Gecko asks the app to show the dynamic toolbar fully expanded (e.g. content/IME
        // needs the space declared via setDynamicToolbarMaxHeight). The bottom bar follows
        // the toolbar through BottomNavigationBehavior — no separate call needed.
        expandBars();
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
        // Deliberately NO SwipeRefreshLayout gating here (Fenix parity — don't re-add
        // it). The old heuristic, setEnabled(scrollY < 10 && !canScrollToTop()),
        // derived the pull-to-refresh decision from two stale inputs: the ASYNC
        // ScrollDelegate position (which lags the page and never moves at all when a
        // site scrolls an inner container — root scrollY stuck at 0 kept P2R armed
        // mid-page) and the PREVIOUS gesture's InputResultDetail (reset to UNKNOWN on
        // every ACTION_UP, so canScrollToTop() was almost always false at decision
        // time; a fling settling at scrollY 10..N left P2R dead for the next at-top
        // pull). The result was the "is this a scroll or a refresh?" coin-flip. The
        // per-gesture mechanisms own the decision now: NestedGeckoView's
        // disallow-intercept arbitration + the live canChildScrollUp veto installed in
        // onCreateView. Fenix never toggles isEnabled from scroll events either —
        // only settings and fullscreen do.
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

        // "Save snapshot" produces a local blob (text/html). The user already
        // chose to save it from the overflow menu, so the generic "save / cancel"
        // download dialog is redundant friction — download it directly and flip
        // the in-progress snackbar to "Snapshot saved · View". Gated on
        // mSnapshotPending (set only on the initiating fragment) so: (a) only
        // that fragment acts — the callback fans out to both regular + incognito
        // fragments, so without the gate the service would start twice; and
        // (b) a genuine, unrelated text/html FILE download (no snapshot in
        // flight) still falls through to the normal dialog below instead of
        // being swallowed.
        if (mSnapshotPending && isSnapshotResponse(response)) {
            mSnapshotPending = false;
            downloadSnapshotDirect(geckoState);
            return;
        }

        Bundle bundle = new Bundle();
        bundle.putInt(Keys.ITEM_ID, geckoState.getEntityId());
        bundle.putBoolean(Keys.IS_INCOGNITO, mIsIncognitoThemed);
        NavigationUtils.navigateSafe(mNavController, R.id.dialog_browser_download, R.id.browser, bundle);
    }

    /**
     * A snapshot download is a locally-produced {@code blob:} {@code text/html}
     * — the only such download the app makes (captured media is always an http
     * URL). Detect it so it skips the confirm dialog and gets the "saved"
     * (not "Downloading…") snackbar.
     */
    private boolean isSnapshotResponse(WebResponse response) {
        if (response == null) return false;
        if (response.uri != null && response.uri.startsWith("blob:")) return true;
        String contentType = response.headers != null
                ? response.headers.get(BrowserHeaders.CONTENT_TYPE) : null;
        return contentType != null && contentType.toLowerCase(Locale.ROOT).startsWith("text/html");
    }

    /**
     * Starts the snapshot download without the confirm dialog and flips the
     * in-progress snackbar to "Snapshot saved · View". Mirrors the service
     * dispatch {@code startDownload} does, minus the dialog and the generic
     * "Downloading…" snackbar.
     */
    private void downloadSnapshotDirect(GeckoState geckoState) {
        BrowserDownloadEntity entity = new BrowserDownloadEntity(geckoState);
        entity.setIncognito(mIsIncognitoThemed);
        DownloadRequest request = DownloadRequest.from(entity);

        Intent intent = new Intent(mActivity, RunnableManager.class);
        intent.setAction(IntentActions.DOWNLOAD_START);
        intent.putExtra(Keys.DOWNLOAD_REQUEST, request);
        mActivity.startService(intent);

        showSnapshotSavedSnackbar(request.isSaveToVault());
    }

    /**
     * In-progress snackbar for a snapshot save: indefinite "Saving snapshot…"
     * that {@link #onDownload} replaces with the "saved" snackbar once the page
     * has serialized. A timeout clears it if no download is ever produced (the
     * page errored), so an indefinite snackbar can't get stuck on screen.
     */
    private void showSnapshotSavingSnackbar() {
        dismissSnapshotSnackbar();
        Snackbar snackbar = makeAnchoredSnackbar(R.string.browser_snapshot_saving);
        snackbar.setDuration(Snackbar.LENGTH_INDEFINITE);
        final Snackbar shown = snackbar;
        snackbar.getView().postDelayed(() -> {
            if (mSnapshotSnackbar == shown) {
                mSnapshotPending = false;
                dismissSnapshotSnackbar();
            }
        }, SNAPSHOT_SAVE_TIMEOUT_MS);
        mSnapshotSnackbar = snackbar;
        snackbar.show();
    }

    /**
     * Turns the in-progress snackbar INTO the result, in place — the SAME
     * snackbar, text "Saving snapshot…" → "Snapshot saved" plus a View action —
     * so it reads as one continuous progress→done transition rather than one
     * snackbar sliding out and another sliding in. If the saving snackbar
     * already timed out, a fresh one is shown. Indefinite snackbars don't
     * self-dismiss, so it's hidden after a short linger.
     */
    private void showSnapshotSavedSnackbar(boolean saveToVault) {
        Snackbar snackbar = mSnapshotSnackbar;
        if (snackbar == null) {
            snackbar = makeAnchoredSnackbar(R.string.browser_snapshot_saved);
            snackbar.setDuration(Snackbar.LENGTH_INDEFINITE);
            mSnapshotSnackbar = snackbar;
            snackbar.show();
        } else {
            snackbar.setText(R.string.browser_snapshot_saved);
        }
        snackbar.setAction(R.string.file_view, v -> {
            Intent intent = new Intent(mActivity, saveToVault ? VaultActivity.class : DownloadsActivity.class);
            mStartForResult.launch(intent);
        });
        final Snackbar shown = snackbar;
        shown.getView().postDelayed(() -> {
            if (mSnapshotSnackbar == shown) dismissSnapshotSnackbar();
        }, SNAPSHOT_SAVED_LINGER_MS);
    }

    private void dismissSnapshotSnackbar() {
        if (mSnapshotSnackbar != null) {
            mSnapshotSnackbar.dismiss();
            mSnapshotSnackbar = null;
        }
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
    public void onLoadRequest(GeckoState geckoState, String uri, boolean autoRedirect, boolean wasRedirector) {
        Log.d(TAG, "onLoadRequest: " + uri + " autoRedirect=" + autoRedirect
                + " wasRedirector=" + wasRedirector);
        Intent browsableIntent = AppLinkUseCases.createBrowsableIntent(uri);
        if (browsableIntent == null
                || UrlStringUtils.isHttpOrHttps(uri)
                || UrlStringUtils.isMozExtensionLike(uri)
                // Defence-in-depth: blob: is engine content, never an
                // external-app link. The NavigationDelegate already allows it
                // so it shouldn't reach here, but guard anyway so a blob can
                // never surface the "open in app" dialog.
                || UrlStringUtils.isBlobLike(uri))
            return;

        boolean blockAppRedirects = mSharedPreferences.getBoolean(
                Preferences.SETTINGS_BLOCK_APP_REDIRECTS,
                Preferences.DEFAULT_BLOCK_APP_REDIRECTS);

        // Whether any installed app can actually handle this intent. Gates every
        // "Open" affordance below: no point offering to open (or popping an
        // "open in another app" dialog) when nothing will — that's a dead-end
        // tap, and a follow-up "no app found" snackbar would be one too many. For
        // an uninstalled-app deeplink createBrowsableIntent already rewrote this
        // to a Play Store intent, so on a de-Googled device with no Play Store
        // this is correctly false.
        boolean canOpen = mActivity != null
                && browsableIntent.resolveActivity(mActivity.getPackageManager()) != null;

        // When the toggle is on, silently block any PAGE-initiated app redirect
        // (autoRedirect = !isDirectNavigation): TikTok firing tiktok://, a site
        // bouncing to market://, etc. wasRedirector (recent load + back-history)
        // was too narrow — it missed TikTok, which fires its deeplink on a
        // first/cached view with no back-entry, so the dialog leaked through.
        // The deliberately-typed/bookmarked deeplink (isDirectNavigation) still
        // falls through to the dialog, and for the rarer deliberately-TAPPED app
        // link the snackbar's "Open" is the one-tap escape. User comms schemes
        // (mailto:/tel:/sms:/geo:) are carved out — never app nags, must keep
        // working. goBack only when we actually bounced (wasRedirector implies
        // back-history), so a first-load deeplink just stays put.
        if (blockAppRedirects && autoRedirect && !UrlStringUtils.isUserCommsScheme(uri)) {
            if (wasRedirector && geckoState != null) {
                geckoState.goBack();
            }
            // Silent block + a one-shot "Open" escape — but only attach "Open"
            // when an app can actually handle it (canOpen): no dead-end tap, and
            // no need for a follow-up "nothing can open this" snackbar.
            // browsableIntent is effectively final and non-null here.
            Snackbar snackbar = makeAnchoredSnackbar(getString(R.string.block_redirect_snackbar));
            if (canOpen) {
                snackbar.setAction(R.string.open, v -> {
                    try {
                        mActivity.startActivity(browsableIntent);
                    } catch (ActivityNotFoundException e) {
                        Log.e(TAG, "No Activity found: " + browsableIntent, e);
                    }
                });
            }
            snackbar.show();
            return;
        }

        // Not blocking (or carved out / direct nav) → the "open in another app"
        // dialog. Only show it when an app can actually handle the intent —
        // otherwise its only button would no-op, so skip the prompt entirely
        // rather than offer a dead end (e.g. an uninstalled-app deeplink with no
        // Play Store to fall back to).
        if (!canOpen) {
            Log.d(TAG, "onLoadRequest: no app resolves " + uri + " — skipping open-in-app dialog");
            return;
        }

        // "Open in app" links to an uninstalled app get rewritten by
        // createBrowsableIntent into a Play Store intent (the "install our
        // app" nag). That hop is born after the NavigationDelegate's
        // URL-level anti-nag check, so honour the block-app-redirects toggle
        // here too: when it's on and this resolves to the store, the dialog's
        // "Open" blocks + shows the snackbar instead of launching Google Play.
        // The dialog itself is unchanged for genuine app opens.
        boolean blockStoreRedirect = AppLinkUseCases.isMarketplaceIntent(browsableIntent)
                && blockAppRedirects;
        try {
            Bundle bundle = new Bundle();
            bundle.putParcelable(Keys.ITEM_ID, browsableIntent);
            bundle.putBoolean(Keys.OPEN_INCOGNITO, mIsIncognitoThemed);
            bundle.putBoolean(Keys.BLOCK_STORE_REDIRECT, blockStoreRedirect);
            NavigationUtils.navigateSafe(mNavController, R.id.dialog_browser_open_in_app, R.id.browser, bundle);
        } catch (ActivityNotFoundException e) {
            Log.e(TAG, "No Activity found: " + browsableIntent, e);
        }
    }

    /**
     * Site is trying to push the user to a Play Store listing (the
     * NavigationDelegate already denied the navigation). With the unified
     * "block app redirects" toggle:
     *   • ON (default): silent block — bounce off the tracker page if we came
     *     from one, then a Snackbar with a one-shot "Open" that loads the
     *     listing in-browser, so the block isn't invisible.
     *   • OFF: not blocking, so just load the listing in-browser. No prompt —
     *     unlike a generic app deeplink this stays in the browser (no app
     *     context switch), so there's nothing to confirm. (This replaced the
     *     old 3-choice BlockRedirectDialogFragment, which was redundant with
     *     the Settings toggle once the pref defaulted ON.)
     *
     * "Open"/load uses loadUri (isDirectNavigation=true → not re-intercepted),
     * resolved against the currently-visible session in case the user switched
     * tabs before tapping.
     */
    @Override
    public void onPlayStoreRedirect(GeckoState geckoState, String uri, boolean wasRedirector) {
        Log.d(TAG, "onPlayStoreRedirect: uri=" + uri + " wasRedirector=" + wasRedirector);
        boolean autoBlock = mSharedPreferences.getBoolean(
                Preferences.SETTINGS_BLOCK_APP_REDIRECTS,
                Preferences.DEFAULT_BLOCK_APP_REDIRECTS);
        if (!autoBlock) {
            loadInCurrentSession(uri);
            return;
        }
        if (wasRedirector && geckoState != null) {
            geckoState.goBack();
        }
        makeAnchoredSnackbar(getString(R.string.block_redirect_snackbar))
                .setAction(R.string.open, v -> loadInCurrentSession(uri))
                .show();
    }

    /**
     * Load a URL through the currently-visible session (regular/incognito).
     * Routes through {@link #openUri} rather than a raw
     * {@code getOrCreateGeckoSession().loadUri()}: the raw call bypassed every
     * load hardening — on a killed tab it constructed a fresh session that was
     * never {@code connectSession}'d nor opened (delegate-less, load no-ops or
     * later races restoreState), and it skipped the open/attach checks, the
     * stale-commit guard, and reactivation. openUri owns all of that.
     */
    private void loadInCurrentSession(String uri) {
        GeckoState state = resolveActiveGeckoState();
        if (state != null && !TextUtils.isEmpty(uri)) {
            state.setEntityUri(mSearchRepository.parseUri(uri));
            openUri(state);
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
        // The dead session was already discarded at the DATA layer
        // (GeckoComponents.onCrash) — it must happen even when no fragment
        // view is alive, or the closed session reference survives and a later
        // reopen never replays restoreState (the blank-tab bug). Here only the
        // UI decision remains: reopen ONLY when this fragment is showing the
        // crashed tab. The old unconditional openSession let a BACKGROUND
        // tab's crash rip the GeckoView off the page the user was reading —
        // and a cross-MODE crash even flipped incognito theming off
        // mid-private-session. Background tabs recover lazily on the next
        // switch (setGeckoViewSession's !isOpen branch), same as onKill.
        if (geckoState.isIncognito() == mIsIncognitoThemed
                && geckoState == peekCurrentGeckoState()) {
            openSession(geckoState);
        }
    }

    @Override
    public void onOrientation(Integer screenOrientation) {
        mActivity.setRequestedOrientation(screenOrientation);
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
        // A killed content process never delivers onPageStop — if the killed
        // tab is the one this fragment is showing, drop the load-pin or the
        // bars stay pinned for a load that no longer exists. (GeckoComponents
        // already cleared the per-tab GeckoState.isLoading flag.)
        if (geckoState.isIncognito() == mIsIncognitoThemed
                && geckoState == peekCurrentGeckoState()) {
            mPageLoading = false;
            applyToolbarScrollPolicy();
        }
        // Lazy recovery for BACKGROUND kills. onKill usually fires because
        // the OS reclaimed the content process while we're backgrounded —
        // eagerly reopening then would immediately spin a new content process
        // back up, defeating the kill's whole purpose and probably failing
        // under the same memory pressure that triggered it.
        //
        // GeckoView flips isOpen() to false internally before this
        // callback runs (per the ContentDelegate contract), so the
        // existing recovery paths handle the background cases lazily:
        //   - ensureSessionConnected on ON_RESUME sees !isOpen() and
        //     calls openSession when the user returns to the tab.
        //   - setGeckoViewSession's !isOpen() branch reopens on tab
        //     switch for non-current tabs.
        //
        // EXCEPTION — the kill hit the tab the user is LOOKING AT while the
        // app is foregrounded (observed on-device: switching through several
        // killed tabs spawns a burst of fresh content processes and the LMK
        // immediately reclaims one of them). None of the lazy paths can ever
        // fire then (no ON_RESUME coming, no tab switch happening), so the
        // user would sit on a dead, blank tab until they happen to switch
        // away and back or pull to refresh. Reopen eagerly for exactly this
        // case — Fenix does the same (the SELECTED tab's engine session is
        // recreated on demand; only background tabs stay torn down). The
        // memory-pressure argument doesn't apply: the visible tab is the one
        // process the user has explicitly asked for.
        //
        // Detach the dead session from the GeckoView first. The discard
        // happens at the DATA layer (GeckoComponents.onKill, BEFORE this
        // observer — it must run even when no fragment view is alive, or the
        // closed session reference survives and a later reopen never replays
        // restoreState, the blank-tab bug), so the GeckoState's reference is
        // already null and an identity comparison can't work. Guard on the
        // ATTACHED session being CLOSED instead: that is exactly the stale
        // attachment this release exists to clean up (a live foreground
        // tab's session is open, so a background-tab kill never tears the
        // visible tab off the view). Without the release, the GeckoView
        // keeps the dead session's compositor/surface binding pinned and the
        // tab comes back painting nothing.
        if (mGeckoView != null
                && mGeckoView.getSession() != null
                && !mGeckoView.getSession().isOpen()) {
            mGeckoView.releaseSession();
        }

        if (isResumed()
                && geckoState.isIncognito() == mIsIncognitoThemed
                && geckoState == peekCurrentGeckoState()) {
            openSession(geckoState);
        }
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

        // Deactivate the previous session if we're switching. Route through
        // the owning GeckoState when one can be resolved: GeckoState
        // .setActive(false) is a strict superset of the raw session call — it
        // first fires mOnDeactivateAction (GeckoPromptManager's prompt-dialog
        // dismissal) and stamps the entity's active flag, then forwards to
        // the same GeckoSession.setActive(false). On the normal tab-switch
        // path the repo's current-id sweep does this, but on the
        // mCurrentId-DRIFT re-attach (openUri found a different session on
        // screen) mCurrentId doesn't change, the sweep never runs, and the
        // raw call alone left the drifted-but-visible tab's open prompt
        // floating over the newly attached tab. Fall back to the raw call
        // for an orphaned session no repo knows (or one whose state already
        // swapped its session reference) — same behavior as before.
        if (previousSession != null && previousSession != newSession) {
            Log.d(TAG, "setGeckoViewSession: deactivating previousSession");
            controller.setTabActive(previousSession, false);
            GeckoState previousState = findGeckoStateBySession(previousSession);
            if (previousState != null) {
                previousState.setActive(false);
            } else {
                previousSession.setActive(false);
            }
        }

        mAutoCompleteEditText.setEnabled(true);

        if (!newSession.isOpen()) {
            Log.d(TAG, "setGeckoViewSession: session not open, opening + loading URI");
            newSession.open(mGeckoRuntimeHelper.getGeckoRuntime());
            newSession.setActive(true);
            String uri = mSearchRepository.parseUri(geckoState.getEntityUri());
            geckoState.setEntityUri(uri);
            // When the GeckoState has serialized SessionState, getOrCreateGeckoSession
            // already called restoreState(), which navigates to the last history entry
            // on its own. A second loadUri here races the restore: restore completes
            // (progress 100), the queued loadUri then restarts the load (progress 15)
            // and stalls — visible from TabsFragment as a tab that never finishes
            // loading. Apply only the UI side of openUri in that case.
            boolean hasRestoredState = !geckoState.getGeckoStateEntity().isIncognito()
                    && !TextUtils.isEmpty(geckoState.getEntityState());
            if (hasRestoredState) {
                applyOpenUriUi(geckoState, uri);
            } else {
                // Load directly instead of routing through openUri: the session
                // is freshly opened but not yet attached (the setSession below
                // runs after this branch), so openUri's attached-session guard
                // would RE-ENTER this method and double-run the whole
                // deactivate/setTabActive/viewmodel tail. Inline exactly what
                // openUri would do for an open session: arm the stale-commit
                // guard, load, apply the UI half. Activation was done above.
                geckoState.setPendingUserLoadUri(uri);
                newSession.loadUri(uri);
                applyOpenUriUi(geckoState, uri);
            }
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

        // Always (re)activate the session we just attached. Previously
        // setActive(true) ran only in the !isOpen branch above, so switching
        // back to an already-open tab relied on the focus-loss handler or the
        // repo sweep to un-suspend it — and a focus-gain setActive(false) that
        // wasn't symmetrically restored (current tab changed before focus
        // loss) left the GeckoView painting a blank, frozen surface.
        // setActive is idempotent, so asserting it here is safe and removes
        // that dependency.
        newSession.setActive(true);

        if (geckoState.getGeckoStateEntity().isIncognito()) {
            mIncognitoStateViewModel.setGeckoState(geckoState, true);
        } else {
            mGeckoStateViewModel.setGeckoState(geckoState, true);
        }
    }


    private void applyBrowserIncognitoTheme(boolean incognito) {
        // Default callers re-arm the first-paint cover: they attach/replace a
        // session that genuinely produces a fresh first frame.
        applyBrowserIncognitoTheme(incognito, true);
    }

    /**
     * @param armFirstPaintCover when true, re-arm GeckoView's first-paint
     *     cover so the pre-paint frame matches the (incognito) chrome. Pass
     *     false on a plain theme re-apply over an already-attached, already-
     *     painted session (e.g. resume from Settings with the view↔session
     *     binding intact): no new first paint is coming, so re-arming would
     *     leave the cover colour stuck over the live page until the next
     *     repaint.
     */
    private void applyBrowserIncognitoTheme(boolean incognito, boolean armFirstPaintCover) {
        // Sync FLAG_SECURE every call. The Activity's Window may hold
        // FLAG_SECURE from a previous fragment incarnation regardless
        // of what mIsIncognitoThemed currently says.
        if (mActivity != null) {
            Window w = mActivity.getWindow();
            if (incognito) {
                w.addFlags(WindowManager.LayoutParams.FLAG_SECURE);
            } else if (!mAppLock.isEnabled()) {
                w.clearFlags(WindowManager.LayoutParams.FLAG_SECURE);
            }
        }

        // No "incognito == mIsIncognitoThemed" early exit: onViewCreated
        // resets the freshly-inflated chrome to non-incognito on every
        // view re-creation, but mIsIncognitoThemed is preserved across
        // back-press so the two go out of sync. With the early-exit
        // here, restoring BrowserFragment from web_history / web_bookmark
        // would skip the re-paint and leave incognito browsing under a
        // regular toolbar + window decor.
        mIsIncognitoThemed = incognito;
        if (mActivity == null || mGeckoToolbar == null || mBottomNavigationBar == null)
            return;

        mGeckoToolbar.updateTheme(mActivity, incognito, false);
        mBottomNavigationBar.updateTheme(mActivity, incognito);
        // Flat SURFACE chrome (see resetWindowTheme): the whole frame is the
        // incognito surface tone, matching the surface toolbar + bottom bar.
        // The window layer below mirrors it for Android <= 14.
        paintFrameBackground(IncognitoColors.getSurface(mActivity, incognito));
        paintSystemBars(
                IncognitoColors.getSurface(mActivity, incognito),
                IncognitoColors.getSurface(mActivity, incognito));
        mAutoCompleteView.updateTheme(mActivity, incognito, false);
        mAutoCompleteViewModel.setIncognito(incognito);
        mSearchAutocompleteAdapter.setIncognito(incognito);

        // Keep GeckoView's first-paint cover in sync with the theme so any
        // pre-paint frame matches the chrome — purple in incognito — rather
        // than the regular surface set once at view creation. Only when a
        // fresh first paint is actually coming (session open / view re-create);
        // re-arming over an already-painted surface strands the cover colour.
        if (mGeckoView != null && armFirstPaintCover) {
            mGeckoView.coverUntilFirstPaint(IncognitoColors.getSurface(mActivity, incognito));
        }

        int nightMode = getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK;
        boolean lightBars = !incognito && nightMode != Configuration.UI_MODE_NIGHT_YES;

        Window window = mActivity.getWindow();
        window.getDecorView().setBackgroundColor(
                IncognitoColors.getSurface(mActivity, incognito));

        WindowInsetsControllerCompat insetsController =
                WindowCompat.getInsetsController(window, window.getDecorView());
        insetsController.setAppearanceLightStatusBars(lightBars);
        insetsController.setAppearanceLightNavigationBars(lightBars);

        Integer count = incognito
                ? mIncognitoStateViewModel.getTabsCount().getValue()
                : mGeckoStateViewModel.getTabsCount().getValue();
        if (count != null) mBottomNavigationBar.onTabsCount(count);

        // Mirror the tab-count refresh for the download badge: the
        // observers above only fire on LiveData emission, so a mode
        // switch without a count change would leave the badge stale
        // (e.g. regular shows 1 → switch to incognito → still 1 until
        // a vault download lands).
        Integer badge = incognito
                ? mTaskViewModel.getSafeCount().getValue()
                : mTaskViewModel.getRegularCount().getValue();
        mBottomNavigationBar.onBadgeCount(badge != null ? badge : 0);
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

            // Reuse the same attachment path as every other tab-switch —
            // setGeckoViewSession's !isOpen branch opens the new session
            // and drives the load (restoreState if there's saved history,
            // openUri otherwise). Previously this method also called
            // session.open() then openUri() explicitly, which queued a
            // second loadUri that raced restoreState's auto-navigation
            // and stalled the page reload after a UA toggle.
            openSession(newGeckoState);
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
        updateProgress(geckoState, 0);
        // Adopt the incoming tab's load state BEFORE enterBrowsing applies the
        // scroll policy. A flat `= false` was wrong for a tab that started
        // loading while backgrounded: its onPageStart was foreground-gated out
        // and will never re-fire, so nothing would re-pin the bars for the rest
        // of that load. GeckoState.isLoading() is tracked ungated per tab.
        mPageLoading = geckoState.isLoading();
        // Apply incognito theme BEFORE enterBrowsing so peekCurrentGeckoState works
        applyBrowserIncognitoTheme(geckoState.getGeckoStateEntity().isIncognito());
        enterBrowsing(geckoState);
        // Every tab switch / (re)connect lands here — bring the bars back (Fenix's
        // handleTabSelected → browserToolbar.expand()). Still needed despite
        // enterBrowsing's own policy call: enterBrowsing early-returns when
        // already BROWSING (the plain tab-switch case).
        expandBarsAndApplyPolicy();
        Log.d(TAG, "openSession end: id=" + geckoState.getEntityId());
    }

    private void openUri(GeckoState geckoState) {
        Log.d(TAG, "openUri: " + geckoState.getEntityUri());
        // Lazy onKill recovery: if the content process was reclaimed
        // (discardGeckoSession nulls mGeckoSession) or the session was never
        // opened, getOrCreateGeckoSession() would hand back a fresh DETACHED,
        // unopened session and loadUri() on it silently does nothing. Route
        // through openSession() so the session is opened + attached to the
        // GeckoView before the load. We only pay this when the user actually
        // navigates the tab again, so it doesn't undo onKill's memory reclaim.
        GeckoSession session = geckoState.getGeckoSession();
        if (session == null || !session.isOpen()) {
            openSession(geckoState);
            return;
        }

        // The load must go to the session that's actually ON SCREEN. If the
        // resolved state's session isn't the one attached to the GeckoView
        // (mCurrentId drift), loadUri here would load into a DETACHED session:
        // the visible page keeps loading its old URL and "wins" while the
        // typed URL loads invisibly — the lost-commit failure. Fenix never
        // hits this because its load funnels through the selected tab's
        // engine session; we re-attach first. (setGeckoViewSession is
        // idempotent for an open session — it only swaps the view attachment
        // and the active/tab bookkeeping. The nested call from its own
        // !isOpen branch can re-enter here, where the session is by then open
        // and gets attached one step earlier than before — harmless.)
        if (mGeckoView.getSession() != session) {
            Log.d(TAG, "openUri: resolved session not attached to GeckoView — re-attaching");
            setGeckoViewSession(geckoState);
        }

        // Re-activate BEFORE loading. The URL-bar focus handler deactivates
        // the session while the autocomplete sheet is up (onFocusChanged →
        // setActive(false)); previously the load was issued on the inactive
        // session and only applyOpenUriUi's clearFocus() reactivated it
        // afterwards. setActive is idempotent, so asserting it first is free.
        geckoState.setActive(true);

        String currentUri = geckoState.getEntityUri();
        // Arm the stale-commit guard: until Gecko STARTS this load
        // (onPageStart), any onLocationChange from this session is a late
        // commit of the load the user just abandoned and must not overwrite
        // the entity URI / toolbar. See GeckoState.mPendingUserLoadUri and the
        // suppression in GeckoComponents.NavigationDelegate.onLocationChange.
        geckoState.setPendingUserLoadUri(currentUri);
        session.loadUri(currentUri);
        applyOpenUriUi(geckoState, currentUri);
    }

    /**
     * Reload the tab, transparently reopening it first when its content
     * process was reclaimed (onKill → discardGeckoSession leaves the session
     * null / {@code !isOpen}). This is the lazy "verify the session is opened
     * once the tab is active again" recovery that the onKill teardown relies
     * on — a killed session's {@link GeckoState#reload()} is a no-op, so
     * without this the reload / pull-to-refresh button silently did nothing.
     * Reopening only when the user returns to the tab keeps onKill's memory
     * reclaim intact.
     */
    private void reloadOrReopen(GeckoState geckoState) {
        GeckoSession session = geckoState.getGeckoSession();
        if (session == null || !session.isOpen()) {
            openSession(geckoState);
        } else {
            geckoState.reload();
        }
    }

    /**
     * UI-only half of {@link #openUri} — enter browsing mode, refresh
     * tracking-protection state, update the toolbar, hide the keyboard.
     * Shared with the saved-state restore path in setGeckoViewSession,
     * which navigates via GeckoSession.restoreState and must NOT also
     * call loadUri (the two collide and the second load stalls).
     */
    private void applyOpenUriUi(GeckoState geckoState, String currentUri) {
        enterBrowsing(geckoState);
        if (geckoState.getGeckoStateEntity().isIncognito()) {
            mIncognitoStateViewModel.isTrackingProtected(currentUri);
        } else {
            mGeckoStateViewModel.isTrackingProtected(currentUri);
        }
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
        // show()/hide() (vs setVisibility) so the FAB scales + fades on the
        // state changes where it's visible — re-appearing after find-in-page
        // or fullscreen exit. On the initial home→browser arrival this is
        // called before the first layout pass, so show() takes its documented
        // instant fallback (no animation); harmless, and never a regression.
        mDownloadButton.show();
        mGeckoView.setVisibility(View.VISIBLE);
        applyToolbarScrollPolicy();
        mGeckoToolbar.onLocationChange(geckoState.getEntityUri());
        mBottomNavigationBar.show();
    }


    // ─────────────────────────────────────────────────────────────────────────────────────────────
    // Fullscreen layout helpers
    // ─────────────────────────────────────────────────────────────────────────────────────────────

    private void expandBrowserView() {
        // Only the toolbar is force-collapsed; the bottom bar is slaved to it
        // (BottomNavigationBehavior) and both are GONE for the whole fullscreen
        // stay anyway — visibility is what hides them, translation is bookkeeping.
        mGeckoToolbar.forceCollapse();
        mGeckoToolbar.setVisibility(View.GONE);
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
        // Both bars VISIBLE first, then expand the toolbar — the bottom bar re-syncs from
        // the toolbar's translation on its layout pass (BottomNavigationBehavior.onLayoutChild)
        // and follows the expand animation frame-by-frame.
        mGeckoToolbar.setVisibility(View.VISIBLE);
        mBottomNavigationBar.setVisibility(View.VISIBLE);
        mGeckoToolbar.forceExpand();

        CoordinatorLayout.LayoutParams srlParams =
                (CoordinatorLayout.LayoutParams) mSwipeRefreshLayout.getLayoutParams();
        srlParams.setBehavior(new NestedGeckoViewBehavior(
                mSwipeRefreshLayout.getContext(), null,
                mSwipeRefreshLayout, mGeckoToolbarSize, mBottomBarSize));
        mSwipeRefreshLayout.requestLayout();

        mGeckoView.setDynamicToolbarMaxHeight(mGeckoToolbarSize + mBottomBarSize);
    }

    /**
     * Paints the FRAMED chrome frame: the root CoordinatorLayout's background
     * is what shows through its all-sides safe-area padding — i.e. the opaque
     * status/nav strips. The whole browser chrome is now one flat surface tone
     * (toolbar + bottom bar + both strips), so this is a single colour. Called
     * from both theme paths (regular {@link #resetWindowTheme} /
     * {@link #applyBrowserIncognitoTheme}). No-ops if the view isn't attached
     * yet (the theme can be applied pre-onViewCreated).
     */
    private void paintFrameBackground(int color) {
        View root = getView();
        if (root != null) {
            root.setBackgroundColor(color);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────────────────────
    // Toolbar scroll policy (Fenix ToolbarBehaviorController equivalent)
    // ─────────────────────────────────────────────────────────────────────────────────────────────

    /**
     * Animates the toolbar fully on-screen; the bottom bar follows through its slaved
     * {@code BottomNavigationBehavior} (and the engine view re-clips via
     * {@link NestedGeckoViewBehavior}), so this is the single force-show entry point.
     *
     * <p>No-op in fullscreen: both bars are GONE and the engine-parent behavior is detached
     * there — {@link #collapseBrowserView()} owns the restore on exit.
     */
    private void expandBars() {
        if (mUiState == UiState.FULL_SCREEN) return;
        // Null guard: system callbacks (AccessibilityManager posts its listener
        // invocations) can race the view teardown by one main-loop hop — a
        // posted callback captured before removal may run after onDestroyView
        // nulled the fields. The bottom bar follows via BottomNavigationBehavior.
        if (mGeckoToolbar != null) {
            mGeckoToolbar.forceExpand();
        }
    }

    /**
     * The common "bring the bars back, then re-decide hideability" pair —
     * every force-show trigger (page start, tab switch, resume, IME close,
     * accessibility toggle) needs both halves or the bars end up expanded but
     * scroll-locked / hideable but half-off-screen depending on the path.
     */
    private void expandBarsAndApplyPolicy() {
        expandBars();
        applyToolbarScrollPolicy();
    }

    /**
     * The one decision point for whether the bars may scroll away — the conditions Fenix
     * spreads across ToolbarBehaviorController (loading), BrowserToolbarComposable
     * (keyboard), and shouldUseFixedTopToolbar (accessibility). Call after every change to
     * one of the inputs; the non-scrollable-page case needs no policy because
     * {@code GeckoToolbarBehavior.shouldScroll} already gates on
     * {@code InputResultDetail.canScrollToTop/Bottom}.
     */
    private void applyToolbarScrollPolicy() {
        if (mGeckoToolbar == null) {
            return; // see expandBars() — a posted system callback can outlive the view
        }
        boolean allowHide = mUiState == UiState.BROWSING
                && !mPageLoading
                && !mImeVisible
                && !mTouchExplorationEnabled;
        if (allowHide) {
            mGeckoToolbar.enableScrolling();
        } else {
            mGeckoToolbar.disableScrolling();
        }
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
     * Resolves the GeckoState that owns {@code session}, checking the
     * current theme's repo first (a session can only belong to one repo, so
     * the order is just a likely-hit optimization). Used by
     * {@link #setGeckoViewSession} to route the previous session's
     * deactivation through the state wrapper. Returns null for a session no
     * repo tracks (orphaned) or one whose state already swapped/discarded
     * its session reference.
     */
    @Nullable
    private GeckoState findGeckoStateBySession(GeckoSession session) {
        GeckoState state;
        if (mIsIncognitoThemed) {
            state = mIncognitoStateViewModel.getGeckoState(session);
            if (state == null) {
                state = mGeckoStateViewModel.getGeckoState(session);
            }
        } else {
            state = mGeckoStateViewModel.getGeckoState(session);
            if (state == null) {
                state = mIncognitoStateViewModel.getGeckoState(session);
            }
        }
        return state;
    }

    /**
     * GeckoState the toolbar controls (stop / reload / URL commit) should act
     * on. Prefers the current state by id, but falls back to the state that
     * owns the session actually attached to the GeckoView.
     *
     * <p>Why the fallback: the toolbar resolves "current" via mCurrentId, but
     * the visible page is whatever session is bound to mGeckoView. After a
     * kill-on-trim → discard → resume, or any path that leaves mCurrentId
     * pointing at a tab that no longer resolves, peekCurrentGeckoState()
     * returns null and the stop/reload/commit handlers used to early-return —
     * leaving a visibly-loading tab whose controls did nothing. Resolving the
     * attached session keeps the controls acting on what the user sees.</p>
     */
    @Nullable
    private GeckoState resolveActiveGeckoState() {
        GeckoState current = peekCurrentGeckoState();
        if (current != null) return current;
        if (mGeckoView == null) return null;
        GeckoSession attached = mGeckoView.getSession();
        if (attached == null) return null;
        GeckoState bySession = mGeckoStateViewModel.getGeckoState(attached);
        if (bySession == null) bySession = mIncognitoStateViewModel.getGeckoState(attached);
        return bySession;
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
        // Single invariant-enforcing path (pops to the existing home, or
        // clears + re-roots on a mode switch) — replaces the old
        // pop-browser-then-swap-or-push logic whose fallback could leave a
        // duplicate home on the stack.
        NavigationUtils.navigateToHome(mNavController, isIncognito);
    }

    private void findNextResult(String currentText, int flags) {
        GeckoState geckoState = peekCurrentGeckoState();
        if (geckoState == null)
            return;
        // Non-creating getter: a find request only makes sense on an
        // already-open session. Spawning a fresh content process via
        // getOrCreateGeckoSession just to search would attach the finder
        // to a session that's not the one rendered in mGeckoView.
        GeckoSession session = geckoState.getGeckoSession();
        if (session == null) return;
        session.getFinder().find(currentText, flags).then(result -> {
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