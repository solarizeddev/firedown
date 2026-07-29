package com.solarized.firedown.phone.fragments;

import android.animation.Animator;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.Intent;
import android.icu.text.CompactDecimalFormat;
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
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleEventObserver;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.card.MaterialCardView;

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
import com.solarized.firedown.data.models.RecentDownloadsViewModel;
import com.solarized.firedown.geckoview.GeckoState;
import com.solarized.firedown.geckoview.GeckoToolbar;
import com.solarized.firedown.geckoview.GeckoUblockHelper;
import com.solarized.firedown.manager.DownloadRequest;

import com.solarized.firedown.phone.DownloadsActivity;
import com.solarized.firedown.phone.SettingsActivity;
import com.solarized.firedown.sync.CloudBackupManager;
import com.solarized.firedown.sync.VaultBackupWorker;
import com.solarized.firedown.sync.StorageApiClient;

import androidx.work.WorkInfo;
import androidx.work.WorkManager;

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

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Locale;

import javax.inject.Inject;

import dagger.hilt.android.AndroidEntryPoint;


@AndroidEntryPoint
public class HomeFragment extends BaseBrowserFragment implements BottomNavigationBar.OnBottomBarListener,
        AutoCompleteEditText.OnCommitListener, AutoCompleteEditText.OnFilterListener, AutoCompleteEditText.OnFocusChangedListener,
        AutoCompleteEditText.OnTextChangedListener, AutoCompleteEditText.OnSearchStateChangeListener,
        GeckoToolbar.OnToolbarListener , OnItemClickListener {


    private static final String TAG = HomeFragment.class.getName();
    /** Resting-line fade-in (ms) — alpha only; see fadeInRestLine. */
    private static final long REST_LINE_FADE_MS = 300;
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
    // The home is a calm minimalist landing — the centred brand mark plus ONE
    // quiet subtitle line of two independently-tappable live figures: trackers
    // blocked (→ trackers info sheet) and total downloaded (→ Downloads). Each
    // half hides at 0 and the divider hides unless both show, so a fresh install
    // reads just the wordmark. Everything else (tabs, downloads badge, vault)
    // lives in the bottom bar / menu — no dashboard.
    private RecentDownloadsViewModel mRecentDownloadsViewModel;
    private View mSubtitle;
    // The two figures are MaterialCardView pills (own the tap target + ripple +
    // visibility); their text lives in the inner *_text TextViews.
    private View mSubtitleBlocked;
    private TextView mSubtitleBlockedText;
    private View mSubtitleSep;
    private View mSubtitleSaved;
    private TextView mSubtitleSavedText;

    // Cloud Backup slot/card (home v4): ALL cloud state on home lives in this
    // ONE slot under the subtitle — never as a third subtitle counter (that
    // was built and reverted; see the layout comment). Priority order, see
    // applyBackupPill: "Paused" CARD (metered credit ran out, setUp-gated),
    // "Backing up…" CHIP (a transfer is RUNNING), "Waiting to back up" CHIP
    // (enqueued-only), then the resting "N backed up" QUIET LINE, and nothing
    // when the account isn't set up or has nothing in it.
    /** Fixed-height frame hosting BOTH calm presentations (transfer chip +
     *  resting line). Kept VISIBLE with INVISIBLE children for a set-up
     *  account so the resting total — a late NETWORK value — fades in without
     *  growing the centred brand block and shifting the flame. See the
     *  home_backup_slot layout comment for the full rationale. */
    private View mBackupSlot;
    /** The transfer chip — "Backing up…" / "Waiting to back up" ONLY. The
     *  resting total moved to {@link #mBackupRest}; the chip's ground, ink
     *  and upload glyph are static XML now. */
    private MaterialCardView mBackupPill;
    private TextView mBackupPillText;
    /** The resting quiet line — "N backed up" in the counters' own grammar
     *  (transparent card, onSurfaceVariant ink, 12sp, plain 14dp cloud). See
     *  the layout comment for why the chip treatment was demoted. */
    private MaterialCardView mBackupRest;
    private TextView mBackupRestText;
    /** The deadline card — a different SILHOUETTE from the chip, never shown
     *  at the same time. See applyBackupPill. */
    private MaterialCardView mBackupCard;
    private TextView mBackupCardTitle;
    private TextView mBackupCardDetail;
    /** An identified backup worker is actually TRANSFERRING right now. */
    private boolean mCloudRunning;
    /** …or merely enqueued (constraints unmet / retry backoff) — rendered as
     *  "Waiting to back up", never "Backing up…". */
    private boolean mCloudQueued;
    /** Where a calm-slot tap goes (chip or resting line — one shared
     *  listener), decided when the state was RENDERED (transfer/resting →
     *  Backups list, Paused → Cloud screen). */
    private boolean mPillToFiles;
    /** Drops a stale loadStatus result when a newer refresh started (two rapid
     *  resumes complete in NETWORK order, not call order). */
    private int mCloudStatusGen;
    private StorageApiClient.Quota mCloudQuota;
    /** Lifetime bytes held in Cloud Backup — the resting pill's figure.
     *  <b>-1 = unknown</b> (never pulled, or the pull failed), which renders as
     *  NO pill rather than "0 B": the total is the one cloud fact we cannot
     *  derive locally, so an unknown must stay silent instead of claiming a
     *  number. Cleared to -1 whenever the account isn't set up. */
    private long mCloudTotalBytes = -1;
    // The brand flame doubles as the live "a download is running" indicator:
    // a soft ember glow that breathes behind the logo while the active+queued
    // count (the same signal as the bottom-bar badge) is > 0, and is GONE
    // otherwise so the idle home is just the wordmark. Identity-as-status, no
    // extra widget — status that's actionable lives on the bottom bar (the
    // badge); the home only gives the calm ambient heartbeat.
    private View mBrandGlow;
    private Animator mBrandGlowAnimator;
    @Inject
    GeckoUblockHelper mGeckoUblockHelper;
    @Inject
    CloudBackupManager mCloudBackup;


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
        // Cloud Backup status can change while away (a backup finishes, credit is
        // added, or it's set up for the first time) — recompute the home line.
        refreshCloudStatus();
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


        // Brand mark + the two-figure subtitle. Each half is its own tap target:
        // blocked → the trackers info sheet, saved → Downloads. (VaultActivity /
        // DownloadsActivity own their own auth/result handling.)
        mBrandGlow = v.findViewById(R.id.home_brand_glow);
        mSubtitle = v.findViewById(R.id.home_subtitle);
        mSubtitleBlocked = v.findViewById(R.id.home_subtitle_blocked);
        mSubtitleBlockedText = v.findViewById(R.id.home_subtitle_blocked_text);
        mSubtitleSep = v.findViewById(R.id.home_subtitle_sep);
        mSubtitleSaved = v.findViewById(R.id.home_subtitle_saved);
        mSubtitleSavedText = v.findViewById(R.id.home_subtitle_saved_text);
        // Within-segment graceful wrap, the level the Flow can't cover: a SINGLE
        // translated segment (es "Creando copia de seguridad…") plus font scale
        // 2.0 on a narrow screen can be wider than the whole line by itself — the
        // Flow would give it its own row, but the row itself would clip at the
        // screen edge. Cap each counter's text to the line's real width so the
        // extreme case wraps INSIDE its chip (two centred lines) instead.
        if (mSubtitle != null) {
            mSubtitle.addOnLayoutChangeListener(
                    (view, l, t, r, b, oldL, oldT, oldR, oldB) -> {
                        int chipPadding = Math.round(
                                24 * getResources().getDisplayMetrics().density);
                        int cap = (r - l) - chipPadding;
                        if (cap > 0) {
                            applyTextWidthCap(mSubtitleBlockedText, cap);
                            applyTextWidthCap(mSubtitleSavedText, cap);
                        }
                    });
        }
        if (mSubtitleBlocked != null) {
            mSubtitleBlocked.setOnClickListener(view ->
                    TrackersInfoSheet.show(getChildFragmentManager()));
        }
        if (mSubtitleSaved != null) {
            mSubtitleSaved.setOnClickListener(view ->
                    mStartForResult.launch(new Intent(mActivity, DownloadsActivity.class)));
        }

        // Cloud Backup calm slot — see applyBackupPill for the state machine.
        // Tap routes by the state the slot CURRENTLY SHOWS (mPillToFiles is
        // set at render, so a tap can't race a state flip between render and
        // click): transfer/resting states → the Backups list, Paused → the
        // Cloud status screen. ONE listener shared by the chip and the resting
        // line — they are two presentations of the same door, and a shared
        // lambda can't drift.
        mBackupSlot = v.findViewById(R.id.home_backup_slot);
        mBackupPill = v.findViewById(R.id.home_backup_pill);
        mBackupPillText = v.findViewById(R.id.home_backup_pill_text);
        mBackupRest = v.findViewById(R.id.home_backup_rest);
        mBackupRestText = v.findViewById(R.id.home_backup_rest_text);
        mBackupCard = v.findViewById(R.id.home_backup_card);
        mBackupCardTitle = v.findViewById(R.id.home_backup_card_title);
        mBackupCardDetail = v.findViewById(R.id.home_backup_card_detail);
        View.OnClickListener openCloud = view -> {
            Intent intent = new Intent(mActivity, SettingsActivity.class);
            intent.putExtra(mPillToFiles
                            ? SettingsActivity.EXTRA_OPEN_CLOUD_BACKUP_FILES
                            : SettingsActivity.EXTRA_OPEN_CLOUD_BACKUP,
                    true);
            startActivity(intent);
        };
        if (mBackupPill != null) {
            mBackupPill.setOnClickListener(openCloud);
        }
        if (mBackupRest != null) {
            mBackupRest.setOnClickListener(openCloud);
        }
        if (mBackupCard != null) {
            // The card only ever renders the paused state, so unlike the pill it
            // needs no mPillToFiles routing — it always opens the Cloud status
            // screen, where the top-up lives.
            mBackupCard.setOnClickListener(view -> {
                Intent intent = new Intent(mActivity, SettingsActivity.class);
                intent.putExtra(SettingsActivity.EXTRA_OPEN_CLOUD_BACKUP, true);
                startActivity(intent);
            });
        }
        WorkManager.getInstance(mActivity.getApplicationContext())
                .getWorkInfosByTagLiveData(CloudBackupManager.WORK_TAG)
                .observe(getViewLifecycleOwner(), infos -> {
                    // RUNNING and ENQUEUED tracked SEPARATELY: an enqueued
                    // worker may be hours from transferring (retry backoff, or
                    // waiting for network offline), and rendering it as
                    // "Backing up…" was the audit's honesty gap — it gets its
                    // own "Waiting to back up" copy instead.
                    boolean running = false;
                    boolean queued = false;
                    if (infos != null) {
                        for (WorkInfo wi : infos) {
                            // Only IDENTIFIED backup workers count: restores and
                            // legacy pre-tag WorkSpecs render no row anywhere, so
                            // they must not raise a transfer pill pointing at an
                            // empty list (the on-device ghost state).
                            if (!hasBackupTag(wi)) {
                                continue;
                            }
                            WorkInfo.State st = wi.getState();
                            if (st == WorkInfo.State.RUNNING) {
                                running = true;
                                break; // strongest state — nothing can upgrade it
                            }
                            if (st == WorkInfo.State.ENQUEUED) {
                                queued = true;
                            }
                        }
                    }
                    mCloudRunning = running;
                    mCloudQueued = queued;
                    applyBackupPill();
                });

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

        // Subtitle, left half — lifetime trackers blocked (uBlock cumulative,
        // pushed by firedown.js). Compact + locale-aware ("10.5K"); hidden at 0.
        mGeckoUblockHelper.getCumulativeBlockedLive().observe(getViewLifecycleOwner(), blocked -> {
            if (mSubtitleBlocked == null || mSubtitleBlockedText == null) return;
            long n = blocked == null ? 0L : blocked;
            if (n > 0) {
                CompactDecimalFormat fmt = CompactDecimalFormat.getInstance(
                        Locale.getDefault(), CompactDecimalFormat.CompactStyle.SHORT);
                fmt.setMaximumFractionDigits(1);
                mSubtitleBlockedText.setText(getString(R.string.home_subtitle_blocked, fmt.format(n)));
                mSubtitleBlocked.setVisibility(View.VISIBLE);
            } else {
                mSubtitleBlocked.setVisibility(View.GONE);
            }
            updateSubtitleVisibility();
        });

        // Subtitle, right half — total bytes of finished regular downloads,
        // locale-formatted ("9.5 GB"); hidden at 0.
        mRecentDownloadsViewModel.getFinishedSize().observe(getViewLifecycleOwner(), size -> {
            if (mSubtitleSaved == null || mSubtitleSavedText == null) return;
            long bytes = size == null ? 0L : size;
            if (bytes > 0) {
                mSubtitleSavedText.setText(getString(R.string.home_subtitle_saved,
                        Utils.readableFileSize(bytes)));
                mSubtitleSaved.setVisibility(View.VISIBLE);
            } else {
                mSubtitleSaved.setVisibility(View.GONE);
            }
            updateSubtitleVisibility();
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
        mBrandGlow = null;
        mHomeScroll = null;
        mAutoCompleteView = null;
        mGeckoToolbar = null;
        mNewTabView = null;
        mBottomNavigationBar = null;
        mSubtitle = null;
        mSubtitleBlocked = null;
        mSubtitleBlockedText = null;
        mSubtitleSep = null;
        mSubtitleSaved = null;
        mSubtitleSavedText = null;
        mBackupSlot = null;
        mBackupPill = null;
        mBackupPillText = null;
        mBackupRest = null;
        mBackupRestText = null;
        mBackupCard = null;
        mBackupCardTitle = null;
        mBackupCardDetail = null;
    }

    /** Guarded setter — setMaxWidth always requestLayout()s, so an unguarded
     *  call from the layout-change listener would loop the layout pass. */
    private static void applyTextWidthCap(TextView text, int cap) {
        if (text != null && text.getMaxWidth() != cap) {
            text.setMaxWidth(cap);
        }
    }

    /**
     * Shows the subtitle row only when at least one counter has a value, and
     * the middle-dot divider only between visible neighbours. So a fresh
     * install shows just the wordmark; any subset composes cleanly:
     * "10.5K blocked · 9.5 GB saved". Called by both counters' bindings (they
     * update on independent cadences). Cloud Backup state deliberately does
     * NOT live here — it's the bottom pill (see the field doc), which is
     * state-only and transient.
     */
    private void updateSubtitleVisibility() {
        if (mSubtitle == null) return;
        boolean blocked = mSubtitleBlocked != null && mSubtitleBlocked.getVisibility() == View.VISIBLE;
        boolean saved = mSubtitleSaved != null && mSubtitleSaved.getVisibility() == View.VISIBLE;
        // The dot shows only BETWEEN two visible counters. With exactly two
        // segments that is just "both visible" — a third one made this an
        // "anything to my left?" test, and its separator was what ended up
        // orphaned at the end of a wrapped row. Two segments cannot wrap at
        // real values, so the line stays one row.
        if (mSubtitleSep != null) {
            mSubtitleSep.setVisibility(blocked && saved ? View.VISIBLE : View.GONE);
        }
        mSubtitle.setVisibility(blocked || saved ? View.VISIBLE : View.GONE);
    }

    /**
     * Loads the quota when set up (the pill's "Paused" input), then re-renders
     * the pill. Called on resume; the transfer-active state comes from the
     * WorkManager observer wired in onViewCreated.
     */
    private void refreshCloudStatus() {
        if (mBackupPill == null) {
            return;
        }
        if (!mCloudBackup.isSetUp()) {
            // Not set up (fresh install, never used, or erased): clear BOTH
            // inputs so a later render can't resurrect a stale figure, and hide
            // the surfaces now rather than waiting on a network round-trip that
            // will never be made.
            mCloudQuota = null;
            mCloudTotalBytes = -1;
            applyBackupPill();
            return;
        }
        // CACHED-FIRST, the status hero's rule: seed the resting figure from the
        // last successful pull so the pill is already correct on entry, then let
        // the async result update it in place. Rendering only from the network
        // result would pop the pill in ~a second into every resume.
        CloudBackupManager.Status cached = mCloudBackup.lastStatus();
        if (cached == null) {
            // No in-memory snapshot: either a COLD process, or the manager
            // dropped it because usage changed ("Delete backed-up files" nulls
            // mLastStatus). Fall through to the DURABLE total, which separates
            // those two — it survives the process but is cleared by the erase
            // and by the dead-account reconcile, so it answers -1 in exactly the
            // cases where the in-memory null meant "don't trust the old number".
            // This is what stops the pill popping in a second after launch.
            mCloudTotalBytes = mCloudBackup.lastKnownTotalBytes();
        } else if (cached.totalBytes >= 0) {
            mCloudTotalBytes = cached.totalBytes;
        }
        applyBackupPill(); // show promptly with whatever we already have
        // One combined load with the guarded reconciliation: retiring flips
        // isSetUp() false (pill hides); a heal makes the paused check reachable.
        // Gen-guarded so two rapid resumes can't apply results in the wrong
        // order (network order != call order on the manager's pooled executor).
        final int gen = ++mCloudStatusGen;
        mCloudBackup.loadStatus(status -> {
            if (isAdded() && mBackupPill != null && gen == mCloudStatusGen) {
                mCloudQuota = status.quota;
                // -1 means the pull couldn't report a total (offline, transient
                // failure). KEEP the previous figure rather than blanking it —
                // an offline resume must not make the pill vanish and come back.
                if (status.totalBytes >= 0) {
                    mCloudTotalBytes = status.totalBytes;
                }
                applyBackupPill();
            }
        });
    }

    /** Whether a WorkInfo carries the backup identity tags stamped at enqueue
     *  (restores and legacy pre-tag WorkSpecs don't). */
    private static boolean hasBackupTag(WorkInfo wi) {
        for (String tag : wi.getTags()) {
            if (tag.startsWith(VaultBackupWorker.TAG_NAME)) {
                return true;
            }
        }
        return false;
    }

    /** Renders the ONE home cloud slot from the latest transfer/quota/total
     *  state. Four rungs, highest first:
     *
     *  <ol>
     *    <li><b>Paused</b> — metered credit ran out. The CARD, tap → the status
     *        screen. It BEATS both transfer states because in grace every upload
     *        402s at create, so a doomed queued backup rendering "Backing up…"
     *        would hide the one actionable fact (top up).</li>
     *    <li><b>Backing up…</b> — an identified backup worker is RUNNING.</li>
     *    <li><b>Waiting to back up</b> — enqueued only (constraints unmet /
     *        retry backoff; hours may pass with nothing transferring, so it
     *        never claims to be backing up).</li>
     *    <li><b>N backed up</b> — the RESTING state, the lifetime total —
     *        rendered as the QUIET LINE ({@code home_backup_rest}), never the
     *        chip: a standing total earns no fill (see the layout comment for
     *        the demotion's full rationale). This is what a set-up account
     *        sees when nothing is happening.</li>
     *  </ol>
     *
     *  <p><b>What each rung is gated on, and why they differ.</b> The three
     *  states above the resting one are EVIDENCE-based — a paused quota, a live
     *  WorkInfo — and that evidence only exists because the user engaged with
     *  the feature, so they are self-gating (this is why "Backing up…" is
     *  deliberately NOT setUp-gated: the very FIRST backup runs before
     *  markEnabled lands, and suppressing it would blank the pill for exactly
     *  the transfer that most wants reporting). The resting rung has no such
     *  evidence — it is a standing claim — so it takes the strict gate:
     *  {@code isSetUp()} read LIVE here (never a cached copy, so the erase path
     *  can't leave it showing) AND a known, non-zero total. A fresh install
     *  fails both; an erased account fails both; a set-up account that has
     *  backed up nothing yet fails the second and stays silent rather than
     *  saying "0 B".
     *
     *  <p><b>Visibility contract.</b> At most ONE of chip / resting line /
     *  card is ever VISIBLE, and every branch sets ALL THREE (they are
     *  persistent views that flip, so a one-sided set leaves the previous
     *  state on screen). The chip and the line hide as INVISIBLE inside the
     *  fixed-height slot; the slot itself stays up (empty) for a set-up
     *  account so the resting total's late network arrival can't grow the
     *  centred brand block and shift the flame — see the home_backup_slot
     *  layout comment. Everything else drops the slot — the calm home is the
     *  default. */
    private void applyBackupPill() {
        if (mBackupPill == null || mBackupPillText == null
                || mBackupRest == null || mBackupRestText == null) {
            return;
        }
        if (!mSharedPreferences.getBoolean(Preferences.SETTINGS_CLOUD_HOME_STATUS, true)) {
            // The user turned the home status off (Settings → Cloud). It gates
            // EVERY rung including the grace card: a control that says "show
            // backup status on home" and still paints one would be lying, and
            // the deadline is reported on the Cloud screen and the Backups list
            // header regardless. Read live on each render, so returning from
            // Settings applies it on the next resume with no extra plumbing.
            // The slot goes too — a reserved-but-forever-empty band under the
            // subtitle would be dead space the control said wouldn't be there.
            mBackupPill.setVisibility(View.INVISIBLE);
            hideRestLine();
            setCalmSlotVisible(false);
            if (mBackupCard != null) {
                mBackupCard.setVisibility(View.GONE);
            }
            return;
        }
        String text = null;
        boolean attention = false;
        boolean toFiles = false;
        boolean resting = false;
        boolean setUp = mCloudBackup.isSetUp();
        if (setUp && mCloudQuota != null && mCloudQuota.metered && mCloudQuota.readOnly) {
            text = getString(R.string.home_cloud_paused);
            attention = true;
        } else if (mCloudRunning) {
            text = getString(R.string.home_cloud_backing_up);
            toFiles = true;
        } else if (mCloudQueued) {
            text = getString(R.string.home_cloud_waiting);
            toFiles = true;
        } else if (setUp && mCloudTotalBytes > 0) {
            text = getString(R.string.home_cloud_backed_up,
                    Utils.readableFileSize(mCloudTotalBytes));
            toFiles = true;
            resting = true;
        }
        if (text == null) {
            // Nothing to report. The chip/line go INVISIBLE, and the SLOT
            // stays up for a set-up account (height reserved) so a total that
            // arrives a beat later fades in with zero reflow; a not-set-up
            // account drops the slot entirely — the bare fresh-install home.
            mBackupPill.setVisibility(View.INVISIBLE);
            hideRestLine();
            setCalmSlotVisible(setUp);
            if (mBackupCard != null) {
                mBackupCard.setVisibility(View.GONE);
            }
            return;
        }
        mPillToFiles = toFiles;
        // The states are told apart by SHAPE, not colour. A live/promised
        // transfer is a small filled chip (fill is earned by WORK — transient,
        // self-clearing); the resting total is a naked status line in the
        // counters' grammar; the DEADLINE is a wide two-line card with a verb,
        // because it reports 30 days ending in deleted files rather than
        // something that resolves itself. None carries a semantic hue — see
        // the note in values/colors.xml for why the amber container that
        // briefly lived here was removed.
        if (attention) {
            // The alarm replaces the calm slot outright (GONE, not reserved):
            // the card is taller than the slot anyway, and an alarm is allowed
            // to move things.
            mBackupPill.setVisibility(View.INVISIBLE);
            hideRestLine();
            setCalmSlotVisible(false);
            showBackupCard(text);
            return;
        }
        if (mBackupCard != null) {
            mBackupCard.setVisibility(View.GONE);
        }
        setCalmSlotVisible(true);
        if (resting) {
            // Fade only on an actual appearance: applyBackupPill re-runs on
            // every resume and WorkInfo tick, and re-fading a line that is
            // already up would read as a refresh that never happened.
            boolean appearing = mBackupRest.getVisibility() != View.VISIBLE;
            mBackupPill.setVisibility(View.INVISIBLE);
            mBackupRestText.setText(text);
            mBackupRest.setVisibility(View.VISIBLE);
            if (appearing) {
                fadeInRestLine();
            }
        } else {
            hideRestLine();
            mBackupPillText.setText(text);
            mBackupPill.setVisibility(View.VISIBLE);
        }
    }

    /** Shows (reserves) or drops the fixed-height calm slot. GONE — never
     *  INVISIBLE — when dropped: the reservation trick lives on the slot's
     *  CHILDREN; the slot itself is either holding space or absent. */
    private void setCalmSlotVisible(boolean visible) {
        if (mBackupSlot != null) {
            mBackupSlot.setVisibility(visible ? View.VISIBLE : View.GONE);
        }
    }

    /** Hides the resting line, cancelling any fade in flight and resetting
     *  alpha — a cancelled ViewPropertyAnimator leaves alpha wherever it
     *  stopped, and the next appearance must not start half-transparent. */
    private void hideRestLine() {
        if (mBackupRest == null) {
            return;
        }
        mBackupRest.animate().cancel();
        mBackupRest.setAlpha(1f);
        mBackupRest.setVisibility(View.INVISIBLE);
    }

    /** Fades the resting line in — alpha only, no translation: a late network
     *  value should seep in, not mount (the arrival pop was half of why the
     *  old resting chip read as "the component working"). Skipped when the
     *  user has animations off (Settings.Global.ANIMATOR_DURATION_SCALE = 0;
     *  {@link ValueAnimator#areAnimatorsEnabled()} is the platform's read of
     *  it) — the Android analogue of prefers-reduced-motion. */
    private void fadeInRestLine() {
        if (!ValueAnimator.areAnimatorsEnabled()) {
            mBackupRest.setAlpha(1f);
            return;
        }
        mBackupRest.setAlpha(0f);
        mBackupRest.animate().alpha(1f).setDuration(REST_LINE_FADE_MS).start();
    }

    /** Renders the deadline card. The detail line is the COUNTDOWN — "3 days
     *  left before your files are removed" is what makes the state actionable,
     *  where "Paused" only named it — computed from the quota's graceUntil,
     *  which the server already sends, so this needs no API change.
     *
     *  <p>Falls back to the title alone when graceUntil is missing or
     *  unparseable (an older server, a clock skew): the card still says the
     *  backup is paused, it just can't say for how long. Never shows a negative
     *  or zero count — past the deadline the reap is already due, so it reads
     *  "today" rather than a stale number. */
    private void showBackupCard(String title) {
        if (mBackupCard == null || mBackupCardTitle == null) {
            return;
        }
        mBackupCardTitle.setText(title);
        if (mBackupCardDetail != null) {
            String detail = graceCountdown();
            mBackupCardDetail.setText(detail);
            mBackupCardDetail.setVisibility(detail == null ? View.GONE : View.VISIBLE);
        }
        mBackupCard.setVisibility(View.VISIBLE);
    }

    /** Whole days from now until the read-only grace ends, as the localized
     *  plural, or null when the deadline isn't known. */
    @Nullable
    private String graceCountdown() {
        if (mCloudQuota == null || mCloudQuota.graceUntil == null) {
            return null;
        }
        try {
            long days = ChronoUnit.DAYS.between(Instant.now(),
                    OffsetDateTime.parse(mCloudQuota.graceUntil).toInstant());
            int shown = (int) Math.max(1, days);
            return getResources().getQuantityString(
                    R.plurals.home_cloud_grace_days, shown, shown);
        } catch (RuntimeException e) {
            return null;
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
        // On focus with an empty field, load the most-visited tiles (the strip
        // observer fills its own view; showEmpty() reveals it once it has tiles);
        // on blur just clear. showEmpty() paints the clipboard chip immediately.
        if (hasFocus) {
            mAutoCompleteViewModel.loadMostVisited();
        } else {
            mAutoCompleteViewModel.resetEngines();
        }
        mAutoCompleteView.showEmpty();
        mAutoCompleteView.updateVisibility(hasFocus);
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