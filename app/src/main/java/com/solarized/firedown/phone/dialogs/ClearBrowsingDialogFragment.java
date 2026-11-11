package com.solarized.firedown.phone.dialogs;

import android.app.Dialog;
import android.content.DialogInterface;
import android.os.Bundle;
import android.text.Html;
import android.text.Spanned;

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
        mHost = bundle != null ? bundle.getString(Keys.ITEM_ID) : null;

        mBrowserDialogViewModel = new ViewModelProvider(mActivity)
                .get(BrowserDialogViewModel.class);
    }


    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {

        if (mHost == null) {
            // Args lost on restore — dismiss instead of crashing.
            Dialog dialog = new Dialog(requireContext());
            dialog.setOnShowListener(d -> dismissAllowingStateLoss());
            return dialog;
        }

        int themeResId = mIsIncognito
                ? R.style.Theme_FireDown_VaultDialogTheme
                : getTheme(); // or just use the default

        String template = getString(R.string.settings_clear_browsing_dialog);
        Spanned message = Html.fromHtml(String.format(template, mHost), Html.FROM_HTML_MODE_COMPACT);

        return new MaterialAlertDialogBuilder(requireContext(), themeResId)
                .setTitle(R.string.settings_clear_browsing)
                .setMessage(message)
                .setPositiveButton(R.string.delete, (dialog, which) -> clearBrowsingData())
                .setNegativeButton(R.string.cancel, (dialog, which) -> mExit.markBack())
                .create();
    }


    /**
     * Left with Cancel or Back: return to the shield sheet
     * ({@link ShieldReturn}). A tap outside and Delete return nowhere.
     */
    private final ShieldReturn.Exit mExit = new ShieldReturn.Exit();

    @Override
    public void onStart() {
        super.onStart();
        mExit.watch(getDialog());
    }

    @Override
    public void onDismiss(@NonNull DialogInterface dialog) {
        super.onDismiss(dialog);
        ShieldReturn.onDismiss(this, mExit.leftWithBack(false));
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