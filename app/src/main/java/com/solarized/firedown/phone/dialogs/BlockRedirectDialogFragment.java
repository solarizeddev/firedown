package com.solarized.firedown.phone.dialogs;

import android.app.Dialog;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.material.checkbox.MaterialCheckBox;
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
 * was already denied by the time this dialog opens — the buttons
 * decide what to do next:
 *
 *  • Cancel (negative)            — one-shot block, dismiss.
 *  • Open Play Store (positive)   — proceed via FragmentResult so
 *    BrowserFragment can loadUri the original URL.
 *
 * A "Always block Play Store redirects" checkbox sits above the
 * buttons. When the user dismisses the dialog with the box checked
 * the auto-block preference is flipped on regardless of which
 * button was pressed — interpretation: the user has made up their
 * mind, and this last Open (if any) is the final exception. In
 * practice no-one checks the box AND hits Open; the typical paths
 * are check + Cancel (lock in silent block) or no check + Open
 * (one-shot allow).
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
    public static final String RESULT_OPEN_URI = "com.solarized.firedown.blockredirect.open_uri";

    @Inject
    SharedPreferences mSharedPreferences;

    private String mUri;
    private String mPackageId;
    private MaterialCheckBox mAlwaysBlockCheckbox;

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

        View content = LayoutInflater.from(requireContext())
                .inflate(R.layout.dialog_block_redirect, null, false);
        TextView messageView = content.findViewById(R.id.block_redirect_message);
        messageView.setText(message);
        mAlwaysBlockCheckbox = content.findViewById(R.id.block_redirect_always);

        return new MaterialAlertDialogBuilder(requireContext(), themeResId)
                .setTitle(getString(R.string.block_redirect_title))
                .setView(content)
                .setNegativeButton(getString(R.string.cancel),
                        (dialog, which) -> {
                            persistAlwaysBlockIfChecked();
                            popBackStack();
                        })
                .setPositiveButton(getString(R.string.block_redirect_open),
                        (dialog, which) -> {
                            persistAlwaysBlockIfChecked();
                            Bundle result = new Bundle();
                            result.putString(RESULT_OPEN_URI, mUri);
                            getParentFragmentManager().setFragmentResult(RESULT_KEY, result);
                            popBackStack();
                        })
                .create();
    }

    private void persistAlwaysBlockIfChecked() {
        if (mAlwaysBlockCheckbox != null && mAlwaysBlockCheckbox.isChecked()) {
            mSharedPreferences.edit()
                    .putBoolean(Preferences.SETTINGS_BLOCK_PLAYSTORE_REDIRECTS, true)
                    .apply();
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        mAlwaysBlockCheckbox = null;
    }

    private void popBackStack() {
        NavigationUtils.popBackStackSafe(mNavController, R.id.dialog_block_redirect);
    }
}
