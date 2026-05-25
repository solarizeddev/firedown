package com.solarized.firedown.phone.dialogs;

import android.app.Dialog;
import android.content.SharedPreferences;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.solarized.firedown.Keys;
import com.solarized.firedown.Preferences;
import com.solarized.firedown.R;
import com.solarized.firedown.utils.NavigationUtils;

import javax.inject.Inject;

import dagger.hilt.android.AndroidEntryPoint;

/**
 * Three-option prompt fired when the page tries to redirect the user
 * to a Play Store listing (the "install our app" nag pattern). The
 * navigation was already denied by the time this dialog opens — the
 * buttons decide what to do next:
 *
 *  • Always block (neutral) — flip the auto-block preference on so
 *    future redirects are silently denied (with a Snackbar so the
 *    block stays visible), then dismiss.
 *  • Cancel (negative)      — one-shot block, no preference change.
 *    Next redirect from this or any other site shows this dialog
 *    again.
 *  • Open Play Store (positive) — let the navigation proceed this
 *    once. The original URL is passed back to BrowserFragment via
 *    a FragmentResult so the active GeckoSession can load it.
 *
 * Arguments (set by BrowserFragment.onPlayStoreRedirect):
 *   Keys.ITEM_ID       — String, the Play Store URL the page wanted
 *   Keys.PACKAGE_ID    — String, parsed package id (nullable, shown
 *                        in the dialog body when present)
 *   Keys.IS_INCOGNITO  — boolean, theme switch
 */
@AndroidEntryPoint
public class BlockRedirectDialogFragment extends BaseDialogFragment {

    public static final String RESULT_KEY = "com.solarized.firedown.blockredirect.result";
    public static final String RESULT_OPEN_URI = "com.solarized.firedown.blockredirect.open_uri";

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

        String message = mPackageId != null
                ? getString(R.string.block_redirect_subtitle_package, mPackageId)
                : getString(R.string.block_redirect_subtitle);

        return new MaterialAlertDialogBuilder(requireContext(), themeResId)
                .setTitle(getString(R.string.block_redirect_title))
                .setMessage(message)
                .setNeutralButton(getString(R.string.block_redirect_always_block),
                        (dialog, which) -> {
                            mSharedPreferences.edit()
                                    .putBoolean(Preferences.SETTINGS_BLOCK_PLAYSTORE_REDIRECTS, true)
                                    .apply();
                            popBackStack();
                        })
                .setNegativeButton(getString(R.string.cancel),
                        (dialog, which) -> popBackStack())
                .setPositiveButton(getString(R.string.block_redirect_open),
                        (dialog, which) -> {
                            Bundle result = new Bundle();
                            result.putString(RESULT_OPEN_URI, mUri);
                            getParentFragmentManager().setFragmentResult(RESULT_KEY, result);
                            popBackStack();
                        })
                .create();
    }

    private void popBackStack() {
        NavigationUtils.popBackStackSafe(mNavController, R.id.dialog_block_redirect);
    }
}
