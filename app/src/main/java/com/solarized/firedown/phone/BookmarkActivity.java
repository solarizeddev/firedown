package com.solarized.firedown.phone;

import android.content.Intent;
import android.os.Bundle;

import androidx.navigation.NavController;
import androidx.navigation.fragment.NavHostFragment;

import com.solarized.firedown.BaseActivity;
import com.solarized.firedown.R;
import com.solarized.firedown.IntentActions;
import com.solarized.firedown.Keys;

public class BookmarkActivity extends BaseActivity {


    private static final String TAG = BookmarkActivity.class.getSimpleName();


    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.actvity_bookmark);

        mActivityContentFrame = findViewById(R.id.content_frame);

        NavHostFragment navHostFragment = mActivityContentFrame.getFragment();

        NavController navController = navHostFragment.getNavController();

        Intent intent = getIntent();

        String action = intent.getAction();

        Bundle bundle = intent.getExtras();

        if(bundle != null) {
            bundle.putString(Keys.INTENT_ACTION, intent.getAction());
            bundle.putInt(Keys.ITEM_ID, intent.getIntExtra(Keys.ITEM_ID, 0));
        }

        navController.setGraph(R.navigation.nav_graph_bookmark, bundle);

        if(IntentActions.BOOKMARK_EDIT.equals(action)){
            navController.navigate(R.id.action_web_bookmark_edit, bundle);
        }

    }


}