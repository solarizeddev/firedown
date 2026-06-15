package com.solarized.firedown.phone.fragments;

import android.content.Intent;
import android.icu.text.CompactDecimalFormat;
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
import androidx.appcompat.widget.AppCompatImageView;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleEventObserver;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.load.resource.bitmap.RoundedCorners;
import com.bumptech.glide.request.RequestOptions;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.solarized.firedown.GlideHelper;
import com.solarized.firedown.ui.IncognitoColors;
import com.solarized.firedown.Keys;
import com.solarized.firedown.Preferences;
import com.solarized.firedown.R;
import com.solarized.firedown.geckoview.GeckoUblockHelper;
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
import com.solarized.firedown.geckoview.GeckoState;
import com.solarized.firedown.geckoview.GeckoToolbar;
import com.solarized.firedown.manager.DownloadRequest;
import com.solarized.firedown.manager.RunnableManager;

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
import com.solarized.firedown.utils.WebUtils;

import dagger.hilt.android.AndroidEntryPoint;
import java.util.ArrayList;
import java.util.List;
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
    private MaterialCardView mContinueCard;
    private AppCompatImageView mContinueIcon;
    // Continue-browsing favicon: rounded to match the M3 chip behind it (a
    // full-bleed square favicon gets rounded; a transparent glyph is
    // unaffected). Built in onCreateView once resources are available.
    private RequestOptions mContinueIconOptions = new RequestOptions();
    private View mHomeScroll;
    private TextView mContinueSubtitle;
    // Live privacy line (folds in the old Trackers card): "N trackers blocked ·
    // ~X saved", tappable → trackers info sheet.
    private View mPrivacyRow;
    private TextView mPrivacyText;
    // Total-downloaded chip beside the trackers chip — its own tap target
    // (→ Downloads). Hidden, with the "·" divider, until something's downloaded.
    private View mDownloadsRow;
    private TextView mDownloadsText;
    private View mStatSeparator;
    // Active-download banner (the one coloured surface; shown while downloading).
    private View mDownloadBanner;
    private TextView mDownloadStatus;
    private TextView mDownloadName;
    private LinearProgressIndicator mDownloadBar;
    @Nullable private DownloadEntity mActiveEntity;
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

        // 'Continue browsing' card - resumes the most-recently-used non-home
        // tab (bound below; tap handler set there since it needs the tab id).
        mContinueCard = v.findViewById(R.id.continue_browsing_card);
        mContinueIcon = v.findViewById(R.id.continue_browsing_icon);
        mContinueSubtitle = v.findViewById(R.id.continue_browsing_subtitle);
        mContinueIconOptions = RequestOptions.bitmapTransform(new RoundedCorners(
                getResources().getDimensionPixelOffset(R.dimen.icon_rounded)));

        mHomeScroll = v.findViewById(R.id.home_scroll);
        mBottomNavigationBar = v.findViewById(R.id.bottom_app_bar);


        // Live privacy line — the old Trackers card, folded into one quiet row.
        // Text bound from uBlock's cumulative blocked count (below); tapping the
        // row opens the same contextual trackers info sheet the card used to.
        mPrivacyRow = v.findViewById(R.id.home_privacy_row);
        mPrivacyText = v.findViewById(R.id.home_privacy_text);
        if (mPrivacyRow != null) {
            mPrivacyRow.setOnClickListener(view ->
                    TrackersInfoSheet.show(getChildFragmentManager()));
        }

        // Downloaded-total chip: independent tap target → Downloads.
        mDownloadsRow = v.findViewById(R.id.home_downloads_row);
        mDownloadsText = v.findViewById(R.id.home_downloads_text);
        mStatSeparator = v.findViewById(R.id.home_stat_separator);
        if (mDownloadsRow != null) {
            mDownloadsRow.setOnClickListener(view ->
                    mStartForResult.launch(new Intent(mActivity, DownloadsActivity.class)));
        }

        // Active-download banner — the one coloured surface, shown only while
        // downloading (bound below). Tap → Downloads; the X cancels the active
        // download via the same DOWNLOAD_DELETE service intent the downloads UI
        // uses.
        mDownloadBanner = v.findViewById(R.id.home_download_banner);
        mDownloadStatus = v.findViewById(R.id.home_download_status);
        mDownloadName = v.findViewById(R.id.home_download_name);
        mDownloadBar = v.findViewById(R.id.home_download_bar);
        if (mDownloadBanner != null) {
            mDownloadBanner.setOnClickListener(view ->
                    mStartForResult.launch(new Intent(mActivity, DownloadsActivity.class)));
        }
        View downloadCancel = v.findViewById(R.id.home_download_cancel);
        if (downloadCancel != null) {
            downloadCancel.setOnClickListener(view -> cancelActiveDownload());
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

        // 'Continue browsing' — the one quiet utility row that jumps back into
        // the user's last browsing tab. Driven by the tab list: shown only when a
        // non-home tab exists, hidden otherwise (see bindContinueBrowsing).
        mGeckoStateViewModel.getTabs().observe(
                getViewLifecycleOwner(), this::bindContinueBrowsing);

        // Active-download banner — the most recent in-progress / queued download
        // with a live bar; hidden when none are active. Re-emits on each
        // progress write, so the bar animates.
        mRecentDownloadsViewModel.getActiveDownloads().observe(
                getViewLifecycleOwner(), this::bindDownloadBanner);

        // Live trackers footnote. firedown.js pushes uBlock's cumulative
        // blocked value periodically; format with locale-aware grouping
        // separators so 12345 reads as '12,345' / '12.345'. Just the count —
        // no estimated-bytes figure (kept deliberately minimal). Zero case →
        // 'Protection active' between app start and the extension's first push.
        mGeckoUblockHelper.getCumulativeBlockedLive().observe(getViewLifecycleOwner(), blocked -> {
            if (mPrivacyText == null) return;
            long n = blocked == null ? 0L : blocked;
            if (n <= 0) {
                mPrivacyText.setText(R.string.home_trackers_subtitle_idle);
                return;
            }
            // Compact, locale-aware count ("10.5K" / "1.4M" / "1,4 Mio.") — bounds
            // the chip width as the cumulative count climbs to 6–7 digits, and
            // reads cleaner than a long comma-grouped number. The shield icon
            // carries the "blocked" meaning, so the label is just "N trackers".
            CompactDecimalFormat fmt = CompactDecimalFormat.getInstance(
                    Locale.getDefault(), CompactDecimalFormat.CompactStyle.SHORT);
            fmt.setMaximumFractionDigits(1);
            mPrivacyText.setText(getString(R.string.home_privacy_line, fmt.format(n)));
        });

        // Total downloaded — the downloader half of the home's identity line:
        // the live sum of finished regular (non-vault) download sizes. The chip
        // and its "·" divider stay hidden until there's at least one finished
        // download, so a fresh install shows only the trackers stat (never a sad
        // '0 B downloaded').
        mRecentDownloadsViewModel.getFinishedSize().observe(getViewLifecycleOwner(), size -> {
            long bytes = size == null ? 0L : size;
            boolean show = bytes > 0;
            if (mDownloadsRow != null) {
                mDownloadsRow.setVisibility(show ? View.VISIBLE : View.GONE);
            }
            if (mStatSeparator != null) {
                mStatSeparator.setVisibility(show ? View.VISIBLE : View.GONE);
            }
            if (show && mDownloadsText != null) {
                // "<size> saved" — a short trailing word balances the trackers
                // chip; "saved" translates more compactly than "downloaded" (the
                // ⬇ icon already conveys it was a download). Size is locale-
                // formatted by readableFileSize.
                mDownloadsText.setText(getString(
                        R.string.home_downloads_line, Utils.readableFileSize(bytes)));
            }
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
        mContinueCard = null;
        mContinueIcon = null;
        mContinueSubtitle = null;
        mPrivacyRow = null;
        mPrivacyText = null;
        mDownloadsRow = null;
        mDownloadsText = null;
        mStatSeparator = null;
        mDownloadBanner = null;
        mDownloadStatus = null;
        mDownloadName = null;
        mDownloadBar = null;
        mActiveEntity = null;
    }

    /**
     * Binds the 'Continue browsing' card from the tab list: picks the
     * most-recently-used NON-home tab (by lastAccess, stamped on every tab
     * switch) and wires the tap to resume it via {@link #openSessionId(int)}.
     * The icon is that tab's FAVICON (falling back to the generated
     * domain-letter tile when the site has none, the app-wide favicon
     * pattern); the subtitle is the tab's title, falling back to its domain.
     * Hidden when there's no non-home tab (a fresh install / all tabs closed),
     * so the card never offers a dead resume. The home tab itself is excluded
     * (isHome) - resuming "to home" from home is a no-op.
     */
    private void bindContinueBrowsing(@Nullable List<GeckoStateEntity> tabs) {
        if (mContinueCard == null) return;
        GeckoStateEntity last = null;
        if (tabs != null) {
            for (GeckoStateEntity e : tabs) {
                if (e == null || e.isHome()) continue;
                if (last == null || e.getLastAccess() > last.getLastAccess()) {
                    last = e;
                }
            }
        }
        if (last == null) {
            mContinueCard.setVisibility(View.GONE);
            return;
        }
        final int sessionId = last.getId();
        mContinueCard.setVisibility(View.VISIBLE);
        mContinueCard.setOnClickListener(view -> openSessionId(sessionId));

        if (mContinueIcon != null) {
            // Site favicon as a rounded tile (full colour); GlideHelper falls
            // back to the generated domain-letter tile when the tab has no icon
            // - the same favicon path the tabs / bookmarks lists use.
            GlideHelper.load(last.getIcon(), last.getUri(), mContinueIcon, mContinueIconOptions);
        }

        if (mContinueSubtitle != null) {
            String label = last.getTitle();
            if (label == null || label.trim().isEmpty()) {
                label = WebUtils.getDomainName(last.getUri());
            }
            if (label == null || label.trim().isEmpty()) {
                mContinueSubtitle.setVisibility(View.GONE);
            } else {
                mContinueSubtitle.setText(label);
                mContinueSubtitle.setVisibility(View.VISIBLE);
            }
        }
    }

    /**
     * Binds the active-download banner (the one coloured surface). Shows the
     * most recent in-progress / queued download with a live bar; hides the
     * banner when none are active. The stream re-emits on each progress write,
     * so the bar tracks the download and the banner removes itself when the
     * last active download finishes.
     */
    private void bindDownloadBanner(@Nullable List<DownloadEntity> active) {
        if (mDownloadBanner == null) return;
        DownloadEntity top = null;
        if (active != null) {
            for (DownloadEntity e : active) {
                if (e != null) {
                    top = e;
                    break;
                }
            }
        }
        mActiveEntity = top;
        if (top == null) {
            mDownloadBanner.setVisibility(View.GONE);
            return;
        }
        mDownloadBanner.setVisibility(View.VISIBLE);
        mDownloadName.setText(top.getFileName());

        boolean retrieving = top.getFileIsLive();
        boolean queued = top.getFileStatus() == Download.QUEUED;
        boolean indeterminate = retrieving || queued;
        mDownloadBar.setIndeterminate(indeterminate);
        if (!indeterminate) {
            mDownloadBar.setProgress(top.getFileProgress());
        }

        // 'Downloading…' + percent / running size; the layout's textAllCaps
        // uppercases it ('DOWNLOADING… · 46%').
        String label = getString(R.string.downloading);
        String detail;
        if (retrieving) {
            detail = Utils.readableFileSize(top.getFileSize());
        } else if (queued) {
            detail = null;
        } else {
            detail = String.format(Locale.US, "%d%%", top.getFileProgress());
        }
        mDownloadStatus.setText(detail == null ? label : (label + " · " + detail));
    }

    /**
     * Cancels the currently-shown active download via the same DOWNLOAD_DELETE
     * service intent the downloads list uses (stops the runnable + removes the
     * partial file). The banner then hides on the next stream emit.
     */
    private void cancelActiveDownload() {
        if (mActiveEntity == null) return;
        ArrayList<DownloadEntity> list = new ArrayList<>(1);
        list.add(mActiveEntity);
        Intent intent = new Intent(mActivity, RunnableManager.class);
        intent.putExtra(Keys.ITEM_LIST_ID, list);
        intent.setAction(IntentActions.DOWNLOAD_DELETE);
        mActivity.startService(intent);
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