package com.solarized.firedown.phone.dialogs;

import static android.content.Context.CLIPBOARD_SERVICE;

import android.app.Dialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.ViewModelProvider;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.solarized.firedown.R;
import com.solarized.firedown.data.entity.OptionEntity;
import com.solarized.firedown.data.models.BrowserDialogViewModel;
import com.solarized.firedown.utils.BuildUtils;
import com.solarized.firedown.Keys;


public class ClipboardDialogFragment extends BaseDialogFragment {


    private BrowserDialogViewModel mBrowserDialogViewModel;

    private String mText;



    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Bundle bundle = getArguments();

        if(bundle == null)
            throw new IllegalArgumentException("Bunlde can not be Null");

        mBrowserDialogViewModel = new ViewModelProvider(mActivity).get(BrowserDialogViewModel.class);

        mText = bundle.getString(Keys.TITLE, "");
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {

        int themeResId = mIsIncognito
                ? R.style.Theme_FireDown_VaultDialogTheme
                : getTheme(); // or just use the default

        return new MaterialAlertDialogBuilder(requireContext(), themeResId)
                .setMessage(getString(R.string.clipboard_delete))
                .setPositiveButton(getString(R.string.delete), (dialog, which) -> {
                    ClipboardManager clipboardManager = (ClipboardManager) mActivity.getSystemService(CLIPBOARD_SERVICE);
                    if(BuildUtils.hasAndroidP()){
                        while (clipboardManager.hasPrimaryClip()) {
                            clipboardManager.clearPrimaryClip();
                        }
                    } else{
                        ClipData clip = ClipData.newPlainText(null,null);
                        clipboardManager.setPrimaryClip(clip);
                    }
                    OptionEntity optionEntity = new OptionEntity();
                    optionEntity.setId(R.id.action_delete_clipboard);
                    mBrowserDialogViewModel.onOptionSelected(optionEntity);
                    dismiss();
                } )
                .setNegativeButton(getString(R.string.cancel), (dialog, which) -> {
                    dismiss();
                } )
                .create();
    }

}
