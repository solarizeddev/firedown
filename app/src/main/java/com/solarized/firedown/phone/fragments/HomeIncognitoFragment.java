package com.solarized.firedown.phone.fragments;

import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextUtils;
import android.util.Log;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.Window;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.coordinatorlayout.widget.CoordinatorLayout;
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
import com.solarized.firedown.UpdateReadyLiveData;
import com.solarized.firedown.BaseActivity;
import com.solarized.firedown.IntentActions;
import com.solarized.firedown.Keys;
import com.solarized.firedown.R;
import com.solarized.firedown.autocomplete.AutoCompleteEditText;
import com.solarized.firedown.autocomplete.AutoCompleteView;
import com.solarized.firedown.autocomplete.AutoCompleteViewBehavior;
import com.solarized.firedown.autocomplete.AutoCompleteViewModel;
import com.solarized.firedown.data.entity.AutoCompleteEntity;
import com.solarized.firedown.data.entity.GeckoStateEntity;
import com.solarized.firedown.data.models.BrowserDialogViewModel;
import com.solarized.firedown.data.models.BrowserURIViewModel;
import com.solarized.firedown.data.models.IncognitoStateViewModel;
import com.solarized.firedown.data.models.GeckoStateViewModel;
import com.solarized.firedown.geckoview.GeckoState;
import com.solarized.firedown.geckoview.GeckoToolbar;
import com.solarized.firedown.geckoview.toolbar.BottomNavigationBar;
import com.solarized.firedown.manager.DownloadRequest;
import com.solarized.firedown.phone.DownloadsActivity;
import com.solarized.firedown.phone.SettingsActivity;
import com.solarized.firedown.phone.VaultActivity;
import com.solarized.firedown.ui.IncognitoColors;
import com.solarized.firedown.ui.OnItemClickListener;
import com.solarized.firedown.ui.adapters.SearchAutocompleteAdapter;
import com.solarized.firedown.ui.diffs.SearchDiffCallback;
import com.solarized.firedown.utils.NavigationUtils;


import dagger.hilt.android.AndroidEntryPoint;

/**
 * Incognito home screen — the "new tab" page for private browsing.
 *
 * <p>Mirrors {@link HomeFragment}'s search/URL bar functionality but:
 * <ul>
 *   <li>Shows the private browsing info card instead of shortcuts/onboarding</li>
 *   <li>Uses {@link IncognitoStateViewModel} for tab management</li>
 *   <li>Applies incognito purple theme to all surfaces</li>
 *   <li>Does not show bookmarks, history shortcuts, or personalized content</li>
 *   <li>Autocomplete search results do NOT include tab suggestions from regular tabs</li>
 * </ul>
 */
@AndroidEntryPoint
public class HomeIncognitoFragment extends BaseBrowserFragment implements
        BottomNavigationBar.OnBottomBarListener,
        AutoCompleteEditText.OnCommitListener,
        AutoCompleteEditText.OnFilterListener,
        AutoCompleteEditText.OnFocusChangedListener,
        AutoCompleteEditText.OnTextChangedListener,
        AutoCompleteEditText.OnSearchStateChangeListener,
        GeckoToolbar.OnToolbarListener,
        OnItemClickListener {

    private static final String TAG = HomeIncognitoFragment.class.getSimpleName();

    private IncognitoStateViewModel mIncognitoStateViewModel;
    private BrowserURIViewModel mBrowserURIViewModel;
    private BrowserDialogViewModel mBrowserDialogViewModel;
    private AutoCompleteEditText mAutoCompleteEditText;
    private AutoCompleteView mAutoCompleteView;
    private GeckoToolbar mGeckoToolbar;
    private BottomNavigationBar mBottomNavigationBar;
    private View mNewTabView;
    private View mHomeScroll;


    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mBrowserDialogViewModel = new ViewModelProvider(mActivity).get(BrowserDialogViewModel.class);
        mAutoCompleteViewModel = new ViewModelProvider(this).get(AutoCompleteViewModel.class);
        mIncognitoStateViewModel = new ViewModelProvider(mActivity).get(IncognitoStateViewModel.class);
        mGeckoStateViewModel = new ViewModelProvider(mActivity).get(GeckoStateViewModel.class);
        mBrowserURIViewModel = new ViewModelProvider(mActivity).get(BrowserURIViewModel.class);

        mActivity.getOnBackPressedDispatcher().addCallback(this,
                new OnBackPressedCallback(true) {
                    @Override
                    public void handleOnBackPressed() {
                        if (dismissAutocompleteOverlayIfVisible()) return;
                        // Default back from incognito home pops to regular home.
                        setEnabled(false);
                        mActivity.getOnBackPressedDispatcher().onBackPressed();
                    }
                });
    }

    /**
     * Closes the URL-bar autocomplete overlay if it's currently up, and
     * returns true to short-circuit the back-press handler. Returns
     * false (no-op) when the overlay isn't visible, so the caller can
     * fall through to its default back behavior.
     */
    private boolean dismissAutocompleteOverlayIfVisible() {
        if (mAutoCompleteView == null || mAutoCompleteView.getVisibility() != View.VISIBLE) {
            return false;
        }
        hideKeyboard(mAutoCompleteEditText);
        mGeckoToolbar.clearFocus();
        mGeckoToolbar.startAnimation(false);
        mGeckoToolbar.updateViewVisibility(false);
        mAutoCompleteView.updateVisibility(false);
        return true;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {

        View v = inflater.inflate(R.layout.fragment_home_incognito, container, false);

        mNewTabView = v.findViewById(R.id.bottom_new_tab);

        mHomeScroll = v.findViewById(R.id.home_scroll);

        mAutoCompleteView = v.findViewById(R.id.auto_complete_view);
        mBottomNavigationBar = v.findViewById(R.id.bottom_app_bar);
        mBottomNavigationBar.setListener(this);

        // Bookmarks is the flat middle-slot button in the bottom bar
        // (see onBottomBarButtonClick's R.id.search_button branch). The
        // former hero FAB was removed in favour of this plain in-bar
        // affordance.

        mGeckoToolbar = v.findViewById(R.id.toolbar_layout);
        mGeckoToolbar.setListener(this);

        // Same as HomeFragment: the overlay opens under the toolbar's bottom
        // (AutoCompleteViewBehavior, installed in code); the pill IS the
        // toolbar, in the browser's exact rect, and never moves (frame N2).
        CoordinatorLayout.LayoutParams overlayParams =
                (CoordinatorLayout.LayoutParams) mAutoCompleteView.getLayoutParams();
        overlayParams.setBehavior(new AutoCompleteViewBehavior(
                mAutoCompleteView.getContext(), null, mAutoCompleteView));

        mAutoCompleteEditText = mGeckoToolbar.getAutoCompleteEditText();
        mAutoCompleteEditText.setOnTextChangedListener(this);
        mAutoCompleteEditText.setOnCommitListener(this);
        mAutoCompleteEditText.setOnSearchStateChangeListener(this);
        mAutoCompleteEditText.setOnFilterListener(this);
        mAutoCompleteEditText.setOnFocusChangeListener(this);

        mSearchAutocompleteAdapter = new SearchAutocompleteAdapter(mActivity, new SearchDiffCallback(), this);
        mAutoCompleteView.getRecyclerView().setAdapter(mSearchAutocompleteAdapter);

        mAutoCompleteView.setClipboardCallback(new AutoCompleteView.OnClipboardListener() {
            @Override
            public void onClipboardClick(CharSequence text) {
                if (!TextUtils.isEmpty(text)) {
                    String uri = mSearchRepository.parseUri(text.toString());
                    openUri(uri);
                }
            }

            @Override
            public void onClipboardLongClick(CharSequence text) { }
        });

        return v;
    }

    @Nullable
    @Override
    public Snackbar makeScreenSnackbar(@NonNull String text) {
        if (mBottomNavigationBar == null) {
            return null;
        }
        Snackbar snackbar = makeSnackbar(mBottomNavigationBar, text, true);
        snackbar.setAnchorView(mBottomNavigationBar);
        return snackbar;
    }

    @Override
    public void onResume() {
        super.onResume();
        // An add-on's new tab or popup opens in the mode on screen.
        mAddonManager.setBrowsingPrivate(true);
        // Back on the home screen from a page (no activity resume): the
        // update sheet may open here (UpdatePromptPolicy). Posted, behind the
        // activity's own resume check, which it then finds by tag.
        View root = getView();
        if (root != null) {
            root.post(() -> {
                if (isResumed() && getActivity() instanceof BaseActivity) {
                    ((BaseActivity) getActivity()).offerUpdateHere();
                }
            });
        }
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        observeAddonAttention(mBottomNavigationBar);

        Log.d(TAG, "onViewCreated");

        // Observe incognito tab count for the bottom bar badge.
        // If all incognito tabs are closed externally (e.g. via the
        // notification "Close all" action), navigate to regular home.
        // The ⋮ dot while an update is ready (its "Update ready" row).
        new UpdateReadyLiveData(requireContext()).observe(getViewLifecycleOwner(),
                ready -> mBottomNavigationBar.setUpdateReady(Boolean.TRUE.equals(ready)));
        mIncognitoStateViewModel.getTabsCount().observe(getViewLifecycleOwner(), count -> {
            mBottomNavigationBar.onTabsCount(count);
            if (count == 0) {
                Log.d(TAG, "All incognito tabs closed externally, navigating to regular home");
                resetWindowTheme();
                NavigationUtils.navigateSafe(mNavController, R.id.action_home_incognito_to_home);
            }
        });

        mAutoCompleteViewModel.setIncognito(true);

        // Autocomplete observers
        mAutoCompleteViewModel.getAutoComplete().observe(getViewLifecycleOwner(), result -> {
            if (TextUtils.isEmpty(result))
                mAutoCompleteEditText.noAutocompleteResult();
            else
                mAutoCompleteEditText.applyAutocompleteResult(result);
        });

        mAutoCompleteViewModel.getWebSearch().observe(getViewLifecycleOwner(), results -> {
            if (results == null || results.isEmpty()) {
                mAutoCompleteView.showEmpty();
            } else {
                mAutoCompleteView.hideAll();
            }
            mSearchAutocompleteAdapter.submitList(results);
        });

        // Clear text on resume
        mAutoCompleteEditText.getViewTreeObserver().addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
            @Override
            public boolean onPreDraw() {
                mAutoCompleteEditText.getViewTreeObserver().removeOnPreDrawListener(this);
                if(mGeckoToolbar != null)
                    mGeckoToolbar.clearText();
                return true;
            }
        });

        ViewCompat.setOnApplyWindowInsetsListener(mHomeScroll, (v, windowInsets) -> {
            Insets insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            // Top AND bottom insets as padding — the toolbar floats over the
            // content, nothing reserves the top (see HomeFragment).
            v.setPadding(insets.left, insets.top, insets.right, insets.bottom);
            return WindowInsetsCompat.CONSUMED;
        });

        // Window insets for the toolbar: the status inset as a top MARGIN,
        // never padding — the box must be the browser's plain 64dp bar, see
        // the matching listener in HomeFragment for the why.
        ViewCompat.setOnApplyWindowInsetsListener(mGeckoToolbar, (v, windowInsets) -> {
            Insets insets = windowInsets.getInsets(
                    WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            ViewGroup.MarginLayoutParams params =
                    (ViewGroup.MarginLayoutParams) v.getLayoutParams();
            if (params.topMargin != insets.top) {
                params.topMargin = insets.top;
                v.setLayoutParams(params);
            }
            v.setPadding(insets.left, 0, insets.right, 0);
            return WindowInsetsCompat.CONSUMED;
        });

        ViewCompat.setOnApplyWindowInsetsListener(mAutoCompleteView, (v, windowInsets) -> {
            Insets insets = windowInsets.getInsets(WindowInsetsCompat.Type.displayCutout());
            v.setPadding(insets.left, 0, insets.right, 0);
            return WindowInsetsCompat.CONSUMED;
        });


        mBrowserDialogViewModel.getOptionsEvent().observe(getViewLifecycleOwner(), mOptionEntity -> {

            int id = mOptionEntity.getId();

            if(id == R.id.action_download){
                DownloadRequest request = mOptionEntity.getDownloadRequest();
                if (request != null) {
                    startDownload(request, mBottomNavigationBar, R.id.anchor_view);
                }
            } else if(id == R.id.action_delete_clipboard){
                mAutoCompleteView.hideClipboard();
            } else if (id == R.id.new_tab) {
                // New tab from incognito home → open a REGULAR tab and leave
                // private browsing for the regular home (mirrors HomeFragment's
                // new_incognito_tab in reverse).
                GeckoState geckoState = new GeckoState(new GeckoStateEntity(true));
                mGeckoStateViewModel.setGeckoState(geckoState, true);
                NavigationUtils.navigateSafe(mNavController, R.id.action_home_incognito_to_home);
            } else if (id == R.id.new_incognito_tab) {
                flashNewTab(mNewTabView);
                addNewIncognitoTab();
            } else if (id == R.id.popup_downloads) {
                Intent downloadsIntent = new Intent(mActivity, DownloadsActivity.class);
                mStartForResult.launch(downloadsIntent);
            } else if (id == R.id.popup_history) {
                // Opens the saved regular-session history read-only (incognito
                // records none), with the incognito flag so the list paints in
                // incognito tones and entries open incognito tabs — same
                // treatment as the bookmarks entry.
                Bundle args = new Bundle();
                args.putBoolean(Keys.IS_INCOGNITO, true);
                NavigationUtils.navigateSafe(mNavController,
                        R.id.action_home_incognito_to_history, args);
            } else if (id == R.id.popup_settings) {
                Intent settingsIntent = new Intent(mActivity, SettingsActivity.class);
                mStartForResult.launch(settingsIntent);
            } else if (id == R.id.addon_actions_manage) {
                Intent addonsIntent = new Intent(mActivity, SettingsActivity.class);
                addonsIntent.putExtra(SettingsActivity.EXTRA_OPEN_ADDONS, true);
                mStartForResult.launch(addonsIntent);
            } else if (id == R.id.popup_quit) {
                quitApp();
            }


        });

        // Apply incognito theme to system bars
        applyIncognitoSystemBars();
        mBottomNavigationBar.updateTheme(mActivity,true);
        // Flat bar (Firefox-parity): bar is now incognito SURFACE, so the
        // scrim under the system-nav strip matches at surface too (the bar's
        // top hairline divides it). See BaseFocusFragment.setNavScrimColor.
        setNavScrimColor(IncognitoColors.getSurface(mActivity, true));
        // Same chrome spec as normal Home: quiet top / flat-surface bottom,
        // mirrored on the window layer for Android <= 14.
        paintSystemBars(
                IncognitoColors.getSurface(mActivity, true),
                IncognitoColors.getSurface(mActivity, true));
        // tonalHolder=false: quiet top, toolbar merges with the canvas
        // (same as normal Home — see GeckoToolbar.updateTheme javadoc).
        mGeckoToolbar.updateTheme(mActivity, true, false);
        mAutoCompleteView.updateTheme(mActivity, true);
        mSearchAutocompleteAdapter.setIncognito(true);

        // Lifecycle observer for GeckoObserverRegistry
        getViewLifecycleOwner().getLifecycle().addObserver((LifecycleEventObserver) (source, event) -> {
            if (Lifecycle.Event.ON_CREATE.equals(event)) {
                mGeckoObserverRegistry.register(HomeIncognitoFragment.this);
            } else if (Lifecycle.Event.ON_PAUSE.equals(event) || Lifecycle.Event.ON_STOP.equals(event)) {
                mStop = true;
            } else if (Lifecycle.Event.ON_RESUME.equals(event)) {
                mStop = false;
            } else if (Lifecycle.Event.ON_DESTROY.equals(event)) {
                mGeckoObserverRegistry.unregister(HomeIncognitoFragment.this);
            }
        });
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        mHomeScroll = null;
        mNewTabView = null;
        mAutoCompleteView = null;
        mGeckoToolbar = null;
        mBottomNavigationBar = null;
    }

    // ── Incognito system bars ───────────────────────────────────────

    private void applyIncognitoSystemBars() {
        if (mActivity == null) return;

        Window window = mActivity.getWindow();
        window.getDecorView().setBackgroundColor(IncognitoColors.getSurface(mActivity, true));

        WindowInsetsControllerCompat insetsController =
                WindowCompat.getInsetsController(window, window.getDecorView());
        insetsController.setAppearanceLightStatusBars(false);
        insetsController.setAppearanceLightNavigationBars(false);
    }

    // ── Bottom bar callbacks ────────────────────────────────────────

    @Override
    public void onBottomBarButtonClick(View v, int id) {
        if (id == R.id.more_button) {
            // Could open a simplified incognito menu or the same popup
            Bundle bundle = new Bundle();
            bundle.putBoolean(Keys.IS_INCOGNITO, true);
            NavigationUtils.navigateSafe(mNavController, R.id.dialog_home_popup, R.id.home_incognito, bundle);
        } else if (id == R.id.tab_button) {
            Bundle bundle = new Bundle();
            bundle.putBoolean(Keys.OPEN_INCOGNITO, true);
            NavigationUtils.navigateSafe(mNavController, R.id.tabs, R.id.home_incognito, bundle);
        } else if (id == R.id.new_tab_button) {
            flashNewTab(mNewTabView);
            addNewIncognitoTab();
        } else if (id == R.id.downloads_button) {
            Intent downloadsIntent = new Intent(mActivity, VaultActivity.class);
            mStartForResult.launch(downloadsIntent);
        } else if (id == R.id.search_button) {
            // The middle slot is the flat Bookmarks button. Pass the
            // incognito flag through so the list paints in incognito
            // tones and tapping an entry opens an incognito tab.
            Bundle args = new Bundle();
            args.putBoolean(Keys.IS_INCOGNITO, true);
            NavigationUtils.navigateSafe(mNavController, R.id.action_home_incognito_to_bookmarks, args);
        }
    }

    // ── Autocomplete callbacks ──────────────────────────────────────

    @Override
    public void onCommit() {
        Editable editable = mAutoCompleteEditText.getText();
        if (editable != null) {
            String text = editable.toString();
            if (!TextUtils.isEmpty(text)) {
                openUri(text);
            }
        }
    }

    @Override
    public void onRefreshAutoComplete(String text) { }

    @Override
    public void onFocusChanged(boolean hasFocus) {
        // Nothing moves on focus (frame N2, as HomeFragment): the pill
        // changes colour and corner in place and the suggestions fade in
        // under it on the same clock; a blur is a plain cut.
        mGeckoToolbar.updateViewVisibility(hasFocus);
        mGeckoToolbar.setAutoCompleteVisible(hasFocus);
        mGeckoToolbar.startAnimation(hasFocus);
        mAutoCompleteViewModel.resetEngines();
        mAutoCompleteView.showEmpty();
        if (hasFocus) {
            mAutoCompleteView.showFadingIn(GeckoToolbar.FOCUS_ANIMATION_MS);
        } else {
            mAutoCompleteView.updateVisibility(false);
        }
    }

    @Override
    public void onTextChanged(String afterText, String currentText) {
        if (TextUtils.isEmpty(afterText)) {
            mAutoCompleteViewModel.resetEngines();
            mAutoCompleteView.showEmpty();
        }
        mAutoCompleteViewModel.search(afterText);
    }

    @Override
    public void onSearchStateChanged(boolean hasFocus) {
        mGeckoToolbar.updateSearchView(hasFocus);
    }

    // ── Toolbar callbacks ───────────────────────────────────────────

    @Override
    public void onToolbarButtonClick(View v, int id) {
        if (id == R.id.clear_button) {
            mAutoCompleteViewModel.resetEngines();
            mAutoCompleteView.showEmpty();
            mGeckoToolbar.clearText();
        } else if (id == R.id.security_button) {
            Bundle bundle = new Bundle();
            bundle.putBoolean(Keys.IS_INCOGNITO, true);
            NavigationUtils.navigateSafe(mNavController, R.id.dialog_search_engine, R.id.home_incognito, bundle);
        }
    }

    @Override
    public void onToolbarKey(int keyCode, KeyEvent event) { }

    // ── Item click (autocomplete results) ───────────────────────────

    @Override
    public void onItemClick(int position, int resId) {
        if (position == RecyclerView.NO_POSITION) return;

        if (resId == R.id.item_search) {
            AutoCompleteEntity searchEntity = mSearchAutocompleteAdapter.getCurrentList().get(position);
            int type = searchEntity.getType();
            if (type == AutoCompleteEntity.TAB) {
                // For incognito, we only handle incognito tab switches
                int sessionId = searchEntity.getSessionId();
                GeckoState geckoState = mIncognitoStateViewModel.getGeckoState(sessionId);
                if (geckoState != null) {
                    mIncognitoStateViewModel.setGeckoState(geckoState, true);
                    GeckoStateEntity entity = geckoState.getGeckoStateEntity();
                    mBrowserURIViewModel.onEventSelected(entity, IntentActions.OPEN_SESSION);
                    NavigationUtils.navigateToBrowser(mNavController, true);
                }
            } else {
                String text = mSearchRepository.parseUri(searchEntity.getSubText());
                openUri(text);
            }
        }
    }

    @Override
    public void onLongClick(int position, int resId) {
        if (position == RecyclerView.NO_POSITION) return;

        if (resId == R.id.item_search) {
            AutoCompleteEntity searchEntity = mSearchAutocompleteAdapter.getCurrentList().get(position);
            String uri = mSearchRepository.parseUri(searchEntity.getSubText());
            openUri(uri);
            mAutoCompleteViewModel.clearClipboard();
        }
    }

    @Override
    public void onItemVariantClick(int position, int variant, int resId) { }

    // ── Navigation helpers ──────────────────────────────────────────

    private void openUri(String text) {
        // Format here, not downstream. BrowserFragment.setGeckoViewSession
        // only runs parseUri when opening a brand-new GeckoSession, so a
        // toolbar commit that lands on an already-open session would
        // otherwise pass the raw query straight to loadUri.
        String url = mSearchRepository.parseUri(text);
        Log.d(TAG, "openUri: url=" + url);
        GeckoState geckoState = mIncognitoStateViewModel.getCurrentGeckoState();
        GeckoStateEntity geckoStateEntity = geckoState.getGeckoStateEntity();
        Log.d(TAG, "openUri: using geckoState id=" + geckoStateEntity.getId()
                + " wasHome=" + geckoStateEntity.isHome());
        geckoStateEntity.setHome(false);
        geckoStateEntity.setUri(url);
        // Parity with HomeFragment.openUri: clear any persisted SessionState
        // before publishing OPEN_URI so a typed URL isn't silently overridden
        // by restoreState's auto-nav to the entity's previous URL. Today
        // incognito getOrCreateGeckoSession skips restoreState entirely
        // (GeckoState.java) so this is a no-op in practice — kept for
        // symmetry so the two home flows can't diverge.
        geckoStateEntity.setSessionState("");
        mBrowserURIViewModel.onEventSelected(geckoStateEntity, IntentActions.OPEN_URI);
        NavigationUtils.navigateToBrowser(mNavController, true);
    }

    private void addNewIncognitoTab() {
        GeckoStateEntity entity = new GeckoStateEntity(true);
        entity.setIncognito(true);
        GeckoState geckoState = new GeckoState(entity);
        Log.d(TAG, "addNewIncognitoTab: created incognito home tab id=" + geckoState.getEntityId());
        mIncognitoStateViewModel.setGeckoState(geckoState, true);
    }
}