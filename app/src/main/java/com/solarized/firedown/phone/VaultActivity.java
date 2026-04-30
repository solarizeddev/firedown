package com.solarized.firedown.phone;

import android.content.Intent;
import android.os.Bundle;

import androidx.navigation.NavController;
import androidx.navigation.fragment.NavHostFragment;

import com.solarized.firedown.BaseActivity;
import com.solarized.firedown.R;
import com.solarized.firedown.Keys;

public class VaultActivity extends BaseActivity {


    private static final String TAG = VaultActivity.class.getSimpleName();


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


}