package com.solarized.firedown.phone.dialogs;


import android.annotation.SuppressLint;
import android.app.Dialog;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.ViewModelProvider;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.solarized.firedown.R;
import com.solarized.firedown.data.entity.BrowserDownloadEntity;
import com.solarized.firedown.data.entity.OptionEntity;
import com.solarized.firedown.data.models.BrowserDialogViewModel;
import com.solarized.firedown.Keys;
import com.solarized.firedown.data.models.GeckoStateViewModel;
import com.solarized.firedown.geckoview.GeckoState;
import com.solarized.firedown.manager.DownloadRequest;
import com.solarized.firedown.utils.FileUriHelper;
import com.solarized.firedown.utils.Utils;

import org.apache.commons.io.FilenameUtils;

import java.util.Locale;

public class BrowserDownloadDialogFragment extends BaseDialogFragment {

    private BrowserDownloadEntity mEntity;

    private BrowserDialogViewModel mBrowserDialogViewModel;

    private GeckoState mGeckoState;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        mBrowserDialogViewModel = new ViewModelProvider(mActivity).get(BrowserDialogViewModel.class);
        GeckoStateViewModel geckoStateViewModel = new ViewModelProvider(mActivity).get(GeckoStateViewModel.class);

        Bundle bundle = getArguments();
        if (bundle == null) return;

        int sessionId = bundle.getInt(Keys.ITEM_ID);
        mGeckoState = geckoStateViewModel.getGeckoState(sessionId);
        // mGeckoState is null when the session has been collected (process
        // death wipes session state). onCreateDialog dismisses in that case.
        if (mGeckoState == null || mGeckoState.getWebResponse() == null) return;
        mEntity = new BrowserDownloadEntity(mGeckoState);
        // The GeckoState constructor doesn't carry the tab's incognito state,
        // so stamp it from the authoritative IS_INCOGNITO arg (mIsIncognito,
        // set by super.onCreate). DownloadRequest.from() reads this to route
        // the file to the private vault — without it an incognito download
        // would silently save to public Downloads while the dialog claims
        // otherwise.
        mEntity.setIncognito(mIsIncognito);
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {

        if (mEntity == null) {
            Dialog dialog = new Dialog(requireContext());
            dialog.setOnShowListener(d -> dismissAllowingStateLoss());
            return dialog;
        }

        int themeResId = mIsIncognito
                ? R.style.Theme_FireDown_VaultDialogTheme
                : getTheme(); // or just use the default

        LayoutInflater inflater = getLayoutInflater();
        @SuppressLint("InflateParams") View v =
                inflater.inflate(R.layout.fragment_dialog_download_confirm, null);

        ((TextView) v.findViewById(R.id.file_name)).setText(mEntity.getFileName());
        bindDestinationRow(v);

        // No title: the verbose "A file is ready to be downloaded:" string
        // crowded the dialog above an already-long filename. The filename is
        // now the headline (see layout), with the Download button + icon
        // carrying the intent.
        return new MaterialAlertDialogBuilder(requireContext(), themeResId)
                .setView(v)
                .setPositiveButton(getString(R.string.download), (dialog, which) -> {
                    DownloadRequest request = DownloadRequest.from(mEntity);

                    OptionEntity optionEntity = new OptionEntity();
                    optionEntity.setId(R.id.action_download);
                    optionEntity.setDownloadRequest(request);
                    mBrowserDialogViewModel.onOptionSelected(optionEntity);

                    dismiss();
                })
                .setNegativeButton(getString(R.string.cancel), (dialog, which) -> {
                    if (mGeckoState != null) {
                        mGeckoState.setWebResponse(null);
                    }
                    dismiss();
                })
                .create();
    }

    /**
     * Surfaces where the file will land. Incognito downloads are routed to
     * the private vault (DownloadRequest.from sets saveToVault from the
     * entity's incognito flag), so the row mirrors that with a distinct icon
     * and label, plus the size (when known) and output format.
     */
    private void bindDestinationRow(View v) {
        ImageView icon = v.findViewById(R.id.destination_icon);
        TextView text = v.findViewById(R.id.destination_text);

        String format = outputExtension(mEntity.getFileName(), mEntity.getMimeType())
                .toUpperCase(Locale.ROOT);
        long length = mEntity.getFileLength();
        String sizeFormat = length > 0
                ? getString(R.string.save_dest_size_format, Utils.getFileSize(length), format)
                : format;

        icon.setImageResource(mIsIncognito
                ? R.drawable.ic_incognito_24
                : R.drawable.download_24);
        text.setText(getString(mIsIncognito
                ? R.string.save_dest_vault
                : R.string.save_dest_downloads, sizeFormat));
    }

    /**
     * Best-effort output extension for display: prefer the filename's own
     * extension, otherwise derive from the mime type (audio→mp3, image→jpg,
     * everything else muxes to mp4 — mirrors SaveFileDialog).
     */
    private static String outputExtension(String name, String mime) {
        String ext = FilenameUtils.getExtension(name);
        if (!TextUtils.isEmpty(ext)) return ext;
        if (!TextUtils.isEmpty(mime)) {
            if (FileUriHelper.isAudio(mime)) {
                String m = FileUriHelper.getFileExtensionFromMimeType(mime);
                return (!TextUtils.isEmpty(m) && !"bin".equals(m)) ? m : "mp3";
            }
            if (FileUriHelper.isImage(mime)) {
                String i = FileUriHelper.getFileExtensionFromMimeType(mime);
                return (!TextUtils.isEmpty(i) && !"bin".equals(i)) ? i : "jpg";
            }
        }
        return "mp4";
    }
}