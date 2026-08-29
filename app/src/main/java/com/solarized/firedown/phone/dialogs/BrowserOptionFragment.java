package com.solarized.firedown.phone.dialogs;

import android.annotation.SuppressLint;
import android.app.Dialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.activity.ComponentDialog;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.PopupMenu;
import androidx.appcompat.widget.Toolbar;
import androidx.core.content.res.ResourcesCompat;
import androidx.core.graphics.drawable.DrawableCompat;
import androidx.core.view.MenuProvider;
import androidx.fragment.app.DialogFragment;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.ListPreloader;
import com.bumptech.glide.RequestBuilder;
import com.bumptech.glide.RequestManager;
import com.bumptech.glide.integration.recyclerview.RecyclerViewPreloader;
import com.bumptech.glide.load.resource.bitmap.RoundedCorners;
import com.bumptech.glide.request.RequestOptions;
import com.bumptech.glide.util.FixedPreloadSizeProvider;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.chip.ChipGroup;
import com.solarized.firedown.BuildConfig;
import com.solarized.firedown.GlideHelper;
import com.solarized.firedown.GlideRequestOptions;
import com.solarized.firedown.Keys;
import com.solarized.firedown.Preferences;
import com.solarized.firedown.R;
import com.solarized.firedown.Sorting;
import com.solarized.firedown.data.entity.BrowserDownloadEntity;
import com.solarized.firedown.data.entity.OptionEntity;
import com.solarized.firedown.data.models.BrowserDownloadViewModel;
import com.solarized.firedown.data.models.FragmentsOptionsViewModel;
import com.solarized.firedown.manager.DownloadRequest;
import com.solarized.firedown.phone.DownloadsActivity;
import com.solarized.firedown.phone.SettingsActivity;
import com.solarized.firedown.phone.VaultActivity;
import com.solarized.firedown.phone.fragments.BaseFocusFragment;
import com.solarized.firedown.ui.EqualSpacingItemDecoration;
import com.solarized.firedown.ui.IncognitoColors;
import com.solarized.firedown.ui.OnItemClickListener;
import com.solarized.firedown.ui.adapters.BrowserOptionAdapter;
import com.solarized.firedown.ui.diffs.BrowserDownloadsDiffCallback;
import com.solarized.firedown.utils.CaptureUrlActions;
import com.solarized.firedown.utils.NavigationUtils;
import com.solarized.firedown.utils.SelectionStyling;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;

import javax.inject.Inject;
import dagger.hilt.android.AndroidEntryPoint;

@AndroidEntryPoint
public class BrowserOptionFragment extends BaseFocusFragment implements OnItemClickListener, ChipGroup.OnCheckedStateChangeListener {

    private static final String TAG = BrowserOptionFragment.class.getName();
    @Inject SharedPreferences mSharedPreferences;
    private BrowserOptionAdapter mAdapter;
    private BrowserDownloadViewModel mBrowserDownloadViewModel;
    private FragmentsOptionsViewModel mFragmentsViewModel;
    private GridLayoutManager mLayoutManager;
    private ChipGroup mChipGroup;
    private Toolbar mToolbar;
    private View mHelpBanner;
    private boolean mHelpBannerDismissed;
    private boolean mEnableGrid;
    private boolean mIsIncognito;
    private int mScrollLimit;
    private OnBackPressedCallback mClearFilterOnBack;

    // "Still scanning" indicator state. mBusyShown is the debounced, displayed
    // state; mListEmpty mirrors the latest list so a busy/empty change re-renders
    // the right state. Debounced so fast/aborting inspect tasks don't flash.
    // empty+busy → LCEE loading; content+busy → a toolbar spinner (action_progress).
    private MenuItem mProgressMenuItem;
    private boolean mListEmpty = true;

    /** A presentation flip (mime suppression / dense mosaic) is set on the
     *  adapter but its commit callback hasn't applied the span/rebind yet —
     *  a newer submit can supersede the older one's callback, so the next
     *  emission must re-run the commit path. See submitWithPresentation. */
    private boolean mPresentationDirty;
    private boolean mBusyShown;
    private static final long SPINNER_HIDE_LINGER_MS = 500L;
    private final Handler mSpinnerHandler = new Handler(Looper.getMainLooper());
    private final Runnable mHideSpinner = () -> { mBusyShown = false; renderListState(); };

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mScrollLimit = Preferences.LIST_LIMIT;
        mEnableGrid = mSharedPreferences.getBoolean(Preferences.SORT_LIST, false);
        mIsIncognito = getArguments() != null && getArguments().getBoolean(Keys.IS_INCOGNITO, false);
        mHelpBannerDismissed = mSharedPreferences.getBoolean(Preferences.CAPTURE_HELP_BANNER_DISMISSED, false);
        mBrowserDownloadViewModel = new ViewModelProvider(this).get(BrowserDownloadViewModel.class);
        mFragmentsViewModel = new ViewModelProvider(mActivity).get(FragmentsOptionsViewModel.class);
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {

        LayoutInflater themedInflater = container != null
                ? LayoutInflater.from(container.getContext())
                : inflater;

        View view = themedInflater.inflate(R.layout.fragment_dialog_browser_options, container, false);

        mToolbar = view.findViewById(R.id.toolbar);
        mChipGroup = view.findViewById(R.id.chip_group);
        mLCEERecyclerView = view.findViewById(R.id.list_recycler_lcee);
        mLCEERecyclerView.setEmptyButtonVisibility(View.VISIBLE);

        // One-shot help banner (non-empty list only). Tapping the body opens the
        // same help screen as the toolbar Help item; the X just dismisses. Either
        // gesture persists the dismissal so it never returns.
        mHelpBanner = view.findViewById(R.id.capture_help_banner);
        // Brand wash (primaryContainer @ 20% over surface) — composed in code, so
        // set here rather than XML — matching the archive / incognito-in-progress
        // banners so the three list-banners read as siblings.
        //
        // Resolve the wash off the BANNER's own context, NOT requireContext():
        // this sheet inflates against the incognito-themed dialog context
        // (Theme_FireDown_BottomSheetVaultDialogTheme — see
        // BaseBottomSheetDialogFragment.getTheme), so the banner's XML
        // ?attr/colorOnSurface text is the incognito (light) onSurface. The
        // fragment's requireContext() is the regular (light) activity theme, so
        // washing over its colorSurface produced a LIGHT pink card — light text
        // on a light card, illegible (the incognito Captured sheet bug). Using
        // the themed view context makes the wash track whatever palette the
        // text does, exactly as the sibling adapters do with parent.getContext().
        if (mHelpBanner instanceof MaterialCardView) {
            ((MaterialCardView) mHelpBanner).setCardBackgroundColor(
                    SelectionStyling.selectedCardWashOver(
                            mHelpBanner.getContext(),
                            com.google.android.material.R.attr.colorSurface));
        }
        mHelpBanner.setOnClickListener(v -> {
            openCaptureHelp();
            dismissHelpBanner();
        });
        view.findViewById(R.id.capture_help_banner_dismiss)
                .setOnClickListener(v -> dismissHelpBanner());

        if (mIsIncognito) {
            mToolbar.setPopupTheme(R.style.Theme_FireDown_Popup_Vault);
            int onSurface = IncognitoColors.getOnSurface(requireContext(), true);
            mToolbar.setTitleTextColor(onSurface);
            Drawable navIcon = mToolbar.getNavigationIcon();
            if (navIcon != null) DrawableCompat.setTint(navIcon, onSurface);
        }

        // Set the initial checked chip BEFORE attaching the listener so
        // the synthetic onCheckedChange callback that ChipGroup fires on
        // .check() doesn't run our sort/filter pipeline once on launch.
        // chip_all is no longer a physical chip in the group — when the
        // persisted state is "all", we leave the group with no
        // selection (Material filter-chip convention: nothing
        // selected = unfiltered). Trying to .check(chip_all) would be
        // a silent no-op now anyway, but the explicit guard makes the
        // intent obvious.
        int persistedId = mBrowserDownloadViewModel.getCurrentSortBrowserId();
        if (persistedId != R.id.chip_all) {
            mChipGroup.check(persistedId);
        }
        mChipGroup.setOnCheckedStateChangeListener(this);

        mToolbar.setContentInsetsAbsolute(getResources().getDimensionPixelSize(R.dimen.list_spacing), 0);

        mLCEERecyclerView.setButtonListener(id -> {
            OptionEntity option = new OptionEntity();
            option.setId(id);
            mFragmentsViewModel.onOptionsSelected(option);
        });

        mRecyclerView = mLCEERecyclerView.getRecyclerView();
        mLayoutManager = new GridLayoutManager(requireContext(), getSpanCount());
        mRecyclerView.setLayoutManager(mLayoutManager);

        mAdapter = new BrowserOptionAdapter(mActivity, new BrowserDownloadsDiffCallback(), this, mEnableGrid);
        // The persisted chip is checked above (before the listener attaches,
        // which suppresses the synthetic callback) — so when the sheet
        // reopens with a filter active, the presentation flags must be
        // seeded here; getSpanCount() above already read the same chip
        // state. Silent setter is fine pre-list (nothing bound yet).
        mAdapter.setPresentation(
                mChipGroup.getCheckedChipId() != View.NO_ID,
                !mEnableGrid && isDenseImageFilter());
        mRecyclerView.setAdapter(mAdapter);

        // The RecyclerView's own dimensions don't depend on the adapter
        // (it's match_parent inside the LCEE wrapper) — paging-driven
        // inserts can't resize it, so skip the outer remeasure pass.
        mRecyclerView.setHasFixedSize(true);
        // Default off-screen view cache is 2; bumping it to 8 keeps
        // viewholders that just left the viewport hot, so a quick
        // scroll-back doesn't go through onBindViewHolder again
        // (which would null the ImageView via clearSafe and re-fire
        // a Glide load).
        mRecyclerView.setItemViewCacheSize(8);

        // Defer the preloader and the load-more scroll listener via
        // RecyclerView.post — they have nothing to contribute to first
        // frame, and the bottom sheet's slide-in animation makes any
        // work scheduled here visibly stutter. Building Glide options,
        // RoundedCorners, and the RecyclerViewPreloader callback chain
        // all run AFTER the dialog has painted instead of during it.
        mRecyclerView.post(() -> {
            if (mRecyclerView == null || mAdapter == null) return;

            // Glide's memory cache evicts older bitmaps as the user scrolls
            // through a long list, so the original top-row thumbnails are
            // gone by the time the user scrolls back up — leaving a brief
            // empty frame plus a disk/network round-trip on rebind. Preload
            // 8 items ahead of the scroll direction so the cache stays
            // warm for the rows about to enter the viewport.
            RoundedCorners roundedCorners = new RoundedCorners(
                    getResources().getDimensionPixelOffset(R.dimen.card_radius));
            RequestOptions baseOptions = new RequestOptions()
                    .apply(RequestOptions.bitmapTransform(roundedCorners));

            RequestManager glide = Glide.with(this);

            RecyclerViewPreloader<BrowserDownloadEntity> preloader = new RecyclerViewPreloader<>(
                    glide,
                    new ListPreloader.PreloadModelProvider<>() {
                        @NonNull
                        @Override
                        public List<BrowserDownloadEntity> getPreloadItems(int position) {
                            if (position < 0 || position >= mAdapter.getItemCount()) {
                                return Collections.emptyList();
                            }
                            BrowserDownloadEntity entity = mAdapter.getCurrentList().get(position);
                            return entity == null
                                    ? Collections.emptyList()
                                    : Collections.singletonList(entity);
                        }

                        @Nullable
                        @Override
                        public RequestBuilder<?> getPreloadRequestBuilder(@NonNull BrowserDownloadEntity entity) {
                            // Match the per-item options the adapter builds
                            // at bind time so the preload-warmed bitmap and
                            // the bind-time request resolve to the same
                            // Glide cache key.
                            String fileUrl = entity.getFileUrl();
                            String mimeType = BrowserOptionAdapter.resolveMimeType(
                                    entity.getMimeType(), fileUrl);
                            // clone() — the lambda is called repeatedly with the
                            // same shared baseOptions; .set() mutates in place,
                            // so without the clone every prior preload request
                            // ends up tagged with the latest item's metadata.
                            RequestOptions options = baseOptions.clone()
                                    .set(GlideRequestOptions.MIMETYPE, mimeType)
                                    .set(GlideRequestOptions.FILEPATH, fileUrl)
                                    .set(GlideRequestOptions.HEADERS, entity.getFileHeaders())
                                    .set(GlideRequestOptions.KEY, String.valueOf(entity.getUid()));
                            return GlideHelper.preloadBrowser(glide, entity, options);
                        }
                    },
                    new FixedPreloadSizeProvider<>(
                            GlideHelper.browserThumbWidth(),
                            GlideHelper.browserThumbHeight()),
                    8);
            mRecyclerView.addOnScrollListener(preloader);

            mRecyclerView.addOnScrollListener(new RecyclerView.OnScrollListener() {
                @Override
                public void onScrollStateChanged(@NonNull RecyclerView recyclerView, int newState) {
                    if (!recyclerView.canScrollVertically(1) && newState == RecyclerView.SCROLL_STATE_IDLE) {
                        mScrollLimit += Preferences.LIST_LIMIT;
                        mBrowserDownloadViewModel.loadMore(mScrollLimit);
                    }
                }
            });
        });

        updateItemDecoration();

        mToolbar.invalidateMenu();
        mToolbar.addMenuProvider(new MenuProvider() {
            @Override
            public void onCreateMenu(@NonNull Menu menu, @NonNull MenuInflater menuInflater) {
                if(mIsIncognito){
                    menuInflater.inflate(mActionModeEnabled ? R.menu.menu_capture_action : R.menu.menu_capture_incognito, menu);
                }else{
                    menuInflater.inflate(mActionModeEnabled ? R.menu.menu_capture_action : R.menu.menu_capture, menu);
                }
                MenuItem actionView = menu.findItem(R.id.action_view);
                if (actionView != null) {
                    actionView.setIcon(mEnableGrid ? R.drawable.ic_grid_view_24 : R.drawable.ic_view_list_24);
                }
                // The "scanning" spinner lives next to the grid/list toggle
                // (absent in the action-mode menu). Re-apply its visibility on
                // every (re)inflate from the current debounced busy state.
                mProgressMenuItem = menu.findItem(R.id.action_progress);
                updateProgressIndicator();
                if (mIsIncognito) {
                    int onSurface = IncognitoColors.getOnSurface(requireContext(), true);
                    for (int i = 0; i < menu.size(); i++) {
                        Drawable icon = menu.getItem(i).getIcon();
                        if (icon != null) DrawableCompat.setTint(icon, onSurface);
                    }
                    Drawable overflow = mToolbar.getOverflowIcon();
                    if (overflow != null) DrawableCompat.setTint(overflow, onSurface);
                }
            }

            @Override
            public boolean onMenuItemSelected(@NonNull MenuItem menuItem) {
                int id = menuItem.getItemId();
                if (id == R.id.action_view) {
                    toggleViewMode(menuItem);
                    return true;
                } else if (id == R.id.action_clear) {
                    mBrowserDownloadViewModel.clearBrowserDownloads();
                    return true;
                } else if (id == R.id.action_download) {
                    processBatchDownload();
                    return true;
                } else if (id == R.id.action_select_all) {
                    mAdapter.selectAll();
                    updateActionModeTitle();
                    return true;
                } else if (id == R.id.action_select_all_not) {
                    mAdapter.clearSelection();
                    updateActionModeTitle();
                    return true;
                } else if (id == R.id.action_help) {
                    openCaptureHelp();
                    return true;
                } else if (id == R.id.action_downloads) {
                    mStartForResult.launch(new Intent(requireContext(), DownloadsActivity.class));
                    return true;
                } else if (id == R.id.action_safe) {
                    mStartForResult.launch(new Intent(requireContext(), VaultActivity.class));
                    return true;
                } else if (id == R.id.action_settings) {
                    mStartForResult.launch(new Intent(requireContext(), SettingsActivity.class));
                    return true;
                }
                return false;
            }
        }, getViewLifecycleOwner(), Lifecycle.State.RESUMED);

        return view;
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        mBrowserDownloadViewModel.getBrowserDownloads(mScrollLimit).observe(getViewLifecycleOwner(), downloads -> {

            Log.d(TAG, "getBrowserDownloads: " + (downloads != null ? downloads.size() : 0));

            if (mActionModeEnabled) return;

            mListEmpty = downloads == null || downloads.isEmpty();
            renderListState();

            // Refresh the origin → caption-count map (from the unfiltered
            // repository) before submitting so the CC badge binds correctly
            // even when the active chip has filtered the subtitle siblings out.
            mAdapter.setSubtitleCounts(mBrowserDownloadViewModel.subtitleCountsByOrigin());
            submitWithPresentation(downloads);
        });

        mBrowserDownloadViewModel.getInflight().observe(getViewLifecycleOwner(),
                count -> onInflightChanged(count == null ? 0 : count));

        mBrowserDownloadViewModel.update();

        /* Back deselects an active filter chip (reverts to the unfiltered
         * list) instead of dismissing the screen — mirrors DownloadFragment
         * so "Back unwinds my last narrowing step" is consistent across the
         * two list surfaces. The callback enables/disables itself in
         * onCheckedChanged so it only intercepts Back while a chip is
         * actually checked; otherwise Back behaves normally.
         *
         * IMPORTANT: this fragment is hosted inside a BottomSheetDialog,
         * which owns its own Window and intercepts Back BEFORE the Activity's
         * OnBackPressedDispatcher ever runs. The callback must therefore
         * register against the DIALOG's dispatcher (ComponentDialog, since
         * Material 1.6+), not the Activity's — otherwise the dialog
         * dismisses on Back and the callback never fires. */
        mClearFilterOnBack = new OnBackPressedCallback(false) {
            @Override
            public void handleOnBackPressed() {
                if (mChipGroup != null && mChipGroup.getCheckedChipId() != View.NO_ID) {
                    mChipGroup.clearCheck();
                }
            }
        };
        Fragment parent = getParentFragment();
        if (parent instanceof DialogFragment) {
            Dialog dialog = ((DialogFragment) parent).getDialog();
            if (dialog instanceof ComponentDialog) {
                ((ComponentDialog) dialog).getOnBackPressedDispatcher()
                        .addCallback(getViewLifecycleOwner(), mClearFilterOnBack);
            }
        }
    }


    private void toggleViewMode(MenuItem item) {
        mEnableGrid = !mEnableGrid;
        mAdapter.enableGrid(mEnableGrid);
        refreshGridDensity();
        updateItemDecoration();
        item.setIcon(mEnableGrid ? R.drawable.ic_grid_view_24 : R.drawable.ic_view_list_24);
    }

    private void updateItemDecoration() {
        while (mRecyclerView.getItemDecorationCount() > 0) mRecyclerView.removeItemDecorationAt(0);
        if (!mEnableGrid) {
            mRecyclerView.addItemDecoration(new EqualSpacingItemDecoration(requireContext(), R.dimen.list_spacing));
        }
    }

    /** NOTE the historic naming inversion: {@code mEnableGrid == true} is
     *  LIST mode (it persists {@code Preferences.SORT_LIST} and maps to
     *  {@code browser_list_number}); grid mode is {@code !mEnableGrid}. */
    private int getSpanCount() {
        if (mEnableGrid) {
            return getResources().getInteger(R.integer.browser_list_number);
        }
        // Grid + images-only filter → the dense square mosaic (the tiles
        // carry no text there, so the density costs no information).
        return getResources().getInteger(isDenseImageFilter()
                ? R.integer.browser_grid_dense_number
                : R.integer.browser_grid_number);
    }

    /** Images and GIF chips both narrow to image-mime captures (the same
     *  class whose grid tiles already hide the title), so both go dense. */
    private boolean isDenseImageFilter() {
        if (mChipGroup == null) {
            return false;
        }
        int chipId = mChipGroup.getCheckedChipId();
        return chipId == R.id.chip_image || chipId == R.id.chip_gif;
    }

    /** Re-syncs the adapter's dense flag and the span count with the
     *  current view mode + filter chip. enableDenseGrid no-ops (and skips
     *  its notifyDataSetChanged) when the flag didn't actually change.
     *  Used by the grid/list TOGGLE only, where the data doesn't change
     *  and an immediate flip is correct — a filter chip change must go
     *  through {@link #submitWithPresentation} instead. */
    private void refreshGridDensity() {
        if (mAdapter == null || mLayoutManager == null) {
            return;
        }
        mAdapter.enableDenseGrid(!mEnableGrid && isDenseImageFilter());
        mLayoutManager.setSpanCount(getSpanCount());
    }

    /**
     * Submits a list with the chip-derived presentation (mime suppression +
     * dense mosaic) applied ATOMICALLY with the commit. A chip tap's requery
     * is async — flipping the adapter from the tap re-renders the old list
     * in the new presentation for a beat. So the flags are set silently
     * here, and when they changed the span/rebind runs in submitList's
     * commit callback: the differ has already swapped the content (the
     * dense flag is read per-bind, so freshly inserted tiles inflate the
     * right layout), the span flip and the survivors' rebind land in the
     * same main-thread message — no intermediate frame. mPresentationDirty
     * covers a commit callback that never ran because a newer submit
     * superseded it (AsyncListDiffer drops the older commit).
     */
    @SuppressLint("NotifyDataSetChanged")
    private void submitWithPresentation(List<BrowserDownloadEntity> downloads) {
        boolean suppress = mChipGroup != null && mChipGroup.getCheckedChipId() != View.NO_ID;
        boolean dense = !mEnableGrid && isDenseImageFilter();
        boolean changed = mAdapter.setPresentation(suppress, dense);
        if (!changed && !mPresentationDirty) {
            submitListPreservingScroll(downloads);
            return;
        }
        mPresentationDirty = true;
        // Scroll anchoring is meaningless across a filter flip (the lists
        // are disjoint) — plain submit with the presentation commit.
        mAdapter.submitList(downloads, () -> {
            if (mAdapter == null || mLayoutManager == null) {
                return;
            }
            mPresentationDirty = false;
            mLayoutManager.setSpanCount(getSpanCount());
            mAdapter.notifyDataSetChanged();
        });
    }

    /**
     * Reacts to the in-flight inspect-task count. The spinner is small and
     * unobtrusive, so show it immediately when work starts and hide it after a
     * short linger once idle — the linger bridges the gaps between bursts so a
     * busy page (hundreds of rapid increments/decrements) doesn't strobe it.
     */
    private void onInflightChanged(int count) {
        if (BuildConfig.DEBUG && mToolbar != null) {
            mToolbar.setSubtitle(count > 0 ? ("queue: " + count) : null);
        }
        if (count > 0) {
            mSpinnerHandler.removeCallbacks(mHideSpinner);
            if (!mBusyShown) {
                mBusyShown = true;
                renderListState();
            }
        } else if (mBusyShown) {
            mSpinnerHandler.removeCallbacks(mHideSpinner);
            mSpinnerHandler.postDelayed(mHideSpinner, SPINNER_HIDE_LINGER_MS);
        }
    }

    /**
     * Owns the LCEE list state: content when non-empty, otherwise the empty
     * state (with the active chip's message). The toolbar spinner is the single
     * "work happening" cue and is driven independently — see
     * {@link #updateProgressIndicator()} — so it doesn't double up with a centre
     * loading spinner, and an empty *filtered* view (e.g. "Audio" with no audio)
     * correctly reads "No audio available" rather than a misleading spinner.
     */
    private void renderListState() {
        if (!mActionModeEnabled) {
            if (mListEmpty) {
                configureEmptyState();
                mLCEERecyclerView.showEmpty();
            } else {
                mLCEERecyclerView.hideAll();
            }
        }
        updateProgressIndicator();
        updateHelpBanner();
    }

    /**
     * The one-shot help banner shows only on a NON-empty list (the empty state
     * already carries the same guidance via its button), while not in action mode,
     * and only until the user taps or dismisses it once (persisted). The toolbar
     * Help item remains the permanent way back to the same screen.
     */
    private void updateHelpBanner() {
        if (mHelpBanner == null) return;
        boolean show = !mActionModeEnabled && !mListEmpty && !mHelpBannerDismissed;
        mHelpBanner.setVisibility(show ? View.VISIBLE : View.GONE);
    }

    /** Persist the dismissal and hide the banner — both the tap and the X land here. */
    private void dismissHelpBanner() {
        mHelpBannerDismissed = true;
        mSharedPreferences.edit().putBoolean(Preferences.CAPTURE_HELP_BANNER_DISMISSED, true).apply();
        updateHelpBanner();
    }

    /** Open the "play the media before downloading" help screen (toolbar item + banner). */
    private void openCaptureHelp() {
        OptionEntity option = new OptionEntity();
        option.setId(R.id.action_help);
        mFragmentsViewModel.onOptionsSelected(option);
    }

    /**
     * Toolbar "scanning" spinner — a global "background work" indicator. It
     * tracks only the in-flight count (debounced), independent of the active
     * filter chip or whether the filtered list is currently empty: filtering to
     * an empty type must not hide it while captures are still streaming in.
     * No-op when the menu isn't inflated or is the action-mode menu.
     */
    private void updateProgressIndicator() {
        if (mProgressMenuItem != null) {
            mProgressMenuItem.setVisible(mBusyShown && !mActionModeEnabled);
        }
    }

    /**
     * Submit a new capture list while keeping the user's scroll fixed. The list
     * re-sorts on every emit (current-visit media floats to the top), so as
     * captures stream in — 200+ while a Twitch/Kick feed loads — items would
     * otherwise insert/move above the viewport and reflow the grid under the
     * user's finger. Anchor on the first visible item's uid + its top offset and
     * restore it after the diff commits: new rows land off-screen above (scroll
     * up to reveal) instead of yanking the view. When nothing is scrolled past
     * (first load / at the top) we don't anchor, so fresh captures show normally.
     */
    private void submitListPreservingScroll(List<BrowserDownloadEntity> downloads) {
        if (mAdapter == null) return;
        if (mLayoutManager == null) {
            mAdapter.submitList(downloads);
            return;
        }
        int firstPos = mLayoutManager.findFirstVisibleItemPosition();
        List<BrowserDownloadEntity> current = mAdapter.getCurrentList();
        if (firstPos <= 0 || firstPos >= current.size()) {
            // Nothing meaningful to anchor (at the top, or first load).
            mAdapter.submitList(downloads);
            return;
        }
        final int anchorUid = current.get(firstPos).getUid();
        final View anchorView = mLayoutManager.findViewByPosition(firstPos);
        final int anchorOffset = anchorView != null ? anchorView.getTop() : 0;
        mAdapter.submitList(downloads, () -> {
            if (mLayoutManager == null || downloads == null) return;
            for (int i = 0; i < downloads.size(); i++) {
                if (downloads.get(i).getUid() == anchorUid) {
                    mLayoutManager.scrollToPositionWithOffset(i, anchorOffset);
                    return;
                }
            }
        });
    }

    private void configureEmptyState() {
        int selectedId = mChipGroup.getCheckedChipId();
        int textRes = R.string.capture_empty_message;
        int imgRes = R.drawable.ill_baloons;

        if (selectedId == R.id.chip_video) {
            textRes = R.string.capture_empty_video_message;
            imgRes = R.drawable.ill_small_video;
        } else if (selectedId == R.id.chip_audio) {
            textRes = R.string.capture_empty_audio_message;
            imgRes = R.drawable.ill_small_audio;
        } else if (selectedId == R.id.chip_image) {
            textRes = R.string.capture_empty_images_message;
            imgRes = R.drawable.ill_small_image;
        } else if (selectedId == R.id.chip_gif) {
            textRes = R.string.capture_empty_gifs_message;
            imgRes = R.drawable.ill_small_gif;
        } else if (selectedId == R.id.chip_subtitle) {
            textRes = R.string.capture_empty_subtitles_message;
            imgRes = R.drawable.ill_small_doc;
        }

        mLCEERecyclerView.setEmptyText(textRes);
        mLCEERecyclerView.setEmptySubText(R.string.capture_empty_subtext);
        mLCEERecyclerView.setEmptyImageView(imgRes);
    }

    // ========================================================================
    // Click handling
    // ========================================================================

    @Override
    public void onItemClick(int position, int resId) {
        if (position == RecyclerView.NO_POSITION) return;

        if (mActionModeEnabled) {
            mAdapter.toggleSelected(position);
            updateActionModeTitle();
            return;
        }

        BrowserDownloadEntity entity = mAdapter.getCurrentList().get(position);

        if (resId == R.id.item) {
            handlePrimaryItemClick(entity);
        } else if (resId == R.id.item_download_more) {
            showItemMenu(position, entity);
        }
    }

    /**
     * The row ⋮ menu (issue #302): Copy URL / Share URL / Open in another app
     * / Select quality. The ⋮ used to open the variant picker DIRECTLY and
     * showed only on multi-quality items; it now shows whenever at least one
     * of these entries applies (the adapter's hasActions gate mirrors this
     * method's visibility rules — keep them in step), and the picker moved
     * behind the "Select quality" entry. Copy/Share/Open work off the
     * capture's default/best stream URL; picking a specific quality's URL is
     * not a flow this menu serves.
     */
    private void showItemMenu(int position, BrowserDownloadEntity entity) {
        View anchor = null;
        RecyclerView recycler = mLCEERecyclerView.getRecyclerView();
        RecyclerView.ViewHolder holder =
                recycler == null ? null : recycler.findViewHolderForAdapterPosition(position);
        if (holder != null) {
            anchor = holder.itemView.findViewById(R.id.item_download_more);
        }
        if (anchor == null) {
            anchor = mLCEERecyclerView;
        }

        String url = CaptureUrlActions.copyableUrl(entity);
        boolean canOpen = url != null
                && CaptureUrlActions.canOpenExternal(requireContext(), entity, url);

        PopupMenu menu = new PopupMenu(requireContext(), anchor);
        menu.inflate(R.menu.menu_browser_capture_item);
        menu.getMenu().findItem(R.id.action_copy_url).setVisible(url != null);
        menu.getMenu().findItem(R.id.action_share_url).setVisible(url != null);
        menu.getMenu().findItem(R.id.action_open_external).setVisible(canOpen);
        menu.getMenu().findItem(R.id.action_show_variants).setVisible(entity.getHasVariants());

        final String menuUrl = url;
        menu.setOnMenuItemClickListener(item -> {
            int id = item.getItemId();
            if (id == R.id.action_copy_url && menuUrl != null) {
                CaptureUrlActions.copy(requireContext(), menuUrl);
                return true;
            }
            if (id == R.id.action_share_url && menuUrl != null) {
                CaptureUrlActions.share(requireContext(), menuUrl);
                return true;
            }
            if (id == R.id.action_open_external && menuUrl != null) {
                CaptureUrlActions.openExternal(requireContext(), entity, menuUrl);
                return true;
            }
            if (id == R.id.action_show_variants) {
                // The pre-menu ⋮ behavior: the holder sheet listens for this
                // event id and pushes the variant picker.
                sendOptionEvent(R.id.item_download_more, entity);
                return true;
            }
            return false;
        });
        menu.show();
    }

    private void handlePrimaryItemClick(BrowserDownloadEntity entity) {
        if (mSharedPreferences.getBoolean(Preferences.SETTINGS_SAVE_ASK, Preferences.DEFAULT_SETTINGS_SAVE_ASK)) {
            // "Ask filename" setting is on — navigate to save dialog (entity still needed for display)
            Bundle bundle = new Bundle();
            bundle.putParcelable(Keys.ITEM_ID, entity);
            NavigationUtils.navigateSafe(mNavController, R.id.dialog_save_file, bundle);
        } else {
            // Direct download — build DownloadRequest from entity (best stream auto-selected)
            dispatchDownload(entity);
        }
    }

    /**
     * Build a DownloadRequest from the entity and dispatch it.
     * Uses getSelectedStream() which defaults to best quality (first variant).
     * No entity mutation — the request is immutable.
     */
    private void dispatchDownload(BrowserDownloadEntity entity) {
        DownloadRequest request = DownloadRequest.from(entity);

        OptionEntity option = new OptionEntity();
        option.setId(R.id.start_download);
        option.setDownloadRequest(request);
        mFragmentsViewModel.onOptionsSelected(option);
    }

    // ========================================================================
    // Batch download
    // ========================================================================

    private void processBatchDownload() {
        ArrayList<DownloadRequest> requests = new ArrayList<>();
        List<BrowserDownloadEntity> list = mAdapter.getCurrentList();
        HashSet<Integer> selectedIds = mAdapter.getSelected();

        for (BrowserDownloadEntity entity : list) {
            if (selectedIds.contains(entity.getUid())) {
                requests.add(DownloadRequest.from(entity));
            }
        }

        OptionEntity option = new OptionEntity();
        option.setId(R.id.start_multiple_download);
        option.setDownloadRequests(requests);
        mFragmentsViewModel.onOptionsSelected(option);
        invalidateActionMode();
    }

    // ========================================================================
    // Action mode
    // ========================================================================

    @Override
    public void onLongClick(int position, int resId) {
        if (!mActionModeEnabled) {
            sendOptionEvent(R.id.divider, null);
            mActionModeEnabled = true;
            mAdapter.setActionMode(true);
            mAdapter.toggleSelected(position);
            setChipsEnabled(false);
            updateActionModeTitle();
            mToolbar.invalidateMenu();
            updateHelpBanner();
        }
    }

    public boolean isActionMode() {
        return mActionModeEnabled;
    }

    public void invalidateActionMode() {
        mActionModeEnabled = false;
        mAdapter.clearSelection();
        mAdapter.setActionMode(false);
        setChipsEnabled(true);
        mToolbar.setTitle(R.string.navigation_captured);
        mToolbar.setBackgroundColor(ResourcesCompat.getColor(getResources(), R.color.transparent, null));
        mToolbar.invalidateMenu();
        mBrowserDownloadViewModel.update();
        sendOptionEvent(R.id.divider, null);
    }

    // ========================================================================
    // Helpers
    // ========================================================================

    private void sendOptionEvent(int id, BrowserDownloadEntity entity) {
        OptionEntity option = new OptionEntity();
        option.setId(id);
        option.setBrowserDownloadEntity(entity);
        mFragmentsViewModel.onOptionsSelected(option);
    }

    private void updateActionModeTitle() {
        mToolbar.setTitle(getString(R.string.action_mode_selected, mAdapter.getSelectedSize()));
    }

    private void setChipsEnabled(boolean enabled) {
        for (int i = 0; i < mChipGroup.getChildCount(); i++) {
            mChipGroup.getChildAt(i).setEnabled(enabled);
        }
        mChipGroup.setEnabled(enabled);
    }

    @Override
    public void onCheckedChanged(@NonNull ChipGroup group, @NonNull List<Integer> checkedIds) {
        // Empty selection now means "show all" — Material filter-chip
        // convention, matches DownloadFragment's effective behavior.
        // getCurrentSortForIds returns SORT_TYPE_ALL on an unknown id,
        // so View.NO_ID routes to the same predicate fall-through.
        int selectedId = checkedIds.isEmpty() ? View.NO_ID : checkedIds.get(0);
        String type = mBrowserDownloadViewModel.getCurrentSortForIds(selectedId);
        mBrowserDownloadViewModel.sortBrowserDownloads(type);
        // Deliberately NO presentation change here (density/span/mime
        // suppression): the requery is async, so flipping the adapter now
        // would re-render the OLD list in the NEW presentation first —
        // on-device that showed the images mosaic collapsing to normal
        // span-2 image tiles for a beat before the videos arrived. The
        // downloads observer flips presentation atomically with the new
        // list's commit (submitWithPresentation).
        // Only intercept Back while a chip is actually checked — otherwise
        // Back must behave normally (close the screen).
        if (mClearFilterOnBack != null) mClearFilterOnBack.setEnabled(!checkedIds.isEmpty());
    }


    @Override
    public void onStop() {
        super.onStop();
        mBrowserDownloadViewModel.setCurrentSortBrowser(mChipGroup.getCheckedChipId());
        mSharedPreferences.edit().putBoolean(Preferences.SORT_LIST, mEnableGrid).apply();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        mSpinnerHandler.removeCallbacks(mHideSpinner);
        mChipGroup = null;
        mToolbar = null;
        mHelpBanner = null;
        mAdapter = null;
        mLayoutManager = null;
        mProgressMenuItem = null;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        mBrowserDownloadViewModel.setCurrentSortBrowser(Sorting.SORT_TYPE_ALL);
    }

    @Override
    public void onItemVariantClick(int position, int variant, int resId) {
        if (position == RecyclerView.NO_POSITION) return;
        BrowserDownloadEntity entity = mAdapter.getCurrentList().get(position);
        handlePrimaryItemClick(entity);
    }
}