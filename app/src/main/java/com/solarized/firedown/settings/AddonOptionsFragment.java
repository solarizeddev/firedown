package com.solarized.firedown.settings;

import android.app.Activity;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.ActionBar;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.fragment.app.Fragment;
import androidx.navigation.NavController;
import androidx.navigation.fragment.NavHostFragment;

import com.solarized.firedown.Keys;
import com.solarized.firedown.R;
import com.solarized.firedown.addons.AddonInfo;
import com.solarized.firedown.addons.AddonManager;

import org.mozilla.geckoview.GeckoSession;
import org.mozilla.geckoview.GeckoView;

import javax.inject.Inject;

import dagger.hilt.android.AndroidEntryPoint;

/**
 * An add-on's own settings page (its options_ui), in a session of its own
 * under the Settings toolbar, the way Firefox for Android shows it. Links
 * to web pages open in a browser tab ({@link AddonManager}'s navigation
 * delegate); the page closing itself goes back. The session lives as long
 * as this view (Settings handles rotation itself).
 */
@AndroidEntryPoint
public class AddonOptionsFragment extends Fragment {

    @Inject
    AddonManager mAddonManager;

    private GeckoView mGeckoView;
    @Nullable
    private GeckoSession mSession;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_addon_options, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        ViewCompat.setOnApplyWindowInsetsListener(view, (v, windowInsets) -> {
            Insets insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars()
                    | WindowInsetsCompat.Type.displayCutout()
                    | WindowInsetsCompat.Type.ime());
            v.setPadding(insets.left, 0, insets.right, insets.bottom);
            return WindowInsetsCompat.CONSUMED;
        });
        mGeckoView = view.findViewById(R.id.addon_options_view);
        String id = getArguments() == null ? null : getArguments().getString(Keys.ADDON_ID);
        AddonInfo info = mAddonManager.find(id);
        GeckoSession session = id == null ? null : mAddonManager.openOptionsSession(id);
        if (info == null || session == null) {
            leave();
            return;
        }
        Activity activity = getActivity();
        if (activity instanceof AppCompatActivity) {
            ActionBar actionBar = ((AppCompatActivity) activity).getSupportActionBar();
            if (actionBar != null) {
                actionBar.setTitle(info.name);
            }
        }
        session.setContentDelegate(new GeckoSession.ContentDelegate() {
            @Override
            public void onCloseRequest(@NonNull GeckoSession closing) {
                leave();
            }
        });
        mSession = session;
        mGeckoView.setSession(session);
    }

    @Override
    public void onDestroyView() {
        if (mGeckoView != null) {
            mGeckoView.releaseSession();
        }
        if (mSession != null) {
            mSession.close();
            mSession = null;
        }
        mGeckoView = null;
        super.onDestroyView();
    }

    private void leave() {
        Activity activity = getActivity();
        if (activity == null) {
            return;
        }
        NavController navController = NavHostFragment.findNavController(this);
        if (!navController.popBackStack()) {
            activity.finish();
        }
    }
}
