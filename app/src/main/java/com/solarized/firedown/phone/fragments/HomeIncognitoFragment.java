package com.solarized.firedown.phone.fragments;

import android.content.Context;
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
import android.view.inputmethod.InputMethodManager;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleEventObserver;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.RecyclerView;

import com.solarized.firedown.IntentActions;
import com.solarized.firedown.Keys;
import com.solarized.firedown.R;
import com.solarized.firedown.autocomplete.AutoCompleteEditText;
import com.solarized.firedown.autocomplete.AutoCompleteView;
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
import com.solarized.firedown.phone.BookmarkActivity;
import com.solarized.firedown.phone.DownloadsActivity;
import com.solarized.firedown.phone.HistoryActivity;
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
                        if (mAutoCompleteView.getVisibility() == View.VISIBLE) {
                            hideKeyboard(mAutoCompleteEditText);
                            mGeckoToolbar.clearFocus();
                            mGeckoToolbar.startAnimation(false);
                            mGeckoToolbar.updateViewVisibility(false);
                            mAutoCompleteView.updateVisibility(false);
                        } else {
                            // Go back to regular home
                            setEnabled(false);
                            mActivity.getOnBackPressedDispatcher().onBackPressed();
                        }
                    }
                });
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {

        View v = inflater.inflate(R.layout.fragment_home_incognito, container, false);

        mNewTabView = v.findViewById(R.id.bottom_new_tab);


        mAutoCompleteView = v.findViewById(R.id.auto_complete_view);
        mBottomNavigationBar = v.findViewById(R.id.bottom_app_bar);
        mBottomNavigationBar.setListener(this);


        mGeckoToolbar = v.findViewById(R.id.toolbar_layout);
        mGeckoToolbar.setListener(this);

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

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        Log.d(TAG, "onViewCreated");

        // Observe incognito tab count for the bottom bar badge.
        // If all incognito tabs are closed externally (e.g. via the
        // notification "Close all" action), navigate to regular home.
        mIncognitoStateViewModel.getTabsCount().observe(getViewLifecycleOwner(), count -> {
            mBottomNavigationBar.onTabsCount(count);
            if (count != null && count == 0) {
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

        // Window insets for toolbar
        ViewCompat.setOnApplyWindowInsetsListener(mGeckoToolbar, (v, windowInsets) -> {
            Insets insets = windowInsets.getInsets(
                    WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            v.setPadding(insets.left, insets.top, insets.right, 0);
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
            } else if (id == R.drawable.download_24) {
                Intent vaultIntent = new Intent(mActivity, DownloadsActivity.class);
                mStartForResult.launch(vaultIntent);
            } else if (id == R.drawable.ic_bookmarks_24) {
                Intent bookmarksIntent = new Intent(mActivity, BookmarkActivity.class);
                mStartForResult.launch(bookmarksIntent);
            } else if (id == R.drawable.ic_history_24) {
                Intent historyIntent = new Intent(mActivity, HistoryActivity.class);
                mStartForResult.launch(historyIntent);
            } else if(id == R.drawable.ic_baseline_settings_24 || id == R.drawable.ic_settings_24){
                Intent settingsIntent = new Intent(mActivity, SettingsActivity.class);
                mStartForResult.launch(settingsIntent);
            } else if (id == R.drawable.ic_logout_24) {
                quitApp();
            }


        });

        // Apply incognito theme to system bars
        applyIncognitoSystemBars();
        mBottomNavigationBar.updateTheme(mActivity,true);
        mGeckoToolbar.updateTheme(mActivity, true);
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
            mAutoCompleteViewModel.resetEngines();
            mAutoCompleteView.showEmpty();
            mAutoCompleteEditText.requestFocus();
            InputMethodManager imm = (InputMethodManager) mActivity.getSystemService(Context.INPUT_METHOD_SERVICE);
            imm.showSoftInput(mAutoCompleteEditText, InputMethodManager.SHOW_FORCED);
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
        mGeckoToolbar.updateViewVisibility(hasFocus);
        mGeckoToolbar.setAutoCompleteVisible(hasFocus);
        mGeckoToolbar.startAnimation(hasFocus);
        mAutoCompleteViewModel.resetEngines();
        mAutoCompleteView.showEmpty();
        mAutoCompleteView.updateVisibility(hasFocus);
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
                    NavigationUtils.navigateSafe(mNavController, R.id.browser);
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

    private void openUri(String url) {
        Log.d(TAG, "openUri: url=" + url);
        GeckoState geckoState = mIncognitoStateViewModel.getCurrentGeckoState();
        GeckoStateEntity entity = geckoState.getGeckoStateEntity();
        entity.setHome(false);
        entity.setUri(url);
        mBrowserURIViewModel.onEventSelected(entity, IntentActions.OPEN_URI);
        NavigationUtils.navigateSafe(mNavController, R.id.browser);
    }

    private void addNewIncognitoTab() {
        GeckoStateEntity entity = new GeckoStateEntity(true);
        entity.setIncognito(true);
        GeckoState geckoState = new GeckoState(entity);
        Log.d(TAG, "addNewIncognitoTab: created incognito home tab id=" + geckoState.getEntityId());
        mIncognitoStateViewModel.setGeckoState(geckoState, true);
    }
}