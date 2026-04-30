package com.solarized.firedown.phone.dialogs;

import android.annotation.SuppressLint;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.ArrayRes;
import androidx.annotation.Nullable;
import androidx.navigation.NavBackStackEntry;
import androidx.recyclerview.widget.RecyclerView;
import com.solarized.firedown.R;
import com.solarized.firedown.data.Download;
import com.solarized.firedown.data.OptionItem;
import com.solarized.firedown.data.entity.DownloadEntity;
import com.solarized.firedown.data.entity.OptionEntity;
import com.solarized.firedown.IntentActions;
import com.solarized.firedown.ui.adapters.OptionsAdapter;
import com.solarized.firedown.utils.FileUriHelper;
import com.solarized.firedown.Keys;

import java.util.ArrayList;
import java.util.List;

public class DownloadsOptionDialogFragment extends BaseBottomResizedDialogFragment implements View.OnClickListener, OptionsAdapter.OnItemClickListener {


    private DownloadEntity mDownloadEntity;

    private int mPosition;


    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Bundle bundle = getArguments();

        if (bundle == null) {
            throw new IllegalArgumentException("DownloadEntity can not be null");
        }

        mDownloadEntity = bundle.getParcelable(Keys.ITEM_ID);

        mPosition = bundle.getInt(Keys.ITEM_POSITION);


    }

    @SuppressLint("InvalidSetHasFixedSize")
    @Nullable
    @Override
    public View onCreateView(LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {

        // get the views and attach the listener

        mView = inflater.inflate(R.layout.fragment_dialog_options, container, false);

        RecyclerView mRecyclerView = mView.findViewById(R.id.recycler_view);

        List<OptionItem> optionItemList = buildOptionItems();

        OptionsAdapter optionsAdapter = new OptionsAdapter(optionItemList, this);

        mRecyclerView.setAdapter(optionsAdapter);

        mRecyclerView.setHasFixedSize(true);

        return mView;

    }


    private List<OptionItem> buildOptionItems() {
        int[] arrayPair = resolveArrayPair();
        return loadOptionItems(arrayPair[0], arrayPair[1]);
    }

    /** Returns {labelArrayRes, iconArrayRes} based on download state. */
    private int[] resolveArrayPair() {
        if (mDownloadEntity.getFileErrorType() != Download.PROGRESS) {
            return new int[]{R.array.download_options_error_type, R.array.download_options_error_icon};
        }
        if (mDownloadEntity.getFileStatus() == Download.PROGRESS) {
            return new int[]{R.array.download_options_pause_type, R.array.download_options_pause_icon};
        }
        if (mDownloadEntity.isFileSafe()) {
            return new int[]{R.array.download_options_encrypted_type, R.array.download_options_encrypted_icon};
        }
        if (!FileUriHelper.isVideo(mDownloadEntity.getFileMimeType())) {
            return new int[]{R.array.download_options_no_thumb_type, R.array.download_options_no_thumb_icon};
        }
        return new int[]{R.array.download_options_type, R.array.download_options_icon};
    }

    private List<OptionItem> loadOptionItems(@ArrayRes int labelsRes, @ArrayRes int iconsRes) {
        Resources res = getResources();
        String[] labels = res.getStringArray(labelsRes);
        TypedArray icons = res.obtainTypedArray(iconsRes);

        List<OptionItem> items = new ArrayList<>(icons.length());
        try {
            for (int i = 0; i < labels.length; i++) {
                int iconResId = icons.getResourceId(i, 0);
                items.add(new OptionItem(labels[i], iconResId));
            }
        } finally {
            icons.recycle();
        }
        return items;
    }

    @Override
    public void onClick(View view) {
        NavBackStackEntry navBackStackEntry = mNavController.getPreviousBackStackEntry();

        if (navBackStackEntry != null) {
            OptionEntity optionEntity = new OptionEntity();
            optionEntity.setId(view.getId());
            optionEntity.setDownloadEntity(mDownloadEntity);
            optionEntity.setPosition(mPosition);
            navBackStackEntry.getSavedStateHandle().set(IntentActions.DOWNLOAD_ITEM, optionEntity);
        }
        mNavController.popBackStack();
    }


    @Override
    public void onItemClick(int position, OptionItem item) {
        if (position == RecyclerView.NO_POSITION)
            return;

        NavBackStackEntry navBackStackEntry = mNavController.getPreviousBackStackEntry();

        if (navBackStackEntry != null) {
            OptionEntity optionEntity = new OptionEntity();
            optionEntity.setId(item.getIconRes());
            optionEntity.setDownloadEntity(mDownloadEntity);
            optionEntity.setPosition(mPosition);
            navBackStackEntry.getSavedStateHandle().set(IntentActions.DOWNLOAD_ITEM, optionEntity);
        }
        mNavController.popBackStack();
    }
}

