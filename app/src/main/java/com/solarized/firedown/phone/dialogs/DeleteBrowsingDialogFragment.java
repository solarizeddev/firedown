package com.solarized.firedown.phone.dialogs;

import android.app.Dialog;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.ViewModelProvider;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;
import com.solarized.firedown.R;
import com.solarized.firedown.data.models.GeckoStateViewModel;

import org.mozilla.geckoview.StorageController;

public class DeleteBrowsingDialogFragment extends BaseDialogFragment {

    private GeckoStateViewModel mGeckoStateViewModel;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mGeckoStateViewModel = new ViewModelProvider(this).get(GeckoStateViewModel.class);
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {

        int themeResId = mIsIncognito
                ? R.style.Theme_FireDown_VaultDialogTheme
                : getTheme(); // or just use the default

        return new MaterialAlertDialogBuilder(requireContext(), themeResId)
                .setMessage(getString(R.string.delete_all_browsing))
                .setTitle(getString(R.string.delete_browsing))
                .setPositiveButton(getString(R.string.delete), (dialog, which) -> {
                    mGeckoRuntimeHelper.getGeckoRuntime().getStorageController().clearData(StorageController.ClearFlags.ALL);
                    mGeckoStateViewModel.clearStorage();
                    Snackbar snackbar = Snackbar.make(mActivity.getSnackAnchorView(), R.string.browser_cache_cleared, Snackbar.LENGTH_LONG);
                    snackbar.show();
                   dismiss();
                } )
                .setNegativeButton(getString(R.string.cancel), (dialog, which) -> {
                    dismiss();
                } )
                .create();
    }

}
