package com.solarized.firedown.phone.dialogs;

import android.app.Dialog;
import android.content.Context;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.DialogFragment;
import androidx.fragment.app.Fragment;
import androidx.navigation.NavBackStackEntry;
import androidx.navigation.NavController;
import androidx.navigation.fragment.NavHostFragment;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.solarized.firedown.BaseActivity;
import com.solarized.firedown.R;
import com.solarized.firedown.data.entity.OptionEntity;
import com.solarized.firedown.IntentActions;

public class EnrollDialogFragment extends BaseDialogFragment {


    private NavController mNavController;

    private BaseActivity mActivity;


    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mNavController = getNavController();
    }

    @NonNull
    public NavController getNavController() {
        Fragment fragment = mActivity.getSupportFragmentManager().findFragmentById(R.id.content_frame);
        if (!(fragment instanceof NavHostFragment)) {
            throw new IllegalStateException("Activity " + this
                    + " does not have a NavHostFragment");
        }
        return ((NavHostFragment) fragment).getNavController();
    }

    @Override
    public void onAttach(@NonNull Context context) {
        super.onAttach(context);
        if(context instanceof BaseActivity)
            mActivity = (BaseActivity) context;
    }

    @Override
    public void onDetach(){
        super.onDetach();
        mActivity = null;
        mNavController = null;
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {

        int themeResId = mIsIncognito
                ? R.style.Theme_FireDown_VaultDialogTheme
                : getTheme(); // or just use the default

        return new MaterialAlertDialogBuilder(requireContext(), themeResId)
                .setTitle(getString(R.string.lock_enroll_title))
                .setMessage(getString(R.string.lock_enroll_message))
                .setPositiveButton(getString(R.string.lock_enroll_accept), (dialog, which) -> {
                    NavBackStackEntry navBackStackEntry = mNavController.getPreviousBackStackEntry();

                    if(navBackStackEntry != null){
                        navBackStackEntry.getSavedStateHandle().set(IntentActions.ENROLL, new OptionEntity());
                    }

                    mNavController.popBackStack();
                } )
                .setNegativeButton(getString(R.string.lock_enroll_cancel), (dialog, which) -> {
                    mNavController.popBackStack();
                } )
                .create();
    }


}
