package com.solarized.firedown.phone;

import android.os.Bundle;

import androidx.appcompat.app.ActionBar;
import androidx.appcompat.widget.Toolbar;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.navigation.NavController;
import androidx.navigation.NavDestination;
import androidx.navigation.fragment.NavHostFragment;

import com.solarized.firedown.BaseActivity;
import com.solarized.firedown.R;

public class SettingsActivity extends BaseActivity {


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

        mToolbar.setNavigationOnClickListener(v1 -> {
            NavDestination navDestination = navController.getCurrentDestination();
            int id = navDestination != null ? navDestination.getId() :  R.id.settings;
            if(id == R.id.settings){
                finish();
            }else{
                navController.popBackStack();
            }
        });

        navController.setGraph(R.navigation.nav_graph_settings, getIntent().getExtras());

        navController.addOnDestinationChangedListener((navController1, navDestination, bundle) -> {
            int id = navDestination.getId();
            if(id == R.id.settings)
                mToolbar.setTitle(R.string.navigation_settings);
            else if(id == R.id.settings_about)
                mToolbar.setTitle(R.string.settings_about);
            else if(id == R.id.settings_license)
                mToolbar.setTitle(R.string.settings_license);
            else if(id == R.id.settings_doh)
                mToolbar.setTitle(R.string.settings_doh_title);
            else if(id == R.id.settings_tracking)
                mToolbar.setTitle(R.string.settings_enhanced_tracking_protection);
            else if(id == R.id.settings_search)
                mToolbar.setTitle(R.string.settings_search_engine);
            else if(id == R.id.settings_donate)
                mToolbar.setTitle(R.string.donate_title);
            else if(id == R.id.settings_lock)
                mToolbar.setTitle(R.string.settings_lock_title);
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