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
import com.solarized.firedown.utils.FileUriHelper;
import com.solarized.firedown.utils.FragmentArgs;

import org.apache.commons.io.FilenameUtils;


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

        BrowserDownloadEntity browserDownloadEntity =
                FragmentArgs.parcelable(this, Keys.ITEM_ID, BrowserDownloadEntity.class);

        if (browserDownloadEntity == null) {
            // Args lost on restore or never set — onCreateDialog dismisses
            // on first show so the user lands behind the dialog and can
            // re-tap Save.
            return;
        }

        mBrowserDownloadEntity = new BrowserDownloadEntity(browserDownloadEntity);

        mFilename = displayFilenameFor(mBrowserDownloadEntity);


    }


    /**
     * Filename to seed the EditText with. Captured-media entities from
     * sites like x.com / Twitter arrive with a human "Author - tweet text"
     * name and no extension — the downstream FFmpeg mux appends ".mp4"
     * when saving, but the dialog would otherwise show a bare (and often
     * trailing-dot) name. Strip dangling dots and append the extension
     * the saved file will actually have.
     */
    private static String displayFilenameFor(BrowserDownloadEntity entity) {
        String name = entity.getFileName();
        String ext = outputExtension(entity.getMimeType());
        if (TextUtils.isEmpty(name)) return "download." + ext;
        if (!TextUtils.isEmpty(FilenameUtils.getExtension(name))) return name;
        String base = name.replaceAll("[.\\s]+$", "");
        if (base.isEmpty()) return "download." + ext;
        return base + "." + ext;
    }

    private static String outputExtension(String mime) {
        if (!TextUtils.isEmpty(mime)) {
            if (FileUriHelper.isAudio(mime)) {
                String ext = FileUriHelper.getFileExtensionFromMimeType(mime);
                if (!TextUtils.isEmpty(ext) && !"bin".equals(ext)) return ext;
                return "mp3";
            }
            if (FileUriHelper.isImage(mime)) {
                String ext = FileUriHelper.getFileExtensionFromMimeType(mime);
                if (!TextUtils.isEmpty(ext) && !"bin".equals(ext)) return ext;
                return "jpg";
            }
        }
        // Video / HLS / unknown all get muxed to mp4 by FFmpegMuxStrategy.
        return "mp4";
    }


    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {

        if (mBrowserDownloadEntity == null) {
            // See onCreate — args parcelable was lost. Return a stub that
            // dismisses on first show so the lifecycle completes cleanly.
            Dialog dialog = new Dialog(requireContext());
            dialog.setOnShowListener(d -> dismissAllowingStateLoss());
            return dialog;
        }

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
