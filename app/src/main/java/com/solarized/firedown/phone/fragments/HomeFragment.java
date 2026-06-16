package com.solarized.firedown.phone.fragments;

import android.content.Context;
import android.content.Intent;
import android.icu.text.CompactDecimalFormat;
import android.os.Bundle;
import android.text.Editable;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.RelativeSizeSpan;
import android.util.Log;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.inputmethod.InputMethodManager;
import android.widget.TextView;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
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
import com.solarized.firedown.geckoview.GeckoUblockHelper;
import com.solarized.firedown.data.entity.GeckoStateEntity;
import com.solarized.firedown.data.entity.AutoCompleteEntity;
import com.solarized.firedown.autocomplete.AutoCompleteViewModel;
import com.solarized.firedown.data.models.BrowserDialogViewModel;
import com.solarized.firedown.data.models.BrowserURIViewModel;
import com.solarized.firedown.data.models.GeckoStateViewModel;
import com.solarized.firedown.data.models.IncognitoStateViewModel;
import com.solarized.firedown.data.models.RecentDownloadsViewModel;
import com.solarized.firedown.geckoview.GeckoState;
import com.solarized.firedown.geckoview.GeckoToolbar;
import com.solarized.firedown.manager.DownloadRequest;

import com.solarized.firedown.phone.DownloadsActivity;
import com.solarized.firedown.phone.SettingsActivity;
import com.solarized.firedown.phone.VaultActivity;
import com.solarized.firedown.autocomplete.AutoCompleteEditText;
import com.solarized.firedown.autocomplete.AutoCompleteView;
import com.solarized.firedown.geckoview.toolbar.BottomNavigationBar;
import com.solarized.firedown.phone.dialogs.TrackersInfoSheet;
import com.solarized.firedown.ui.OnItemClickListener;
import com.solarized.firedown.ui.adapters.SearchAutocompleteAdapter;
import com.solarized.firedown.ui.diffs.SearchDiffCallback;
import com.solarized.firedown.IntentActions;
import com.solarized.firedown.utils.NavigationUtils;
import com.solarized.firedown.utils.Utils;

import dagger.hilt.android.AndroidEntryPoint;
import java.text.NumberFormat;
import java.util.Locale;
import javax.inject.Inject;


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
    private RecentDownloadsViewModel mRecentDownloadsViewModel;
    private AutoCompleteEditText mAutoCompleteEditText;
    private AutoCompleteView mAutoCompleteView;
    private View mNewTabView;
    private GeckoToolbar mGeckoToolbar;
    private BottomNavigationBar mBottomNavigationBar;
    private View mHomeScroll;
    // Home stats card (Brave-style): three live figures, each its own tap
    // target — trackers blocked (→ trackers info sheet), total saved
    // (→ Downloads), Safe Folder item count (→ vault). Only the value
    // TextViews are held; the column tap handlers are wired in onCreateView.
    private TextView mTrackersValue;
    private TextView mDownloadsValue;
    private TextView mSafeValue;
    // First-run onboarding: the two mutually-exclusive home cards (only one is
    // ever VISIBLE) and a one-way "retired" flag mirrored from prefs. While the
    // flag is false (a fresh install with all figures still zero) the onboarding
    // card replaces the stats card; the first non-zero figure retires it for
    // good. See updateHomeCard / retireOnboarding.
    private View mStatsCard;
    private View mOnboardCard;
    private boolean mOnboardingDone;
    // Small coral trend/context lines under the trackers (today) and saved
    // (this week) figures; the Safe column's "private" line is static in XML.
    private TextView mTrackersTrend;
    private TextView mDownloadsTrend;
    // Safe column footer — now the vault ITEM COUNT (the headline shows the
    // vault's total size, mirroring Saved). Held because it's bound from
    // LiveData instead of being a static XML string.
    private TextView mSafeTrend;
    // Latest values feeding the Saved column's context line. The line needs
    // BOTH the total saved bytes and the 7-day finished count to decide what
    // to show, but each arrives from its own LiveData — so they're cached here
    // and updateSavedTrend() reconciles them (see the observers below).
    private long mFinishedBytes;
    private int mFinishedThisWeek;
    @Inject
    GeckoUblockHelper mGeckoUblockHelper;


    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        mAutoCompleteViewModel = new ViewModelProvider(this).get(AutoCompleteViewModel.class);
        mRecentDownloadsViewModel = new ViewModelProvider(this).get(RecentDownloadsViewModel.class);
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


        // Home stats card — three live, independently-tappable columns.
        // Values bound from their LiveData in onViewCreated; each column's tap
        // opens that domain's surface.
        mTrackersValue = v.findViewById(R.id.home_trackers_value);
        mDownloadsValue = v.findViewById(R.id.home_downloads_value);
        mSafeValue = v.findViewById(R.id.home_safe_value);
        mTrackersTrend = v.findViewById(R.id.home_trackers_trend);
        mDownloadsTrend = v.findViewById(R.id.home_downloads_trend);
        mSafeTrend = v.findViewById(R.id.home_safe_trend);

        View trackersCol = v.findViewById(R.id.home_trackers_col);
        if (trackersCol != null) {
            trackersCol.setOnClickListener(view ->
                    TrackersInfoSheet.show(getChildFragmentManager()));
        }
        View downloadsCol = v.findViewById(R.id.home_downloads_col);
        if (downloadsCol != null) {
            downloadsCol.setOnClickListener(view ->
                    mStartForResult.launch(new Intent(mActivity, DownloadsActivity.class)));
        }
        View safeCol = v.findViewById(R.id.home_safe_col);
        if (safeCol != null) {
            // VaultActivity owns its own auth gate, same as the menu's Vault row.
            safeCol.setOnClickListener(view ->
                    mStartForResult.launch(new Intent(mActivity, VaultActivity.class)));
        }

        // First-run onboarding card and its CTA. The CTA just focuses the URL
        // bar (the same thing tapping the omnibox does) — it opens nothing, per
        // the "no paste-a-link flow" architecture invariant.
        mStatsCard = v.findViewById(R.id.home_stats_card);
        mOnboardCard = v.findViewById(R.id.home_onboard_card);
        View onboardCta = v.findViewById(R.id.home_onboard_cta);
        if (onboardCta != null) {
            onboardCta.setOnClickListener(view -> focusUrlBar());
        }

        mBottomNavigationBar.setListener(this);

        // Bookmarks is the flat middle-slot button in the bottom bar
        // (see onBottomBarButtonClick's R.id.search_button branch); the
        // URL bar at the top covers search. The former hero FAB was
        // removed in favour of this plain in-bar affordance.

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

        // Decide which home card shows BEFORE the stat observers attach, so a
        // returning user never sees a flash of onboarding. Read straight from
        // prefs (the flag is one-way; once retired it stays retired).
        mOnboardingDone = mSharedPreferences.getBoolean(Preferences.HOME_ONBOARDING_DONE, false);
        updateHomeCard();

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
        mTaskViewModel.getRegularCount().observe(getViewLifecycleOwner(), count ->
                mBottomNavigationBar.onBadgeCount(count));

        // Floor the displayed count at 1: while Home is on screen the user IS
        // on the home tab, so the counter must never read 0 (it could after a
        // close-all / close-last, or a race where the home GeckoState isn't in
        // the list yet - the current tab is still at least tab 1).
        mGeckoStateViewModel.getTabsCount().observe(getViewLifecycleOwner(), count
                -> mBottomNavigationBar.onTabsCount(count == null ? 1 : Math.max(1, count)));

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

        // Stats card column 1 — trackers blocked. firedown.js pushes uBlock's
        // cumulative blocked value periodically; show it compact + locale-aware
        // ("10.5K" / "1.4M" / "1,4 Mio.") so the figure stays bounded as it
        // climbs to 6–7 digits. Fresh install (no push yet) reads "0" — the card
        // is a numeric 3-up, so a bare figure fits better than a sentence.
        mGeckoUblockHelper.getCumulativeBlockedLive().observe(getViewLifecycleOwner(), blocked -> {
            if (mTrackersValue == null) return;
            long n = blocked == null ? 0L : blocked;
            retireOnboarding(n);
            if (n <= 0) {
                mTrackersValue.setText("0");
                return;
            }
            CompactDecimalFormat fmt = CompactDecimalFormat.getInstance(
                    Locale.getDefault(), CompactDecimalFormat.CompactStyle.SHORT);
            fmt.setMaximumFractionDigits(1);
            mTrackersValue.setText(withSmallUnit(fmt.format(n)));
        });

        // Trackers trend line — today's blocked count. Always shown: a positive
        // count gets a leading "+" ("+340 today"), a quiet day reads "0 today"
        // so the column never looks empty.
        mGeckoUblockHelper.getTodayBlockedLive().observe(getViewLifecycleOwner(), today -> {
            if (mTrackersTrend == null) return;
            long n = today == null ? 0L : today;
            CompactDecimalFormat fmt = CompactDecimalFormat.getInstance(
                    Locale.getDefault(), CompactDecimalFormat.CompactStyle.SHORT);
            fmt.setMaximumFractionDigits(1);
            String count = (n > 0 ? "+" : "") + fmt.format(n);
            mTrackersTrend.setText(getString(R.string.home_stat_trend_today, count));
        });

        // Stats card column 2 — total saved: live sum of finished regular
        // (non-vault) download bytes, locale-formatted by readableFileSize.
        mRecentDownloadsViewModel.getFinishedSize().observe(getViewLifecycleOwner(), size -> {
            mFinishedBytes = size == null ? 0L : size;
            retireOnboarding(mFinishedBytes);
            if (mDownloadsValue != null) {
                mDownloadsValue.setText(mFinishedBytes > 0
                        ? withSmallUnit(Utils.readableFileSize(mFinishedBytes)) : "0");
            }
            updateSavedTrend();
        });

        // Saved trend line — files finished in the last 7 days ("3 this week").
        mRecentDownloadsViewModel.getFinishedThisWeekCount().observe(getViewLifecycleOwner(), week -> {
            mFinishedThisWeek = week == null ? 0 : week;
            updateSavedTrend();
        });

        // Stats card column 3 — Safe Folder. Headline = total vault SIZE
        // (mirrors the Saved column, locale-formatted with the shrunk unit);
        // footer = the item COUNT (replaces the old static "private" — the lock
        // icon + label still convey privacy).
        mRecentDownloadsViewModel.getSafeSize().observe(getViewLifecycleOwner(), size -> {
            if (mSafeValue == null) return;
            long bytes = size == null ? 0L : size;
            mSafeValue.setText(bytes > 0 ? withSmallUnit(Utils.readableFileSize(bytes)) : "0");
        });

        mRecentDownloadsViewModel.getSafeCount().observe(getViewLifecycleOwner(), count -> {
            int n = count == null ? 0 : count;
            retireOnboarding(n);
            if (mSafeTrend == null) return;
            mSafeTrend.setText(getResources().getQuantityString(
                    R.plurals.home_safe_item_count, n, n));
        });

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
            // Apply the insets as padding to the view. Here, set all the dimensions
            // as appropriate to your layout. You can also update the view's margin if
            // more appropriate.
            v.setPadding(insets.left, 0, insets.right, insets.bottom);

            // Return CONSUMED if you don't want the window insets to keep passing down
            // to descendant views.
            return WindowInsetsCompat.CONSUMED;
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

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        mHomeScroll = null;
        mAutoCompleteView = null;
        mGeckoToolbar = null;
        mNewTabView = null;
        mBottomNavigationBar = null;
        mTrackersValue = null;
        mDownloadsValue = null;
        mSafeValue = null;
        mTrackersTrend = null;
        mDownloadsTrend = null;
        mSafeTrend = null;
        mStatsCard = null;
        mOnboardCard = null;
    }

    /**
     * Shows exactly one of the two home cards from {@link #mOnboardingDone}: the
     * first-run onboarding card while it is false, the live stats card once it
     * is true. Safe to call before the views exist (no-op then).
     */
    private void updateHomeCard() {
        if (mStatsCard == null || mOnboardCard == null) return;
        boolean showOnboard = !mOnboardingDone;
        mOnboardCard.setVisibility(showOnboard ? View.VISIBLE : View.GONE);
        mStatsCard.setVisibility(showOnboard ? View.GONE : View.VISIBLE);
    }

    /**
     * Retires the onboarding card the first time any home figure goes non-zero
     * (a tracker blocked, a byte saved, a vaulted item). One-way and persisted,
     * so it fires at most once and never reappears — a returning user, or one
     * who later clears all data, keeps the stats card. Cheap no-op after the
     * first retire.
     */
    private void retireOnboarding(long figure) {
        if (mOnboardingDone || figure <= 0) return;
        mOnboardingDone = true;
        mSharedPreferences.edit().putBoolean(Preferences.HOME_ONBOARDING_DONE, true).apply();
        updateHomeCard();
    }

    /**
     * Focuses the URL bar exactly as a tap on the omnibox would — requests focus
     * on the address EditText (which fires {@link #onFocusChanged} to raise the
     * autocomplete overlay) and shows the keyboard. It opens nothing: the
     * onboarding CTA only invites the user to start browsing, where capture
     * actually happens.
     */
    private void focusUrlBar() {
        if (mAutoCompleteEditText == null) return;
        mAutoCompleteEditText.requestFocus();
        InputMethodManager imm = (InputMethodManager) mActivity.getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) {
            imm.showSoftInput(mAutoCompleteEditText, InputMethodManager.SHOW_IMPLICIT);
        }
    }

    /**
     * Reconciles the Saved column's context line from the two LiveData feeding
     * it. Three states, so the column never looks empty (parallel to the
     * trackers "0 today" and the always-on vault "private"):
     * <ul>
     *   <li>nothing ever saved (no bytes) — a fresh install — reads
     *       "Nothing yet";</li>
     *   <li>something saved in the last 7 days — "N this week";</li>
     *   <li>saved before but nothing this week — hidden (no trend to show,
     *       same as the original behaviour).</li>
     * </ul>
     */
    private void updateSavedTrend() {
        if (mDownloadsTrend == null) return;
        if (mFinishedBytes <= 0) {
            mDownloadsTrend.setText(R.string.home_stat_trend_empty);
            mDownloadsTrend.setVisibility(View.VISIBLE);
        } else if (mFinishedThisWeek > 0) {
            mDownloadsTrend.setText(getString(R.string.home_stat_trend_week,
                    NumberFormat.getInstance(Locale.getDefault()).format(mFinishedThisWeek)));
            mDownloadsTrend.setVisibility(View.VISIBLE);
        } else {
            mDownloadsTrend.setVisibility(View.GONE);
        }
    }

    /**
     * Brave-style stat figure: shrink the trailing UNIT so the number reads as
     * the hero and the unit as a small suffix — "9.2 GB", "10.6 K", "1,4 Mio.".
     * The unit is the trailing run of letters (plus an optional dot and one
     * leading space); a pure number ("14") is returned unchanged. Locale-safe:
     * letters are matched by {@link Character#isLetter} so CJK compact units
     * ("万") shrink too.
     */
    private static CharSequence withSmallUnit(String s) {
        if (s == null || s.isEmpty()) {
            return s == null ? "" : s;
        }
        int end = s.length();
        int i = end;
        while (i > 0) {
            char c = s.charAt(i - 1);
            if (Character.isLetter(c) || c == '.') {
                i--;
            } else {
                break;
            }
        }
        if (i == end || i == 0) {
            // no trailing unit (a bare number), or it's all letters — leave it.
            return s;
        }
        int unitStart = i;
        if (s.charAt(unitStart - 1) == ' ') {
            unitStart--;
        }
        SpannableString out = new SpannableString(s);
        out.setSpan(new RelativeSizeSpan(0.6f), unitStart, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return out;
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
            mAutoCompleteViewModel.resetEngines();
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