package com.solarized.firedown.phone.fragments;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.util.Log;
import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.core.view.MenuItemCompat;
import androidx.core.view.MenuProvider;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.ViewModelProvider;
import androidx.navigation.NavOptions;
import androidx.paging.LoadState;
import androidx.recyclerview.widget.ConcatAdapter;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.color.MaterialColors;
import com.google.android.material.snackbar.Snackbar;
import com.solarized.firedown.IntentActions;
import com.solarized.firedown.Preferences;
import com.solarized.firedown.R;
import com.solarized.firedown.data.entity.GeckoStateEntity;
import com.solarized.firedown.data.entity.WebBookmarkEntity;
import com.solarized.firedown.data.models.BrowserURIViewModel;
import com.solarized.firedown.data.models.WebBookmarkViewModel;
import com.solarized.firedown.phone.SettingsActivity;
import com.solarized.firedown.sync.SyncManager;
import com.solarized.firedown.ui.EqualSpacingItemDecoration;
import com.solarized.firedown.ui.OnItemClickListener;
import com.solarized.firedown.ui.diffs.WebBookmarkDiffCallback;
import com.solarized.firedown.ui.adapters.SyncBannerAdapter;
import com.solarized.firedown.ui.adapters.WebBookmarkAdapter;
import com.solarized.firedown.Keys;
import com.solarized.firedown.StoragePaths;
import com.solarized.firedown.utils.NavigationUtils;

import java.io.File;
import java.io.InputStream;
import java.util.HashSet;

import javax.inject.Inject;

import dagger.hilt.android.AndroidEntryPoint;

@AndroidEntryPoint
public class WebBookmarkFragment extends BaseFocusFragment implements OnItemClickListener,
        SyncBannerAdapter.OnBannerListener {

    private static final String TAG = WebBookmarkFragment.class.getName();

    /** Bookmark backup file name + folder, inside Download/Firedown (the
     *  public folder that survives uninstall — kept in step with
     *  DownloadBackupMirror's "backup" subdir). */
    private static final String EXPORT_FILE_NAME = "firedown_bookmarks.html";
    private static final String BACKUP_DIR_NAME = "backup";

    @Inject
    SyncManager mSyncManager;

    @Inject
    SharedPreferences mSyncPrefs;

    private WebBookmarkAdapter mAdapter;
    private WebBookmarkViewModel mWebBookmarkViewModel;
    private BrowserURIViewModel mBrowserURIViewModel;
    private boolean mPendingScrollToTop = false;
    private boolean mIncognito;

    // Sync discoverability (non-incognito only): the toolbar cloud reflects sync
    // state, and a one-time announce banner heads the list while sync is off.
    private SyncBannerAdapter mSyncBannerAdapter;
    private MenuItem mSyncMenuItem;
    private int mSyncState = SyncManager.STATE_OFF;

    // Export writes directly into the public Download/Firedown/backup folder
    // (no picker) — it survives uninstall like the download mirror, so the
    // bookmark backup can be re-imported after a reinstall. Import is a SAF
    // picker (a reinstalled app must take a read grant on the foreign-owned
    // file), filtered to HTML and pre-pointed at that same backup folder.
    // Registered as a field initializer so it exists before the fragment
    // reaches STARTED, as the activity-result API requires.
    private final ActivityResultLauncher<String[]> mImportLauncher =
            registerForActivityResult(new ActivityResultContracts.OpenDocument() {
                @NonNull
                @Override
                public Intent createIntent(@NonNull Context context, @NonNull String[] input) {
                    // SAF filters by MIME, and the OpenDocument contract sends
                    // type=*/* + EXTRA_MIME_TYPES — which many OEM pickers and the
                    // "Recent" view IGNORE, showing every file (png/mp4/…). A
                    // single concrete type filters far more reliably, so override
                    // to text/html and drop the multi-type extra. (Browser
                    // bookmark exports are always text/html.)
                    Intent intent = super.createIntent(context, input);
                    intent.setType("text/html");
                    intent.removeExtra(Intent.EXTRA_MIME_TYPES);
                    // Open in the backup folder where Export writes, so the
                    // bookmark file is right there. Best-effort: providers that
                    // ignore EXTRA_INITIAL_URI just open at their own default.
                    intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI,
                            DocumentsContract.buildDocumentUri(
                                    "com.android.externalstorage.documents",
                                    "primary:Download/Firedown/backup"));
                    return intent;
                }
            }, uri -> { if (uri != null) doImport(uri); });

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Bundle args = getArguments();
        mIncognito = args != null && args.getBoolean(Keys.IS_INCOGNITO, false);
        mWebBookmarkViewModel = new ViewModelProvider(this).get(WebBookmarkViewModel.class);
        // Activity-scoped so BrowserFragment (the OPEN_URI observer)
        // picks up the same event instance we publish here.
        mBrowserURIViewModel = new ViewModelProvider(mActivity).get(BrowserURIViewModel.class);
        mWebBookmarkViewModel.search(null);
        setupBackPress();
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        // Re-inflate against a ContextThemeWrapper that overlays every
        // Material colour token with its incognito variant so the
        // app bar, toolbar, list cards and empty-state surfaces all
        // paint in the same purple-ish palette the incognito browser
        // chrome uses. Window decor + status / nav bar are handled
        // separately by applyWindowIncognitoTheme below.
        if (mIncognito) {
            inflater = inflater.cloneInContext(new ContextThemeWrapper(
                    inflater.getContext(), R.style.ThemeOverlay_FireDown_Incognito));
        }
        View v = inflater.inflate(R.layout.fragment_web_bookmark, container, false);

        mWebBookmarkViewModel.getDispatchedQuery().observe(getViewLifecycleOwner(), q -> mPendingScrollToTop = true);

        setupViews(v);
        setupToolbar(v);

        return v;
    }


    @Override
    public void onViewCreated(@NonNull View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        // Sync window decor + system bars to the mode we were opened
        // in. Without this, the list inherits whatever theming the
        // previous fragment left on the activity window — e.g. an
        // incognito-themed bookmark list opened from normal home would
        // appear with normal-coloured system bars, and the reverse is
        // even more visually broken.
        applyWindowIncognitoTheme(mIncognito);

        // Drop any opaque status-bar tint inherited from a chrome-owning
        // screen so the liftOnScroll AppBarLayout paints/tracks the status
        // strip itself on scroll (see clearStatusBarForLift).
        clearStatusBarForLift();

        postponeEnterTransition();

        final ViewGroup parentView = (ViewGroup) view.getParent();
        // Wait for the data to load

        mAdapter.addLoadStateListener(loadStates -> {
            if (loadStates.getRefresh() instanceof LoadState.NotLoading) {
                if (mAdapter.getItemCount() == 0) {
                    if (isSearchActive() && mSearchEdit != null && mSearchEdit.getText().length() > 0) {
                        mLCEERecyclerView.setEmptyText(R.string.empty_list_query);
                    } else {
                        mLCEERecyclerView.setEmptyText(R.string.empty_list_bookmarks);
                    }
                    mLCEERecyclerView.showEmpty();
                } else {
                    mLCEERecyclerView.hideAll();
                    if (mPendingScrollToTop && mRecyclerView != null) {
                        mPendingScrollToTop = false;
                        mRecyclerView.scrollToPosition(0);
                    }
                }

                parentView.getViewTreeObserver()
                        .addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
                            @Override public boolean onPreDraw() {
                                parentView.getViewTreeObserver().removeOnPreDrawListener(this);
                                startPostponedEnterTransition();
                                return true;
                            }
                        });
            }
            return null;
        });


    }

    public void setupViews(View v){
        mLCEERecyclerView = v.findViewById(R.id.list_recycler_lcee);
        mLCEERecyclerView.setEmptyText(R.string.empty_list_bookmarks);
        mLCEERecyclerView.setEmptyImageView(R.drawable.ill_bookmark);

        mRecyclerView = mLCEERecyclerView.getRecyclerView();
        mRecyclerView.setVerticalScrollBarEnabled(true);
        // EqualSpacingItemDecoration: halfSpacing between rows, full
        // list_spacing at the top/bottom and on left/right edges.
        // CardViewListItemDecoration emitted spacing only on the very
        // first and last items and zero between, so adjacent 2dp card
        // strokes touched and read as a doubled border in action mode.
        // Same decoration the downloads list now uses.
        mRecyclerView.addItemDecoration(new EqualSpacingItemDecoration(mActivity, R.dimen.list_spacing));
        mAdapter = new WebBookmarkAdapter(mActivity, new WebBookmarkDiffCallback(), this, mIncognito);

        // Sync discoverability is non-incognito only. ConcatAdapter prepends the
        // announce banner; the toolbar cloud + banner react to sync state.
        if (!mIncognito) {
            mSyncBannerAdapter = new SyncBannerAdapter(this);
            mRecyclerView.setAdapter(new ConcatAdapter(mSyncBannerAdapter, mAdapter));
            mSyncManager.observeState().observe(getViewLifecycleOwner(), state -> {
                mSyncState = state == null ? SyncManager.STATE_OFF : state;
                applySyncIcon();
                updateSyncBannerVisibility();
            });
        } else {
            mRecyclerView.setAdapter(mAdapter);
        }

        mWebBookmarkViewModel.getWebBookmark().observe(getViewLifecycleOwner(), mObservableDownloads ->
                mAdapter.submitData(getLifecycle(), mObservableDownloads));
    }

    public void setupToolbar(View v){
        mToolbar = v.findViewById(R.id.toolbar);
        mToolbar.setContentInsetsAbsolute(getResources().getDimensionPixelSize(R.dimen.address_bar_inset),0);
        setupSearchBar(v);
        mToolbar.setNavigationOnClickListener(v1 -> {
            if(mActionModeEnabled){
                stopActionMode();
            } else if (isSearchActive()) {
                closeSearchBar();
            }else{
                mNavController.popBackStack();
            }
        });
        mToolbar.addMenuProvider(new MenuProvider() {
            @Override
            public void onCreateMenu(@NonNull Menu menu, @NonNull MenuInflater menuInflater) {
                if(mActionModeEnabled){
                    menuInflater.inflate(R.menu.menu_action, menu);
                    mSyncMenuItem = null; // action menu has no sync item
                }else{
                    // Bookmarks-specific menu (search + sort + delete).
                    // NOT menu_web_options — that one is shared with
                    // WebHistoryFragment, which has no sort toggle.
                    menuInflater.inflate(R.menu.menu_web_bookmark_options, menu);
                    // The cloud item reflects sync state (and hides in incognito).
                    mSyncMenuItem = menu.findItem(R.id.action_sync);
                    applySyncIcon();
                    if (isSearchActive()) {
                        // Search field owns the toolbar row — hide every icon.
                        for (int i = 0; i < menu.size(); i++) menu.getItem(i).setVisible(false);
                    }
                }
            }

            @Override
            public boolean onMenuItemSelected(@NonNull MenuItem menuItem) {
                int id = menuItem.getItemId();
                if (id == R.id.action_search) {
                    openSearchBar();
                    return true;
                } else if (id == R.id.action_sort) {
                    // Recent ↔ A–Z toggle for the unfiltered list
                    // (search results stay recency-ordered; flipping
                    // mid-search just persists the mode and applies
                    // once the query clears). Scroll-to-top so the new
                    // order is visible from its head, matching the
                    // query-dispatch pattern.
                    mWebBookmarkViewModel.setSortAlphabetical(
                            !mWebBookmarkViewModel.isSortAlphabetical());
                    mPendingScrollToTop = true;
                    return true;
                } else if (id == R.id.action_delete) {
                    if (mActionModeEnabled) {
                        HashSet<Integer> selected = mAdapter.getSelected();
                        for (int position : selected) {
                            mWebBookmarkViewModel.delete(mAdapter.getWebBookmarkItem(position));
                        }
                        stopActionMode();
                    } else {
                        NavigationUtils.navigateSafe(mNavController, R.id.dialog_delete_bookmarks);
                    }
                    return true;
                } else if (id == R.id.action_export) {
                    doExport();
                    return true;
                } else if (id == R.id.action_import) {
                    // The contract override forces a single text/html type, so
                    // the input array is unused (kept for the launcher signature).
                    mImportLauncher.launch(new String[0]);
                    return true;
                } else if (id == R.id.action_sync) {
                    // Opens the bookmark-sync screen directly (Settings is a
                    // separate activity); EXTRA_OPEN_SYNC deep-links to it.
                    Intent syncIntent = new Intent(requireContext(), SettingsActivity.class);
                    syncIntent.putExtra(SettingsActivity.EXTRA_OPEN_SYNC, true);
                    startActivity(syncIntent);
                    return true;
                } else if (id == android.R.id.home) {
                    mNavController.popBackStack();
                    return true;
                }
                return false;
            }
        }, getViewLifecycleOwner(), Lifecycle.State.RESUMED);
    }

    /** Sets the toolbar cloud icon + tint for the current sync state (and hides
     *  it entirely in incognito). No-op until the menu item exists. */
    private void applySyncIcon() {
        if (mSyncMenuItem == null) {
            return;
        }
        if (mIncognito) {
            mSyncMenuItem.setVisible(false);
            return;
        }
        int drawable;
        int colorAttr;
        switch (mSyncState) {
            case SyncManager.STATE_SYNCING:
                drawable = R.drawable.cloud_24;
                colorAttr = com.google.android.material.R.attr.colorPrimary;
                break;
            case SyncManager.STATE_SYNCED:
                drawable = R.drawable.cloud_done_24;
                colorAttr = com.google.android.material.R.attr.colorPrimary;
                break;
            case SyncManager.STATE_ERROR:
                drawable = R.drawable.cloud_off_24;
                colorAttr = com.google.android.material.R.attr.colorError;
                break;
            case SyncManager.STATE_OFF:
            default:
                drawable = R.drawable.cloud_24;
                colorAttr = com.google.android.material.R.attr.colorOnSurface;
                break;
        }
        mSyncMenuItem.setVisible(true);
        mSyncMenuItem.setIcon(drawable);
        int color = MaterialColors.getColor(mToolbar, colorAttr, Color.GRAY);
        MenuItemCompat.setIconTintList(mSyncMenuItem, ColorStateList.valueOf(color));
    }

    /** Shows the one-time announce banner while sync is off and not yet retired
     *  (dismissed or enabled). Retires permanently once sync is enabled. */
    private void updateSyncBannerVisibility() {
        if (mSyncBannerAdapter == null) {
            return; // incognito: no banner
        }
        if (mSyncManager.isEnabled()) {
            if (!isSyncBannerDismissed()) {
                setSyncBannerDismissed(true); // retire once sync is on
            }
            mSyncBannerAdapter.setVisible(false);
            return;
        }
        mSyncBannerAdapter.setVisible(!isSyncBannerDismissed());
    }

    private boolean isSyncBannerDismissed() {
        return mSyncPrefs.getBoolean(Preferences.SYNC_BANNER_DISMISSED, false);
    }

    private void setSyncBannerDismissed(boolean dismissed) {
        mSyncPrefs.edit().putBoolean(Preferences.SYNC_BANNER_DISMISSED, dismissed).apply();
    }

    @Override
    public void onSyncBannerClicked() {
        Intent syncIntent = new Intent(requireContext(), SettingsActivity.class);
        syncIntent.putExtra(SettingsActivity.EXTRA_OPEN_SYNC, true);
        startActivity(syncIntent);
    }

    @Override
    public void onSyncBannerDismissed() {
        setSyncBannerDismissed(true);
        if (mSyncBannerAdapter != null) {
            mSyncBannerAdapter.setVisible(false);
        }
    }

    /** Export all bookmarks (Netscape HTML) directly into the public
     *  Download/Firedown/backup folder — survives uninstall, alongside the
     *  download mirror. The write + count callback run on background/main via
     *  the repository; only the (cheap) path is computed here. */
    private void doExport() {
        File target = new File(
                new File(StoragePaths.getDownloadPath(requireContext()), BACKUP_DIR_NAME),
                EXPORT_FILE_NAME);
        mWebBookmarkViewModel.exportBookmarks(target, count -> {
            if (!isAdded()) return;
            showSnack(count >= 0
                    ? getString(R.string.bookmark_export_done, count)
                    : getString(R.string.bookmark_io_error));
        });
    }

    /** Read bookmarks from the user-picked file. Imported links merge with
     *  existing bookmarks by URL; the Paging list refreshes via Room. */
    private void doImport(Uri uri) {
        try {
            InputStream is = requireContext().getContentResolver().openInputStream(uri);
            if (is == null) {
                showSnack(getString(R.string.bookmark_io_error));
                return;
            }
            mWebBookmarkViewModel.importBookmarks(is, count -> {
                if (!isAdded()) return;
                showSnack(count >= 0
                        ? getString(R.string.bookmark_import_done, count)
                        : getString(R.string.bookmark_io_error));
            });
        } catch (Exception e) {
            showSnack(getString(R.string.bookmark_io_error));
        }
    }

    private void showSnack(String message) {
        Snackbar.make(mActivity.getSnackAnchorView(), message, Snackbar.LENGTH_LONG).show();
    }

    public void setupBackPress(){
        OnBackPressedCallback callback = new OnBackPressedCallback(true /* enabled by default */) {
            @Override
            public void handleOnBackPressed() {
                Log.d(TAG, "handleBackPressed");
                if(mActionModeEnabled){
                    stopActionMode();
                } else if (isSearchActive()) {
                    closeSearchBar();
                }else {
                    setEnabled(false); //this is important line
                    mActivity.getOnBackPressedDispatcher().onBackPressed();
                }
            }
        };
        mActivity.getOnBackPressedDispatcher().addCallback(this, callback);
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        mToolbar = null;
        mAdapter = null;
        mSyncBannerAdapter = null;
        mSyncMenuItem = null;
    }

    @Override
    public void onItemClick(int position, int resId) {

        Log.d(TAG, "onItemClick: " + resId);

        if (position == RecyclerView.NO_POSITION) {
            Log.d(TAG, "onItemClick incorrect position");
            return;
        }

        if(mActionModeEnabled) {
            mAdapter.setSelected(position);
            setActionModeTitle(mAdapter.getSelectedSize());
        }else{
            if(resId == R.id.file_more){
                WebBookmarkEntity webBookmarkEntity = mAdapter.snapshot().get(position);
                if(webBookmarkEntity != null){
                    Bundle bundle = new Bundle();
                    bundle.putInt(Keys.ITEM_ID, webBookmarkEntity.getId());
                    bundle.putString(Keys.SHARE_URL, webBookmarkEntity.getUrl());
                    bundle.putString(Keys.TITLE, webBookmarkEntity.getTitle());
                    bundle.putBoolean(Keys.EDIT, true);
                    // Carry the incognito flag through so the bookmark
                    // edit surface (and its 'Open in New Tab' path)
                    // continues in the same mode.
                    bundle.putBoolean(Keys.IS_INCOGNITO, mIncognito);
                    NavigationUtils.navigateSafe(mNavController,R.id.dialog_web_options, R.id.web_bookmark, bundle);
                }
            }else if(resId == R.id.item_web_bookmark){
                WebBookmarkEntity webBookmarkEntity = mAdapter.snapshot().get(position);
                if(webBookmarkEntity != null){
                    // GeckoStateEntity(boolean) is the home-flag constructor —
                    // not incognito. We're loading a real URL, so home=false;
                    // incognito is set explicitly below.
                    GeckoStateEntity geckoStateEntity = new GeckoStateEntity(false);
                    geckoStateEntity.setIncognito(mIncognito);
                    geckoStateEntity.setUri(webBookmarkEntity.getUrl());
                    // Publish OPEN_URI on the shared ViewModel, then
                    // navigate to browser popping back to the home that
                    // matches our mode — keeps the back stack
                    // consistent so back-from-browser lands on
                    // home / home_incognito to match the launching
                    // chrome.
                    mBrowserURIViewModel.onEventSelected(geckoStateEntity, IntentActions.OPEN_URI);
                    NavigationUtils.navigateSafe(mNavController, R.id.browser, null,
                            buildToBrowserNavOptions());
                }
            }
        }
    }

    @Override
    public void onLongClick(int position, int resId) {
        if(mActionModeEnabled) {
            setActionModeTitle(mAdapter.getSelectedSize());
            return;
        }
        mActionModeEnabled = true;
        mToolbar.invalidateMenu();
        mAdapter.setActionMode(true);
        mAdapter.setSelected(position);
        setActionModeTitle(mAdapter.getSelectedSize());
    }

    @Override
    public void onItemVariantClick(int position, int variant, int resId) {

    }


    /** Route the live search query to the bookmark list. */
    @Override
    protected void onSearchQueryChanged(String query) {
        mWebBookmarkViewModel.search(query);
    }


    private void stopActionMode(){
        mAdapter.resetSelected();
        mAdapter.setActionMode(false);
        mActionModeEnabled = false;
        mToolbar.invalidateMenu();
        mToolbar.setTitle(getString(R.string.navigation_web_bookmarks));
    }


    /**
     * Builds the pop-and-push NavOptions for navigating into the
     * browser. Pops the back stack up to (but not including) the home
     * that matches our launch mode — so a normal-mode bookmark list
     * unwinds to {@code @id/home} and an incognito-mode list unwinds
     * to {@code @id/home_incognito}. Replaces the XML-level
     * action_web_bookmark_to_browser whose popUpTo was hard-coded to
     * {@code @id/home}.
     */
    private NavOptions buildToBrowserNavOptions() {
        return new NavOptions.Builder()
                .setPopUpTo(mIncognito ? R.id.home_incognito : R.id.home, false)
                .build();
    }




}


