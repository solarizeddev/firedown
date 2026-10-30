package com.solarized.firedown.phone;

import android.content.Intent;
import android.os.Bundle;

import androidx.navigation.NavController;
import androidx.navigation.fragment.NavHostFragment;

import com.solarized.firedown.BaseActivity;
import com.solarized.firedown.IntentActions;
import com.solarized.firedown.R;
import com.solarized.firedown.Keys;

public class VaultActivity extends BaseActivity {


    private static final String TAG = VaultActivity.class.getSimpleName();


    /* Opened from a notification or a link with no browser behind it,
     * Back and Up go to the browser, not out of the app. */
    @Override
    protected boolean leavesToBrowser() {
        return true;
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.activity_vault);

        mActivityContentFrame = findViewById(R.id.content_frame);

        NavHostFragment navHostFragment = mActivityContentFrame.getFragment();

        NavController navController = navHostFragment.getNavController();

        Intent intent = getIntent();

        Bundle bundle = intent.getExtras();

        if(bundle != null) bundle.putString(Keys.INTENT_ACTION, intent.getAction());

        navController.setGraph(R.navigation.nav_graph_vault, bundle);

    }

    @Override
    protected void onNewIntent(Intent intent) {
        // A download notification is about the list: show it, not a screen
        // the Safe Folder was left on. Still on the lock screen, nothing pops.
        if (intent != null && IntentActions.DOWNLOAD_FINISH.equals(intent.getAction())
                && mActivityContentFrame != null) {
            NavHostFragment navHostFragment = mActivityContentFrame.getFragment();
            navHostFragment.getNavController().popBackStack(R.id.vault, false);
        }
        super.onNewIntent(intent);
    }


}