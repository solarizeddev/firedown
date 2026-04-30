package com.solarized.firedown.phone.dialogs;

import android.app.Dialog;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.ViewModelProvider;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.solarized.firedown.R;
import com.solarized.firedown.data.models.WebBookmarkViewModel;


public class DeleteBookmarkDialogFragment extends BaseDialogFragment {

    private WebBookmarkViewModel mWebBookmarkViewModel;


    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mWebBookmarkViewModel = new ViewModelProvider(this).get(WebBookmarkViewModel.class);
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {

        int themeResId = mIsIncognito
                ? R.style.Theme_FireDown_VaultDialogTheme
                : getTheme(); // or just use the default

        return new MaterialAlertDialogBuilder(requireContext(), themeResId)
                .setTitle(getString(R.string.delete_bookmarks))
                .setMessage(getString(R.string.delete_all_bookmarks))
                .setPositiveButton(getString(R.string.delete), (dialog, which) -> {
                    mWebBookmarkViewModel.deleteAll();
                } )
                .setNegativeButton(getString(R.string.cancel), (dialog, which) -> {
                    dismiss();
                } )
                .create();
    }

}
