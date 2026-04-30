package com.solarized.firedown.phone.fragments;

import android.app.Activity;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.graphics.drawable.DrawableCompat;
import androidx.core.view.MenuProvider;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.RecyclerView;
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
import com.solarized.firedown.ui.IncognitoColors;
import com.solarized.firedown.utils.NavigationUtils;

import androidx.navigation.NavController;
import androidx.navigation.fragment.NavHostFragment;

import javax.inject.Inject;

import dagger.hilt.android.AndroidEntryPoint;

/**
 * Holder fragment with Material3 segmented toggle + ViewPager2.
 *
 * <p><b>AppBar lift (scrim approach):</b> AppBar's own liftOnScroll machinery
 * races our updates across the ViewPager2 boundary — its async
 * {@code shouldLift} check runs {@code canScrollVertically(-1)} on its target
 * and flips state behind our back. We bypass that entirely:
 *
 * <ul>
 * <li>AppBarLayout itself: static resting {@code surface} background,
 *     {@code elevation=0}, {@code liftOnScroll=false}. We never touch
 *     {@code setLifted}.</li>
 * <li>A sibling View inside the AppBar ({@code @id/appbar_lift_scrim})
 *     holds the "lifted" colour and fades its alpha 0↔1 based on scroll.</li>
 * <li>Scroll state is driven by observing the currently-visible RecyclerView
 *     (scroll listener + adapter observer) and checking
 *     {@code computeVerticalScrollOffset() > 0}.</li>
 * </ul>
 *
 * <p>There's no AppBar state machine to race because we never write to
 * AppBar. A plain {@code View.setAlpha} call has no async reconciliation.</p>
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
    private View mAppBarLiftScrim;
    private MaterialButtonToggleGroup mToggleGroup;
    private ViewPager2 mViewPager;
    private NavController mNavController;
    private GeckoStateViewModel mGeckoStateViewModel;
    private IncognitoStateViewModel mIncognitoStateViewModel;
    private BrowserURIViewModel mBrowserURIViewModel;
    private boolean mIsIncognitoThemed = false;
    private boolean mEnabledGrid;

    /**
     * Observer infrastructure for the lift scrim. See class doc.
     */
    @Nullable private RecyclerView mLiftTarget;
    @Nullable private RecyclerView.Adapter<?> mLiftObservedAdapter;

    private final RecyclerView.OnScrollListener mLiftScrollListener =
            new RecyclerView.OnScrollListener() {
                @Override
                public void onScrolled(@NonNull RecyclerView rv, int dx, int dy) {
                    updateScrimFor(rv);
                }
            };

    private final RecyclerView.AdapterDataObserver mLiftAdapterObserver =
            new RecyclerView.AdapterDataObserver() {
                @Override public void onChanged() { scheduleScrimRecheck(); }
                @Override public void onItemRangeInserted(int s, int c) { scheduleScrimRecheck(); }
                @Override public void onItemRangeRemoved(int s, int c) { scheduleScrimRecheck(); }
                @Override public void onItemRangeChanged(int s, int c) { scheduleScrimRecheck(); }
                @Override public void onItemRangeChanged(int s, int c, @Nullable Object p) { scheduleScrimRecheck(); }
                @Override public void onItemRangeMoved(int f, int t, int c) { scheduleScrimRecheck(); }
            };

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
        mAppBarLiftScrim = view.findViewById(R.id.appbar_lift_scrim);
        mToolbar = view.findViewById(R.id.toolbar);
        mToggleGroup = view.findViewById(R.id.tab_mode_toggle);
        mViewPager = view.findViewById(R.id.tabs_view_pager);
        mFab = view.findViewById(R.id.fab_new_tab);

        // AppBar stays at resting surface colour forever. The scrim view
        // underneath the toolbar holds the "lifted" (surfaceContainerHigh)
        // colour and has its alpha driven by scroll. Neither colour is ever
        // written to the AppBar itself after this initial setup —
        // applyIncognitoTheme swaps both at once for incognito mode.
        mAppBarLayout.setBackgroundColor(
                IncognitoColors.getSurface(mActivity, false));
        mAppBarLiftScrim.setBackgroundColor(
                IncognitoColors.getSurfaceContainerHigh(mActivity, false));
        mAppBarLiftScrim.setAlpha(0f);

        mToolbar.addMenuProvider(new MenuProvider() {
            @Override
            public void onCreateMenu(@NonNull Menu menu, @NonNull MenuInflater menuInflater) {
                menuInflater.inflate(R.menu.menu_tabs, menu);
                MenuItem actionView = menu.findItem(R.id.action_view);
                if (actionView != null) {
                    actionView.setIcon(!mEnabledGrid ? R.drawable.ic_view_list_24 : R.drawable.ic_grid_view_24);
                }
            }

            @Override
            public boolean onMenuItemSelected(@NonNull MenuItem menuItem) {
                int id = menuItem.getItemId();
                if (id == R.id.action_view) {
                    mEnabledGrid = !mEnabledGrid;
                    menuItem.setIcon(!mEnabledGrid ? R.drawable.ic_view_list_24 : R.drawable.ic_grid_view_24);
                    if (mIsIncognitoThemed && mActivity != null) {
                        Drawable icon = menuItem.getIcon();
                        if (icon != null) {
                            DrawableCompat.setTint(icon, IncognitoColors.getOnSurface(mActivity, true));
                        }
                    }
                    applyGridModeToChildren(mEnabledGrid);
                    return true;
                } else if (id == R.id.action_close_all) {
                    mGeckoStateViewModel.deleteAll();
                    mIncognitoStateViewModel.deleteAll();
                    return true;
                } else if (id == R.id.action_tabs_archive) {
                    NavigationUtils.navigateSafe(mNavController, R.id.tabs_archive);
                    return true;
                }
                return false;
            }
        }, getViewLifecycleOwner(), Lifecycle.State.RESUMED);

        mFab.setOnClickListener(v -> addNewTab());

        setupToolbar();
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
        // ViewPager2 doesn't always fire onPageSelected for the initial item
        // set via setCurrentItem(…, false). Wire the scrim observer to the
        // initial page's RecyclerView.
        view.post(() -> bindScrimTargetForPage(mViewPager.getCurrentItem()));
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
        unbindScrimObservers();
        mLiftTarget = null;
        mCoordinatorRoot = null;
        mFab = null;
        mAppBarLayout = null;
        mAppBarLiftScrim = null;
        mToolbar = null;
        mToggleGroup = null;
        mViewPager = null;
    }

    // ── Setup ────────────────────────────────────────────────────────

    private void setupToolbar() {
        mToolbar.setNavigationOnClickListener(v -> handleBack());
    }

    private void setupViewPager() {
        mViewPager.setAdapter(new TabsPagerAdapter(this));
        mViewPager.setOffscreenPageLimit(1);
        mViewPager.setUserInputEnabled(true);

        mViewPager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override
            public void onPageSelected(int position) {
                updateToggle(position);
                applyIncognitoTheme(position == PAGE_INCOGNITO);
                // Rebind the scrim observer to the newly-visible page's RV.
                mViewPager.post(() -> bindScrimTargetForPage(position));
            }
        });
    }

    // ── Scrim driver ────────────────────────────────────────────────
    //
    // The AppBar is static; a sibling View inside it (@id/appbar_lift_scrim)
    // holds the "lifted" colour and has its alpha driven by scroll. We never
    // touch AppBarLayout's own lift/background machinery, so there's no
    // internal state to race.

    private void bindScrimTargetForPage(int pageIndex) {
        Fragment page = getChildFragmentManager().findFragmentByTag("f" + pageIndex);
        RecyclerView rv = (page instanceof BaseTabsFragment)
                ? ((BaseTabsFragment) page).getRecyclerView()
                : null;
        bindScrimTarget(rv);
    }

    private void bindScrimTarget(@Nullable RecyclerView rv) {
        if (rv == mLiftTarget) {
            if (rv != null) updateScrimFor(rv);
            return;
        }
        unbindScrimObservers();
        mLiftTarget = rv;
        if (rv != null) {
            rv.addOnScrollListener(mLiftScrollListener);
            RecyclerView.Adapter<?> adapter = rv.getAdapter();
            if (adapter != null) {
                try {
                    adapter.registerAdapterDataObserver(mLiftAdapterObserver);
                    mLiftObservedAdapter = adapter;
                } catch (IllegalStateException e) {
                    Log.w(TAG, "Could not register scrim adapter observer", e);
                }
            }
            // Sample scroll state immediately. For page swaps, the newly
            // visible RV is already settled (offscreenPageLimit=1 kept it
            // measured), so this paints the correct scrim alpha without
            // waiting. For the initial open where data is still loading,
            // computeVerticalScrollOffset returns 0 (no content yet), which
            // is the right answer — scheduleScrimRecheck runs afterward to
            // update once data arrives and any scroll-to-active lands.
            updateScrimFor(rv);
            scheduleScrimRecheck();
        } else {
            setScrimVisible(false, false);
        }
    }

    private void unbindScrimObservers() {
        if (mLiftTarget != null) {
            mLiftTarget.removeOnScrollListener(mLiftScrollListener);
        }
        if (mLiftObservedAdapter != null) {
            try {
                mLiftObservedAdapter.unregisterAdapterDataObserver(mLiftAdapterObserver);
            } catch (IllegalStateException ignored) { }
            mLiftObservedAdapter = null;
        }
    }

    /**
     * Re-read scroll state after layout + item animations settle + one extra
     * frame. Needed for programmatic scroll-to-position (which doesn't emit
     * onScrolled) and for initial data arrival.
     */
    private void scheduleScrimRecheck() {
        final RecyclerView target = mLiftTarget;
        if (target == null) return;
        android.view.ViewTreeObserver vto = target.getViewTreeObserver();
        if (!vto.isAlive()) {
            target.post(() -> recheckScrimAfterAnimations(target));
            return;
        }
        vto.addOnGlobalLayoutListener(
                new android.view.ViewTreeObserver.OnGlobalLayoutListener() {
                    @Override
                    public void onGlobalLayout() {
                        android.view.ViewTreeObserver current = target.getViewTreeObserver();
                        if (current.isAlive()) {
                            current.removeOnGlobalLayoutListener(this);
                        }
                        if (target != mLiftTarget) return;
                        target.post(() -> {
                            if (target == mLiftTarget) recheckScrimAfterAnimations(target);
                        });
                    }
                });
    }

    private void recheckScrimAfterAnimations(@NonNull RecyclerView target) {
        RecyclerView.ItemAnimator anim = target.getItemAnimator();
        if (anim == null) {
            if (target == mLiftTarget) updateScrimFor(target);
            return;
        }
        anim.isRunning(() -> {
            if (target == mLiftTarget) updateScrimFor(target);
        });
    }

    private void updateScrimFor(@NonNull RecyclerView rv) {
        setScrimVisible(rv.computeVerticalScrollOffset() > 0, true);
    }

    /**
     * Fade the scrim to the given visibility. 80ms fade matches Material's
     * standard elevation change timing.
     */
    private void setScrimVisible(boolean visible, boolean animate) {
        if (mAppBarLiftScrim == null) return;
        float target = visible ? 1f : 0f;
        if (mAppBarLiftScrim.getAlpha() == target) return;
        mAppBarLiftScrim.animate().cancel();
        if (animate) {
            mAppBarLiftScrim.animate()
                    .alpha(target)
                    .setDuration(80L)
                    .start();
        } else {
            mAppBarLiftScrim.setAlpha(target);
        }
    }

    /**
     * Called by child fragments after a programmatic scroll. Child's scroll
     * listener covers finger scrolls; programmatic scrollToPosition doesn't
     * emit onScrolled, so we re-sample after layout settles.
     */
    void refreshAppBarLiftFor(@NonNull RecyclerView rv) {
        if (rv == mLiftTarget) updateScrimFor(rv);
    }

    /** Kept for API compatibility with child fragment callsites. No-ops. */
    void suspendAppBarLift() { /* no-op */ }
    void resumeAppBarLift(@NonNull RecyclerView rv) {
        refreshAppBarLiftFor(rv);
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
                    NavigationUtils.navigateSafe(mNavController, R.id.action_tabs_to_browser);
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

    private void applyIncognitoTheme(boolean incognito) {
        Log.d(TAG, "applyIncognito: " + incognito + " mIsIncognitoThemed: " + mIsIncognitoThemed);
        if (incognito == mIsIncognitoThemed) return;
        mIsIncognitoThemed = incognito;

        Window window = mActivity.getWindow();

        int surfaceColor = IncognitoColors.getSurface(mActivity, incognito);
        int onSurfaceColor = IncognitoColors.getOnSurface(mActivity, incognito);
        int liftedColor = IncognitoColors.getSurfaceContainerHigh(mActivity, incognito);

        Fragment incognitoPage = getChildFragmentManager()
                .findFragmentByTag("f" + PAGE_INCOGNITO);
        if (incognitoPage instanceof TabsIncognitoFragment incognitoFragment) {
            incognitoFragment.applyIncognitoColors(incognito);
        }

        mCoordinatorRoot.setBackgroundColor(surfaceColor);
        if (mNavScrim != null) {
            mNavScrim.setBackgroundColor(surfaceColor);
        }

        // AppBar = resting surface (static, never animated).
        // Scrim = lifted colour (alpha driven by scroll state).
        mAppBarLayout.setBackgroundColor(surfaceColor);
        if (mAppBarLiftScrim != null) {
            mAppBarLiftScrim.setBackgroundColor(liftedColor);
        }

        mToolbar.setTitleTextColor(onSurfaceColor);

        Drawable navIcon = mToolbar.getNavigationIcon();
        if (navIcon != null) {
            DrawableCompat.setTint(navIcon, onSurfaceColor);
        }

        Menu menu = mToolbar.getMenu();
        if (menu != null) {
            for (int i = 0; i < menu.size(); i++) {
                Drawable icon = menu.getItem(i).getIcon();
                if (icon != null) {
                    DrawableCompat.setTint(icon, onSurfaceColor);
                }
            }
        }

        Drawable overflowIcon = mToolbar.getOverflowIcon();
        if (overflowIcon != null) {
            DrawableCompat.setTint(overflowIcon, onSurfaceColor);
        }

        updateSegmentedButtons(mActivity, incognito);

        window.getDecorView().setBackgroundColor(surfaceColor);

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
            NavigationUtils.navigateSafe(mNavController, R.id.action_tabs_to_browser);
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