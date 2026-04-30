package com.solarized.firedown.phone.dialogs;

import android.annotation.SuppressLint;
import android.app.Dialog;
import android.content.res.Resources;
import android.os.Bundle;
import android.text.InputFilter;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.widget.EditText;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.navigation.NavBackStackEntry;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.solarized.firedown.R;
import com.solarized.firedown.data.entity.BrowserDownloadEntity;
import com.solarized.firedown.data.entity.OptionEntity;
import com.solarized.firedown.IntentActions;
import com.solarized.firedown.Keys;


public class SaveFileDialog extends BaseDialogFragment {

    private static final String TAG = SaveFileDialog.class.getName();

    private String mFilename;

    private EditText mEditText;

    private BrowserDownloadEntity mBrowserDownloadEntity;


    @Override
    public void onResume() {
        super.onResume();
        if (getDialog() != null && getDialog().getWindow() != null) {
            Resources resources = getResources();
            int width = Math.min((int)(resources.getDisplayMetrics().widthPixels*0.90), resources.getDimensionPixelOffset(R.dimen.max_dialog_width));
            getDialog().getWindow().setLayout(width, WindowManager.LayoutParams.WRAP_CONTENT);
        }
    }



    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Bundle bundle = getArguments();

        BrowserDownloadEntity browserDownloadEntity = bundle != null ? bundle.getParcelable(Keys.ITEM_ID) : null;

        if(browserDownloadEntity == null)
            throw new IllegalStateException("Bundle can not be Null " + getClass().getSimpleName());

        mBrowserDownloadEntity = new BrowserDownloadEntity(browserDownloadEntity);

        mFilename = mBrowserDownloadEntity.getFileName();


    }


    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {

        int themeResId = mIsIncognito
                ? R.style.Theme_FireDown_VaultDialogTheme
                : getTheme(); // or just use the default

        LayoutInflater inflater = getLayoutInflater();

        @SuppressLint("InflateParams") View v = inflater.inflate(R.layout.fragment_dialog_save_file, null);

        mEditText = v.findViewById(R.id.edit_text);

        mEditText.setFilters(new InputFilter[]{filter});

        mEditText.requestFocus();

        mEditText.setText(mFilename);

        mEditText.setSelection(mFilename.length());

        return new MaterialAlertDialogBuilder(requireContext(), themeResId)
                .setTitle(getString(R.string.save_file))
                .setView(v)
                .setPositiveButton(getString(R.string.download), (dialog, which) -> {
                    String text = mEditText.getText().toString();
                    if (!TextUtils.isEmpty(text)) {
                        mBrowserDownloadEntity.setFileName(text);
                        mBrowserDownloadEntity.setFileNameForced(true);
                    }
                    NavBackStackEntry navBackStackEntry = mNavController.getPreviousBackStackEntry();

                    if(navBackStackEntry != null){
                        OptionEntity optionEntity = new OptionEntity();
                        optionEntity.setBrowserDownloadEntity(mBrowserDownloadEntity);
                        navBackStackEntry.getSavedStateHandle().set(IntentActions.DOWNLOAD, optionEntity);
                    }

                    mNavController.popBackStack();
                } )
                .setNegativeButton(getString(R.string.cancel), (dialog, which) -> {
                    mNavController.popBackStack();
                } )
                .create();
    }



    private final InputFilter filter = (source, start, end, dest, dstart, dend) -> {
        if (source.length() < 1) return null;
        char last = source.charAt(source.length() - 1);
        String reservedChars = "?:\"*|/\\<>";
        if(reservedChars.indexOf(last) > -1) return source.subSequence(0, source.length() - 1);
        return null;
    };



    @Override
    public void onDestroyView() {
        super.onDestroyView();
        mEditText = null;
    }
}
