package com.solarized.firedown.phone.fragments;

import android.animation.Animator;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.Intent;
import android.os.Bundle;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.text.Editable;
import android.text.TextUtils;
import android.util.Log;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.TextView;

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
import androidx.recyclerview.widget.RecyclerView;


import com.solarized.firedown.ui.IncognitoColors;
import com.solarized.firedown.Keys;
import com.solarized.firedown.Preferences;
import com.solarized.firedown.R;
import com.solarized.firedown.data.entity.GeckoStateEntity;
import com.solarized.firedown.data.entity.AutoCompleteEntity;
import com.solarized.firedown.autocomplete.AutoCompleteViewModel;
import com.solarized.firedown.data.models.BrowserDialogViewModel;
import com.solarized.firedown.data.models.BrowserURIViewModel;
import com.solarized.firedown.data.models.GeckoStateViewModel;
import com.solarized.firedown.data.models.IncognitoStateViewModel;
import com.solarized.firedown.geckoview.GeckoState;
import com.solarized.firedown.geckoview.GeckoToolbar;
import com.solarized.firedown.geckoview.GeckoUblockHelper;
import com.solarized.firedown.manager.DownloadRequest;

import com.solarized.firedown.phone.DownloadsActivity;
import com.solarized.firedown.phone.SettingsActivity;


import com.solarized.firedown.phone.VaultActivity;
import com.solarized.firedown.autocomplete.AutoCompleteEditText;
import com.solarized.firedown.autocomplete.AutoCompleteView;
import com.solarized.firedown.autocomplete.AutoCompleteViewBehavior;
import com.solarized.firedown.geckoview.toolbar.BottomNavigationBar;
import com.solarized.firedown.ui.OnItemClickListener;
import com.solarized.firedown.ui.adapters.SearchAutocompleteAdapter;
import com.solarized.firedown.ui.diffs.SearchDiffCallback;
import com.solarized.firedown.IntentActions;
import com.solarized.firedown.utils.NavigationUtils;



import java.text.NumberFormat;
import java.util.Locale;

import javax.inject.Inject;

import dagger.hilt.android.AndroidEntryPoint;


@AndroidEntryPoint
public class HomeFragment extends BaseBrowserFragment implements BottomNavigationBar.OnBottomBarListener,
        AutoCompleteEditText.OnCommitListener, AutoCompleteEditText.OnFilterListener, AutoCompleteEditText.OnFocusChangedListener,
        AutoCompleteEditText.OnTextChangedListener, AutoCompleteEditText.OnSearchStateChangeListener,
        GeckoToolbar.OnToolbarListener , OnItemClickListener {


    private static final String TAG = HomeFragment.class.getName();
    private BrowserURIViewModel mBrowserURIViewModel;
    private BrowserDialogViewModel mBrowserDialogViewModel;
    private GeckoStateViewModel mGeckoStateViewModel;
    private IncognitoStateViewModel mIncognitoStateViewModel;
    private AutoCompleteEditText mAutoCompleteEditText;
    private AutoCompleteView mAutoCompleteView;
    private View mNewTabView;
    private GeckoToolbar mGeckoToolbar;
    private BottomNavigationBar mBottomNavigationBar;
    private View mHomeScroll;
    // The home is the address bar and the mark (home v6, frame N2): the
    // search pill in the browser's exact toolbar rect, the flame, the wordmark
    // and the blocked line centred in the page below it — nothing moves on
    // focus. The pill is the REAL GeckoToolbar; the layout file comment holds
    // the composition and its history (the retired HomeToolbarDock, the P1
    // field-in-page build). The one figure is the ad blocker's lifetime count
    // as a bare line under the wordmark (canvas frame H2 — a tonal card read as
    // a second search field and lasted one build), the door to
    // Settings → Privacy → "Trackers and ads blocked"; the "blocked · saved"
    // PAIR that used to sit there is gone — it wrapped onto two rows on 360dp
    // phones in the long locales. Everything else (tabs, downloads badge,
    // vault) lives in the bottom bar / menu — no dashboard.
    private TextView mBlockedText;

    @Inject
    GeckoUblockHelper mGeckoUblockHelper;

    // No cloud state on the home (maintainer, 2026-10-12: "we don't need all
    // the plumbing for backups, downloaded bytes and blocked ads in home").
    // The transfer chip and the deadline card that lived under the mark
    // (home v5) went with their WorkManager observer and status load, and the
    // hosted service they reported on is gone since; a transfer shows as a
    // Backups list row, a lost backup folder on the Backup screen's hero.
    // The brand flame doubles as the live "a download is running" indicator:
    // a soft ember glow that breathes behind the logo while the active+queued
    // count (the same signal as the bottom-bar badge) is > 0, and is GONE
    // otherwise so the idle home is just the wordmark. Identity-as-status, no
    // extra widget — status that's actionable lives on the bottom bar (the
    // badge); the home only gives the calm ambient heartbeat.
    private View mBrandGlow;
    private Animator mBrandGlowAnimator;


    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        mAutoCompleteViewModel = new ViewModelProvider(this).get(AutoCompleteViewModel.class);
        mGeckoStateViewModel = new ViewModelProvider(mActivity).get(GeckoStateViewModel.class);
        mIncognitoStateViewModel = new ViewModelProvider(mActivity).get(IncognitoStateViewModel.class);
        mBrowserURIViewModel = new ViewModelProvider(mActivity).get(BrowserURIViewModel.class);
        mBrowserDialogViewModel = new ViewModelProvider(mActivity).get(BrowserDialogViewModel.class);


        // This callback will only be called when MyFragment is at least Started.
        OnBackPressedCallback callback = new OnBackPressedCallback(true /* enabled by default */) {
            @Override
            public void handleOnBackPressed() {
                if (dismissAutocompleteOverlayIfVisible()) return;
                setEnabled(false);
                mActivity.getOnBackPressedDispatcher().onBackPressed();
            }
        };

        mActivity.getOnBackPressedDispatcher().addCallback(this, callback);

    }

    @Override
    public void onResume() {
        super.onResume();
        // An add-on's new tab or popup opens in the mode on screen.
        mAddonManager.setBrowsingPrivate(false);
        // Being on Home means the user is on the home tab; if the tab list is
        // empty (e.g. just closed the last tab), materialise it so the count and
        // the Tabs screen agree that there's >=1 tab. No-op when a tab exists.
        if (mGeckoStateViewModel != null) {
            mGeckoStateViewModel.ensureHomeTabIfEmpty();
        }
    }

    /**
     * Back handling while the URL-bar autocomplete overlay is up — TWO steps,
     * so a single back never skips one. While the soft keyboard is showing, the
     * first back only LOWERS it (the overlay and the typed query stay); a second
     * back (keyboard already down) dismisses the overlay. Returns true when it
     * consumed the press, false (overlay not visible) so the caller falls
     * through to its default back behavior.
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
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {

        View v = inflater.inflate(R.layout.fragment_home, container, false);

        mNewTabView = v.findViewById(R.id.bottom_new_tab);
        mAutoCompleteView = v.findViewById(R.id.auto_complete_view);

        mHomeScroll = v.findViewById(R.id.home_scroll);
        mBottomNavigationBar = v.findViewById(R.id.bottom_app_bar);


        // The ember glow behind the flame, and the blocked line under the
        // wordmark (bound in onViewCreated; its tap opens the stats screen,
        // which replaces the settings list so Back returns here).
        mBrandGlow = v.findViewById(R.id.home_brand_glow);
        mBlockedText = v.findViewById(R.id.home_blocked_text);
        v.findViewById(R.id.home_blocked_line).setOnClickListener(view -> {
            Intent statsIntent = new Intent(mActivity, SettingsActivity.class);
            statsIntent.putExtra(SettingsActivity.EXTRA_OPEN_BLOCKED_STATS, true);
            mStartForResult.launch(statsIntent);
        });

        mBottomNavigationBar.setListener(this);

        // Bookmarks is the flat middle-slot button in the bottom bar
        // (see onBottomBarButtonClick's R.id.search_button branch); the
        // URL bar at the top covers search. The former hero FAB was
        // removed in favour of this plain in-bar affordance.

        mGeckoToolbar = v.findViewById(R.id.toolbar_layout);
        mGeckoToolbar.setListener(this);

        // The suggestions overlay opens under the toolbar's laid-out bottom
        // (the browser's behavior, installed in code because it needs the
        // view reference). The toolbar sits in the browser's exact rect and
        // never moves (frame N2); on focus the overlay fades in under it —
        // see onFocusChanged.
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

        // Most-visited tile tap → open the URL (same path as the clipboard chip).
        mAutoCompleteView.setMostVisitedClickListener(url -> openUri(url));
        // Long-press a tile → confirm dialog (in AutoCompleteView) → hide the
        // site from the strip (blocklist; history untouched), then refresh.
        mAutoCompleteView.setMostVisitedRemoveListener(
                url -> mAutoCompleteViewModel.hideFromMostVisited(url));

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

        return v;
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        Log.d(TAG, "onViewCreated");

        // Download-count badge on the bottom bar's Downloads button —
        // same source BrowserFragment's regular-mode chrome uses
        // (TaskRepository.getRegularCount keeps vault/incognito
        // downloads out, so private activity never advertises itself
        // here). Home is never incognito-themed (HomeIncognitoFragment
        // is its own fragment), so no mIsIncognitoThemed gate is
        // needed. This badge used to be deliberately omitted while the
        // active-download strip card existed; with that card removed
        // (redundant with both the ongoing download notification and
        // the Downloads entry card), the badge is the one lightweight
        // 'something is downloading' cue left on Home.
        mTaskViewModel.getRegularCount().observe(getViewLifecycleOwner(), count -> {
            mBottomNavigationBar.onBadgeCount(count);
            // Same active+queued signal drives the breathing ember behind the
            // flame: on while something downloads, off (idle wordmark) at 0.
            setBrandGlowActive(count != null && count > 0);
        });

        // Floor the displayed count at 1: while Home is on screen the user IS
        // on the home tab, so the counter must never read 0 (it could after a
        // close-all / close-last, or a race where the home GeckoState isn't in
        // the list yet - the current tab is still at least tab 1).
        mGeckoStateViewModel.getTabsCount().observe(getViewLifecycleOwner(), count
                -> mBottomNavigationBar.onTabsCount(count == null ? 1 : Math.max(1, count)));

        // The one figure on the home: the ad blocker's lifetime count on the
        // bare line under the wordmark (frame H2). Same source and
        // the same two states as the Settings → Privacy row
        // (SettingsFragment.bindBlockedStats): the locale-formatted count, or
        // "Protection active" at 0 — a "0 blocked" reads as broken protection.
        // The LiveData replays the last pushed value, and GeckoUblockHelper
        // seeds it from prefs at boot, so the card is never blank.
        mGeckoUblockHelper.getCumulativeBlockedLive().observe(getViewLifecycleOwner(), blocked -> {
            if (mBlockedText == null) {
                return;
            }
            long n = blocked == null ? 0L : blocked;
            if (n > 0L) {
                mBlockedText.setText(getString(R.string.tracking_status_blocked,
                        NumberFormat.getInstance(Locale.getDefault()).format(n)));
            } else {
                mBlockedText.setText(R.string.trackers_info_idle);
            }
        });

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

        // Empty-focus most-visited strip — its own view, populated here and
        // toggled by showEmpty()/hideAll() (visibility only, no list diff).
        mAutoCompleteViewModel.getMostVisited().observe(getViewLifecycleOwner(),
                list -> mAutoCompleteView.setMostVisited(list));

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
            } else if (id == R.id.popup_history) {
                NavigationUtils.navigateSafe(mNavController, R.id.action_home_to_history);
            } else if (id == R.id.popup_vault) {
                mStartForResult.launch(new Intent(mActivity, VaultActivity.class));
            } else if (id == R.id.popup_sync) {
                Intent syncIntent = new Intent(mActivity, SettingsActivity.class);
                syncIntent.putExtra(SettingsActivity.EXTRA_OPEN_SYNC, true);
                mStartForResult.launch(syncIntent);
            } else if (id == R.id.popup_settings) {
                Intent settingsIntent = new Intent(mActivity, SettingsActivity.class);
                mStartForResult.launch(settingsIntent);
            } else if (id == R.id.popup_quit) {
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


        ViewCompat.setOnApplyWindowInsetsListener(mHomeScroll, (v, windowInsets) -> {
            Insets insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            // Top AND bottom insets as padding: no AppBarLayout reserves the
            // top any more (the toolbar floats over the content, see the
            // layout comment), so the content lays out between the status
            // strip and the bottom bar.
            v.setPadding(insets.left, insets.top, insets.right, insets.bottom);
            return WindowInsetsCompat.CONSUMED;
        });

        ViewCompat.setOnApplyWindowInsetsListener(mGeckoToolbar, (v, windowInsets) -> {
            Insets insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            // The status inset is a top MARGIN here, never padding: with a
            // margin the box is a plain 64dp bar laid out at y = inset — the
            // browser's root-padded toolbar rect exactly, which is what makes
            // the commit hand-off to the browser a seamless cut and what the
            // content's address_bar_height top padding reserves.
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
            } else if (Lifecycle.Event.ON_DESTROY.equals(event)) {
                // The registry holds STRONG references (see GeckoObserverRegistry):
                // without this, every recreated HomeFragment leaks its predecessor
                // (fragment + view tree) and the stale instance keeps receiving
                // callbacks. Mirrors HomeIncognitoFragment/BrowserFragment.
                mGeckoObserverRegistry.unregister(HomeFragment.this);
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
        // Flat bar (Firefox-parity): the bar is now SURFACE, so the scrim
        // under the system-nav strip matches at surface too (its top hairline
        // provides the division, not a tonal step). See
        // BaseFocusFragment.setNavScrimColor.
        setNavScrimColor(IncognitoColors.getSurface(mActivity, false));
        // Home chrome: QUIET top (toolbar = surface, merging with the
        // canvas — tonalHolder=false) and now a flat-surface bottom too, so
        // the whole window is one surface tone. paintSystemBars mirrors that
        // on the WINDOW layer for Android <= 14, where the theme's opaque bar
        // attrs would otherwise paint the strips.
        mGeckoToolbar.updateTheme(mActivity, false, false);
        paintSystemBars(
                IncognitoColors.getSurface(mActivity, false),
                IncognitoColors.getSurface(mActivity, false));
        mAutoCompleteView.updateTheme(mActivity, false);
        mSearchAutocompleteAdapter.setIncognito(false);


    }

    /**
     * Shows/hides the breathing ember behind the flame. ON while a download is
     * active (a soft, constant "something's happening" pulse — deliberately NOT
     * progress-linked: how-far belongs to the bottom bar, the home only says
     * whether); OFF returns the home to the plain wordmark. The glow is sized
     * to the flame and grown into a halo purely by the SCALE transform, so it
     * costs no layout and bleeds past its box (wrappers clipChildren="false").
     */
    private void setBrandGlowActive(boolean active) {
        if (mBrandGlow == null) return;
        if (active) {
            mBrandGlow.setVisibility(View.VISIBLE);
            if (mBrandGlowAnimator == null) {
                mBrandGlowAnimator = buildBrandGlowAnimator(mBrandGlow);
            }
            if (!mBrandGlowAnimator.isStarted()) {
                mBrandGlowAnimator.start();
            }
        } else {
            if (mBrandGlowAnimator != null) {
                mBrandGlowAnimator.cancel();
            }
            mBrandGlow.setVisibility(View.GONE);
            mBrandGlow.setAlpha(0f);
        }
    }

    /**
     * A slow alpha+scale breath, infinite and reversing, on the ember view.
     * Soft on purpose (never fully dies, never flares) so it reads as a calm
     * heartbeat rather than a notification blink; the scale carries the halo
     * out beyond the flame.
     */
    private static Animator buildBrandGlowAnimator(View v) {
        v.setScaleX(1.3f);
        v.setScaleY(1.3f);
        ObjectAnimator alpha = ObjectAnimator.ofFloat(v, View.ALPHA, 0.15f, 0.42f);
        ObjectAnimator scaleX = ObjectAnimator.ofFloat(v, View.SCALE_X, 1.3f, 1.6f);
        ObjectAnimator scaleY = ObjectAnimator.ofFloat(v, View.SCALE_Y, 1.3f, 1.6f);
        ObjectAnimator[] parts = {alpha, scaleX, scaleY};
        for (ObjectAnimator part : parts) {
            part.setRepeatCount(ValueAnimator.INFINITE);
            part.setRepeatMode(ValueAnimator.REVERSE);
        }
        AnimatorSet set = new AnimatorSet();
        set.setDuration(2200);
        set.setInterpolator(new AccelerateDecelerateInterpolator());
        set.playTogether(alpha, scaleX, scaleY);
        return set;
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (mBrandGlowAnimator != null) {
            mBrandGlowAnimator.cancel();
            mBrandGlowAnimator = null;
        }
        mBlockedText = null;
        mBrandGlow = null;
        mHomeScroll = null;
        mAutoCompleteView = null;
        mGeckoToolbar = null;
        mNewTabView = null;
        mBottomNavigationBar = null;
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
        } else if (id == R.id.search_button) {
            // The middle slot is the flat Bookmarks button.
            NavigationUtils.navigateSafe(mNavController, R.id.action_home_to_bookmarks);
        }
    }

    @Override
    public boolean onBottomBarButtonLongClick(View v, int id){
        if (id == R.id.new_tab_button) {
            NavigationUtils.navigateSafe(mNavController, R.id.dialog_new_tabs, R.id.home);
            return true;
        }
        // Home intentionally has no other long-press affordances —
        // every bottom-bar slot already has a visible-on-home
        // entry (Downloads card, Safe Folder card, the Bookmarks
        // hero FAB). Hidden long-press gestures were the right call
        // when the slot had no on-screen surface (BrowserFragment
        // keeps the Downloads long-press sheet for that reason),
        // not here.
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
        // Nothing moves on focus (frame N2): the pill changes colour and
        // corner in place (startAnimation below) and the suggestions fade in
        // under it on the same clock; every blur path (back, outside tap,
        // commit) ends here with a plain cut.
        mGeckoToolbar.updateViewVisibility(hasFocus);
        mGeckoToolbar.setAutoCompleteVisible(hasFocus);
        mGeckoToolbar.startAnimation(hasFocus);
        // On focus with an empty field, load the most-visited tiles (the strip
        // observer fills its own view; showEmpty() reveals it once it has tiles);
        // on blur just clear. showEmpty() paints the clipboard chip immediately.
        if (hasFocus) {
            mAutoCompleteViewModel.loadMostVisited();
        } else {
            mAutoCompleteViewModel.resetEngines();
        }
        mAutoCompleteView.showEmpty();
        if (hasFocus) {
            mAutoCompleteView.showFadingIn(GeckoToolbar.FOCUS_ANIMATION_MS);
        } else {
            mAutoCompleteView.updateVisibility(false);
        }
    }

    @Override
    public void onTextChanged(String afterText, String currentText) {
        if(TextUtils.isEmpty(afterText)){
            mAutoCompleteViewModel.loadMostVisited();
            mAutoCompleteView.showEmpty();
        } else {
            mAutoCompleteViewModel.search(afterText);
        }
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
        // If this entity was previously a visited tab (now navigated back to
        // home in-process), it may still carry the serialized SessionState of
        // its last URL. Without clearing, BrowserFragment.setGeckoViewSession
        // would take the hasRestoredState branch and let restoreState
        // navigate to the OLD URL instead of the URL the user just typed.
        geckoStateEntity.setSessionState("");
        mBrowserURIViewModel.onEventSelected(geckoStateEntity, IntentActions.OPEN_URI);
        Log.d(TAG, "openUri: event fired, navigating to browser");
        NavigationUtils.navigateToBrowser(mNavController, false);
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
        NavigationUtils.navigateToBrowser(mNavController, false);
    }


    private void addNewTab() {
        GeckoState geckoState = new GeckoState(new GeckoStateEntity(true));
        Log.d(TAG, "addNewTab: created home tab id=" + geckoState.getEntityId());
        mGeckoStateViewModel.setGeckoState(geckoState, true);
    }


    @Override
    public void onToolbarButtonClick(View v, int id) {
        if (id == R.id.clear_button) {
            // Field is now empty again → most-visited strip (its observer fills
            // the strip; showEmpty reveals it when it has tiles).
            mAutoCompleteViewModel.loadMostVisited();
            mAutoCompleteView.showEmpty();
            mGeckoToolbar.clearText();
        }else if (id == R.id.security_button) {
            NavigationUtils.navigateSafe(mNavController, R.id.dialog_search_engine, R.id.home);
        }
    }

    @Override
    public void onToolbarKey(int keyCode, KeyEvent event) {

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
        }
    }

    @Override
    public void onItemVariantClick(int position, int variant, int resId) {

    }


}