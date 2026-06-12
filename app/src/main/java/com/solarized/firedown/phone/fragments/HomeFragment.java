package com.solarized.firedown.phone.fragments;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
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
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleEventObserver;
import androidx.lifecycle.ViewModelProvider;
import androidx.preference.PreferenceManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.card.MaterialCardView;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.solarized.firedown.ui.IncognitoColors;
import com.solarized.firedown.Keys;
import com.solarized.firedown.Preferences;
import com.solarized.firedown.R;
import com.solarized.firedown.geckoview.GeckoUblockHelper;
import com.solarized.firedown.ui.HomeCardStyle;
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
    private MaterialCardView mRecentDownloadsCard;
    private View mHomeScroll;
    private TextView mHomeVaultSubtitle;
    private TextView mRecentDownloadsSubtitle;
    private MaterialCardView mTrackersCard;
    private TextView mTrackersSubtitle;
    @Inject
    GeckoUblockHelper mGeckoUblockHelper;
    @Nullable private Integer mLastFinishedCount;
    private long mLastFinishedSize = 0L;

    /** Estimated bytes that would have been transferred per blocked
     *  request. uBlock cancels the request before the response, so the
     *  real number is unknown — Brave's published methodology pegs the
     *  average at ~50KB and we follow the same so users comparing
     *  across browsers see consistent figures. */
    private static final long AVG_BYTES_PER_BLOCKED_REQUEST = 50_000L;


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

    /**
     * Closes the URL-bar autocomplete overlay if it's currently up, and
     * returns true to short-circuit the back-press handler. Returns
     * false (no-op) when the overlay isn't visible, so the caller can
     * fall through to its default back behavior.
     */
    private boolean dismissAutocompleteOverlayIfVisible() {
        if (mAutoCompleteView.getVisibility() != View.VISIBLE) return false;
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

        mRecentDownloadsCard = v.findViewById(R.id.recent_downloads_card);

        mHomeScroll = v.findViewById(R.id.home_scroll);
        mBottomNavigationBar = v.findViewById(R.id.bottom_app_bar);


        mRecentDownloadsSubtitle = v.findViewById(R.id.recent_downloads_subtitle);
        mRecentDownloadsCard.setOnClickListener(view ->
                mStartForResult.launch(new Intent(mActivity, DownloadsActivity.class)));

        View vaultCard = v.findViewById(R.id.home_vault_card);
        mHomeVaultSubtitle = v.findViewById(R.id.home_vault_subtitle);
        vaultCard.setOnClickListener(view ->
                mStartForResult.launch(new Intent(mActivity, VaultActivity.class)));

        // Trackers-blocked shelf card. Subtitle reflects uBlock's
        // cumulative requestStats.blockedCount, relayed live via
        // GeckoUblockHelper. Tap spawns a contextual info sheet —
        // big number, bytes-saved estimate, breakdown of what's
        // being blocked, and a CTA into Privacy settings.
        mTrackersCard = v.findViewById(R.id.home_trackers_card);
        mTrackersSubtitle = v.findViewById(R.id.home_trackers_subtitle);
        if (mTrackersCard != null) {
            mTrackersCard.setOnClickListener(view ->
                    TrackersInfoSheet.show(
                            getChildFragmentManager()));
        }

        applyHomeCardStyle(v);


        mBottomNavigationBar.setListener(this);

        // Hero FAB in the bottom bar's cradle is Bookmarks — the URL
        // bar at the top already covers the search path, so the centre
        // affordance gives the bookmarks list a one-tap entry. Promoted
        // from the flat, unlabeled middle-slot icon to the same hero-FAB
        // treatment Browser (capture) and Tabs already use; the middle
        // slot itself is hidden (hideMiddleSlot) underneath it.
        FloatingActionButton bookmarkButton = v.findViewById(R.id.bookmark_button);
        bookmarkButton.setOnClickListener(view ->
                NavigationUtils.navigateSafe(mNavController, R.id.action_home_to_bookmarks));

        // Dock the FAB the TABS way (TabsHolderFragment): bottomMargin =
        // (bar height − the 64dp content row) + app_bar_fab_margin
        // = nav inset + lift, so the FAB pokes just above the bar's top
        // edge. Recomputed on every bar layout pass — the bar grows when
        // BottomNavigationBar's own insets listener pads it by the nav
        // inset, so a one-shot read would race that and dock too low.
        mBottomNavigationBar.addOnLayoutChangeListener(
                (bar, l, t, r, b, ol, ot, or, ob) -> {
                    int barHeight = b - t;
                    if (barHeight <= 0) {
                        return;
                    }
                    int contentRow = getResources().getDimensionPixelOffset(R.dimen.app_bar_size);
                    int lift = getResources().getDimensionPixelOffset(R.dimen.app_bar_fab_margin);
                    int margin = Math.max(0, barHeight - contentRow) + lift;
                    ViewGroup.MarginLayoutParams params =
                            (ViewGroup.MarginLayoutParams) bookmarkButton.getLayoutParams();
                    if (params.bottomMargin != margin) {
                        params.bottomMargin = margin;
                        bookmarkButton.setLayoutParams(params);
                    }
                });

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

        // Two streams power the home Downloads card:
        //  * getFinishedCount / getFinishedSize — drive the
        //    Downloads card subtitle ('N files saved · X.Y GB').
        //    Card itself is visible whenever the toggle is on, even
        //    with zero saved files, so the entry is discoverable.
        //  (In-flight downloads surface as the bottom-bar badge above
        //  plus the ongoing system notification — there is no longer a
        //  dedicated active-download card on Home.)
        mRecentDownloadsViewModel.getFinishedCount().observe(getViewLifecycleOwner(), count -> {
            mLastFinishedCount = count;
            bindDownloadsSubtitle();
        });
        mRecentDownloadsViewModel.getFinishedSize().observe(getViewLifecycleOwner(), size -> {
            mLastFinishedSize = size == null ? 0L : size;
            bindDownloadsSubtitle();
        });

        // Vault subtitle. Populated state shows 'N items saved'; empty
        // state shows the 'Private and encrypted' explainer so the
        // card has a stable two-line layout matching the Downloads
        // and Trackers shelves, and a first-time user sees a
        // one-line description of what the card even does.
        mRecentDownloadsViewModel.getVaultCount().observe(getViewLifecycleOwner(), count -> {
            if (mHomeVaultSubtitle == null) return;
            int n = count == null ? 0 : count;
            mHomeVaultSubtitle.setVisibility(View.VISIBLE);
            if (n > 0) {
                mHomeVaultSubtitle.setText(getResources().getQuantityString(
                        R.plurals.home_vault_item_count, n, n));
            } else {
                mHomeVaultSubtitle.setText(R.string.home_vault_empty_subtitle);
            }
        });

        // Trackers-blocked subtitle. firedown.js pushes the cumulative
        // value periodically; format with locale-aware grouping
        // separators so 12345 reads as '12,345' or '12.345' depending
        // on the user's locale, and append an estimated-bytes-saved
        // figure (Brave's published methodology: 50KB average per
        // blocked request — flagged with '~' so users read it as an
        // estimate, not a measured value).
        //
        // Zero case → 'Protection active' placeholder instead of the
        // cosmetic '0 · ~0 saved' you'd otherwise see between app
        // start and the extension's first push, or on a fresh install
        // before any browsing.
        mGeckoUblockHelper.getCumulativeBlockedLive().observe(getViewLifecycleOwner(), blocked -> {
            if (mTrackersSubtitle == null) return;
            long n = blocked == null ? 0L : blocked;
            if (n <= 0) {
                mTrackersSubtitle.setText(R.string.home_trackers_subtitle_idle);
                return;
            }
            String formattedCount = NumberFormat
                    .getInstance(Locale.getDefault())
                    .format(n);
            String savedBytes = Utils.readableFileSize(n * AVG_BYTES_PER_BLOCKED_REQUEST);
            mTrackersSubtitle.setText(getString(
                    R.string.home_trackers_subtitle, formattedCount, savedBytes));
        });

        mRecentDownloadsCard.setVisibility(View.VISIBLE);

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
                // Pick up any palette change made in Settings → Home
                // cards. The settings sub-screen is hosted by another
                // activity, so the home view survives the round-trip
                // and only its chip backgrounds need to flip.
                View root = getView();
                if (root != null) applyHomeCardStyle(root);
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
        // Scrim follows the bar's surfaceContainer tone (see
        // BaseFocusFragment.setNavScrimColor) so the system-nav strip
        // matches the bar instead of cutting a black seam under it.
        setNavScrimColor(IncognitoColors.getSurfaceContainer(mActivity, false));
        // Home chrome: QUIET top (toolbar = surface, merging with the
        // canvas — tonalHolder=false), tonal bottom. paintSystemBars
        // mirrors that on the WINDOW layer for Android <= 14, where the
        // theme's opaque bar attrs would otherwise paint the strips.
        mGeckoToolbar.updateTheme(mActivity, false, false);
        paintSystemBars(
                IncognitoColors.getSurface(mActivity, false),
                IncognitoColors.getSurfaceContainer(mActivity, false));
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
        mRecentDownloadsCard = null;
        mHomeVaultSubtitle = null;
        mRecentDownloadsSubtitle = null;
        mTrackersCard = null;
        mTrackersSubtitle = null;
    }

    /**
     * Paints the home cards — Downloads, Safe Folder,
     * Trackers blocked — with the user's picked
     * {@link HomeCardStyle}. One pref, all
     * cards flip together; the picker rewards a coherent look rather
     * than per-card tweaks. Called from {@code onCreateView} and again
     * on {@code ON_RESUME} so a style change made in Settings shows up
     * when the user navigates back, without forcing a fragment rebuild.
     */
    private void applyHomeCardStyle(@NonNull View root) {
        SharedPreferences prefs = PreferenceManager
                .getDefaultSharedPreferences(requireContext());
        String key = prefs.getString(
                Preferences.SETTINGS_HOME_CARD_STYLE,
                Preferences.DEFAULT_HOME_CARD_STYLE);
        // Fallback = the default (Tonal), so a stored key for a removed
        // style (blush/bloom) renders the new default rather than Neutral.
        HomeCardStyle style =
                HomeCardStyle.fromKey(key, HomeCardStyle.TONAL);
        boolean night = HomeCardStyle.isNightMode(getResources());

        MaterialCardView downloadsCard = root.findViewById(R.id.recent_downloads_card);
        if (downloadsCard != null) {
            HomeCardStyle.applyToCard(
                    downloadsCard,
                    root.findViewById(R.id.recent_downloads_chip),
                    root.findViewById(R.id.recent_downloads_icon),
                    root.findViewById(R.id.recent_downloads_title),
                    mRecentDownloadsSubtitle,
                    root.findViewById(R.id.recent_downloads_chevron),
                    style.downloads(night));
        }

        MaterialCardView vaultCard = root.findViewById(R.id.home_vault_card);
        if (vaultCard != null) {
            HomeCardStyle.applyToCard(
                    vaultCard,
                    root.findViewById(R.id.home_vault_chip),
                    root.findViewById(R.id.home_vault_icon),
                    root.findViewById(R.id.home_vault_title),
                    mHomeVaultSubtitle,
                    root.findViewById(R.id.home_vault_chevron),
                    style.vault(night));
        }

        MaterialCardView trackersCard = root.findViewById(R.id.home_trackers_card);
        if (trackersCard != null) {
            HomeCardStyle.applyToCard(
                    trackersCard,
                    root.findViewById(R.id.home_trackers_chip),
                    root.findViewById(R.id.home_trackers_icon),
                    root.findViewById(R.id.home_trackers_title),
                    mTrackersSubtitle,
                    root.findViewById(R.id.home_trackers_chevron),
                    style.trackers(night));
        }
    }

    /** Binds the 'N files saved · X.Y GB' subtitle on the Downloads
     *  card. Hidden when no finished files exist so a curious user
     *  with nothing downloaded yet sees the bare entry label. */
    private void bindDownloadsSubtitle() {
        if (mRecentDownloadsSubtitle == null) return;
        int n = mLastFinishedCount == null ? 0 : mLastFinishedCount;
        if (n <= 0) {
            mRecentDownloadsSubtitle.setVisibility(View.GONE);
            return;
        }
        String files = getResources().getQuantityString(
                R.plurals.home_downloads_file_count, n, n);
        String text = mLastFinishedSize > 0
                ? getString(R.string.home_downloads_subtitle_with_size,
                        files, Utils.readableFileSize(mLastFinishedSize))
                : files;
        mRecentDownloadsSubtitle.setVisibility(View.VISIBLE);
        mRecentDownloadsSubtitle.setText(text);
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
        }
        // No R.id.search_button branch: the middle slot is hidden
        // (hideMiddleSlot) and never dispatches — Bookmarks is the
        // hero FAB wired in onCreateView.
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