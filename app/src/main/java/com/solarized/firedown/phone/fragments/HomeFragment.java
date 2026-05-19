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
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.card.MaterialCardView;

import com.google.android.material.color.MaterialColors;
import com.google.android.material.progressindicator.LinearProgressIndicator;
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
import com.solarized.firedown.utils.Utils;

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
    private android.view.ViewGroup mActiveStripRows;
    private View mActiveStripIcon;
    @Nullable private android.animation.ObjectAnimator mActiveStripPulse;
    private TextView mHomeVaultSubtitle;
    private TextView mRecentDownloadsSubtitle;
    private View mHomeMediaStrip;
    private androidx.appcompat.widget.AppCompatImageView mHomeMediaIcon;
    private TextView mHomeMediaLabel;
    private TextView mHomeMediaTitle;
    private TextView mHomeMediaSubtitle;
    private androidx.appcompat.widget.AppCompatImageButton mHomeMediaToggle;
    @Nullable private java.util.List<DownloadEntity> mLastActiveList;
    @Nullable private Integer mLastFinishedCount;
    private long mLastFinishedSize = 0L;


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
        mActiveStripIcon = v.findViewById(R.id.active_download_icon);
        mActiveStripRows = v.findViewById(R.id.active_download_rows);
        mActiveStrip.setOnClickListener(view ->
                mStartForResult.launch(new Intent(mActivity, DownloadsActivity.class)));

        mHomeMediaStrip = v.findViewById(R.id.home_media_strip);
        mHomeMediaIcon = v.findViewById(R.id.home_media_icon);
        mHomeMediaLabel = v.findViewById(R.id.home_media_label);
        mHomeMediaTitle = v.findViewById(R.id.home_media_title);
        mHomeMediaSubtitle = v.findViewById(R.id.home_media_subtitle);
        mHomeMediaToggle = v.findViewById(R.id.home_media_toggle);
        // Tap → switch to the playing tab. Same flow IntentHandler.handleMainMedia
        // uses for the foreground media notification: look up the session,
        // promote it, fire OPEN_SESSION, navigate to browser.
        mHomeMediaStrip.setOnClickListener(view -> {
            int sessionId = mGeckoMediaController.getCurrentSessionId();
            if (sessionId != 0) openSessionId(sessionId);
        });
        // Trailing toggle button: play / pause the current session in
        // place without navigating away. Borderless ripple on the
        // button + tap on the rest of the card → openSessionId; tap
        // here → toggle playback only.
        mHomeMediaToggle.setOnClickListener(view -> {
            Boolean playing = mGeckoMediaController.getIsPlayingLiveData().getValue();
            if (playing != null && playing) {
                mGeckoMediaController.pause();
            } else {
                mGeckoMediaController.play();
            }
        });


        mHomeScroll = v.findViewById(R.id.home_scroll);
        mBottomNavigationBar = v.findViewById(R.id.bottom_app_bar);


        mRecentDownloadsSubtitle = v.findViewById(R.id.recent_downloads_subtitle);
        mRecentDownloadsCard.setOnClickListener(view ->
                mStartForResult.launch(new Intent(mActivity, DownloadsActivity.class)));

        View vaultCard = v.findViewById(R.id.home_vault_card);
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

        // No download-count badge on the bottom bar in normal home —
        // the active strip card above already shows what's downloading
        // (with filenames + progress + a 3-row cap), so the red dot
        // would signal a strict subset of what the card surfaces.
        // BrowserFragment keeps the badge since the strip isn't
        // visible there.

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
            applyActiveStripVisibility();
        });
        mRecentDownloadsViewModel.getFinishedCount().observe(getViewLifecycleOwner(), count -> {
            mLastFinishedCount = count;
            bindDownloadsSubtitle();
        });
        mRecentDownloadsViewModel.getFinishedSize().observe(getViewLifecycleOwner(), size -> {
            mLastFinishedSize = size == null ? 0L : size;
            bindDownloadsSubtitle();
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

        // Background-media strip — visible iff GeckoMediaController has a
        // current session (something is playing or recently paused).
        // currentSessionId == 0 means no media surface; hide the strip.
        // isPlaying drives the PLAYING / PAUSED label so the user can
        // tell at a glance whether tapping the card resumes or just
        // switches.
        mGeckoMediaController.getCurrentSessionIdLiveData().observe(getViewLifecycleOwner(),
                sessionId -> bindMediaStrip());
        mGeckoMediaController.getIsPlayingLiveData().observe(getViewLifecycleOwner(),
                playing -> bindMediaStrip());

        mRecentDownloadsCard.setVisibility(View.VISIBLE);
        applyActiveStripVisibility();

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
        mGeckoToolbar.updateTheme(mActivity, false);
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
        mOnBoardingCard = null;
        mRecentDownloadsCard = null;
        mActiveStrip = null;
        mActiveStripRows = null;
        stopActiveStripPulse();
        mActiveStripIcon = null;
        mHomeVaultSubtitle = null;
        mRecentDownloadsSubtitle = null;
        mHomeMediaStrip = null;
        mHomeMediaIcon = null;
        mHomeMediaLabel = null;
        mHomeMediaTitle = null;
        mHomeMediaSubtitle = null;
        mHomeMediaToggle = null;
    }

    /**
     * Active-strip visibility tracks whether any non-vault download is
     * in PROGRESS / QUEUED. When at least one is live, bind the rows
     * and start the flame pulse; otherwise hide the card and stop the
     * animator so it doesn't burn cycles off-screen.
     */
    private void applyActiveStripVisibility() {
        if (mActiveStrip == null) return;
        boolean hasActive = mLastActiveList != null && !mLastActiveList.isEmpty();
        mActiveStrip.setVisibility(hasActive ? View.VISIBLE : View.GONE);
        if (hasActive) {
            bindActiveStrip(mLastActiveList);
            startActiveStripPulse();
        } else {
            stopActiveStripPulse();
        }
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
                        files, Utils.readableFileSize(mLastFinishedSize))
                : files;
        mRecentDownloadsSubtitle.setVisibility(View.VISIBLE);
        mRecentDownloadsSubtitle.setText(text);
    }

    /**
     * Inflates one compact row per in-flight regular download (DAO
     * caps the list at 3, so the strip height is bounded). Each row
     * carries its own filename + percent label + LinearProgressIndicator;
     * indeterminate while the file is QUEUED or 'live' (size not yet
     * known), determinate once the percentage is real.
     *
     * <p>Re-inflates from scratch on every observer tick rather than
     * trying to diff in place — N is tiny, the rows are cheap, and
     * the visible churn is bounded.</p>
     */
    private void bindActiveStrip(@NonNull java.util.List<DownloadEntity> active) {
        if (mActiveStripRows == null) return;
        mActiveStripRows.removeAllViews();
        LayoutInflater inflater = LayoutInflater.from(mActiveStripRows.getContext());
        int onContainer = MaterialColors.getColor(
                mActiveStripRows, com.google.android.material.R.attr.colorOnPrimaryContainer);
        int trackAlpha = androidx.core.graphics.ColorUtils.setAlphaComponent(onContainer, 0x3D);
        for (DownloadEntity item : active) {
            View row = inflater.inflate(R.layout.view_home_active_download_row, mActiveStripRows, false);
            bindActiveStripRow(row, item, trackAlpha);
            mActiveStripRows.addView(row);
        }
    }

    private void bindActiveStripRow(@NonNull View row, @NonNull DownloadEntity item, int trackAlpha) {
        TextView title = row.findViewById(R.id.active_download_title);
        TextView percent = row.findViewById(R.id.active_download_percent);
        LinearProgressIndicator bar = row.findViewById(R.id.active_download_bar);
        // Track colour: theme attr + alpha can't be combined in XML,
        // and the M3 default (colorSecondary, yellow in Firedown's
        // palette) fights the orange surface. Pin the track to
        // colorOnPrimaryContainer at ~24% alpha so it reads as a
        // subtle ghost of the indicator rather than a competing colour.
        bar.setTrackColor(trackAlpha);

        title.setText(item.getFileName());
        boolean live = item.getFileIsLive();
        boolean queued = item.getFileStatus() == Download.QUEUED;
        boolean indeterminate = live || queued;
        bar.setIndeterminate(indeterminate);
        if (queued) {
            percent.setText(R.string.download_queued);
        } else if (live) {
            percent.setText(Utils.readableFileSize(item.getFileSize()));
        } else {
            int pct = item.getFileProgress();
            bar.setProgress(pct);
            percent.setText(String.format(java.util.Locale.US, "%d%%", pct));
        }
    }

    /**
     * Binds the home media strip from the {@link GeckoMediaController}'s
     * current session. Re-runs on every emission from either
     * currentSessionId or isPlaying — both observers route here so the
     * label flips reactively between PLAYING and PAUSED as the user
     * pauses / resumes from the notification.
     *
     * <p>Strip visibility: shown iff there's a current session AND we
     * have metadata for it. Hidden otherwise (no media on screen → no
     * card on home).</p>
     *
     * <p>Image source priority: iconBitmap (favicon bitmap) → Glide
     * load of favicon URL — the card represents the playing tab, so
     * the tab favicon is the right identity (album art goes on the
     * media notification, not here). Title priority: metadata title
     * → metadata album → metadata URL (domain). Subtitle is artist
     * when present, otherwise the domain — never both, to avoid
     * 'YouTube · youtube.com' duplication.</p>
     */
    private void bindMediaStrip() {
        if (mHomeMediaStrip == null) return;
        int sessionId = mGeckoMediaController.getCurrentSessionId();
        if (sessionId == 0) {
            mHomeMediaStrip.setVisibility(View.GONE);
            return;
        }
        com.solarized.firedown.geckoview.media.GeckoMetaData meta =
                mGeckoMediaController.getGeckoMetaData();
        if (meta == null) {
            mHomeMediaStrip.setVisibility(View.GONE);
            return;
        }
        mHomeMediaStrip.setVisibility(View.VISIBLE);

        Boolean playingValue = mGeckoMediaController.getIsPlayingLiveData().getValue();
        boolean playing = playingValue != null && playingValue;
        mHomeMediaLabel.setText(playing ? R.string.home_media_playing : R.string.home_media_paused);
        // Toggle button mirrors the playing state — pause icon when
        // playing (tap → pause), play icon when paused (tap → resume).
        // contentDescription announces the *current* state for a
        // screen reader (the action to take is the inverse).
        if (mHomeMediaToggle != null) {
            mHomeMediaToggle.setImageResource(playing
                    ? R.drawable.ic_pause_24
                    : R.drawable.ic_play_arrow_24);
            mHomeMediaToggle.setContentDescription(getString(playing
                    ? R.string.home_media_playing
                    : R.string.home_media_paused));
        }

        String title = meta.getTitle();
        if (title == null || title.isEmpty()) title = meta.getAlbum();
        if (title == null || title.isEmpty()) title = meta.getUrl();
        mHomeMediaTitle.setText(title);

        String subtitle = meta.getArtist();
        if (subtitle == null || subtitle.isEmpty()) {
            // Skip the URL if it's identical to the title (already shown).
            String urlPart = meta.getUrl();
            subtitle = (urlPart != null && !urlPart.equals(title)) ? urlPart : null;
        }
        if (subtitle != null && !subtitle.isEmpty()) {
            mHomeMediaSubtitle.setVisibility(View.VISIBLE);
            mHomeMediaSubtitle.setText(subtitle);
        } else {
            mHomeMediaSubtitle.setVisibility(View.GONE);
        }

        // Use the tab's favicon, not the page's MediaSession artwork —
        // the card reads as 'tab playing in the background' and the
        // tab's identity is its favicon. Album art looks great on the
        // notification but here it competes with the playing tab's
        // identity (and pages without artwork would fall through to
        // the favicon anyway, so we'd ship two visual styles).
        android.graphics.Bitmap favicon = meta.getIconBitmap();
        if (favicon != null) {
            mHomeMediaIcon.setImageBitmap(favicon);
        } else if (meta.getIcon() != null && !meta.getIcon().isEmpty()) {
            com.solarized.firedown.GlideHelper.load(meta.getIcon(), meta.getUrl(),
                    mHomeMediaIcon, new com.bumptech.glide.request.RequestOptions());
        } else {
            mHomeMediaIcon.setImageDrawable(null);
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
            // tap-target gives the bookmarks list a one-tap entry.
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