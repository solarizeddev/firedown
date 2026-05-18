package com.solarized.firedown.phone.fragments;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextUtils;
import android.util.Log;
import android.util.Patterns;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.ProgressBar;
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

import com.google.android.material.card.MaterialCardView;
import com.google.android.material.snackbar.Snackbar;

import com.solarized.firedown.Keys;
import com.solarized.firedown.Preferences;
import com.solarized.firedown.R;
import com.solarized.firedown.data.Download;
import com.solarized.firedown.data.entity.DownloadEntity;
import com.solarized.firedown.data.entity.GeckoStateEntity;
import com.solarized.firedown.data.entity.AutoCompleteEntity;
import com.solarized.firedown.autocomplete.AutoCompleteViewModel;
import com.solarized.firedown.data.models.BrowserDialogViewModel;
import com.solarized.firedown.data.models.BrowserURIViewModel;
import com.solarized.firedown.data.models.GeckoStateViewModel;
import com.solarized.firedown.data.models.IncognitoStateViewModel;
import com.solarized.firedown.data.models.RecentDownloadsViewModel;
import com.solarized.firedown.data.models.TaskViewModel;
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
import com.solarized.firedown.ui.OnBoardingCard;
import com.solarized.firedown.geckoview.toolbar.BottomNavigationBar;
import com.solarized.firedown.ui.OnItemClickListener;
import com.solarized.firedown.ui.adapters.SearchAutocompleteAdapter;
import com.solarized.firedown.ui.diffs.SearchDiffCallback;
import com.solarized.firedown.IntentActions;
import com.solarized.firedown.utils.NavigationUtils;

import dagger.hilt.android.AndroidEntryPoint;


@AndroidEntryPoint
public class HomeFragment extends BaseBrowserFragment implements BottomNavigationBar.OnBottomBarListener,
        AutoCompleteEditText.OnCommitListener, AutoCompleteEditText.OnFilterListener, AutoCompleteEditText.OnFocusChangedListener,
        AutoCompleteEditText.OnTextChangedListener, AutoCompleteEditText.OnSearchStateChangeListener,
        GeckoToolbar.OnToolbarListener , OnBoardingCard.OnBoardingCardListener, OnItemClickListener {


    private static final String TAG = HomeFragment.class.getName();
    private BrowserURIViewModel mBrowserURIViewModel;
    private BrowserDialogViewModel mBrowserDialogViewModel;
    private GeckoStateViewModel mGeckoStateViewModel;
    private IncognitoStateViewModel mIncognitoStateViewModel;
    private TaskViewModel mTaskViewModel;
    private RecentDownloadsViewModel mRecentDownloadsViewModel;
    private AutoCompleteEditText mAutoCompleteEditText;
    private AutoCompleteView mAutoCompleteView;
    private View mNewTabView;
    private OnBoardingCard mOnBoardingCard;
    private GeckoToolbar mGeckoToolbar;
    private BottomNavigationBar mBottomNavigationBar;
    private MaterialCardView mRecentDownloadsCard;
    private View mHomeScroll;
    private MaterialCardView mActiveStrip;
    private TextView mActiveStripTitle;
    private TextView mActiveStripPercent;
    private TextView mActiveStripCount;
    private ProgressBar mActiveStripBar;
    private View mActiveStripIcon;
    @Nullable private android.animation.ObjectAnimator mActiveStripPulse;
    private TextView mHomeVaultTitle;
    private TextView mHomeVaultSubtitle;
    private View mHomePasteCard;
    private View mPinnedStripCard;
    private com.solarized.firedown.ui.adapters.PinnedFaviconsAdapter mPinnedAdapter;
    private com.solarized.firedown.data.models.WebBookmarkViewModel mWebBookmarkViewModel;
    @Nullable private android.content.ClipboardManager.OnPrimaryClipChangedListener mClipListener;
    private TextView mRecentDownloadsSubtitle;
    private SharedPreferences.OnSharedPreferenceChangeListener mHomePrefsListener;
    @Nullable private java.util.List<DownloadEntity> mLastActiveList;
    @Nullable private Integer mLastFinishedCount;
    private long mLastFinishedSize = 0L;


    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        mAutoCompleteViewModel = new ViewModelProvider(this).get(AutoCompleteViewModel.class);
        mTaskViewModel = new ViewModelProvider(this).get(TaskViewModel.class);
        mRecentDownloadsViewModel = new ViewModelProvider(this).get(RecentDownloadsViewModel.class);
        mWebBookmarkViewModel = new ViewModelProvider(this).get(com.solarized.firedown.data.models.WebBookmarkViewModel.class);
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

        mNewTabView = v.findViewById(R.id.bottom_new_tab);
        mAutoCompleteView = v.findViewById(R.id.auto_complete_view);
        mOnBoardingCard = v.findViewById(R.id.onboarding);
        mOnBoardingCard.setCallback(this);

        mRecentDownloadsCard = v.findViewById(R.id.recent_downloads_card);

        mActiveStrip = v.findViewById(R.id.active_download_strip);
        mActiveStripTitle = v.findViewById(R.id.active_download_title);
        mActiveStripPercent = v.findViewById(R.id.active_download_percent);
        mActiveStripCount = v.findViewById(R.id.active_download_count);
        mActiveStripBar = v.findViewById(R.id.active_download_bar);
        mActiveStripIcon = v.findViewById(R.id.active_download_icon);
        // Track colour: theme attr + alpha can't be combined in XML, and
        // the M3 default (colorSecondary, yellow in Firedown's palette)
        // fought the orange card surface. Apply colorOnPrimaryContainer
        // at ~24% alpha so the empty band reads as a subtle ghost of
        // the indicator instead of a competing colour.
        if (mActiveStripBar instanceof com.google.android.material.progressindicator.LinearProgressIndicator lpi) {
            int onContainer = com.google.android.material.color.MaterialColors.getColor(
                    lpi, com.google.android.material.R.attr.colorOnPrimaryContainer);
            lpi.setTrackColor(androidx.core.graphics.ColorUtils.setAlphaComponent(onContainer, 0x3D));
        }
        mActiveStrip.setOnClickListener(view ->
                mStartForResult.launch(new Intent(mActivity, DownloadsActivity.class)));


        mHomeScroll = v.findViewById(R.id.home_scroll);
        mBottomNavigationBar = v.findViewById(R.id.bottom_app_bar);


        mRecentDownloadsSubtitle = v.findViewById(R.id.recent_downloads_subtitle);
        mRecentDownloadsCard.setOnClickListener(view ->
                mStartForResult.launch(new Intent(mActivity, DownloadsActivity.class)));

        mHomePasteCard = v.findViewById(R.id.home_paste_card);
        mHomePasteCard.setOnClickListener(view -> onPasteAndDownload());

        mPinnedStripCard = v.findViewById(R.id.home_pinned_strip_card);
        androidx.recyclerview.widget.RecyclerView pinnedRecycler =
                v.findViewById(R.id.home_pinned_strip_recycler);
        pinnedRecycler.setLayoutManager(new androidx.recyclerview.widget.LinearLayoutManager(
                getContext(),
                androidx.recyclerview.widget.LinearLayoutManager.HORIZONTAL,
                false));
        mPinnedAdapter = new com.solarized.firedown.ui.adapters.PinnedFaviconsAdapter(entity ->
                openUri(entity.getUrl()));
        pinnedRecycler.setAdapter(mPinnedAdapter);

        View vaultCard = v.findViewById(R.id.home_vault_card);
        mHomeVaultTitle = v.findViewById(R.id.home_vault_title);
        mHomeVaultSubtitle = v.findViewById(R.id.home_vault_subtitle);
        vaultCard.setOnClickListener(view ->
                mStartForResult.launch(new Intent(mActivity, VaultActivity.class)));


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

        // Three streams power the home Downloads surfaces:
        //  * getActive — drives the active-download strip (visible
        //    only when in-flight non-vault items exist).
        //  * getFinishedCount / getFinishedSize — drive the
        //    Downloads card subtitle ('N files saved · X.Y GB').
        //    Card itself is visible whenever the toggle is on, even
        //    with zero saved files, so the entry is discoverable.
        //  * keeps getRecent hot via the long-press handler — see
        //    onBottomBarButtonLongClick — by reading getValue() at
        //    tap time; the sheet itself owns its own observer.
        mRecentDownloadsViewModel.getActive().observe(getViewLifecycleOwner(), list -> {
            mLastActiveList = list;
            applyHomeCustomisation();
        });
        mRecentDownloadsViewModel.getFinishedCount().observe(getViewLifecycleOwner(), count -> {
            mLastFinishedCount = count;
            applyHomeCustomisation();
        });
        mRecentDownloadsViewModel.getFinishedSize().observe(getViewLifecycleOwner(), size -> {
            mLastFinishedSize = size == null ? 0L : size;
            applyHomeCustomisation();
        });

        // Pinned bookmarks → home favicons strip. Card visible iff
        // ≥1 pinned bookmark; otherwise hidden (no empty 'pin
        // something' state — the card itself appearing when a user
        // first pins is the discovery).
        mWebBookmarkViewModel.getPinned().observe(getViewLifecycleOwner(), list -> {
            if (mPinnedStripCard == null || mPinnedAdapter == null) return;
            boolean hasPinned = list != null && !list.isEmpty();
            mPinnedStripCard.setVisibility(hasPinned ? View.VISIBLE : View.GONE);
            mPinnedAdapter.submitList(hasPinned ? list : java.util.Collections.emptyList());
        });

        // Vault count drives the empty-hero vault button's count badge.
        // Button itself is always visible while the empty hero is
        // showing — discoverability for users who haven't yet used
        // vault — but the badge only appears when count > 0.
        mRecentDownloadsViewModel.getVaultCount().observe(getViewLifecycleOwner(), count -> {
            if (mHomeVaultSubtitle == null) return;
            int n = count == null ? 0 : count;
            if (n > 0) {
                mHomeVaultSubtitle.setVisibility(View.VISIBLE);
                mHomeVaultSubtitle.setText(getResources().getQuantityString(
                        R.plurals.home_vault_item_count, n, n));
            } else {
                mHomeVaultSubtitle.setVisibility(View.GONE);
            }
        });

        mHomePrefsListener = (sharedPreferences, key) -> {
            if (Preferences.SETTINGS_HOME_SHOW_RECENT_DOWNLOADS.equals(key)) {
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
                unregisterClipListener();
            } else if (Lifecycle.Event.ON_RESUME.equals(event)) {
                Log.d(TAG, "onResume");
                mStop = false;
                // Re-check clipboard description on every resume (paste
                // card visibility) + start listening for live changes
                // while we're foregrounded. The listener only fires for
                // foreground apps on modern Android, hence the
                // resume/pause bracket rather than onCreate/onDestroy.
                registerClipListener();
                refreshPasteCardVisibility();
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
        if (mHomePrefsListener != null) {
            mSharedPreferences.unregisterOnSharedPreferenceChangeListener(mHomePrefsListener);
            mHomePrefsListener = null;
        }
        mHomeScroll = null;
        mAutoCompleteView = null;
        mGeckoToolbar = null;
        mNewTabView = null;
        mBottomNavigationBar = null;
        mOnBoardingCard = null;
        mRecentDownloadsCard = null;
        mActiveStrip = null;
        mActiveStripTitle = null;
        mActiveStripPercent = null;
        mActiveStripCount = null;
        mActiveStripBar = null;
        stopActiveStripPulse();
        mActiveStripIcon = null;
        mHomeVaultTitle = null;
        mHomeVaultSubtitle = null;
        mHomePasteCard = null;
        mPinnedStripCard = null;
        mPinnedAdapter = null;
        mRecentDownloadsSubtitle = null;
    }

    /**
     * Resolves the current home composition. Paste and Safe Folder
     * cards are always visible (always-on entry points). Active strip
     * and Downloads card are data- / preference-gated:
     *
     * <ul>
     *   <li>Active strip — any non-vault PROGRESS / QUEUED download.</li>
     *   <li>Downloads card — {@link Preferences#SETTINGS_HOME_SHOW_RECENT_DOWNLOADS}
     *       toggle. Subtitle ('N files saved · X.Y GB') shows when
     *       count > 0; otherwise title-only.</li>
     * </ul>
     */
    private void applyHomeCustomisation() {
        if (mRecentDownloadsCard == null || mActiveStrip == null) return;

        boolean showRecent = mSharedPreferences.getBoolean(
                Preferences.SETTINGS_HOME_SHOW_RECENT_DOWNLOADS,
                Preferences.DEFAULT_HOME_SHOW_RECENT_DOWNLOADS);

        boolean hasActive = mLastActiveList != null && !mLastActiveList.isEmpty();
        boolean stripVisible = showRecent && hasActive;
        boolean cardVisible  = showRecent;

        mActiveStrip.setVisibility(stripVisible ? View.VISIBLE : View.GONE);
        mRecentDownloadsCard.setVisibility(cardVisible ? View.VISIBLE : View.GONE);

        if (stripVisible) {
            bindActiveStrip(mLastActiveList);
            startActiveStripPulse();
        } else {
            stopActiveStripPulse();
        }
        if (cardVisible) bindDownloadsSubtitle();
    }

    /** Subtle alpha pulse on the active-strip's Firedown flame icon
     *  to communicate 'live, this is happening now'. Lazily
     *  instantiated; cancelled when the strip is hidden or the
     *  view is destroyed. */
    private void startActiveStripPulse() {
        if (mActiveStripIcon == null) return;
        if (mActiveStripPulse != null && mActiveStripPulse.isStarted()) return;
        mActiveStripPulse = android.animation.ObjectAnimator.ofFloat(
                mActiveStripIcon, "alpha", 1.0f, 0.45f);
        mActiveStripPulse.setDuration(1100L);
        mActiveStripPulse.setRepeatCount(android.animation.ValueAnimator.INFINITE);
        mActiveStripPulse.setRepeatMode(android.animation.ValueAnimator.REVERSE);
        mActiveStripPulse.start();
    }

    private void stopActiveStripPulse() {
        if (mActiveStripPulse != null) {
            mActiveStripPulse.cancel();
            mActiveStripPulse = null;
        }
        if (mActiveStripIcon != null) {
            mActiveStripIcon.setAlpha(1.0f);
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
                        files, com.solarized.firedown.utils.Utils.readableFileSize(mLastFinishedSize))
                : files;
        mRecentDownloadsSubtitle.setVisibility(View.VISIBLE);
        mRecentDownloadsSubtitle.setText(text);
    }

    /**
     * Binds the headline active download (first item in the active
     * sublist) plus a count subtitle if there's more than one.
     * Progress bar is indeterminate while the file is 'live'
     * (retrieving size) or QUEUED, determinate once a real percentage
     * is available.
     */
    private void bindActiveStrip(@NonNull java.util.List<DownloadEntity> active) {
        DownloadEntity head = active.get(0);
        mActiveStripTitle.setText(head.getFileName());

        boolean live = head.getFileIsLive();
        boolean queued = head.getFileStatus() == Download.QUEUED;
        boolean indeterminate = live || queued;
        mActiveStripBar.setIndeterminate(indeterminate);
        if (queued) {
            mActiveStripPercent.setText(R.string.download_queued);
        } else if (live) {
            // Same UX as the per-row adapter while file size is still
            // being discovered: show the bytes-received counter rather
            // than an empty percentage.
            mActiveStripPercent.setText(com.solarized.firedown.utils.Utils.readableFileSize(
                    head.getFileSize()));
        } else {
            int pct = head.getFileProgress();
            mActiveStripBar.setProgress(pct);
            mActiveStripPercent.setText(String.format(java.util.Locale.US, "%d%%", pct));
        }

        int extra = active.size() - 1;
        if (extra > 0) {
            mActiveStripCount.setVisibility(View.VISIBLE);
            mActiveStripCount.setText(String.format(java.util.Locale.US, "+%d", extra));
        } else {
            mActiveStripCount.setVisibility(View.GONE);
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
            // Cradle slot on normal home is Bookmarks — the URL bar at
            // the top already covers the search path, so the centre
            // tap-target gives pinned bookmarks (which absorbed the
            // old shortcuts surface) a one-tap entry.
            Intent bookmarksIntent = new Intent(mActivity, BookmarkActivity.class);
            mStartForResult.launch(bookmarksIntent);
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
        // entry (Downloads card, Safe Folder card, cradle Bookmarks
        // button). Hidden long-press gestures were the right call
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


    /**
     * Empty-home paste-and-download flow. Reads the primary clip,
     * validates it looks like a URL via {@link Patterns#WEB_URL}, and
     * navigates the active GeckoSession there — the standard download
     * action menu kicks in once the page (or direct media URL) loads.
     * If the clipboard is empty or doesn't look like a link we surface
     * a Snackbar rather than silently routing a search query to the
     * browser, since the CTA is explicitly about downloading.
     */
    private void onPasteAndDownload() {
        ClipboardManager cm = (ClipboardManager) mActivity.getSystemService(Context.CLIPBOARD_SERVICE);
        ClipData clip = cm == null ? null : cm.getPrimaryClip();
        // Skip the MIME-type filter and rely on coerceToText. Browsers
        // and some apps put copied URLs in clip items labelled
        // text/uri-list rather than text/plain, and a strict
        // MIMETYPE_TEXT_PLAIN check rejected those (reproduced: copy
        // a YouTube URL in Brave → paste here was a no-op).
        // coerceToText already handles every supported representation
        // (plain text, HTML stripped to text, URI loaded as text);
        // empty result still routes to the 'copy a link first' hint.
        String text = "";
        if (clip != null && clip.getItemCount() > 0) {
            CharSequence raw = clip.getItemAt(0).coerceToText(mActivity);
            if (raw != null) text = raw.toString().trim();
        }

        if (text.isEmpty()) {
            showPasteHint(R.string.home_paste_hero_empty);
            return;
        }
        if (!Patterns.WEB_URL.matcher(text).matches()) {
            showPasteHint(R.string.home_paste_hero_not_url);
            return;
        }
        // Paste consumed: drop the URL from the system clipboard so
        // the card hides on the next home open and the URL doesn't
        // linger in the system clip. clearPrimaryClip() is the only
        // call that actually empties the clipboard (hasPrimaryClip()
        // → false); setPrimaryClip(empty) leaves a text/plain clip
        // with empty content and the card stays visible. Bracketed
        // for API 28+ (Android 9); the API 26/27 fallback only
        // empties the contents — paste card cosmetic limitation on
        // Android 8 / 8.1, no functional harm.
        if (cm != null) {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                cm.clearPrimaryClip();
            } else {
                cm.setPrimaryClip(ClipData.newPlainText("", ""));
            }
        }
        openUri(text);
    }

    /** Foreground-only OnPrimaryClipChangedListener registration — set
     *  up in ON_RESUME, torn down in ON_PAUSE. Android only fires
     *  these listeners while the app is in the foreground anyway,
     *  and bracketing them tightly avoids leaking the listener
     *  across configuration changes. */
    private void registerClipListener() {
        if (mActivity == null || mClipListener != null) return;
        ClipboardManager cm = (ClipboardManager) mActivity.getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm == null) return;
        mClipListener = this::refreshPasteCardVisibility;
        cm.addPrimaryClipChangedListener(mClipListener);
    }

    private void unregisterClipListener() {
        if (mActivity == null || mClipListener == null) return;
        ClipboardManager cm = (ClipboardManager) mActivity.getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) cm.removePrimaryClipChangedListener(mClipListener);
        mClipListener = null;
    }

    /**
     * Reads the clipboard's *description* (mime types only — never
     * touches the actual content) to decide whether to show the
     * paste card. Description access doesn't trigger Android 13's
     * 'App pasted from your clipboard' toast that getPrimaryClip()
     * would on every check, so we can call this freely on resume
     * and from the OnPrimaryClipChangedListener without nagging the
     * user. Card shows iff a text-typed clip exists; URL validation
     * is deferred to tap-time.
     */
    private void refreshPasteCardVisibility() {
        if (mHomePasteCard == null || mActivity == null) return;
        ClipboardManager cm = (ClipboardManager) mActivity.getSystemService(Context.CLIPBOARD_SERVICE);
        boolean hasText = false;
        if (cm != null && cm.hasPrimaryClip()) {
            android.content.ClipDescription desc = cm.getPrimaryClipDescription();
            if (desc != null) {
                hasText = desc.hasMimeType(android.content.ClipDescription.MIMETYPE_TEXT_PLAIN)
                        || desc.hasMimeType(android.content.ClipDescription.MIMETYPE_TEXT_HTML)
                        || desc.hasMimeType("text/uri-list");
            }
        }
        mHomePasteCard.setVisibility(hasText ? View.VISIBLE : View.GONE);
    }

    private void showPasteHint(int stringRes) {
        Snackbar.make(mBottomNavigationBar.getRootView(), stringRes, Snackbar.LENGTH_SHORT)
                .setAnchorView(mBottomNavigationBar)
                .show();
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