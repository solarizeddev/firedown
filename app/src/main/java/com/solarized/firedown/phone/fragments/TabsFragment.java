package com.solarized.firedown.phone.fragments;

import android.os.Bundle;
import android.util.Log;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.LiveData;

import com.google.android.material.snackbar.Snackbar;
import com.solarized.firedown.BuildConfig;
import com.solarized.firedown.IntentActions;
import com.solarized.firedown.Preferences;
import com.solarized.firedown.R;
import com.solarized.firedown.data.entity.GeckoStateEntity;
import com.solarized.firedown.geckoview.GeckoState;
import com.solarized.firedown.ui.adapters.BrowserTabsAdapter;
import com.solarized.firedown.utils.NavigationUtils;


import java.util.List;
import java.util.concurrent.TimeUnit;

import dagger.hilt.android.AndroidEntryPoint;
import org.mozilla.geckoview.GeckoSession;

/**
 * Tab switcher for regular (non-incognito) tabs.
 *
 * <p>Adds an archive-banner row at adapter position 0 via the shared
 * {@link BrowserTabsAdapter} (single adapter, two view types — no
 * ConcatAdapter), undo-on-close snackbar, scroll-to-active behavior,
 * and auto-archive of inactive tabs.</p>
 */
@AndroidEntryPoint
public class TabsFragment extends BaseTabsFragment {

    private static final String TAG = TabsFragment.class.getSimpleName();

    /**
     * Flip to true when iterating on the banner UX to make the archive
     * banner show unconditionally on debug builds — useful when there
     * are no archived tabs yet but you want to verify the visual.
     * Wrapped in {@code BuildConfig.DEBUG} at the call site so R8
     * constant-folds it out of release. Default false so debug builds
     * still exercise the real "current &gt; dismissedAt" gate.
     */
    private static final boolean FORCE_BANNER_FOR_DEBUG = false;

    private Snackbar mActiveSnackbar;

    /**
     * Listener installed on the adapter for the banner row. Held as a
     * field so a single instance is reused across re-bind / re-show
     * cycles instead of allocating a new one for every observer tick.
     */
    private final BrowserTabsAdapter.OnBannerActionListener mBannerListener =
            new BrowserTabsAdapter.OnBannerActionListener() {
                @Override
                public void onViewArchive() {
                    snapshotDismissedAt();
                    if (mBrowserTabsAdapter != null) mBrowserTabsAdapter.dismissBanner();
                    refreshEmptyVisibility();
                    NavigationUtils.navigateSafe(mNavController, R.id.tabs_archive);
                }

                @Override
                public void onDismiss() {
                    snapshotDismissedAt();
                    if (mBrowserTabsAdapter != null) mBrowserTabsAdapter.dismissBanner();
                    refreshEmptyVisibility();
                }
            };

    @Override
    public void onDestroyView() {
        if (mActiveSnackbar != null) {
            mActiveSnackbar.dismiss();
            mActiveSnackbar = null;
        }
        mToolbar = null;
        super.onDestroyView();
    }


    // ── Abstract implementations ─────────────────────────────────────

    @Override
    protected LiveData<List<GeckoStateEntity>> getTabsLiveData() {
        return mGeckoStateViewModel.getTabs();
    }

    @Override
    protected GeckoState getGeckoState(int tabId) {
        return mGeckoStateViewModel.getGeckoState(tabId);
    }

    @Override
    protected void activateGeckoState(GeckoState geckoState) {
        mGeckoStateViewModel.setGeckoState(geckoState, true);
    }

    @Override
    protected void removeGeckoState(GeckoState geckoState) {
        mGeckoStateViewModel.closeGeckoState(geckoState);
    }

    @Override
    protected int getEmptyTextRes() {
        return R.string.browser_tabs_empty;
    }

    @Override
    protected void onTabSelected(GeckoStateEntity entity, GeckoState geckoState) {
        if (entity.isHome()) {
            getParentFragmentManager().setFragmentResult(
                    TabsHolderFragment.KEY_SELECT_HOME, Bundle.EMPTY);
        } else {
            mBrowserURIViewModel.onEventSelected(
                    geckoState.getGeckoStateEntity(), IntentActions.OPEN_SESSION);
            Bundle args = new Bundle();
            args.putBoolean("incognito", false);
            getParentFragmentManager().setFragmentResult(
                    TabsHolderFragment.KEY_SELECT_BROWSER, args);
        }
    }

    @Override
    protected void onTabClosed(GeckoStateEntity entity, GeckoState geckoState) {
        mActiveSnackbar = makeSnackbar(getString(R.string.snackbar_tab_closed));
        mActiveSnackbar.setAction(R.string.snackbar_deleted_undo, view -> {
            mGeckoStateViewModel.setGeckoState(geckoState, true);
            // closeTab() deactivated this tab's WebExtensionController slot
            // (uBlock and other tab-scoped extensions stop running). The
            // setGeckoState above re-adds the entity but doesn't re-active
            // extensions — that only happens inside setGeckoViewSession,
            // and ensureSessionConnected on the return path takes the
            // fast no-reconnect branch when viewSession == stateSession.
            // Without this call, the undone tab silently loses extension
            // support until the next reload / tab switch.
            reactivateExtensionTab(geckoState);
        });
        mActiveSnackbar.addCallback(new Snackbar.Callback() {
            @Override
            public void onDismissed(Snackbar transientBottomBar, int event) {
                if (event != DISMISS_EVENT_ACTION) {
                    geckoState.closeGeckoSession();
                }
            }
        });
        mActiveSnackbar.show();
    }

    private void reactivateExtensionTab(GeckoState geckoState) {
        GeckoSession session = geckoState.getGeckoSession();
        if (session == null) return;
        try {
            mGeckoRuntimeHelper.getGeckoRuntime()
                    .getWebExtensionController()
                    .setTabActive(session, true);
        } catch (NullPointerException e) {
            Log.e(TAG, "reactivateExtensionTab", e);
        }
    }

    // adjustPosition / getLeadingAdapterCount come from BaseTabsFragment now;
    // both delegate to BrowserTabsAdapter#getPositionOffset which is the
    // single source of truth for "is the banner row in the way?"

    // setupRecyclerView is inherited unchanged — the base attaches the
    // single BrowserTabsAdapter directly. The banner span-full-width
    // rule lives in the base's SpanSizeLookup (keyed off the adapter's
    // view type), so it works for both regular and incognito tabs even
    // though only TabsFragment ever shows a banner.

    @Override
    protected boolean awaitsBannerSignal() {
        // The archived-tab-count LiveData is a separate Room query
        // that lands after the tab-list LiveData. We need to know
        // whether the banner row will be present at adapter position 0
        // BEFORE the first setAdapter / submitList — otherwise the
        // banner would have to insert later via notifyItemInserted(0),
        // shifting every tab and causing a one-row visual jump.
        // tryApplyFirstSnapshot waits for signalBannerReady before
        // attaching the adapter.
        return true;
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        Log.d(TAG, "onViewCreated");

        // Archive banner — windowed by the user's auto-archive interval
        // (day / week / month). The banner shows the count of tabs
        // archived within that window so it stays small and
        // actionable, instead of growing forever with the all-time
        // archive count.
        long intervalMs = mSharedPreferences.getLong(
                Preferences.SETTINGS_TABS_ARCHIVE_INTERVAL,
                Preferences.ONE_WEEK_INTERVAL);
        long sinceMs = System.currentTimeMillis() - intervalMs;
        final int titlePluralsRes = bannerTitlePluralsFor(intervalMs);

        // Synchronous cache read — the Room count query takes ~250 ms
        // on a cold cache, which is exactly the duration of the LCEE
        // spinner the user complained about. Chrome and Fenix avoid
        // that gap by keeping their state in already-warm in-memory
        // stores; the archive count lives in Room here, so we cache
        // the last-known value in SharedPreferences and feed it into
        // the snapshot synchronously. The LiveData fires asynchronously
        // a beat later — if the fresh count disagrees the observer
        // updates the banner through the post-snapshot path.
        int cachedCount = mSharedPreferences.getInt(
                Preferences.SETTINGS_TABS_ARCHIVE_BANNER_LAST_COUNT, -1);
        long cachedInterval = mSharedPreferences.getLong(
                Preferences.SETTINGS_TABS_ARCHIVE_BANNER_LAST_INTERVAL, 0L);
        if (cachedCount >= 0 && cachedInterval == intervalMs
                && mBrowserTabsAdapter != null) {
            int dismissedAt = mSharedPreferences.getInt(
                    Preferences.SETTINGS_TABS_ARCHIVE_BANNER_DISMISSED_AT, 0);
            boolean force = BuildConfig.DEBUG && FORCE_BANNER_FOR_DEBUG;
            boolean shouldShow = force || cachedCount > dismissedAt;
            int effectiveCount = force ? Math.max(cachedCount, 7) : cachedCount;
            mBrowserTabsAdapter.setBannerSilently(shouldShow, effectiveCount,
                    titlePluralsRes, mBannerListener);
            // Snapshot gate opens here, so applyFirstSnapshot can fire
            // as soon as the tabs LiveData emits (~60 ms) without
            // waiting on the count query (~280 ms).
            signalBannerReady();
        }

        mGeckoStateViewModel.getArchivedTabCountSince(sinceMs)
                .observe(getViewLifecycleOwner(), count -> {
                    if (mBrowserTabsAdapter == null) return;
                    int current = count != null ? count : 0;

                    // Keep the cache fresh for the next fragment open.
                    // Write only when the value or interval actually
                    // changes so we don't churn SharedPreferences.
                    int prevCached = mSharedPreferences.getInt(
                            Preferences.SETTINGS_TABS_ARCHIVE_BANNER_LAST_COUNT, -1);
                    long prevInterval = mSharedPreferences.getLong(
                            Preferences.SETTINGS_TABS_ARCHIVE_BANNER_LAST_INTERVAL, 0L);
                    if (prevCached != current || prevInterval != intervalMs) {
                        mSharedPreferences.edit()
                                .putInt(Preferences.SETTINGS_TABS_ARCHIVE_BANNER_LAST_COUNT, current)
                                .putLong(Preferences.SETTINGS_TABS_ARCHIVE_BANNER_LAST_INTERVAL, intervalMs)
                                .apply();
                    }

                    int dismissedAt = mSharedPreferences.getInt(
                            Preferences.SETTINGS_TABS_ARCHIVE_BANNER_DISMISSED_AT, 0);
                    boolean force = BuildConfig.DEBUG && FORCE_BANNER_FOR_DEBUG;
                    boolean shouldShow = force || current > dismissedAt;
                    int effectiveCount = force ? Math.max(current, 7) : current;


                    if (!isFirstSnapshotApplied()) {
                        // Pre-snapshot: still no cache hit. Set state
                        // silently so the adapter carries the banner row
                        // (or its absence) into setAdapter without a
                        // separate notify event after attach.
                        mBrowserTabsAdapter.setBannerSilently(shouldShow,
                                effectiveCount, titlePluralsRes, mBannerListener);
                    } else {
                        // Post-snapshot: animate-style toggle. Adapter is
                        // attached; notify events go through. Predictive
                        // animations are off so this snaps without
                        // anchor drift.
                        if (shouldShow) {
                            mBrowserTabsAdapter.showBanner(effectiveCount,
                                    titlePluralsRes, mBannerListener);
                        } else {
                            mBrowserTabsAdapter.dismissBanner();
                        }
                        refreshEmptyVisibility();
                    }
                    // Release the snapshot gate; idempotent — the cache
                    // path above may have already opened it.
                    signalBannerReady();
                });

        // Debounced auto-archive trigger
        boolean autoArchiveEnabled = mSharedPreferences.getBoolean(
                Preferences.SETTINGS_TABS_ARCHIVE, true);

        if (autoArchiveEnabled) {
            long lastRun = mSharedPreferences.getLong(Preferences.SETTINGS_TABS_ARCHIVE_LAST_RUN, 0);
            long now = System.currentTimeMillis();
            if (now - lastRun > TimeUnit.HOURS.toMillis(6)) {
                long thresholdMillis = mSharedPreferences.getLong(
                        Preferences.SETTINGS_TABS_ARCHIVE_INTERVAL,
                        Preferences.ONE_WEEK_INTERVAL);
                mGeckoStateViewModel.archiveInactiveTabs(thresholdMillis);
                mSharedPreferences.edit()
                        .putLong(Preferences.SETTINGS_TABS_ARCHIVE_LAST_RUN, now)
                        .apply();
            }
        }
    }


    /**
     * Persists the current archived-tab count as the "I've seen this"
     * snapshot. The banner observer compares the live count against
     * this value, so the banner only re-appears if more tabs land in
     * the archive after dismissal.
     *
     * <p>Must snapshot the SAME windowed count the show logic compares
     * against — {@code getArchivedTabCountSince}, cached in LAST_COUNT by
     * the observer. The previous version read the all-time
     * {@code getArchivedTabCount()} LiveData, which is never observed
     * here, so {@code getValue()} returned null → dismissedAt was stored
     * as 0 → the banner reappeared on every launch.</p>
     */
    private void snapshotDismissedAt() {
        int current = mSharedPreferences.getInt(
                Preferences.SETTINGS_TABS_ARCHIVE_BANNER_LAST_COUNT, 0);
        mSharedPreferences.edit()
                .putInt(Preferences.SETTINGS_TABS_ARCHIVE_BANNER_DISMISSED_AT,
                        Math.max(current, 0))
                .apply();
    }

    /**
     * Maps the user's auto-archive interval to the matching plurals
     * resource for the banner title. Falls through to "this week" for
     * any unusual / custom interval value so translators only have to
     * cover the three canonical periods.
     */
    private static int bannerTitlePluralsFor(long intervalMs) {
        if (intervalMs <= Preferences.ONE_DAY_INTERVAL) {
            return R.plurals.archive_banner_title_day;
        }
        if (intervalMs >= Preferences.THIRTY_DAYS_INTERVAL) {
            return R.plurals.archive_banner_title_month;
        }
        return R.plurals.archive_banner_title_week;
    }

    // ── Snackbar helper ─────────────────────────────────────────────

    private Snackbar makeSnackbar(String text) {
        View coordinator = getParentFragment() != null && getParentFragment().getView() != null
                ? getParentFragment().getView().findViewById(R.id.coordinator_root)
                : mRecyclerView;
        View fab = getParentFragment() != null && getParentFragment().getView() != null
                ? getParentFragment().getView().findViewById(R.id.fab_new_tab)
                : null;

        Snackbar snackbar = Snackbar.make(coordinator, text, Snackbar.LENGTH_LONG);
        if (fab != null) snackbar.setAnchorView(fab);
        return snackbar;
    }
}