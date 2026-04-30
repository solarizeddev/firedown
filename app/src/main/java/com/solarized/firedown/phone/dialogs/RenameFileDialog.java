package com.solarized.firedown.phone.dialogs;

import android.annotation.SuppressLint;
import android.app.Dialog;
import android.content.res.Resources;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputFilter;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.lifecycle.ViewModelProvider;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.solarized.firedown.R;
import com.solarized.firedown.data.entity.DownloadEntity;
import com.solarized.firedown.data.models.DownloadsViewModel;
import com.solarized.firedown.ui.FocusEditText;
import com.solarized.firedown.utils.FileUriHelper;
import com.solarized.firedown.Keys;
import com.solarized.firedown.StoragePaths;

import org.apache.commons.io.FilenameUtils;

import java.io.File;

public class RenameFileDialog extends BaseDialogFragment implements TextWatcher {

    private static final String TAG = RenameFileDialog.class.getName();

    private DownloadsViewModel mDownloadsViewModel;

    private String mFilename;

    private FocusEditText mEditText;

    private View mInfoText;

    private DownloadEntity mDownloadEntity;

    private File[] mFileList;

    @Override
    public void onResume() {
        super.onResume();
        if (getDialog() != null && getDialog().getWindow() != null) {
            Window window = getDialog().getWindow();
            Resources resources = getResources();
            int width = Math.min((int)(resources.getDisplayMetrics().widthPixels*0.90), resources.getDimensionPixelOffset(R.dimen.max_dialog_width));
            window.setLayout(width, WindowManager.LayoutParams.WRAP_CONTENT);
        }
    }



    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Bundle bundle = getArguments();

        DownloadEntity downloadEntity = bundle != null ? bundle.getParcelable(Keys.ITEM_ID) : null;

        if(downloadEntity == null)
            dismiss();

        mDownloadEntity = new DownloadEntity(downloadEntity);

        File file = new File(mDownloadEntity.getFilePath());

        File parent = file.getParentFile();

        mFileList = parent != null && parent.exists() && parent.isDirectory() ? parent.listFiles() : null;

        mFilename = mDownloadEntity.getFileName();

        mDownloadsViewModel = new ViewModelProvider(this).get(DownloadsViewModel.class);
    }



    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {

        int themeResId = mIsIncognito
                ? R.style.Theme_FireDown_VaultDialogTheme
                : getTheme(); // or just use the default

        LayoutInflater inflater = getLayoutInflater();

        @SuppressLint("InflateParams") View v = inflater.inflate(R.layout.fragment_dialog_reame_file, null);

        mEditText = v.findViewById(R.id.edit_text);

        mInfoText = v.findViewById(R.id.info_text);

        mEditText.setFilters(new InputFilter[]{filter});

        mEditText.addTextChangedListener(this);

        mEditText.setText(mFilename);

        mEditText.setSelection(0, FilenameUtils.removeExtension(mFilename).length());

        mEditText.focusAndShowKeyboard();

        return new MaterialAlertDialogBuilder(requireContext(), themeResId)
                .setTitle(getString(R.string.rename_file))
                .setView(v)
                .setPositiveButton(getString(R.string.rename), (dialog, which) -> {
                    String filename =  mEditText.getText() != null ? mEditText.getText().toString() : null;
                    if (TextUtils.isEmpty(filename)) {
                        return;
                    }
                    String filePath = mDownloadEntity.getFilePath();
                    File file = new File(filePath);
                    filename =  FileUriHelper.sanitizeFileName(filename);
                    File fileDest = new File(StoragePaths.getDownloadPath(mActivity), filename);
                    if (file.renameTo(fileDest)) {
                        String ext = FilenameUtils.getExtension(fileDest.getName());
                        if(!TextUtils.isEmpty(ext))
                            mDownloadEntity.setFileMimeType(FileUriHelper.getMimeTypeFromFile(fileDest.getName()));
                        mDownloadEntity.setFilePath(fileDest.getPath());
                        mDownloadEntity.setFileName(fileDest.getName());
                        mDownloadsViewModel.addDownload(mDownloadEntity);
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
    public void beforeTextChanged(CharSequence s, int start, int count, int after) {

    }

    @Override
    public void onTextChanged(CharSequence s, int start, int before, int count) {
        boolean present = false;
        if(mFileList != null){
            for(File file : mFileList){
                String name = file.getName();
                if((!TextUtils.isEmpty(mFilename) && !mFilename.contentEquals(s)) && name.contentEquals(s)){
                    present = true;
                    break;
                }
            }
        }
        AlertDialog dialog = (AlertDialog) getDialog();
        if(dialog != null) dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(!present);
        mInfoText.setVisibility(present ? View.VISIBLE : View.INVISIBLE);
    }

    @Override
    public void afterTextChanged(Editable s) {

    }


    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if(mEditText != null) mEditText.removeTextChangedListener(this);
        mEditText = null;
        mInfoText = null;
    }
}
