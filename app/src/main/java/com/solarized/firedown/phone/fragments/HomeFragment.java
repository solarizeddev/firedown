package com.solarized.firedown.phone.fragments;

import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextUtils;
import android.util.Log;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.coordinatorlayout.widget.CoordinatorLayout;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleEventObserver;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.solarized.firedown.Keys;
import com.solarized.firedown.Preferences;
import com.solarized.firedown.R;
import com.solarized.firedown.data.Download;
import com.solarized.firedown.data.entity.DownloadEntity;
import com.solarized.firedown.data.entity.GeckoStateEntity;
import com.solarized.firedown.data.entity.AutoCompleteEntity;
import com.solarized.firedown.autocomplete.AutoCompleteViewModel;
import com.solarized.firedown.data.entity.ShortCutsEntity;
import com.solarized.firedown.data.models.BrowserDialogViewModel;
import com.solarized.firedown.data.models.BrowserURIViewModel;
import com.solarized.firedown.data.models.GeckoStateViewModel;
import com.solarized.firedown.data.models.IncognitoStateViewModel;
import com.solarized.firedown.data.models.RecentDownloadsViewModel;
import com.solarized.firedown.data.models.TaskViewModel;
import com.solarized.firedown.data.models.ShortCutsViewModel;
import com.solarized.firedown.geckoview.GeckoResources;
import com.solarized.firedown.geckoview.GeckoState;
import com.solarized.firedown.geckoview.GeckoToolbar;
import com.solarized.firedown.manager.DownloadRequest;

import com.solarized.firedown.phone.BookmarkActivity;
import com.solarized.firedown.phone.DownloadsActivity;
import com.solarized.firedown.phone.HistoryActivity;
import com.solarized.firedown.phone.SettingsActivity;
import com.solarized.firedown.phone.VaultActivity;
import com.solarized.firedown.autocomplete.AutoCompleteEditText;
import com.solarized.firedown.autocomplete.AutoCompleteView;
import com.solarized.firedown.ui.HomeViewpager;
import com.solarized.firedown.ui.OnBoardingCard;
import com.solarized.firedown.ui.adapters.ShortCutsAdapter;
import com.solarized.firedown.ui.diffs.ShortCutsDiffCallback;
import com.solarized.firedown.geckoview.toolbar.BottomNavigationBar;
import com.solarized.firedown.ui.OnItemClickListener;
import com.solarized.firedown.ui.adapters.SearchAutocompleteAdapter;
import com.solarized.firedown.ui.diffs.SearchDiffCallback;
import com.solarized.firedown.IntentActions;
import com.solarized.firedown.utils.NavigationUtils;
import com.solarized.firedown.utils.WebUtils;

import dagger.hilt.android.AndroidEntryPoint;



@AndroidEntryPoint
public class HomeFragment extends BaseBrowserFragment implements BottomNavigationBar.OnBottomBarListener,
        AutoCompleteEditText.OnCommitListener, AutoCompleteEditText.OnFilterListener, AutoCompleteEditText.OnFocusChangedListener,
        AutoCompleteEditText.OnTextChangedListener, AutoCompleteEditText.OnSearchStateChangeListener,
        GeckoToolbar.OnToolbarListener , OnBoardingCard.OnBoardingCardListener, OnItemClickListener {


    private static final String TAG = HomeFragment.class.getName();
    private BrowserURIViewModel mBrowserURIViewModel;
    private BrowserDialogViewModel mBrowserDialogViewModel;
    private ShortCutsViewModel mShortCutsViewModel;
    private GeckoStateViewModel mGeckoStateViewModel;
    private IncognitoStateViewModel mIncognitoStateViewModel;
    private TaskViewModel mTaskViewModel;
    private RecentDownloadsViewModel mRecentDownloadsViewModel;
    private AutoCompleteEditText mAutoCompleteEditText;
    private AutoCompleteView mAutoCompleteView;
    private ShortCutsAdapter mShortCutsAdapter;
    private View mNewTabView;
    private View mHomeScroll;
    private View mRecentDownloadsCard;
    private View mHomeAllDisabled;
    private com.solarized.firedown.ui.adapters.DownloadsQuickAccessAdapter mRecentDownloadsAdapter;
    private android.content.SharedPreferences.OnSharedPreferenceChangeListener mHomePrefsListener;
    @Nullable private java.util.List<DownloadEntity> mLastRecentList;
    private OnBoardingCard mOnBoardingCard;
    private GeckoToolbar mGeckoToolbar;
    private HomeViewpager mHomeViewPager;
    private BottomNavigationBar mBottomNavigationBar;


    @Override
    public void onConfigurationChanged(@NonNull Configuration newConfig) {
        super.onConfigurationChanged(newConfig);

        RecyclerView recyclerView = mHomeViewPager.getRecyclerView();

        GridLayoutManager gridLayoutManager = (GridLayoutManager) recyclerView.getLayoutManager();

        if(gridLayoutManager != null){
            gridLayoutManager.setSpanCount(getResources().getInteger(R.integer.shortcuts_span));
        }

    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        mAutoCompleteViewModel = new ViewModelProvider(this).get(AutoCompleteViewModel.class);
        mShortCutsViewModel = new ViewModelProvider(this).get(ShortCutsViewModel.class);
        mTaskViewModel = new ViewModelProvider(this).get(TaskViewModel.class);
        mRecentDownloadsViewModel = new ViewModelProvider(this).get(RecentDownloadsViewModel.class);
        mGeckoStateViewModel = new ViewModelProvider(mActivity).get(GeckoStateViewModel.class);
        mIncognitoStateViewModel = new ViewModelProvider(mActivity).get(IncognitoStateViewModel.class);
        mBrowserURIViewModel = new ViewModelProvider(mActivity).get(BrowserURIViewModel.class);
        mBrowserDialogViewModel = new ViewModelProvider(mActivity).get(BrowserDialogViewModel.class);


        // This callback will only be called when MyFragment is at least Started.
        OnBackPressedCallback callback = new OnBackPressedCallback(true /* enabled by default */) {
            @Override
            public void handleOnBackPressed() {
                if(mAutoCompleteView.getVisibility() == View.VISIBLE){
                    hideKeyboard(mAutoCompleteEditText);
                    mGeckoToolbar.clearFocus();
                    mGeckoToolbar.startAnimation(false);
                    mGeckoToolbar.updateViewVisibility(false);
                    mAutoCompleteView.updateVisibility(false);
                }else{
                    setEnabled(false);
                    mActivity.getOnBackPressedDispatcher().onBackPressed();
                }
            }
        };

        mActivity.getOnBackPressedDispatcher().addCallback(this, callback);

    }


    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {

        View v = inflater.inflate(R.layout.fragment_home, container, false);

        mHomeViewPager = v.findViewById(R.id.view_pager_holder);
        mNewTabView = v.findViewById(R.id.bottom_new_tab);
        mAutoCompleteView = v.findViewById(R.id.auto_complete_view);
        mOnBoardingCard = v.findViewById(R.id.onboarding);
        mOnBoardingCard.setCallback(this);

        mBottomNavigationBar = v.findViewById(R.id.bottom_app_bar);
        mBottomNavigationBar.setListener(this);

        // The bottom app bar overlays the bottom of the scroll content
        // in this CoordinatorLayout. Setting paddingBottom = bar
        // height after layout is more reliable than computing
        // app_bar_size + window inset by hand — the bar already
        // applies its own inset padding (CONSUMED) so the inset
        // dispatch may not reach the NestedScrollView, leaving the
        // last card clipped behind the bar on 3-button-nav devices.
        // Mirror whatever the bar measures to.
        mHomeScroll = v.findViewById(R.id.home_scroll);
        mBottomNavigationBar.addOnLayoutChangeListener(
                (bar, l, t, r, b, ol, ot, or_, ob) -> {
                    int barHeight = bar.getHeight();
                    if (mHomeScroll != null && mHomeScroll.getPaddingBottom() != barHeight) {
                        mHomeScroll.setPadding(
                                mHomeScroll.getPaddingLeft(),
                                mHomeScroll.getPaddingTop(),
                                mHomeScroll.getPaddingRight(),
                                barHeight);
                    }
                });

        mGeckoToolbar = v.findViewById(R.id.toolbar_layout);
        mGeckoToolbar.setListener(this);

        mNavScrim = v.findViewById(R.id.navigation_scrim);

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
                if(!TextUtils.isEmpty(text)){
                    String uri = mSearchRepository.parseUri(text.toString());
                    openUri(uri);
                }
            }

            @Override
            public void onClipboardLongClick(CharSequence text) {

            }
        });

        mShortCutsAdapter = new ShortCutsAdapter(mActivity, new ShortCutsDiffCallback(), this);
        mHomeViewPager.getRecyclerView().setAdapter(mShortCutsAdapter);

        // Recent-downloads card — same adapter as the quick-access
        // bottom sheet so both surfaces render rows identically; tap
        // routes through the existing openItem flow (handled here in
        // the Host interface impl below).
        mRecentDownloadsCard = v.findViewById(R.id.recent_downloads_card);
        mHomeAllDisabled = v.findViewById(R.id.home_all_disabled);
        androidx.recyclerview.widget.RecyclerView recentRecycler =
                v.findViewById(R.id.recent_downloads_recycler);
        recentRecycler.setItemAnimator(null);
        mRecentDownloadsAdapter =
                new com.solarized.firedown.ui.adapters.DownloadsQuickAccessAdapter(entity -> {
                    if (entity.getFileStatus() == Download.ERROR) {
                        openSourceUrl(entity);
                    } else {
                        openItem(entity, null);
                    }
                });
        recentRecycler.setAdapter(mRecentDownloadsAdapter);
        v.findViewById(R.id.recent_downloads_view_all).setOnClickListener(view ->
                mStartForResult.launch(new Intent(mActivity, DownloadsActivity.class)));

        return v;
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        Log.d(TAG, "onViewCreated");

        // Regular home shows only the regular (non-vault) download
        // count — incognito-tab downloads stay off this badge so the
        // public chrome doesn't advertise private activity.
        mTaskViewModel.getRegularCount().observe(getViewLifecycleOwner(),
                count -> mBottomNavigationBar.onBadgeCount(count));

        mGeckoStateViewModel.getTabsCount().observe(getViewLifecycleOwner(), mObservableEntities
                -> mBottomNavigationBar.onTabsCount(mObservableEntities));

        mAutoCompleteViewModel.setIncognito(false);

        mAutoCompleteViewModel.getAutoComplete().observe(getViewLifecycleOwner(), mObservableResult -> {
            if (TextUtils.isEmpty(mObservableResult))
                mAutoCompleteEditText.noAutocompleteResult();
            else
                mAutoCompleteEditText.applyAutocompleteResult(mObservableResult);
        });

        mAutoCompleteViewModel.getWebSearch().observe(getViewLifecycleOwner(), mObservableWebSearch -> {
            if (mObservableWebSearch == null || mObservableWebSearch.isEmpty()) {
                mAutoCompleteView.showEmpty();
            } else {
                mAutoCompleteView.hideAll();
            }
            Log.d(TAG, "Size :" + (mObservableWebSearch != null ? mObservableWebSearch.size() : 0));
            mSearchAutocompleteAdapter.submitList(mObservableWebSearch);

        });

        mShortCutsViewModel.getShortCuts().observe(getViewLifecycleOwner(), mObservableShortCuts -> {
            boolean empty = mObservableShortCuts == null || mObservableShortCuts.isEmpty();
            // Fresh installs no longer ship with seeded shortcuts, so a brand-
            // new user lands on the empty state until they pin something
            // themselves (either via this CTA or the in-browser
            // 'Add to shortcuts' menu).
            mHomeViewPager.showEmptyState(empty);
            mShortCutsAdapter.submitList(mObservableShortCuts);
        });

        mHomeViewPager.setOnEmptyStateAddClick(v -> openAddShortcut());

        // Keep the recent-downloads LiveData hot so the long-press
        // quick-access popup has a value to read synchronously on
        // first invocation. Room's LiveData stays cold until it has
        // an active observer, so without this the popup's
        // getValue() returns null on first long-press and we fall
        // back to DownloadsActivity instead of showing the popup.
        // Drives the home recent-downloads card AND keeps the LiveData
        // hot for the bottom-bar cradle flame's quick-access sheet —
        // the sheet reads getValue() synchronously on open, so an
        // observer needs to exist somewhere or Room's LiveData stays
        // cold until first observer attach.
        mRecentDownloadsViewModel.getRecent().observe(getViewLifecycleOwner(), list -> {
            mLastRecentList = list;
            mRecentDownloadsAdapter.submitList(list);
            applyHomeCustomisation();
        });

        // Re-run customisation when the user flips a toggle in
        // Settings (Home category). Lifecycle binding via the
        // listener registered in onResume / removed in onPause.
        mHomePrefsListener = (prefs, key) -> {
            if (Preferences.SETTINGS_HOME_SHOW_RECENT_DOWNLOADS.equals(key)
                    || Preferences.SETTINGS_HOME_SHOW_SHORTCUTS.equals(key)) {
                applyHomeCustomisation();
            }
        };
        mSharedPreferences.registerOnSharedPreferenceChangeListener(mHomePrefsListener);
        applyHomeCustomisation();

        // NOTE: HomeFragment intentionally does NOT observe
        // BrowserURIViewModel.getEvents().  IntentHandler owns all tab
        // activation and navigation.  HomeFragment only uses
        // BrowserURIViewModel to *produce* events (openUri, openSessionId)
        // — never to consume them.

        mBrowserDialogViewModel.getOptionsEvent().observe(getViewLifecycleOwner(), mOptionEntity -> {

            int id = mOptionEntity.getId();

            if(id == R.id.action_download){
                DownloadRequest request = mOptionEntity.getDownloadRequest();
                if (request != null) {
                    startDownload(request, mBottomNavigationBar, R.id.anchor_view);
                }
            } else if(id == R.id.action_delete_clipboard){
                mAutoCompleteView.hideClipboard();
            } else if(id == R.id.new_tab){
                flashNewTab(mNewTabView);
                addNewTab();
            } else if(id == R.id.new_incognito_tab){
                GeckoStateEntity entity = new GeckoStateEntity(true);
                entity.setIncognito(true);
                GeckoState geckoState = new GeckoState(entity);
                mIncognitoStateViewModel.setGeckoState(geckoState, true);
                NavigationUtils.navigateSafe(mNavController, R.id.action_home_to_home_incognito);
            } else if (id == R.drawable.ic_lock_24) {
                Intent vaultIntent = new Intent(mActivity, VaultActivity.class);
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

        //Clear text on resume
        mAutoCompleteEditText.getViewTreeObserver().addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
            @Override
            public boolean onPreDraw() {
                mAutoCompleteEditText.getViewTreeObserver().removeOnPreDrawListener(this);
                mGeckoToolbar.clearText();
                return true;
            }
        });

        ViewCompat.setOnApplyWindowInsetsListener(mGeckoToolbar, (v, windowInsets) -> {
            Insets insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            // Apply the insets as padding to the view. Here, set all the dimensions
            // as appropriate to your layout. You can also update the view's margin if
            // more appropriate.
            v.setPadding(insets.left, insets.top, insets.right, 0);

            // Return CONSUMED if you don't want the window insets to keep passing down
            // to descendant views.
            return WindowInsetsCompat.CONSUMED;
        });


        ViewCompat.setOnApplyWindowInsetsListener(mAutoCompleteView, (v, windowInsets) -> {
            Insets insets = windowInsets.getInsets(WindowInsetsCompat.Type.displayCutout());
            // Apply the insets as padding to the view. Here, set all the dimensions
            // as appropriate to your layout. You can also update the view's margin if
            // more appropriate.
            v.setPadding(insets.left, 0, insets.right, 0);

            // Return CONSUMED if you don't want the window insets to keep passing down
            // to descendant views.
            return WindowInsetsCompat.CONSUMED;
        });


        // As addObserver() does not automatically remove the observer, we
        // call removeObserver() manually when the view lifecycle is destroyed
        getViewLifecycleOwner().getLifecycle().addObserver((LifecycleEventObserver) (source, event) -> {
            if (Lifecycle.Event.ON_CREATE.equals(event)) {
                Log.d(TAG, "onCreate");
                mGeckoObserverRegistry.register(HomeFragment.this);
            }  else if (Lifecycle.Event.ON_PAUSE.equals(event) || Lifecycle.Event.ON_STOP.equals(event)) {
                Log.d(TAG, "onPause");
                mStop = true;
            } else if (Lifecycle.Event.ON_RESUME.equals(event)) {
                Log.d(TAG, "onResume");
                mStop = false;
                // Badge count is updated reactively via TaskRepository.getRegularCount()
                // which is already observed in onViewCreated — no need to poll the service.
            }
        });


        // Always ensure normal (non-incognito) theme. When navigating here
        // from HomeIncognitoFragment (e.g. after "Close all" from notification),
        // the system bars and views may still have incognito colors because
        // HomeIncognitoFragment.onDestroyView hasn't run yet. Resetting
        // unconditionally is safe — setting normal colors when already normal
        // is a visual no-op.
        resetWindowTheme();
        mBottomNavigationBar.updateTheme(mActivity, false);
        mGeckoToolbar.updateTheme(mActivity, false);
        mAutoCompleteView.updateTheme(mActivity, false);
        mSearchAutocompleteAdapter.setIncognito(false);


    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (mHomePrefsListener != null && mSharedPreferences != null) {
            mSharedPreferences.unregisterOnSharedPreferenceChangeListener(mHomePrefsListener);
            mHomePrefsListener = null;
        }
        mHomeViewPager = null;
        mAutoCompleteView = null;
        mGeckoToolbar = null;
        mNewTabView = null;
        mBottomNavigationBar = null;
        mOnBoardingCard = null;
        mRecentDownloadsCard = null;
        mHomeAllDisabled = null;
    }

    /**
     * Resolves the two home-customisation toggles (Settings > Home)
     * into card visibilities. When both toggles are OFF the layout
     * collapses to the centred firedown-logo empty state. Called on
     * every relevant pref change and after each LiveData emission of
     * recent downloads (so the card hides immediately when its
     * underlying list goes empty even if the pref is on).
     */
    private void applyHomeCustomisation() {
        if (mRecentDownloadsCard == null || mHomeViewPager == null
                || mHomeAllDisabled == null || mSharedPreferences == null) {
            return;
        }
        boolean showRecent = mSharedPreferences.getBoolean(
                Preferences.SETTINGS_HOME_SHOW_RECENT_DOWNLOADS,
                Preferences.DEFAULT_HOME_SHOW_RECENT_DOWNLOADS);
        boolean showShortcuts = mSharedPreferences.getBoolean(
                Preferences.SETTINGS_HOME_SHOW_SHORTCUTS,
                Preferences.DEFAULT_HOME_SHOW_SHORTCUTS);
        boolean bothOff = !showRecent && !showShortcuts;

        boolean recentHasData = mLastRecentList != null && !mLastRecentList.isEmpty();
        mRecentDownloadsCard.setVisibility(showRecent && recentHasData ? View.VISIBLE : View.GONE);
        mHomeViewPager.setVisibility(showShortcuts ? View.VISIBLE : View.GONE);
        mHomeAllDisabled.setVisibility(bothOff ? View.VISIBLE : View.GONE);

        // 'Both off' = the user has explicitly asked for a blank
        // home. Hide the onboarding card too so the empty-state
        // logo fills the viewport exactly with no other siblings to
        // push content past the visible scroll area. When toggling
        // back, restore onboarding visibility from its own pref so a
        // dismissed onboarding doesn't reappear.
        if (mOnBoardingCard != null) {
            if (bothOff) {
                mOnBoardingCard.setVisibility(View.GONE);
            } else {
                boolean onboardingVisible = mSharedPreferences.getBoolean(
                        Preferences.ONBOARDING_INFO, true);
                mOnBoardingCard.setVisibility(onboardingVisible ? View.VISIBLE : View.GONE);
            }
        }
    }

    @Override
    public void onBottomBarButtonClick(View v, int id) {
        if (id == R.id.more_button) {
            NavigationUtils.navigateSafe(mNavController, R.id.dialog_home_popup, R.id.home);
        } else if(id == R.id.tab_button){
            Bundle args = new Bundle();
            args.putBoolean(Keys.OPEN_INCOGNITO, false);
            NavigationUtils.navigateSafe(mNavController, R.id.tabs, R.id.home, args);
        } else if(id == R.id.downloads_button){
            Intent downloadsIntent = new Intent(mActivity, DownloadsActivity.class);
            mStartForResult.launch(downloadsIntent);
        } else if(id == R.id.new_tab_button){
            flashNewTab(mNewTabView);
            addNewTab();
        } else if(id == R.id.search_button){
            // Centre cradle slot on home opens Bookmarks — promoting
            // it out of the More-menu popup since it's a primary
            // content collection (sits naturally between Tabs and
            // Downloads on the bar so the four collections all have
            // a top-level slot).
            mStartForResult.launch(new Intent(mActivity, BookmarkActivity.class));
        }
    }

    @Override
    public boolean onBottomBarButtonLongClick(View v, int id){
        if (id == R.id.new_tab_button) {
            NavigationUtils.navigateSafe(mNavController, R.id.dialog_new_tabs, R.id.home);
            return true;
        }
        return false;
    }



    @Override
    public void onCommit() {
        Editable editable = mAutoCompleteEditText.getText();
        if(editable != null){
            String text = editable.toString();
            if (!TextUtils.isEmpty(text)) {
                openUri(text);
            }
        }
    }

    @Override
    public void onRefreshAutoComplete(String text) {

    }

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
        if(TextUtils.isEmpty(afterText)){
            mAutoCompleteViewModel.resetEngines();
            mAutoCompleteView.showEmpty();
        }
        mAutoCompleteViewModel.search(afterText);
    }


    @Override
    public void onSearchStateChanged(boolean hasFocus) {
        mGeckoToolbar.updateSearchView(hasFocus);
    }


    private void openUri(String text){
        // Format here, not downstream. BrowserFragment.setGeckoViewSession
        // only runs parseUri when opening a brand-new GeckoSession, so a
        // toolbar commit that lands on an already-open session would
        // otherwise pass the raw query straight to loadUri.
        String url = mSearchRepository.parseUri(text);
        Log.d(TAG, "openUri: url=" + url);
        GeckoState geckoState = mGeckoStateViewModel.getCurrentGeckoState();
        GeckoStateEntity geckoStateEntity = geckoState.getGeckoStateEntity();
        Log.d(TAG, "openUri: using geckoState id=" + geckoStateEntity.getId()
                + " wasHome=" + geckoStateEntity.isHome());
        geckoStateEntity.setHome(false);
        geckoStateEntity.setUri(url);
        mBrowserURIViewModel.onEventSelected(geckoStateEntity, IntentActions.OPEN_URI);
        Log.d(TAG, "openUri: event fired, navigating to browser");
        NavigationUtils.navigateSafe(mNavController, R.id.browser);
    }


    private void openSessionId(int sessionId){
        Log.d(TAG, "openSessionId: sessionId=" + sessionId);
        GeckoState geckoState = mGeckoStateViewModel.getGeckoState(sessionId);
        if (geckoState == null) {
            Log.w(TAG, "openSessionId: GeckoState not found for id=" + sessionId);
            return;
        }
        mGeckoStateViewModel.setGeckoState(geckoState, true);
        GeckoStateEntity geckoStateEntity = geckoState.getGeckoStateEntity();
        Log.d(TAG, "openSessionId: firing OPEN_SESSION for id=" + geckoStateEntity.getId()
                + " uri=" + geckoStateEntity.getUri());
        mBrowserURIViewModel.onEventSelected(geckoStateEntity, IntentActions.OPEN_SESSION);
        NavigationUtils.navigateSafe(mNavController, R.id.browser);
    }


    private void addNewTab() {
        GeckoState geckoState = new GeckoState(new GeckoStateEntity(true));
        Log.d(TAG, "addNewTab: created home tab id=" + geckoState.getEntityId());
        mGeckoStateViewModel.setGeckoState(geckoState, true);
    }

    private void addIncognitoTab() {
        GeckoState geckoState = new GeckoState(new GeckoStateEntity(true));
        geckoState.setEntityIncognito(true);
        Log.d(TAG, "addIncognitoTab: created home tab id=" + geckoState.getEntityId());

        //mGeckoStateViewModel.setGeckoState(geckoState, true);
    }


    @Override
    public void onToolbarButtonClick(View v, int id) {
        if (id == R.id.clear_button) {
            mAutoCompleteViewModel.resetEngines();
            mAutoCompleteView.showEmpty();
            mGeckoToolbar.clearText();
        } else if (id == R.id.security_button) {
            NavigationUtils.navigateSafe(mNavController, R.id.dialog_search_engine, R.id.home);
        }
    }

    @Override
    public void onToolbarKey(int keyCode, KeyEvent event) {

    }

    /**
     * Shared add-shortcut flow used by both the empty-state CTA and
     * the trailing '+' tile in the populated grid. Routes through the
     * limit guard first (same dialog as the in-browser 'Add to
     * shortcuts' menu path) before opening the edit dialog in add
     * mode — id == 0 on the bundled entity flips the dialog's title
     * and save path.
     */
    private void openAddShortcut() {
        if (mShortCutsViewModel.isFull()) {
            Bundle args = new Bundle();
            args.putBoolean(Keys.OPEN_INCOGNITO, false);
            NavigationUtils.navigateSafe(mNavController, R.id.dialog_shortcuts_max, R.id.home, args);
        } else {
            Bundle args = new Bundle();
            args.putParcelable(Keys.ITEM_ID, new ShortCutsEntity());
            NavigationUtils.navigateSafe(mNavController, R.id.dialog_shortcuts_edit, R.id.home, args);
        }
    }

    @Override
    public void onItemClick(int position, int resId) {
        if (position == RecyclerView.NO_POSITION)
            return;
        if (resId == R.id.item_search) {
            AutoCompleteEntity searchEntity = mSearchAutocompleteAdapter.getCurrentList().get(position);
            int type = searchEntity.getType();
            if(type == AutoCompleteEntity.TAB){
                int sessionId = searchEntity.getSessionId();
                openSessionId(sessionId);
            }else{
                String text = mSearchRepository.parseUri(searchEntity.getSubText());
                openUri(text);
            }
        }else if(resId == R.id.item_web_visited || resId == R.id.item_web_visited_holder){
            ShortCutsEntity shortcutsEntity = mShortCutsAdapter.getCurrentList().get(position);
            String url = WebUtils.getSchemeDomainName(shortcutsEntity.getUrl());
            openUri(url);
        } else if (resId == R.id.item_web_visited_add) {
            // Trailing '+' tile in the populated grid — same flow as
            // the empty-state Add CTA: route through the limit guard,
            // then open the edit dialog in add mode.
            openAddShortcut();
        }
    }

    @Override
    public void onLongClick(int position, int resId) {
        if (position == RecyclerView.NO_POSITION)
            return;
        if (resId == R.id.item_search) {
            AutoCompleteEntity searchEntity = mSearchAutocompleteAdapter.getCurrentList().get(position);
            String uri = mSearchRepository.parseUri(searchEntity.getSubText());
            GeckoState geckoState = mGeckoStateViewModel.getCurrentGeckoState();
            geckoState.setEntityUri(uri);
            openUri(uri);
            mAutoCompleteViewModel.clearClipboard();
        }else if(resId == R.id.item_web_visited || resId == R.id.item_web_visited_holder){
            ShortCutsEntity shortcutsEntity =  mShortCutsAdapter.getCurrentList().get(position);
            Bundle bundle = new Bundle();
            bundle.putParcelable(Keys.ITEM_ID, shortcutsEntity);
            NavigationUtils.navigateSafe(mNavController, R.id.dialog_shortcuts_options, bundle);
        }
    }

    @Override
    public void onItemVariantClick(int position, int variant, int resId) {

    }

    @Override
    public void OnBoardingCardClicked(int id) {
        if (id == R.id.onboarding_card) {
            String uri = GeckoResources.createFiredownTab(mActivity);
            openUri(uri);
        } else if (id == R.id.onboarding_remove) {
            mSharedPreferences.edit().putBoolean(Preferences.ONBOARDING_INFO, false).apply();
            mOnBoardingCard.setVisibility(View.GONE);
        }
    }


}