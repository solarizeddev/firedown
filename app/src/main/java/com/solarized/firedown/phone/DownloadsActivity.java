package com.solarized.firedown.phone;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;

import androidx.navigation.NavController;
import androidx.navigation.fragment.NavHostFragment;

import com.google.android.material.snackbar.Snackbar;
import com.solarized.firedown.BaseActivity;
import com.solarized.firedown.R;
import com.solarized.firedown.Keys;
import com.solarized.firedown.p2pshare.P2pShareController;
import com.solarized.firedown.phone.fragments.P2pReceiveFragment;

import java.util.List;

import javax.inject.Inject;

import dagger.hilt.android.AndroidEntryPoint;

/* @AndroidEntryPoint must be on THIS class, not only on BaseActivity: Hilt
 * members-injection only covers fields declared on the annotated class — an
 * unannotated subclass's own @Inject fields are left null (the
 * DownloadFragment.onRestoreTreePicked NPE lesson). */
@AndroidEntryPoint
public class DownloadsActivity extends BaseActivity {


    private static final String TAG = DownloadsActivity.class.getSimpleName();

    /** Singleton share controller — a tapped REPLY link feeds the live send
     *  session here without touching navigation (the send screen is already
     *  open and observing). */
    @Inject
    P2pShareController mP2pShareController;


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
        // singleTop: a share link tapped while Downloads is already open
        // arrives here — including the REPLY link a sender taps in a messenger
        // while their send screen waits underneath. Route it directly and do
        // NOT fall through to BaseActivity.handleIntent, which would try to
        // open the URI as a web page.
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
        // Custom scheme (QR / bouncer button) OR the https share/relay links
        // (verified App Link firedown.app/s#<code>, relay /s/<id>).
        if (P2pShareController.DEEP_LINK_SCHEME.equals(d.getScheme())) {
            return true;
        }
        // Exactly /s (the share link) or /s/<id> (relay) — a bare startsWith
        // would also swallow explicit-component VIEW intents for /settings etc.
        String path = d.getPath();
        if (path == null || !("https".equals(d.getScheme()) || "http".equals(d.getScheme()))) {
            return false;
        }
        return "/s".equals(path) || path.startsWith("/s/");
    }

    /**
     * Route a P2P share link. Shapes:
     * <ul>
     *   <li>{@code firedown://p2p/<FDS1 code>} and
     *       {@code https://firedown.app/s#<FDS1 code>} — an OFFER: open the
     *       receive flow with the code pre-loaded (ARG_OFFER_CODE).</li>
     *   <li>{@code firedown://p2p/<FDR1 code>} and
     *       {@code https://firedown.app/s#<FDR1 code>} — a REPLY: feed the
     *       LIVE send session ({@link P2pShareController#provideExternalAnswer});
     *       no session → say so honestly instead of dropping the tap.</li>
     *   <li>{@code firedown://p2p/r/<id>?s=<relay>} /
     *       {@code https://<relay>/s/<id>} — signaling-relay links (dormant;
     *       see Preferences.P2P_SIGNALING_DEFAULT).</li>
     * </ul>
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
            List<String> segs = data.getPathSegments();
            if (segs.size() >= 2 && "r".equals(segs.get(0))) {
                // firedown://p2p/r/<id>?s=<relay>
                String base = data.getQueryParameter("s");
                if (base == null || base.isEmpty()) {
                    return;
                }
                args.putString(P2pReceiveFragment.ARG_SIGNALING_BASE, trimSlash(base));
                args.putString(P2pReceiveFragment.ARG_SIGNALING_ID, segs.get(1));
            } else {
                // firedown://p2p/<FDS1|FDR1 code>
                if (!routeCode(P2pShareController.stripDeepLink(data.toString()), args)) {
                    return;
                }
            }
        } else if (data.getFragment() != null && !data.getFragment().isEmpty()
                && data.getPathSegments().size() == 1) {
            // https://firedown.app/s#<FDS1|FDR1 code> — the code rides in the
            // fragment (never sent to the server; the /s page is a static
            // bouncer for unverified devices).
            if (!routeCode(data.getFragment().trim(), args)) {
                return;
            }
        } else {
            // https://<relay>/s/<id> — relay origin is the link's own origin.
            List<String> segs = data.getPathSegments();
            if (segs.size() < 2 || !"s".equals(segs.get(0))) {
                return;
            }
            String base = data.getScheme() + "://" + data.getAuthority();
            args.putString(P2pReceiveFragment.ARG_SIGNALING_BASE, base);
            args.putString(P2pReceiveFragment.ARG_SIGNALING_ID, segs.get(1));
        }
        if (!args.isEmpty()) {
            navController.navigate(R.id.p2p_receive, args);
        }
    }

    /**
     * Route a bare signaling code: an OFFER fills {@code args} for the receive
     * flow (caller navigates); a REPLY goes straight into the live send session
     * — the send screen is already open underneath and reacts through its
     * listener, so there is nothing to navigate to. Returns whether {@code args}
     * should be navigated.
     */
    private boolean routeCode(String code, Bundle args) {
        if (code.startsWith(P2pShareController.OFFER_PREFIX)) {
            args.putString(P2pReceiveFragment.ARG_OFFER_CODE, code);
            return true;
        }
        if (code.startsWith(P2pShareController.ANSWER_PREFIX)) {
            if (!mP2pShareController.provideExternalAnswer(code)) {
                // App relaunched / share closed — the offer this replies to is
                // gone. Tell the sender instead of silently eating the tap.
                Snackbar.make(getSnackAnchorView(),
                        R.string.p2p_reply_no_session, Snackbar.LENGTH_LONG).show();
            }
            return false;
        }
        return false;
    }

    private static String trimSlash(String s) {
        String t = s.trim();
        while (t.endsWith("/")) {
            t = t.substring(0, t.length() - 1);
        }
        return t;
    }


}
