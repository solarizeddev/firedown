package com.solarized.firedown.phone;

import android.os.Bundle;

import androidx.appcompat.app.ActionBar;
import androidx.appcompat.widget.Toolbar;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.navigation.NavController;
import androidx.navigation.NavOptions;
import androidx.navigation.fragment.NavHostFragment;

import com.solarized.firedown.BaseActivity;
import com.solarized.firedown.R;

public class SettingsActivity extends BaseActivity {

    /** Intent boolean extra: open straight to the Sync hub (account page). Used by
     *  the home / browser "Sync" popup row. */
    public static final String EXTRA_OPEN_SYNC = "com.solarized.firedown.extra.OPEN_SYNC";

    /** Intent boolean extra: bookmarks-sync deep link (bookmarks-list overflow +
     *  sync banner). Lands on the merged Cloud screen, which hosts the inline
     *  bookmarks switch since the focused Bookmarks screen was retired; kept as
     *  its own extra because callers express a different intent than
     *  {@link #EXTRA_OPEN_SYNC}. */
    public static final String EXTRA_OPEN_BOOKMARKS_SYNC = "com.solarized.firedown.extra.OPEN_BOOKMARKS_SYNC";

    /** Intent boolean extra: open the Cloud screen — the in-progress
     *  upload/restore notification and the home status line deep-link here. Since
     *  the Cloud Backup sub-screen merged into the Cloud screen this lands on the
     *  same destination as {@link #EXTRA_OPEN_SYNC}; both extras are kept because
     *  callers express different intents (backup status vs account). */
    public static final String EXTRA_OPEN_CLOUD_BACKUP = "com.solarized.firedown.extra.OPEN_CLOUD_BACKUP";

    /** Intent boolean extra: open straight to the backed-up-files list (the
     *  Downloads toolbar overflow deep-links here). */
    public static final String EXTRA_OPEN_CLOUD_BACKUP_FILES = "com.solarized.firedown.extra.OPEN_CLOUD_BACKUP_FILES";

    /** Intent boolean extra: open straight to the Enhanced Tracking Protection
     *  screen (the Home trackers sheet's "Manage protection" button deep-links
     *  here — that screen carries both the tracking-protection stats and the
     *  Standard/Strict/Custom controls). */
    public static final String EXTRA_OPEN_TRACKING = "com.solarized.firedown.extra.OPEN_TRACKING";

    /** Activity-scoped, so {@link #restoreToolbarUp} can re-install the
     *  canonical Up listener after a fragment borrowed the toolbar. */
    private Toolbar mToolbar;
    private NavController mNavController;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.activity_settings);

        mActivityContentFrame = findViewById(R.id.content_frame);

        NavHostFragment navHostFragment = mActivityContentFrame.getFragment();

        NavController navController = navHostFragment.getNavController();
        mNavController = navController;

        mToolbar = findViewById(R.id.toolbar);

        mToolbar.setContentInsetsAbsolute(getResources().getDimensionPixelSize(R.dimen.address_bar_inset),0);

        setSupportActionBar(mToolbar);

        ActionBar actionBar = getSupportActionBar();

        if(actionBar != null){
            actionBar.setDisplayHomeAsUpEnabled(true);
            actionBar.setDisplayShowHomeEnabled(true);
        }

        // Up: pop a sub-screen, or finish the activity at the root (also handles
        // the deep-linked sync screen below, whose back stack is popped to empty).
        restoreToolbarUp();

        navController.setGraph(R.navigation.nav_graph_settings, getIntent().getExtras());

        // Deep-link straight to the Sync hub, replacing the settings list on the
        // back stack so Back returns to the caller (e.g. the browser popup).
        if (getIntent().getBooleanExtra(EXTRA_OPEN_SYNC, false)) {
            NavOptions opts = new NavOptions.Builder()
                    .setPopUpTo(R.id.settings, true)
                    .build();
            navController.navigate(R.id.settings_sync, null, opts);
        }

        // Deep-link for the bookmarks-list overflow + sync banner: the merged
        // Cloud screen (it hosts the inline bookmarks switch). Replace the
        // settings list on the back stack so Back returns to the caller
        // (bookmarks), not into the settings tree.
        if (getIntent().getBooleanExtra(EXTRA_OPEN_BOOKMARKS_SYNC, false)) {
            NavOptions opts = new NavOptions.Builder()
                    .setPopUpTo(R.id.settings, true)
                    .build();
            navController.navigate(R.id.settings_sync, null, opts);
        }

        // Deep-link straight to the merged Cloud screen (home status line + the
        // upload/restore notification). Replace the settings list on the back
        // stack so Back finishes this activity and returns to the CALLER (home /
        // whatever was behind the notification) instead of stepping into the
        // settings tree — same contract as the files deep-link below (a user
        // coming from home who pressed Back landed on Settings, reported
        // on-device).
        if (getIntent().getBooleanExtra(EXTRA_OPEN_CLOUD_BACKUP, false)) {
            NavOptions opts = new NavOptions.Builder()
                    .setPopUpTo(R.id.settings, true)
                    .build();
            navController.navigate(R.id.settings_sync, null, opts);
        }

        // Deep-link straight to the backed-up-files list (Downloads overflow).
        // Replace the settings list on the back stack with ONLY the files screen,
        // so Back (toolbar or system) finishes this activity and returns to
        // Downloads — the caller — instead of stepping into the settings tree.
        if (getIntent().getBooleanExtra(EXTRA_OPEN_CLOUD_BACKUP_FILES, false)) {
            NavOptions opts = new NavOptions.Builder()
                    .setPopUpTo(R.id.settings, true)
                    .build();
            navController.navigate(R.id.settings_cloud_backup_files, null, opts);
        }

        // Deep-link straight to the Enhanced Tracking Protection screen (the
        // Home trackers sheet's "Manage protection" button). Replace the
        // settings list on the back stack so Back finishes this activity and
        // returns to the CALLER (the sheet's host — Home / Browser) instead of
        // stepping into the settings tree, same contract as the deep-links
        // above.
        if (getIntent().getBooleanExtra(EXTRA_OPEN_TRACKING, false)) {
            NavOptions opts = new NavOptions.Builder()
                    .setPopUpTo(R.id.settings, true)
                    .build();
            navController.navigate(R.id.settings_tracking, null, opts);
        }

        navController.addOnDestinationChangedListener((navController1, navDestination, bundle) -> {
            int id = navDestination.getId();
            if(id == R.id.settings)
                mToolbar.setTitle(R.string.navigation_settings);
            else if(id == R.id.settings_about)
                mToolbar.setTitle(R.string.settings_about);
            else if(id == R.id.settings_theme)
                mToolbar.setTitle(R.string.settings_theme);
            else if(id == R.id.settings_wasm)
                mToolbar.setTitle(R.string.settings_wasm);
            else if(id == R.id.settings_security)
                mToolbar.setTitle(R.string.if_preferences_security);
            else if(id == R.id.settings_direct_share)
                mToolbar.setTitle(R.string.settings_p2p_category);
            else if(id == R.id.settings_license)
                mToolbar.setTitle(R.string.settings_license);
            else if(id == R.id.settings_doh)
                mToolbar.setTitle(R.string.settings_doh_title);
            else if(id == R.id.settings_tracking)
                mToolbar.setTitle(R.string.settings_enhanced_tracking_protection);
            else if(id == R.id.settings_query_params)
                mToolbar.setTitle(R.string.settings_query_param_block_list);
            else if(id == R.id.settings_search)
                mToolbar.setTitle(R.string.settings_search_engine);
            else if(id == R.id.settings_lock)
                mToolbar.setTitle(R.string.settings_lock_title);
            else if(id == R.id.settings_sync)
                mToolbar.setTitle(R.string.settings_account_title);
            else if(id == R.id.settings_sync_help)
                mToolbar.setTitle(R.string.settings_sync_help_title);
            else if(id == R.id.settings_cloud_backup_files)
                mToolbar.setTitle(R.string.cloud_backup_files_title);
            else if(id == R.id.settings_cloud_backup_buy)
                mToolbar.setTitle(R.string.buy_credit_title);
        });

        ViewCompat.setOnApplyWindowInsetsListener(mToolbar, (v, windowInsets) -> {
            Insets insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars());
            // Apply the insets as a margin to the view. This solution sets only the
            // bottom, left, and right dimensions, but you can apply whichever insets are
            // appropriate to your layout. You can also update the view padding if that's
            // more appropriate.
            v.setPadding(0, insets.top, 0, 0);

            // Managing statusbar icons colour based on the light/dark mode,
            //I am working on white label solution so this is helping me to set icons colour based on the app theme
//            WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView())
//                    .setAppearanceLightStatusBars(ColorManager.isUsingWhiteTheme());
            return windowInsets;
        });



    }

    /**
     * (Re)installs the CANONICAL toolbar Up listener — pop a sub-screen, or
     * finish the activity at the root. The shared toolbar outlives any fragment,
     * so a fragment that borrows the navigation click (the Backups list's
     * multi-select exit-selection) must restore it through THIS method, never
     * with its own lambda: a fragment-bound restore lambda stayed installed
     * after the fragment detached, and the next Up click at the nav root hit
     * {@code requireActivity()} on the dead fragment —
     * {@code IllegalStateException: Fragment … not attached}, reported from the
     * field. The listener here holds only activity state, which lives exactly
     * as long as the toolbar does.
     */
    public void restoreToolbarUp() {
        mToolbar.setNavigationOnClickListener(v -> {
            if (!mNavController.popBackStack()) {
                finish();
            }
        });
    }

}