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

public class DeleteLoginDialogFragment extends BaseDialogFragment {



    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
        return new MaterialAlertDialogBuilder(requireContext())
                .setTitle(getString(R.string.delete_password))
                .setMessage(getString(R.string.delete_password_description))
                .setPositiveButton(getString(R.string.delete), (dialog, which) -> {
                    NavBackStackEntry navBackStackEntry = mNavController.getPreviousBackStackEntry();
                    if(navBackStackEntry != null){
                        OptionEntity optionEntity = new OptionEntity();
                        navBackStackEntry.getSavedStateHandle().set(IntentActions.DOWNLOAD_ITEM, optionEntity);
                    }
                    mNavController.popBackStack();
                } )
                .setNegativeButton(getString(R.string.cancel), (dialog, which) -> {
                    dismiss();
                } )
                .create();
    }


}
