package com.solarized.firedown.phone.dialogs;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.Toolbar;
import androidx.core.content.ContextCompat;
import androidx.core.content.res.ResourcesCompat;
import androidx.core.graphics.ColorUtils;
import androidx.core.graphics.drawable.DrawableCompat;
import androidx.core.view.MenuProvider;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearSmoothScroller;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.ListPreloader;
import com.bumptech.glide.RequestBuilder;
import com.bumptech.glide.RequestManager;
import com.bumptech.glide.integration.recyclerview.RecyclerViewPreloader;
import com.bumptech.glide.util.FixedPreloadSizeProvider;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.solarized.firedown.BuildConfig;
import com.solarized.firedown.GlideHelper;
import com.solarized.firedown.Keys;
import com.solarized.firedown.Preferences;
import com.solarized.firedown.R;
import com.solarized.firedown.data.capture.CaptureItem;
import com.solarized.firedown.data.capture.CaptureSections.BatchSummary;
import com.solarized.firedown.data.entity.BrowserDownloadEntity;
import com.solarized.firedown.data.entity.OptionEntity;
import com.solarized.firedown.data.models.BrowserDownloadViewModel;
import com.solarized.firedown.data.models.FragmentsOptionsViewModel;
import com.solarized.firedown.manager.DownloadRequest;
import com.solarized.firedown.phone.DownloadsActivity;
import com.solarized.firedown.phone.SettingsActivity;
import com.solarized.firedown.phone.VaultActivity;
import com.solarized.firedown.phone.fragments.BaseFocusFragment;
import com.solarized.firedown.ui.CaptureSpacingDecoration;
import com.solarized.firedown.ui.IncognitoColors;
import com.solarized.firedown.ui.OnItemClickListener;
import com.solarized.firedown.ui.adapters.BrowserOptionAdapter;
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
public class BrowserOptionFragment extends BaseFocusFragment implements OnItemClickListener {

    @Inject SharedPreferences mSharedPreferences;
    private BrowserOptionAdapter mAdapter;
    private BrowserDownloadViewModel mBrowserDownloadViewModel;
    private FragmentsOptionsViewModel mFragmentsViewModel;
    private GridLayoutManager mLayoutManager;
    private Toolbar mToolbar;
    private View mHelpBanner;
    private MaterialCardView mBatchCard;
    private TextView mBatchText;
    private LinearProgressIndicator mBatchProgress;
    private boolean mHelpBannerDismissed;
    private boolean mIsIncognito;
    private int mScrollLimit;
    // Set by a tap that OPENS the fold, cleared by the first list that comes
    // back with it open: that list's commit scrolls the fold to the top.
    private boolean mRevealFoldOnCommit;

    // "Still scanning" indicator state. mBusyShown is the debounced, displayed
    // state; mListEmpty mirrors the latest list so a busy/empty change re-renders
    // the right state. Debounced so fast/aborting inspect tasks don't flash.
    // empty+busy → LCEE loading; content+busy → a toolbar spinner (action_progress).
    private MenuItem mProgressMenuItem;
    private boolean mListEmpty = true;
    private boolean mBusyShown;
    private static final long SPINNER_HIDE_LINGER_MS = 500L;
    private final Handler mSpinnerHandler = new Handler(Looper.getMainLooper());
    private final Runnable mHideSpinner = () -> { mBusyShown = false; renderListState(); };

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mScrollLimit = Preferences.LIST_LIMIT;
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
        mLCEERecyclerView = view.findViewById(R.id.list_recycler_lcee);
        mLCEERecyclerView.setEmptyButtonVisibility(View.VISIBLE);
        // One empty state for the whole sheet.
        mLCEERecyclerView.setEmptyText(R.string.capture_empty_message);
        mLCEERecyclerView.setEmptySubText(R.string.capture_empty_subtext);
        mLCEERecyclerView.setEmptyImageView(R.drawable.ill_baloons);

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

        mBatchCard = view.findViewById(R.id.capture_batch_card);
        mBatchText = view.findViewById(R.id.capture_batch_text);
        mBatchProgress = view.findViewById(R.id.capture_batch_progress);
        mBatchCard.setOnClickListener(v -> openDownloads());

        if (mIsIncognito) {
            mToolbar.setPopupTheme(R.style.Theme_FireDown_Popup_Vault);
            int onSurface = IncognitoColors.getOnSurface(requireContext(), true);
            mToolbar.setTitleTextColor(onSurface);
            Drawable navIcon = mToolbar.getNavigationIcon();
            if (navIcon != null) DrawableCompat.setTint(navIcon, onSurface);
        }

        mToolbar.setContentInsetsAbsolute(getResources().getDimensionPixelSize(R.dimen.list_spacing), 0);

        mLCEERecyclerView.setButtonListener(id -> {
            OptionEntity option = new OptionEntity();
            option.setId(id);
            mFragmentsViewModel.onOptionsSelected(option);
        });

        mRecyclerView = mLCEERecyclerView.getRecyclerView();
        // One grid for the whole mixed list: tiles two to a line, image
        // squares three (on a phone), the Images header and the fold row the
        // full line. The span count is the least common multiple of the two,
        // so both divide it. There is no list/grid toggle — the layout follows
        // the capture's type instead (see CaptureItem).
        int squareColumns = getResources().getInteger(R.integer.capture_columns);
        int tileColumns = getResources().getInteger(R.integer.capture_tile_columns);
        int columns = leastCommonMultiple(squareColumns, tileColumns);
        mLayoutManager = new GridLayoutManager(requireContext(), columns);
        mRecyclerView.setLayoutManager(mLayoutManager);

        // The view's own context, not the activity's: in a private tab the
        // sheet inflates under the incognito overlay, and the adapter resolves
        // its inks from the context it is given.
        mAdapter = new BrowserOptionAdapter(view.getContext(), this);
        mAdapter.setOnFoldClick(this::onFoldClick);
        mAdapter.setOnSelectionChanged(this::updateActionModeTitle);
        mLayoutManager.setSpanSizeLookup(new GridLayoutManager.SpanSizeLookup() {
            @Override
            public int getSpanSize(int position) {
                return mAdapter != null
                        ? mAdapter.spanSize(position, columns, squareColumns, tileColumns)
                        : columns;
            }
        });
        mRecyclerView.setAdapter(mAdapter);
        CaptureSpacingDecoration.attach(mRecyclerView);

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
            // warm for the tiles about to enter the viewport (they decode at
            // the fixed browser-thumb size, so the preload and the bind share
            // a key). Not the squares: an SVG or GIF square loads through a
            // different branch with no fixed size.
            RequestManager glide = Glide.with(this);

            RecyclerViewPreloader<BrowserDownloadEntity> preloader = new RecyclerViewPreloader<>(
                    glide,
                    new ListPreloader.PreloadModelProvider<>() {
                        @NonNull
                        @Override
                        public List<BrowserDownloadEntity> getPreloadItems(int position) {
                            CaptureItem item = mAdapter != null ? mAdapter.getItemAt(position) : null;
                            if (item == null || item.entity == null
                                    || item.type != CaptureItem.TYPE_TILE) {
                                return Collections.emptyList();
                            }
                            return Collections.singletonList(item.entity);
                        }

                        @Nullable
                        @Override
                        public RequestBuilder<?> getPreloadRequestBuilder(@NonNull BrowserDownloadEntity entity) {
                            if (mAdapter == null) return null;
                            // The adapter's own options, so the preload-warmed
                            // bitmap and the bind-time request share a key.
                            return GlideHelper.preloadBrowser(glide, entity,
                                    mAdapter.optionsFor(entity, CaptureItem.TYPE_TILE));
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

        mToolbar.invalidateMenu();
        mToolbar.addMenuProvider(new MenuProvider() {
            @Override
            public void onCreateMenu(@NonNull Menu menu, @NonNull MenuInflater menuInflater) {
                if(mIsIncognito){
                    menuInflater.inflate(mActionModeEnabled ? R.menu.menu_capture_action : R.menu.menu_capture_incognito, menu);
                }else{
                    menuInflater.inflate(mActionModeEnabled ? R.menu.menu_capture_action : R.menu.menu_capture, menu);
                }
                // The "scanning" spinner (absent in the action-mode menu).
                // Re-apply its visibility on every (re)inflate from the
                // current debounced busy state.
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
                if (id == R.id.action_clear) {
                    mBrowserDownloadViewModel.clearBrowserDownloads();
                    return true;
                } else if (id == R.id.action_download) {
                    processBatchDownload();
                    return true;
                } else if (id == R.id.action_copy_url) {
                    copySelectedUrls();
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
                    openDownloads();
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

        mBrowserDownloadViewModel.getCaptureItems(mScrollLimit).observe(getViewLifecycleOwner(), items -> {

            if (mActionModeEnabled) return;

            mListEmpty = items == null || items.isEmpty();
            renderListState();
            // The items carry their own presentation (sections, type labels,
            // marks), built in the view model for the list they belong to:
            // no adapter flags, no commit callback to flip them.
            submitListPreservingScroll(items);
        });

        mBrowserDownloadViewModel.getBatchSummary().observe(getViewLifecycleOwner(), this::renderBatch);

        mBrowserDownloadViewModel.getInflight().observe(getViewLifecycleOwner(),
                count -> onInflightChanged(count == null ? 0 : count));

        mBrowserDownloadViewModel.update();
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
     * state. The toolbar spinner is the single "work happening" cue and is
     * driven independently — see {@link #updateProgressIndicator()} — so it
     * doesn't double up with a centre loading spinner.
     */
    private void renderListState() {
        if (!mActionModeEnabled) {
            if (mListEmpty) {
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
     * tracks only the in-flight count (debounced), independent of whether the
     * list is currently empty. No-op when the menu isn't inflated or is the
     * action-mode menu.
     */
    private void updateProgressIndicator() {
        if (mProgressMenuItem != null) {
            mProgressMenuItem.setVisible(mBusyShown && !mActionModeEnabled);
        }
    }

    /**
     * Submit a new capture list while keeping the user's scroll fixed. The list
     * re-sorts on every emit (current-visit media floats to the top), so as
     * captures stream in — 200+ while a live feed loads — items would
     * otherwise insert/move above the viewport and reflow the grid under the
     * user's finger. Anchor on the first visible item's uid + its top offset and
     * restore it after the diff commits: new rows land off-screen above (scroll
     * up to reveal) instead of yanking the view. When nothing is scrolled past
     * (first load / at the top) we don't anchor, so fresh captures show normally.
     */
    private void submitListPreservingScroll(List<CaptureItem> items) {
        if (mAdapter == null) return;
        if (mLayoutManager == null) {
            mAdapter.submitList(items);
            return;
        }
        if (mRevealFoldOnCommit) {
            int fold = foldPosition(items);
            if (fold < 0) {
                mRevealFoldOnCommit = false;
            } else if (items.get(fold).open) {
                mRevealFoldOnCommit = false;
                mAdapter.submitList(items, () -> revealFold(fold));
                return;
            }
        }
        int firstPos = mLayoutManager.findFirstVisibleItemPosition();
        List<CaptureItem> current = mAdapter.getCurrentList();
        if (firstPos <= 0 || firstPos >= current.size()) {
            // Nothing meaningful to anchor (at the top, or first load).
            mAdapter.submitList(items);
            return;
        }
        // Anchor on the first visible CAPTURE (a header or the divider has no
        // identity that survives a re-sort).
        int anchorPos = firstPos;
        while (anchorPos < current.size() && !current.get(anchorPos).isCapture()) {
            anchorPos++;
        }
        if (anchorPos >= current.size()) {
            mAdapter.submitList(items);
            return;
        }
        final int anchorUid = current.get(anchorPos).uid();
        final View anchorView = mLayoutManager.findViewByPosition(anchorPos);
        final int anchorOffset = anchorView != null ? anchorView.getTop() : 0;
        mAdapter.submitList(items, () -> {
            if (mLayoutManager == null || items == null) return;
            for (int i = 0; i < items.size(); i++) {
                if (items.get(i).isCapture() && items.get(i).uid() == anchorUid) {
                    mLayoutManager.scrollToPositionWithOffset(i, anchorOffset);
                    return;
                }
            }
        });
    }

    /**
     * The fold's own tap (and the screen reader's expand / collapse). Opening
     * it puts the earlier captures UNDER the fold, and the fold usually ends
     * the list, at the bottom of the screen: the captures landed out of
     * sight, the only visible change was the chevron, and the list read as
     * having grown somewhere the user had to go and find (reported
     * on-device). So a tap that opens it scrolls the fold to the top once the
     * opened list is in, and the earlier captures fill the screen under it.
     * Only the tap: the view model also opens the fold by itself on a page
     * that has caught nothing yet, and that must not move the list. Closing
     * scrolls nothing.
     */
    private void onFoldClick() {
        if (mAdapter == null) return;
        List<CaptureItem> current = mAdapter.getCurrentList();
        int fold = foldPosition(current);
        mRevealFoldOnCommit = fold >= 0 && !current.get(fold).open;
        // The view model publishes the rebuilt list synchronously, so the
        // flag has to be set before this call, not after.
        mBrowserDownloadViewModel.toggleEarlier();
    }

    private static int foldPosition(@Nullable List<CaptureItem> items) {
        if (items == null) return -1;
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).type == CaptureItem.TYPE_FOLD) {
                return i;
            }
        }
        return -1;
    }

    /** Scrolls the fold to the top of the list, or as far as the list goes
     *  when what it opened is shorter than the screen. */
    private void revealFold(int position) {
        if (mLayoutManager == null || mRecyclerView == null) return;
        LinearSmoothScroller scroller = new LinearSmoothScroller(mRecyclerView.getContext()) {
            @Override
            protected int getVerticalSnapPreference() {
                return SNAP_TO_START;
            }
        };
        scroller.setTargetPosition(position);
        mLayoutManager.startSmoothScroll(scroller);
    }

    /**
     * The batch card above the list: "Saved 3 of 20 · 4 downloading · 13
     * pending", the batch's overall progress along its bottom edge. Hidden
     * while no batch is in flight; once every item settled it stays, as the
     * record of what the batch did, until the sheet closes.
     */
    private void renderBatch(@Nullable BatchSummary summary) {
        if (mBatchCard == null) return;
        if (summary == null || summary.total == 0) {
            mBatchCard.setVisibility(View.GONE);
            return;
        }
        Context context = mBatchCard.getContext();
        SpannableStringBuilder text = new SpannableStringBuilder(
                getString(R.string.capture_batch_saved, summary.saved, summary.total));
        text.setSpan(new ForegroundColorSpan(MaterialColors.getColor(context,
                        com.google.android.material.R.attr.colorOnSurface, Color.BLACK)),
                0, text.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        text.setSpan(new StyleSpan(Typeface.BOLD), 0, text.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        if (summary.downloading > 0) {
            text.append(" · ").append(getResources().getQuantityString(
                    R.plurals.capture_batch_downloading, summary.downloading, summary.downloading));
        }
        if (summary.pending > 0) {
            text.append(" · ").append(getResources().getQuantityString(
                    R.plurals.capture_batch_pending, summary.pending, summary.pending));
        }
        if (summary.failed > 0) {
            text.append(" · ");
            int start = text.length();
            text.append(getResources().getQuantityString(
                    R.plurals.capture_batch_failed, summary.failed, summary.failed));
            text.setSpan(new ForegroundColorSpan(MaterialColors.getColor(context,
                            androidx.appcompat.R.attr.colorError, Color.RED)),
                    start, text.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        mBatchText.setText(text);
        if (summary.isDone()) {
            mBatchProgress.setVisibility(View.GONE);
        } else {
            mBatchProgress.setIndicatorColor(ContextCompat.getColor(context, R.color.progress_indicator));
            mBatchProgress.setTrackColor(ColorUtils.setAlphaComponent(
                    MaterialColors.getColor(context, android.R.attr.colorPrimary, Color.BLACK), 0x33));
            mBatchProgress.setProgressCompat(summary.percent,
                    mBatchProgress.getVisibility() == View.VISIBLE);
            mBatchProgress.setVisibility(View.VISIBLE);
        }
        mBatchCard.setVisibility(View.VISIBLE);
    }

    /** Downloads, or the Safe Folder in a private tab (whose downloads land
     *  there). The toolbar item and the batch card both land here. */
    private void openDownloads() {
        Class<?> target = mIsIncognito ? VaultActivity.class : DownloadsActivity.class;
        mStartForResult.launch(new Intent(requireContext(), target));
    }

    // ========================================================================
    // Click handling
    // ========================================================================

    @Override
    public void onItemClick(int position, int resId) {
        if (position == RecyclerView.NO_POSITION) return;

        BrowserDownloadEntity entity = mAdapter.getEntityAt(position);
        if (entity == null) return;

        if (mActionModeEnabled) {
            mAdapter.toggleSelected(position);
            updateActionModeTitle();
            return;
        }

        if (resId == R.id.item) {
            handlePrimaryItemClick(entity);
        } else if (resId == R.id.item_download_more) {
            // The row's action slot is the ⋮ of a multi-variant capture only:
            // it opens the quality picker (the holder pushes it as an in-sheet
            // page). The adapter shows the slot for nothing else — the per-row
            // copy glyph was removed at the maintainer's request; Copy URL
            // lives behind long-press → multi-select (copySelectedUrls). Keep
            // this branch and the adapter's slot rule in step. The 1.1.93
            // Copy/Share/Open menu page that sat between the two was removed —
            // see CaptureUrlActions for why.
            if (entity.getHasVariants()) {
                sendOptionEvent(R.id.item_download_more, entity);
            }
        }
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
        ArrayList<BrowserDownloadEntity> captures = new ArrayList<>();
        HashSet<Integer> selectedIds = mAdapter.getSelected();

        for (CaptureItem item : mAdapter.getCurrentList()) {
            BrowserDownloadEntity entity = item.entity;
            if (entity != null && selectedIds.contains(entity.getUid())) {
                requests.add(DownloadRequest.from(entity));
                captures.add(entity);
            }
        }

        OptionEntity option = new OptionEntity();
        option.setId(R.id.start_multiple_download);
        option.setDownloadRequests(requests);
        mFragmentsViewModel.onOptionsSelected(option);
        // The batch card counts these down; the rows and squares carry their
        // own marks either way.
        mBrowserDownloadViewModel.startBatch(captures);
        invalidateActionMode();
    }

    /**
     * Multi-select Copy URL: every selected capture's {@link
     * CaptureUrlActions#externalUrl} in list order, one per line (a
     * playlist / Radio Browser batch pastes as-is). Captures with no plain
     * http(s) URL are skipped silently — an empty result copies nothing.
     * Exits selection like the batch download does.
     */
    private void copySelectedUrls() {
        ArrayList<String> urls = new ArrayList<>();
        HashSet<Integer> selectedIds = mAdapter.getSelected();
        for (CaptureItem item : mAdapter.getCurrentList()) {
            BrowserDownloadEntity entity = item.entity;
            if (entity == null || !selectedIds.contains(entity.getUid())) continue;
            String url = CaptureUrlActions.externalUrl(entity);
            if (url != null) {
                urls.add(url);
            }
        }
        CaptureUrlActions.copy(requireContext(), urls);
        invalidateActionMode();
    }

    // ========================================================================
    // Action mode
    // ========================================================================

    @Override
    public void onLongClick(int position, int resId) {
        if (mAdapter.getEntityAt(position) == null) return;
        if (!mActionModeEnabled) {
            sendOptionEvent(R.id.divider, null);
            mActionModeEnabled = true;
            mAdapter.setActionMode(true);
            mAdapter.toggleSelected(position);
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

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        mSpinnerHandler.removeCallbacks(mHideSpinner);
        mToolbar = null;
        mHelpBanner = null;
        mBatchCard = null;
        mBatchText = null;
        mBatchProgress = null;
        mAdapter = null;
        mLayoutManager = null;
        mProgressMenuItem = null;
    }

    @Override
    public void onItemVariantClick(int position, int variant, int resId) {
        if (position == RecyclerView.NO_POSITION) return;
        BrowserDownloadEntity entity = mAdapter.getEntityAt(position);
        if (entity != null) {
            handlePrimaryItemClick(entity);
        }
    }

    /** The grid's span count for two per-line counts: both must divide it. */
    private static int leastCommonMultiple(int a, int b) {
        int x = Math.max(1, a);
        int y = Math.max(1, b);
        int p = x;
        int q = y;
        while (q != 0) {
            int t = p % q;
            p = q;
            q = t;
        }
        return x / p * y;
    }
}
