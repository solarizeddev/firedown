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
import com.solarized.firedown.utils.NavigationUtils;


public class BrowserAppDialogFragment extends BaseDialogFragment {

    private static final String TAG = BrowserAppDialogFragment.class.getName();

    private Intent mIntent;


    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Bundle bundle = getArguments();
        mIntent = bundle != null ? getArguments().getParcelable(Keys.ITEM_ID) : null;
        if(mIntent == null){
            throw new IllegalArgumentException("BrowserAppDialogFragment Null Intent");
        }
    }


    @Override
    public void onDestroy() {
        super.onDestroy();
        mIntent = null;
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {

        int themeResId = mIsIncognito
                ? R.style.Theme_FireDown_VaultDialogTheme
                : getTheme(); // or just use the default

        return new MaterialAlertDialogBuilder(requireContext(), themeResId)
                .setTitle(getString(R.string.open_in_app_title))
                .setMessage(getString(R.string.open_in_app_subtitle))
                .setPositiveButton(getString(R.string.open), (dialog, which) -> {
                    try{
                        if (mIntent.resolveActivity(mActivity.getPackageManager()) != null) {
                            mActivity.startActivity(mIntent);
                        }else{

                        }
                    }catch(ActivityNotFoundException e){
                        Log.e(TAG, "No Activity found: " + mIntent.toString(), e);
                    }
                    NavigationUtils.popBackStackSafe(mNavController, R.id.dialog_browser_open_in_app);
                } )
                .setNegativeButton(getString(R.string.cancel), (dialog, which) -> {
                    NavigationUtils.popBackStackSafe(mNavController, R.id.dialog_browser_open_in_app);
                } )
                .create();
    }

}
