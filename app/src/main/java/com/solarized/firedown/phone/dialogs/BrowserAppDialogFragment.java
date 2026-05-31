package com.solarized.firedown.phone.dialogs;

import android.app.Dialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.solarized.firedown.R;
import com.solarized.firedown.Keys;
import com.solarized.firedown.utils.FragmentArgs;
import com.solarized.firedown.utils.NavigationUtils;


public class BrowserAppDialogFragment extends BaseDialogFragment {

    private static final String TAG = BrowserAppDialogFragment.class.getName();

    public static final String RESULT_KEY = "com.solarized.firedown.openinapp.result";
    public static final String RESULT_BLOCKED = "com.solarized.firedown.openinapp.blocked";

    private Intent mIntent;
    // When true, the target is a Play Store redirect (an uninstalled-app
    // "open in app" nag) and the block-redirects pref is on: tapping Open
    // blocks it and asks BrowserFragment to show the snackbar instead of
    // launching Google Play. Computed by BrowserFragment.onLoadRequest.
    private boolean mBlockStoreRedirect;


    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mIntent = FragmentArgs.parcelable(this, Keys.ITEM_ID, Intent.class);
        Bundle args = getArguments();
        if (args != null) {
            mBlockStoreRedirect = args.getBoolean(Keys.BLOCK_STORE_REDIRECT, false);
        }
        // Null on restore is handled by onCreateDialog — dismiss instead of crash.
    }


    @Override
    public void onDestroy() {
        super.onDestroy();
        mIntent = null;
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {

        if (mIntent == null) {
            Dialog dialog = new Dialog(requireContext());
            dialog.setOnShowListener(d -> dismissAllowingStateLoss());
            return dialog;
        }

        int themeResId = mIsIncognito
                ? R.style.Theme_FireDown_VaultDialogTheme
                : getTheme(); // or just use the default

        return new MaterialAlertDialogBuilder(requireContext(), themeResId)
                .setTitle(getString(R.string.open_in_app_title))
                .setMessage(getString(R.string.open_in_app_subtitle))
                .setPositiveButton(getString(R.string.open), (dialog, which) -> {
                    if (mBlockStoreRedirect) {
                        // Don't launch Google Play — report the block so
                        // BrowserFragment can show the redirect-blocked snackbar.
                        Bundle result = new Bundle();
                        result.putBoolean(RESULT_BLOCKED, true);
                        getParentFragmentManager().setFragmentResult(RESULT_KEY, result);
                    } else {
                        try{
                            if (mIntent.resolveActivity(mActivity.getPackageManager()) != null) {
                                mActivity.startActivity(mIntent);
                            }
                        }catch(ActivityNotFoundException e){
                            Log.e(TAG, "No Activity found: " + mIntent.toString(), e);
                        }
                    }
                    NavigationUtils.popBackStackSafe(mNavController, R.id.dialog_browser_open_in_app);
                } )
                .setNegativeButton(getString(R.string.cancel), (dialog, which) -> {
                    NavigationUtils.popBackStackSafe(mNavController, R.id.dialog_browser_open_in_app);
                } )
                .create();
    }

}
