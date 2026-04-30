package com.solarized.firedown.phone.dialogs;

import android.app.Dialog;
import android.os.Bundle;
import android.text.Html;
import android.text.Spanned;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.ViewModelProvider;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.solarized.firedown.R;
import com.solarized.firedown.Keys;
import com.solarized.firedown.data.entity.OptionEntity;
import com.solarized.firedown.data.models.BrowserDialogViewModel;

import org.mozilla.geckoview.StorageController;


public class ClearBrowsingDialogFragment extends BaseDialogFragment {


    private String mHost;

    private BrowserDialogViewModel mBrowserDialogViewModel;


    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Bundle bundle = getArguments();

        if (bundle == null)
            throw new IllegalStateException("Bundle can not be Null " + getClass().getSimpleName());

        mHost = bundle.getString(Keys.ITEM_ID);

        mBrowserDialogViewModel = new ViewModelProvider(mActivity)
                .get(BrowserDialogViewModel.class);
    }


    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {

        int themeResId = mIsIncognito
                ? R.style.Theme_FireDown_VaultDialogTheme
                : getTheme(); // or just use the default

        String template = getString(R.string.settings_clear_browsing_dialog);
        Spanned message = Html.fromHtml(String.format(template, mHost), Html.FROM_HTML_MODE_COMPACT);

        return new MaterialAlertDialogBuilder(requireContext(), themeResId)
                .setTitle(R.string.settings_clear_browsing)
                .setMessage(message)
                .setPositiveButton(R.string.delete, (dialog, which) -> clearBrowsingData())
                .setNegativeButton(R.string.cancel, null)
                .create();
    }


    private void clearBrowsingData() {
        mGeckoRuntimeHelper
                .getGeckoRuntime()
                .getStorageController()
                .clearDataFromBaseDomain(mHost, StorageController.ClearFlags.ALL)
                .then(result -> {

                    OptionEntity optionEntity = new OptionEntity();
                    optionEntity.setId(R.id.action_clear_browsing);
                    optionEntity.setAction(mHost);
                    mBrowserDialogViewModel.onOptionSelected(optionEntity);

                    return null;
                })
                .exceptionally(throwable -> {

                    OptionEntity optionEntity = new OptionEntity();
                    optionEntity.setId(R.id.action_clear_error_browsing);
                    optionEntity.setAction(mHost);
                    mBrowserDialogViewModel.onOptionSelected(optionEntity);

                    return null;
                });
    }

}