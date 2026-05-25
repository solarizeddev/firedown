package com.solarized.firedown.phone.dialogs;

import android.app.Dialog;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.solarized.firedown.Keys;
import com.solarized.firedown.Preferences;
import com.solarized.firedown.R;
import com.solarized.firedown.utils.NavigationUtils;

import javax.inject.Inject;

import dagger.hilt.android.AndroidEntryPoint;

/**
 * Prompt fired when the page tries to redirect the user to a Play
 * Store listing (the "install our app" nag pattern). The navigation
 * was already denied by the time this dialog opens — the three
 * vertically stacked buttons in the custom layout decide what to
 * do next:
 *
 *  • Always block (filled, primary)    — flip the silent-block
 *    preference on and dismiss. Future redirects show only a
 *    Snackbar; no more dialog.
 *  • Block this one (tonal, secondary) — one-shot block, no
 *    preference change. Dialog will appear again on the next
 *    redirect.
 *  • Open Play Store (text, tertiary)  — proceed via FragmentResult
 *    so BrowserFragment can loadUri the original URL.
 *
 * Custom layout because MaterialAlertDialog's button bar would
 * auto-stack three buttons vertically with inconsistent visual
 * weight; rolling our own lets us style each action by intent
 * (primary / secondary / tertiary) and gives a clear hierarchy.
 *
 * Arguments (set by BrowserFragment.onPlayStoreRedirect):
 *   Keys.ITEM_ID       — String, the Play Store URL the page wanted
 *   Keys.PACKAGE_ID    — String, parsed package id (nullable; shown
 *                        in the dialog body when present)
 *   Keys.IS_INCOGNITO  — boolean, theme switch
 */
@AndroidEntryPoint
public class BlockRedirectDialogFragment extends BaseDialogFragment {

    public static final String RESULT_KEY = "com.solarized.firedown.blockredirect.result";
    public static final String RESULT_ACTION = "com.solarized.firedown.blockredirect.action";
    public static final String RESULT_OPEN_URI = "com.solarized.firedown.blockredirect.open_uri";

    public static final String ACTION_BLOCK = "block";
    public static final String ACTION_OPEN = "open";

    @Inject
    SharedPreferences mSharedPreferences;

    private String mUri;
    private String mPackageId;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Bundle args = getArguments();
        if (args != null) {
            mUri = args.getString(Keys.ITEM_ID);
            mPackageId = args.getString(Keys.PACKAGE_ID);
        }
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {

        // Restored with no args — dismiss instead of crashing the
        // browser. Matches the defensive pattern in the other
        // FragmentArgs-driven dialogs.
        if (mUri == null) {
            Dialog dialog = new Dialog(requireContext());
            dialog.setOnShowListener(d -> dismissAllowingStateLoss());
            return dialog;
        }

        int themeResId = mIsIncognito
                ? R.style.Theme_FireDown_VaultDialogTheme
                : getTheme();

        View content = LayoutInflater.from(requireContext())
                .inflate(R.layout.dialog_block_redirect, null, false);

        TextView messageView = content.findViewById(R.id.block_redirect_message);
        messageView.setText(mPackageId != null
                ? getString(R.string.block_redirect_subtitle_package, mPackageId)
                : getString(R.string.block_redirect_subtitle));

        // The dialog instance is built first so the button click
        // handlers can dismiss it. setView happens before show().
        Dialog dialog = new MaterialAlertDialogBuilder(requireContext(), themeResId)
                .setView(content)
                .create();

        MaterialButton btnAlways = content.findViewById(R.id.block_redirect_btn_always);
        MaterialButton btnOnce = content.findViewById(R.id.block_redirect_btn_once);
        MaterialButton btnOpen = content.findViewById(R.id.block_redirect_btn_open);

        btnAlways.setOnClickListener(v -> {
            mSharedPreferences.edit()
                    .putBoolean(Preferences.SETTINGS_BLOCK_PLAYSTORE_REDIRECTS, true)
                    .apply();
            sendResult(ACTION_BLOCK, null);
        });

        btnOnce.setOnClickListener(v -> sendResult(ACTION_BLOCK, null));

        btnOpen.setOnClickListener(v -> sendResult(ACTION_OPEN, mUri));

        return dialog;
    }

    private void sendResult(String action, @Nullable String uri) {
        Bundle result = new Bundle();
        result.putString(RESULT_ACTION, action);
        if (uri != null) result.putString(RESULT_OPEN_URI, uri);
        getParentFragmentManager().setFragmentResult(RESULT_KEY, result);
        NavigationUtils.popBackStackSafe(mNavController, R.id.dialog_block_redirect);
    }
}
