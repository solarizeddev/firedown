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

    /** Intent boolean extra: open straight to the bookmark-sync screen (the
     *  bookmarks-list overflow uses this to deep-link past the settings list). */
    public static final String EXTRA_OPEN_SYNC = "com.solarized.firedown.extra.OPEN_SYNC";


    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.activity_settings);

        mActivityContentFrame = findViewById(R.id.content_frame);

        NavHostFragment navHostFragment = mActivityContentFrame.getFragment();

        NavController navController = navHostFragment.getNavController();

        Toolbar mToolbar = findViewById(R.id.toolbar);

        mToolbar.setContentInsetsAbsolute(getResources().getDimensionPixelSize(R.dimen.address_bar_inset),0);

        setSupportActionBar(mToolbar);

        ActionBar actionBar = getSupportActionBar();

        if(actionBar != null){
            actionBar.setDisplayHomeAsUpEnabled(true);
            actionBar.setDisplayShowHomeEnabled(true);
        }

        // Up: pop a sub-screen, or finish the activity at the root (also handles
        // the deep-linked sync screen below, whose back stack is popped to empty).
        mToolbar.setNavigationOnClickListener(v1 -> {
            if (!navController.popBackStack()) {
                finish();
            }
        });

        navController.setGraph(R.navigation.nav_graph_settings, getIntent().getExtras());

        // Deep-link straight to the bookmark-sync screen, replacing the settings
        // list on the back stack so Back returns to the caller (e.g. bookmarks).
        if (getIntent().getBooleanExtra(EXTRA_OPEN_SYNC, false)) {
            NavOptions opts = new NavOptions.Builder()
                    .setPopUpTo(R.id.settings, true)
                    .build();
            navController.navigate(R.id.settings_sync, null, opts);
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
            else if(id == R.id.settings_donate)
                mToolbar.setTitle(R.string.donate_title);
            else if(id == R.id.settings_lock)
                mToolbar.setTitle(R.string.settings_lock_title);
            else if(id == R.id.settings_sync)
                mToolbar.setTitle(R.string.settings_account_title);
            else if(id == R.id.settings_sync_help)
                mToolbar.setTitle(R.string.settings_sync_help_title);
            else if(id == R.id.settings_cloud_backup)
                mToolbar.setTitle(R.string.settings_cloud_backup_title);
            else if(id == R.id.settings_cloud_backup_files)
                mToolbar.setTitle(R.string.cloud_backup_files_title);
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

}