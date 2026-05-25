package com.solarized.firedown.phone.dialogs;

import android.app.Dialog;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.solarized.firedown.Keys;
import com.solarized.firedown.R;
import com.solarized.firedown.utils.NavigationUtils;

import dagger.hilt.android.AndroidEntryPoint;

/**
 * Prompt fired when the page tries to redirect the user to a Play
 * Store listing (the "install our app" nag pattern). The navigation
 * was already denied by the time this dialog opens — the buttons
 * decide what to do next:
 *
 *  • Cancel (negative)            — one-shot block. BrowserFragment
 *    receives a "blocked" result and follows up with a Snackbar that
 *    offers a one-tap "Always block" action so the user can lock
 *    in silent blocking without going to Settings.
 *  • Open Play Store (positive)   — proceed via FragmentResult so
 *    BrowserFragment can loadUri the original URL.
 *
 * The dialog deliberately has no "always block" affordance baked in:
 * mixing a future-tense checkbox with present-tense buttons (Open /
 * Cancel) read as a contradiction. The Snackbar follow-up keeps the
 * dialog itself a clean two-button question.
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
                .setNegativeButton(getString(R.string.cancel),
                        (dialog, which) -> sendResult(ACTION_BLOCK, null))
                .setPositiveButton(getString(R.string.block_redirect_open),
                        (dialog, which) -> sendResult(ACTION_OPEN, mUri))
                .create();
    }

    private void sendResult(String action, @Nullable String uri) {
        Bundle result = new Bundle();
        result.putString(RESULT_ACTION, action);
        if (uri != null) result.putString(RESULT_OPEN_URI, uri);
        getParentFragmentManager().setFragmentResult(RESULT_KEY, result);
        NavigationUtils.popBackStackSafe(mNavController, R.id.dialog_block_redirect);
    }
}
