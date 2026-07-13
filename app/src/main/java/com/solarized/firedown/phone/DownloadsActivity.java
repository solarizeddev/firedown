package com.solarized.firedown.phone;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;

import androidx.navigation.NavController;
import androidx.navigation.fragment.NavHostFragment;

import com.solarized.firedown.BaseActivity;
import com.solarized.firedown.R;
import com.solarized.firedown.Keys;
import com.solarized.firedown.p2pshare.P2pShareController;
import com.solarized.firedown.phone.fragments.P2pReceiveFragment;

public class DownloadsActivity extends BaseActivity {


    private static final String TAG = DownloadsActivity.class.getSimpleName();


    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.actvity_downloads);

        mActivityContentFrame = findViewById(R.id.content_frame);

        NavHostFragment navHostFragment = mActivityContentFrame.getFragment();

        NavController navController = navHostFragment.getNavController();

        Intent intent = getIntent();

        Bundle bundle = intent.getExtras();

        if(bundle != null) bundle.putString(Keys.INTENT_ACTION, intent.getAction());

        navController.setGraph(R.navigation.nav_graph_downloads, bundle);

        handleP2pDeepLink(navController, intent);

    }

    @Override
    protected void onNewIntent(Intent intent) {
        // singleTop: a firedown://p2p/<code> link tapped while Downloads is
        // already open arrives here. Route it to the receive flow directly and
        // do NOT fall through to BaseActivity.handleIntent, which would try to
        // open the custom-scheme URI as a web page.
        if (isP2pDeepLink(intent)) {
            setIntent(intent);
            if (mActivityContentFrame != null) {
                NavHostFragment navHostFragment = mActivityContentFrame.getFragment();
                handleP2pDeepLink(navHostFragment.getNavController(), intent);
            }
            return;
        }
        super.onNewIntent(intent);
    }

    private static boolean isP2pDeepLink(Intent intent) {
        return intent != null
                && Intent.ACTION_VIEW.equals(intent.getAction())
                && intent.getData() != null
                && P2pShareController.DEEP_LINK_SCHEME.equals(intent.getData().getScheme());
    }

    /**
     * A P2P share offer scanned with any scanner opens as
     * {@code firedown://p2p/<FDS1 code>}. Navigate to the receive flow with the
     * offer pre-loaded so it goes straight to the preview. Only an OFFER acts;
     * an answer or malformed link is ignored (the app just opens Downloads).
     */
    private void handleP2pDeepLink(NavController navController, Intent intent) {
        if (!isP2pDeepLink(intent)) {
            return;
        }
        Uri data = intent.getData();
        if (!P2pShareController.DEEP_LINK_HOST.equals(data.getHost())) {
            return;
        }
        // Neutralize the intent so the onResume → BaseActivity.handleIntent pass
        // (cold start) doesn't ALSO route this ACTION_VIEW through the browser's
        // external-URI handler. getIntent() returns this same object.
        intent.setAction("");
        String code = P2pShareController.stripDeepLink(data.toString());
        if (!code.startsWith(P2pShareController.OFFER_PREFIX)) {
            return;
        }
        Bundle args = new Bundle();
        args.putString(P2pReceiveFragment.ARG_OFFER_CODE, code);
        navController.navigate(R.id.p2p_receive, args);
    }


}
