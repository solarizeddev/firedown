package com.solarized.firedown.phone.dialogs;

import android.app.Dialog;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.solarized.firedown.R;
import com.solarized.firedown.utils.NavigationUtils;

public class ShortCutMaxDialogFragment extends BaseDialogFragment {


    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {

        int themeResId = mIsIncognito
                ? R.style.Theme_FireDown_VaultDialogTheme
                : getTheme(); // or just use the default

        return new MaterialAlertDialogBuilder(requireContext(), themeResId)
                .setTitle(R.string.shortcut_max_limit_title)
                .setMessage(R.string.shortcut_max_limit_content)
                .setPositiveButton(getString(R.string.top_sites_max_limit_confirmation_button), (dialog, which) -> {
                    NavigationUtils.popBackStackSafe(mNavController, R.id.dialog_shortcuts_max);
                })
                .create();
    }


}
