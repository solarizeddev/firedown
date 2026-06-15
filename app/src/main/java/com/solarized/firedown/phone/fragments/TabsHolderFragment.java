package com.solarized.firedown.phone.fragments;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.os.Bundle;
import android.util.Log;
import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.ImageButton;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.PopupMenu;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.lifecycle.ViewModelProvider;
import androidx.viewpager2.adapter.FragmentStateAdapter;
import androidx.viewpager2.widget.ViewPager2;

import com.google.android.material.appbar.AppBarLayout;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.button.MaterialButtonToggleGroup;
import com.solarized.firedown.Keys;
import com.solarized.firedown.Preferences;
import com.solarized.firedown.R;
import com.solarized.firedown.data.entity.GeckoStateEntity;
import com.solarized.firedown.data.models.BrowserURIViewModel;
import com.solarized.firedown.data.models.GeckoStateViewModel;
import com.solarized.firedown.data.models.IncognitoStateViewModel;
import com.solarized.firedown.geckoview.GeckoState;
import com.solarized.firedown.IntentActions;
import com.solarized.firedown.phone.SettingsActivity;
import com.solarized.firedown.ui.IncognitoColors;
import com.solarized.firedown.ui.browser.TabCountDrawable;
import com.solarized.firedown.utils.NavigationUtils;

import androidx.navigation.NavController;
import androidx.navigation.fragment.NavHostFragment;

import javax.inject.Inject;

import dagger.hilt.android.AndroidEntryPoint;

/**
 * Holder fragment with a compact icon-only mode switcher + ViewPager2.
 *
 * <p><b>Chrome:</b> no toolbar — the centred icon-pill switcher IS the
 * header, on an ALWAYS-TONAL bar (surfaceContainer, same tone as the bottom
 * action bar, so the screen reads as a framed switcher). The toolbar's old
 * actions live in the bottom bar around the FAB: grid/list as a dedicated
 * button, the rest (close all / archive / settings) behind an overflow
 * {@link PopupMenu}.
 *
 * <p><b>No lift-on-scroll.</b> The header being always tonal removed the
 * need for the old hand-rolled lift scrim (a sibling View whose alpha was
 * driven by the visible page's RecyclerView). If a scroll-elevation effect
 * is ever wanted again, resurrect that scrim approach from git history —
 * AppBarLayout's own liftOnScroll machinery races its async shouldLift /
 * canScrollVertically check across the ViewPager2 boundary and must not be
 * used here.
 */
@AndroidEntryPoint
public class TabsHolderFragment extends BaseFocusFragment {

    private static final String TAG = TabsHolderFragment.class.getSimpleName();

    private static final int PAGE_REGULAR = 0;
    private static final int PAGE_INCOGNITO = 1;

    public static final String KEY_SELECT_HOME = "select_home_tab";
    public static final String KEY_SELECT_BROWSER = "select_browser_tab";
    private static final String KEY_CURRENT_PAGE = "tabs_holder_current_page";
    public static final String KEY_SWITCH_TO_REGULAR = "switch_to_regular_tabs";
    public static final String KEY_SELECT_INCOGNITO_HOME = "select_incognito_home_tab";

    private View mCoordinatorRoot;
    private AppBarLayout mAppBarLayout;
    private View mTabsHeader;
    private MaterialButtonToggleGroup mToggleGroup;
    private ViewPager2 mViewPager;
    private View mBottomBar;
    private View mBottomBarDivider;
    private View mHeaderDivider;
    private ImageButton mViewButton;
    private ImageButton mOverflowButton;
    private NavController mNavController;
    private GeckoStateViewModel mGeckoStateViewModel;
    private IncognitoStateViewModel mIncognitoStateViewModel;
    private BrowserURIViewModel mBrowserURIViewModel;
    private boolean mIsIncognitoThemed = false;
    private boolean mEnabledGrid;

    @Inject
    SharedPreferences mSharedPreferences;

    @Override
    public void onStop() {
        super.onStop();
        mSharedPreferences.edit().putBoolean(Preferences.SORT_TABS_LIST, mEnabledGrid).apply();
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // No postponeEnterTransition: the child tabs fragments now follow
        // Chrome's pattern — they attach the adapter, submit the list,
        // and call scrollToPositionWithOffset all on the same call stack
        // (see BaseTabsFragment#applyFirstSnapshot). The RecyclerView's
        // very first layout pass uses the pending scroll position as
        // its anchor, so frame 1 is already at the active row. There's
        // no "draw top, then jump" race left to hide.

        mEnabledGrid = mSharedPreferences.getBoolean(Preferences.SORT_TABS_LIST, false);

        mGeckoStateViewModel = new ViewModelProvider(mActivity).get(GeckoStateViewModel.class);
        mIncognitoStateViewModel = new ViewModelProvider(mActivity).get(IncognitoStateViewModel.class);
        mBrowserURIViewModel = new ViewModelProvider(mActivity).get(BrowserURIViewModel.class);

        mActivity.getOnBackPressedDispatcher().addCallback(this,
                new OnBackPressedCallback(true) {
                    @Override
                    public void handleOnBackPressed() {
                        handleBack();
                        setEnabled(false);
                    }
                });
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {

        View view = inflater.inflate(R.layout.fragment_tabs_holder, container, false);

        mNavController = NavHostFragment.findNavController(this);

        mCoordinatorRoot = view.findViewById(R.id.coordinator_root);
        mAppBarLayout = view.findViewById(R.id.appbar_layout);
        mTabsHeader = view.findViewById(R.id.tabs_header);
        mToggleGroup = view.findViewById(R.id.tab_mode_toggle);
        mViewPager = view.findViewById(R.id.tabs_view_pager);
        mBottomBar = view.findViewById(R.id.tabs_bottom_bar);
        mBottomBarDivider = view.findViewById(R.id.tabs_bottom_bar_divider);
        mHeaderDivider = view.findViewById(R.id.tabs_header_divider);
        mViewButton = view.findViewById(R.id.action_view_btn);
        mOverflowButton = view.findViewById(R.id.action_overflow_btn);
        mFab = view.findViewById(R.id.fab_new_tab);

        // Header + bottom bar are both flat SURFACE (the same tone as the grid
        // and as Home's chrome); each is divided from the grid by its hairline
        // (mHeaderDivider below the header, mBottomBarDivider above the bar).
        // applyIncognitoTheme swaps the tone for incognito mode.
        mAppBarLayout.setBackgroundColor(
                IncognitoColors.getSurface(mActivity, false));

        // Bottom action bar: the grid/list toggle as a dedicated button,
        // everything else behind the overflow PopupMenu (menu_tabs.xml).
        mViewButton.setImageResource(
                !mEnabledGrid ? R.drawable.ic_view_list_24 : R.drawable.ic_grid_view_24);
        mViewButton.setOnClickListener(v -> toggleViewMode());
        mOverflowButton.setOnClickListener(v -> showOverflowMenu());
        mBottomBar.setBackgroundColor(IncognitoColors.getSurface(mActivity, false));
        if (mBottomBarDivider != null) {
            mBottomBarDivider.setBackgroundColor(bottomBarDividerColor(false));
        }
        if (mHeaderDivider != null) {
            mHeaderDivider.setBackgroundColor(bottomBarDividerColor(false));
        }

        // ENTRY-path window strips. applyIncognitoTheme (which also calls
        // paintSystemBars) only runs on the page toggle / open-incognito
        // arg / close-all — NOT on a plain normal-mode entry, so without
        // this the strips keep the PREVIOUS screen's window colors (e.g.
        // Home leaves a surface-black status bar under our header on
        // Android <= 14, where the window layer is the strip painter).
        // Both strips = surface (the flat header + bottom bar).
        paintSystemBars(
                IncognitoColors.getSurface(mActivity, false),
                IncognitoColors.getSurface(mActivity, false));

        // The header container takes the status-bar inset as top padding,
        // so the tonal bar paints up behind the status bar.
        ViewCompat.setOnApplyWindowInsetsListener(mTabsHeader, (v, windowInsets) -> {
            Insets bars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(v.getPaddingLeft(), bars.top, v.getPaddingRight(),
                    v.getPaddingBottom());
            return windowInsets;
        });

        // Bar paints edge-to-edge behind gesture nav; the pager bottom-pads
        // by the bar's final height so the last tab row clears it. The FAB
        // floats over the bar's middle slot lifted by the same
        // app_bar_fab_margin the browser's fire FAB uses, so the hero
        // button's vertical position matches across the two screens.
        ViewCompat.setOnApplyWindowInsetsListener(mBottomBar, (v, windowInsets) -> {
            Insets bars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(v.getPaddingLeft(), v.getPaddingTop(), v.getPaddingRight(),
                    bars.bottom);
            if (mFab != null) {
                ViewGroup.MarginLayoutParams params =
                        (ViewGroup.MarginLayoutParams) mFab.getLayoutParams();
                int lift = getResources().getDimensionPixelOffset(R.dimen.app_bar_fab_margin);
                int margin = bars.bottom + lift;
                if (params.bottomMargin != margin) {
                    params.bottomMargin = margin;
                    mFab.setLayoutParams(params);
                }
            }
            return windowInsets;
        });
        mBottomBar.addOnLayoutChangeListener(
                (v, l, t, r, b, ol, ot, or, ob) -> {
                    int height = b - t;
                    if (mViewPager != null && mViewPager.getPaddingBottom() != height) {
                        mViewPager.setPadding(0, 0, 0, height);
                    }
                });

        mFab.setOnClickListener(v -> addNewTab());

        setupViewPager();
        setupToggle();
        setupFragmentResultListener();

        int initialPage = PAGE_REGULAR;

        if (savedInstanceState != null) {
            initialPage = savedInstanceState.getInt(KEY_CURRENT_PAGE, PAGE_REGULAR);
        } else if (getArguments() != null) {
            initialPage = getArguments().getBoolean(Keys.OPEN_INCOGNITO, false)
                    ? PAGE_INCOGNITO : PAGE_REGULAR;
        }

        mViewPager.setCurrentItem(initialPage, false);
        updateToggle(initialPage);

        return view;
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        if (mViewPager.getCurrentItem() == PAGE_INCOGNITO) {
            view.post(() -> {
                mIsIncognitoThemed = false;
                applyIncognitoTheme(true);
            });
        }
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        if (mViewPager != null) {
            outState.putInt(KEY_CURRENT_PAGE, mViewPager.getCurrentItem());
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        mCoordinatorRoot = null;
        mFab = null;
        mAppBarLayout = null;
        mTabsHeader = null;
        mBottomBar = null;
        mBottomBarDivider = null;
        mHeaderDivider = null;
        mViewButton = null;
        mOverflowButton = null;
        mToggleGroup = null;
        mViewPager = null;
    }

    // ── Setup ────────────────────────────────────────────────────────

    /** The bottom bar's grid/list button (the old action_view menu item). */
    private void toggleViewMode() {
        mEnabledGrid = !mEnabledGrid;
        mViewButton.setImageResource(
                !mEnabledGrid ? R.drawable.ic_view_list_24 : R.drawable.ic_grid_view_24);
        applyGridModeToChildren(mEnabledGrid);
    }

    /** The bottom bar's overflow: close all / archive / settings. */
    private void showOverflowMenu() {
        PopupMenu popup = new PopupMenu(
                new ContextThemeWrapper(mActivity, R.style.Theme_FireDown_Popup),
                mOverflowButton);
        popup.getMenuInflater().inflate(R.menu.menu_tabs, popup.getMenu());
        popup.setOnMenuItemClickListener(menuItem -> {
            int id = menuItem.getItemId();
            if (id == R.id.action_close_all) {
                mGeckoStateViewModel.deleteAll();
                mIncognitoStateViewModel.deleteAll();
                return true;
            } else if (id == R.id.action_tabs_archive) {
                NavigationUtils.navigateSafe(mNavController, R.id.tabs_archive);
                return true;
            } else if (id == R.id.action_settings) {
                Intent intent = new Intent(mActivity, SettingsActivity.class);
                startActivity(intent);
                return true;
            }
            return false;
        });
        popup.show();
    }

    private void setupViewPager() {
        mViewPager.setAdapter(new TabsPagerAdapter(this));
        mViewPager.setOffscreenPageLimit(1);
        // Tap-only mode switching via the segmented selector above.
        // ViewPager2's input handling intercepts horizontal drags at
        // onInterceptTouchEvent, which races the per-tile
        // ItemTouchHelper that owns swipe-to-close — the tile only
        // wins for slow, perfectly-horizontal drags from the centre,
        // so swipe-to-close felt broken on any natural gesture.
        // Closing tabs is high-frequency, mode-switching is low-
        // frequency, and the segmented selector is the visible
        // affordance for the latter anyway.
        mViewPager.setUserInputEnabled(false);

        mViewPager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override
            public void onPageSelected(int position) {
                updateToggle(position);
                applyIncognitoTheme(position == PAGE_INCOGNITO);
            }
        });
    }

    private void setupToggle() {
        mToggleGroup.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
            if (!isChecked) return;
            if (checkedId == R.id.btn_regular_tabs) {
                mViewPager.setCurrentItem(PAGE_REGULAR, true);
            } else if (checkedId == R.id.btn_incognito_tabs) {
                mViewPager.setCurrentItem(PAGE_INCOGNITO, true);
            }
        });

        // The regular segment shows the LIVE tab count in the same
        // rounded-rect glyph the bottom bar's TabsBrowserButton renders
        // (incl. the >99 🔥 cap — shared formatTabsCount, so the two can
        // never drift). Fenix's tab-tray pattern: the count is most
        // useful on the UNSELECTED segment (how many regular tabs wait
        // while you're on the incognito page). The incognito segment
        // deliberately stays the plain mask with NO count — advertising
        // the private-tab count on a shared screen is a shoulder-surf
        // leak (and Fenix doesn't count private either). Theming is
        // free: updateSegmentedButtons' setIconTint flows into the
        // drawable's setTintList/onStateChange, so selected/unselected/
        // incognito content colors paint it with no extra wiring.
        MaterialButton regularButton = mToggleGroup.findViewById(R.id.btn_regular_tabs);
        if (regularButton != null) {
            TabCountDrawable countDrawable = new TabCountDrawable(getResources());
            regularButton.setIcon(countDrawable);
            mGeckoStateViewModel.getTabsCount().observe(getViewLifecycleOwner(),
                    count -> countDrawable.setCount(count != null ? count : 0));
        }
    }

    private void setupFragmentResultListener() {

        FragmentManager fragmentManager = getChildFragmentManager();

        fragmentManager.setFragmentResultListener(
                KEY_SWITCH_TO_REGULAR,
                getViewLifecycleOwner(),
                (requestKey, result) -> {
                    mViewPager.setCurrentItem(PAGE_REGULAR, true);
                    GeckoState regularState = mGeckoStateViewModel.getCurrentGeckoState();
                    if (regularState != null && !regularState.isHome()) {
                        mBrowserURIViewModel.onEventSelected(
                                regularState.getGeckoStateEntity(), IntentActions.OPEN_SESSION);
                    }
                });

        fragmentManager.setFragmentResultListener(
                KEY_SELECT_HOME,
                getViewLifecycleOwner(),
                (requestKey, result) -> {
                    if (mIsIncognitoThemed) applyIncognitoTheme(false);
                    NavigationUtils.navigateSafe(mNavController, R.id.action_tabs_to_home);
                });

        fragmentManager.setFragmentResultListener(
                KEY_SELECT_INCOGNITO_HOME,
                getViewLifecycleOwner(),
                (requestKey, result) -> {
                    NavigationUtils.navigateSafe(mNavController, R.id.action_tabs_to_home_incognito);
                });

        fragmentManager.setFragmentResultListener(
                KEY_SELECT_BROWSER,
                getViewLifecycleOwner(),
                (requestKey, result) -> {
                    boolean targetIsIncognito = result.getBoolean("incognito", false);
                    if (mIsIncognitoThemed && !targetIsIncognito) {
                        applyIncognitoTheme(false);
                    }
                    NavigationUtils.navigateToBrowser(mNavController, targetIsIncognito);
                });
    }

    // ── Grid/List toggle ────────────────────────────────────────────

    private void applyGridModeToChildren(boolean enableGrid) {
        FragmentManager fm = getChildFragmentManager();

        Fragment regularPage = fm.findFragmentByTag("f" + PAGE_REGULAR);
        if (regularPage instanceof BaseTabsFragment) {
            ((BaseTabsFragment) regularPage).setGridLayoutMode(enableGrid);
        }

        Fragment incognitoPage = fm.findFragmentByTag("f" + PAGE_INCOGNITO);
        if (incognitoPage instanceof BaseTabsFragment) {
            ((BaseTabsFragment) incognitoPage).setGridLayoutMode(enableGrid);
        }
    }

    // ── Toggle sync ─────────────────────────────────────────────────

    private void updateToggle(int page) {
        int buttonId = page == PAGE_INCOGNITO ? R.id.btn_incognito_tabs : R.id.btn_regular_tabs;
        if (mToggleGroup.getCheckedButtonId() != buttonId) {
            mToggleGroup.check(buttonId);
        }
    }

    /**
     * Soft hairline tone for the header / bottom-bar dividers — same scheme as
     * the home/browser bar. Any dark surface (system-dark OR incognito) gets a
     * translucent-WHITE line (a black groove is invisible on dark); a light
     * surface gets a faint translucent-BLACK line.
     */
    private int bottomBarDividerColor(boolean incognito) {
        int nightMode = getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK;
        boolean dark = incognito || nightMode == Configuration.UI_MODE_NIGHT_YES;
        return mActivity.getColor(
                dark ? R.color.bottom_bar_divider_dark : R.color.bottom_bar_divider_light);
    }

    private void applyIncognitoTheme(boolean incognito) {
        Log.d(TAG, "applyIncognito: " + incognito + " mIsIncognitoThemed: " + mIsIncognitoThemed);
        if (incognito == mIsIncognitoThemed) return;
        mIsIncognitoThemed = incognito;

        Window window = mActivity.getWindow();

        int surfaceColor = IncognitoColors.getSurface(mActivity, incognito);
        int onSurfaceColor = IncognitoColors.getOnSurface(mActivity, incognito);

        Fragment incognitoPage = getChildFragmentManager()
                .findFragmentByTag("f" + PAGE_INCOGNITO);
        if (incognitoPage instanceof TabsIncognitoFragment incognitoFragment) {
            incognitoFragment.applyIncognitoColors(incognito);
        }

        mCoordinatorRoot.setBackgroundColor(surfaceColor);

        // Header bar: flat surface (same tone as the grid + bottom bar; divided
        // by its bottom hairline).
        mAppBarLayout.setBackgroundColor(
                IncognitoColors.getSurface(mActivity, incognito));

        // Bottom action bar: flat surface too + its top hairline + icon tints.
        if (mBottomBar != null) {
            mBottomBar.setBackgroundColor(IncognitoColors.getSurface(mActivity, incognito));
        }
        if (mBottomBarDivider != null) {
            mBottomBarDivider.setBackgroundColor(bottomBarDividerColor(incognito));
        }
        if (mHeaderDivider != null) {
            mHeaderDivider.setBackgroundColor(bottomBarDividerColor(incognito));
        }
        if (mViewButton != null) {
            mViewButton.setColorFilter(onSurfaceColor);
        }
        if (mOverflowButton != null) {
            mOverflowButton.setColorFilter(onSurfaceColor);
        }

        updateSegmentedButtons(mActivity, incognito);

        window.getDecorView().setBackgroundColor(surfaceColor);
        // Window layer for Android <= 14: both strips follow the flat surface
        // header + bottom bar (the OLED overlay's opaque bar attrs would
        // otherwise paint them black). No-op on 15+, where the self-padded bars
        // above show through instead.
        paintSystemBars(surfaceColor, surfaceColor);

        boolean lightBars;
        if (incognito) {
            lightBars = false;
        } else {
            int nightMode = getResources().getConfiguration().uiMode
                    & Configuration.UI_MODE_NIGHT_MASK;
            lightBars = nightMode != Configuration.UI_MODE_NIGHT_YES;
        }

        Log.d(TAG, " applyIncognito lightBars: " + lightBars);

        WindowInsetsControllerCompat insetsController =
                WindowCompat.getInsetsController(window, window.getDecorView());
        insetsController.setAppearanceLightStatusBars(lightBars);
        insetsController.setAppearanceLightNavigationBars(lightBars);
    }


    private void updateSegmentedButtons(Activity activity, boolean incognito) {

        ColorStateList backgroundTint = IncognitoColors.segmentedButtonBackground(activity, incognito);
        ColorStateList contentColor = IncognitoColors.segmentedButtonContent(activity, incognito);
        ColorStateList strokeColor = IncognitoColors.segmentedButtonStroke(activity, incognito);

        for (int i = 0; i < mToggleGroup.getChildCount(); i++) {
            View child = mToggleGroup.getChildAt(i);
            if (child instanceof MaterialButton button) {
                button.setBackgroundTintList(backgroundTint);
                button.setTextColor(contentColor);
                button.setIconTint(contentColor);
                button.setStrokeColor(strokeColor);
            }
        }
    }


    // ── Back navigation ─────────────────────────────────────────────

    private void handleBack() {
        boolean onIncognitoPage = mViewPager.getCurrentItem() == PAGE_INCOGNITO;

        GeckoState targetState = null;
        boolean targetIsIncognito = false;

        if (onIncognitoPage && !mIncognitoStateViewModel.isEmpty()) {
            targetState = mIncognitoStateViewModel.getCurrentGeckoState();
            targetIsIncognito = true;
        } else if (!onIncognitoPage) {
            GeckoState regularState = mGeckoStateViewModel.peekCurrentGeckoState();
            if (regularState != null) {
                targetState = regularState;
            }
        }

        if (targetState == null) {
            if (!mIncognitoStateViewModel.isEmpty()) {
                targetState = mIncognitoStateViewModel.getCurrentGeckoState();
                targetIsIncognito = true;
            } else {
                GeckoState regularState = mGeckoStateViewModel.peekCurrentGeckoState();
                if (regularState != null) {
                    targetState = regularState;
                    targetIsIncognito = false;
                }
            }
        }

        if (mIsIncognitoThemed && !targetIsIncognito) {
            applyIncognitoTheme(false);
        }

        if (targetState == null) {
            GeckoStateEntity homeEntity = new GeckoStateEntity(true);
            GeckoState homeState = new GeckoState(homeEntity);
            mGeckoStateViewModel.setGeckoState(homeState, true);
            targetState = homeState;
            targetIsIncognito = false;
        }

        if (targetState.isHome()) {
            if (targetIsIncognito) {
                NavigationUtils.navigateSafe(mNavController, R.id.action_tabs_to_home_incognito);
            } else {
                NavigationUtils.navigateSafe(mNavController, R.id.action_tabs_to_home);
            }
        } else {
            mBrowserURIViewModel.onEventSelected(
                    targetState.getGeckoStateEntity(), IntentActions.OPEN_SESSION);
            NavigationUtils.navigateToBrowser(mNavController, targetIsIncognito);
        }
    }

    // ── New Tab ───────────────────────────────────────────

    private void addNewTab() {
        boolean incognito = mViewPager.getCurrentItem() == PAGE_INCOGNITO;

        GeckoStateEntity entity = new GeckoStateEntity(true);

        if (incognito) {
            entity.setIncognito(true);
            GeckoState geckoState = new GeckoState(entity);
            mIncognitoStateViewModel.setGeckoState(geckoState, true);
        } else {
            GeckoState geckoState = new GeckoState(entity);
            mGeckoStateViewModel.setGeckoState(geckoState, true);
        }
    }

    // ── ViewPager Adapter ───────────────────────────────────────────

    private class TabsPagerAdapter extends FragmentStateAdapter {

        TabsPagerAdapter(@NonNull Fragment fragment) {
            super(fragment);
        }

        @NonNull
        @Override
        public Fragment createFragment(int position) {
            Bundle args = new Bundle();
            args.putBoolean(BaseTabsFragment.ARG_ENABLE_GRID, mEnabledGrid);

            Fragment fragment = position == PAGE_INCOGNITO
                    ? new TabsIncognitoFragment()
                    : new TabsFragment();
            fragment.setArguments(args);
            return fragment;
        }

        @Override
        public int getItemCount() {
            return 2;
        }
    }
}