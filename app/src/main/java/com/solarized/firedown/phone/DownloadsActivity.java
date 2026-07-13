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
        if (intent == null || !Intent.ACTION_VIEW.equals(intent.getAction())) {
            return false;
        }
        Uri d = intent.getData();
        if (d == null) {
            return false;
        }
        // Custom scheme (offer QR / landing button) OR the https relay App Link.
        if (P2pShareController.DEEP_LINK_SCHEME.equals(d.getScheme())) {
            return true;
        }
        return ("https".equals(d.getScheme()) || "http".equals(d.getScheme()))
                && d.getPath() != null && d.getPath().startsWith("/s/");
    }

    /**
     * Route a P2P share link into the receive flow. Three shapes, all landing
     * on {@code p2p_receive}:
     * <ul>
     *   <li>{@code firedown://p2p/<FDS1 code>} — a scanned/opened OFFER QR;
     *       the offer is self-contained (pre-loaded via ARG_OFFER_CODE).</li>
     *   <li>{@code firedown://p2p/r/<id>?s=<relay>} — a relay link (landing-page
     *       button / custom scheme): fetch the offer from the relay.</li>
     *   <li>{@code https://<relay>/s/<id>} — the same relay link as a tappable
     *       App Link; the relay origin is the link's own origin.</li>
     * </ul>
     * Anything else (an answer, a malformed link) is ignored.
     */
    private void handleP2pDeepLink(NavController navController, Intent intent) {
        if (!isP2pDeepLink(intent)) {
            return;
        }
        Uri data = intent.getData();
        // Neutralize the intent so the onResume → BaseActivity.handleIntent pass
        // (cold start) doesn't ALSO route this ACTION_VIEW through the browser's
        // external-URI handler. getIntent() returns this same object.
        intent.setAction("");

        Bundle args = new Bundle();
        if (P2pShareController.DEEP_LINK_SCHEME.equals(data.getScheme())) {
            if (!P2pShareController.DEEP_LINK_HOST.equals(data.getHost())) {
                return;
            }
            java.util.List<String> segs = data.getPathSegments();
            if (segs.size() >= 2 && "r".equals(segs.get(0))) {
                // firedown://p2p/r/<id>?s=<relay>
                String base = data.getQueryParameter("s");
                if (base == null || base.isEmpty()) {
                    return;
                }
                args.putString(P2pReceiveFragment.ARG_SIGNALING_BASE, trimSlash(base));
                args.putString(P2pReceiveFragment.ARG_SIGNALING_ID, segs.get(1));
            } else {
                // firedown://p2p/<FDS1 offer>
                String code = P2pShareController.stripDeepLink(data.toString());
                if (!code.startsWith(P2pShareController.OFFER_PREFIX)) {
                    return;
                }
                args.putString(P2pReceiveFragment.ARG_OFFER_CODE, code);
            }
        } else {
            // https://<relay>/s/<id> — relay origin is the link's own origin.
            java.util.List<String> segs = data.getPathSegments();
            if (segs.size() < 2 || !"s".equals(segs.get(0))) {
                return;
            }
            String base = data.getScheme() + "://" + data.getAuthority();
            args.putString(P2pReceiveFragment.ARG_SIGNALING_BASE, base);
            args.putString(P2pReceiveFragment.ARG_SIGNALING_ID, segs.get(1));
        }
        navController.navigate(R.id.p2p_receive, args);
    }

    private static String trimSlash(String s) {
        String t = s.trim();
        while (t.endsWith("/")) {
            t = t.substring(0, t.length() - 1);
        }
        return t;
    }


}
