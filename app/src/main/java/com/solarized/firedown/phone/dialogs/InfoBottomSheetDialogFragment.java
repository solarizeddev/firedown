package com.solarized.firedown.phone.dialogs;

import android.app.Dialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.res.TypedArray;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.snackbar.Snackbar;
import com.solarized.firedown.Preferences;
import com.solarized.firedown.R;
import com.solarized.firedown.data.entity.DownloadEntity;
import com.solarized.firedown.data.entity.InfoEntity;
import com.solarized.firedown.ui.adapters.InfoAdapter;
import com.solarized.firedown.ui.OnItemClickListener;
import com.solarized.firedown.ui.diffs.InfoDiffCallback;
import com.solarized.firedown.utils.DateUtils;
import com.solarized.firedown.utils.FileUriHelper;
import com.solarized.firedown.Keys;
import com.solarized.firedown.utils.Utils;

import java.util.ArrayList;

public class InfoBottomSheetDialogFragment extends BaseBottomResizedDialogFragment implements View.OnClickListener, OnItemClickListener {

    private DownloadEntity mDownloadEntity;

    private InfoAdapter mAdapter;

    @Override
    public void onCreate(Bundle savedInstanceState){
        super.onCreate(savedInstanceState);

        Bundle bundle = getArguments();

        if(bundle == null)
            throw new IllegalStateException("Bundle can not be Null " + getClass().getSimpleName());

        mDownloadEntity =  bundle.getParcelable(Keys.ITEM_ID);

    }

    @Nullable
    @Override
    public View onCreateView(LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {

        // get the views and attach the listener

        mView = inflater.inflate(R.layout.fragment_dialog_download_info, container,
                false);

        String mimeType = mDownloadEntity.getFileMimeType();

        boolean media = FileUriHelper.isMimeTypeMedia(mimeType) || FileUriHelper.isVideo(mimeType) || FileUriHelper.isAudio(mimeType);

        String description = mDownloadEntity.getFileDescription();

        String[] mLocalDataSet = getResources().getStringArray(media ? R.array.download_details_media_items : R.array.download_details_items);

        TypedArray ids = getResources().obtainTypedArray(media ? R.array.download_details_media_items_ids : R.array.download_details_items_ids);

        ArrayList<InfoEntity> mData = new ArrayList<>();

        for(int i = 0; i < mLocalDataSet.length; i++){
            int resId = ids.getResourceId(i, R.id.info_details_default);
            if(resId == R.id.info_details_description){
                if(!TextUtils.isEmpty(description)) mData.add(new InfoEntity(resId, InfoEntity.ITEM, mLocalDataSet[i]));
            }else{
                mData.add(new InfoEntity(resId, InfoEntity.ITEM, mLocalDataSet[i]));
            }
        }

        mData.add(new InfoEntity(R.id.info_details_final, InfoEntity.ITEM_FINAL, ""));

        ids.recycle();

        RecyclerView mRecyclerView = mView.findViewById(R.id.recycler_view);

        mAdapter = new InfoAdapter( new InfoDiffCallback(),this, mDownloadEntity);

        mRecyclerView.setAdapter(mAdapter);

        mAdapter.submitList(mData);

        return mView;

    }

    @Override
    public void onClick(View view) {

    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        mAdapter = null;
    }

    @Override
    public void onItemClick(int position, int resId) {
        if (resId == R.id.button) {
            mNavController.popBackStack();
        } else {
            if(position == RecyclerView.NO_POSITION)
                return;
            InfoEntity infoEntity = mAdapter.getCurrentList().get(position);
            int id = infoEntity.getId();
            String clipboardText = infoEntity.getText();
            if(id == R.id.info_details_name) {
                clipboardText = mDownloadEntity.getFileName();
            } else if(id == R.id.info_details_description) {
                clipboardText = mDownloadEntity.getFileDescription();
            } else if(id == R.id.info_details_size) {
                clipboardText = Utils.getFileSize(mDownloadEntity.getFileSize());
            } else if(id == R.id.info_details_modified) {
                clipboardText = DateUtils.getFileDate(mDownloadEntity.getFileDate());
            } else if(id == R.id.info_details_origin) {
                clipboardText = mDownloadEntity.getOriginUrl();
            } else if(id == R.id.info_details_path) {
                clipboardText = mDownloadEntity.getFilePath();
            } else if(id == R.id.info_details_duration) {
                String duration = mDownloadEntity.getDurationFormatted();
                clipboardText = TextUtils.isEmpty(duration) ? "00:00" : duration;
            } else if(id == R.id.info_details_url) {
                clipboardText = mDownloadEntity.getFileUrl();
            }
            ClipboardManager clipboard = (ClipboardManager) mActivity.getSystemService(Context.CLIPBOARD_SERVICE);
            ClipData clip = ClipData.newPlainText(Preferences.CLIPBOARD_LABEL, clipboardText);
            clipboard.setPrimaryClip(clip);
            Dialog dialog = getDialog();
            if (dialog != null) {
                Window window = dialog.getWindow();
                if(window != null){
                    Snackbar snackbar = Snackbar.make(window.getDecorView(), R.string.clipboard, Snackbar.LENGTH_LONG);
                    snackbar.setAnchorView(R.id.anchor_view);
                    snackbar.show();
                }

            }
        }
    }

    @Override
    public void onLongClick(int position, int resId) {

    }

    @Override
    public void onItemVariantClick(int position, int variant, int resId) {

    }

}
