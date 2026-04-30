package com.solarized.firedown.phone.dialogs;

import android.app.Dialog;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.navigation.NavBackStackEntry;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.solarized.firedown.R;
import com.solarized.firedown.data.entity.OptionEntity;
import com.solarized.firedown.IntentActions;

public class CancelOperationDialogFragment extends BaseDialogFragment {


    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {

        int themeResId = mIsIncognito
                ? R.style.Theme_FireDown_VaultDialogTheme
                : getTheme(); // or just use the default

        return new MaterialAlertDialogBuilder(requireContext(), themeResId)
                .setMessage(R.string.task_cancel_operation)
                .setPositiveButton(getString(R.string.task_stop), (dialog, which) -> {
                    NavBackStackEntry navBackStackEntry = mNavController.getPreviousBackStackEntry();
                    if(navBackStackEntry != null){
                        OptionEntity optionEntity = new OptionEntity();
                        navBackStackEntry.getSavedStateHandle().set(IntentActions.ACTION_TASK, optionEntity);
                    }
                    mNavController.popBackStack();
                    dismiss();
                })
                .setNegativeButton(getString(R.string.task_dismiss), (dialog, which) -> {
                    dismiss();
                })
                .create();
    }


}
