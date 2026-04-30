package com.solarized.firedown.phone.dialogs;

import android.app.Dialog;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.ViewModelProvider;
import androidx.navigation.NavBackStackEntry;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.solarized.firedown.R;
import com.solarized.firedown.data.entity.DownloadEntity;
import com.solarized.firedown.data.models.TaskViewModel;
import com.solarized.firedown.IntentActions;
import com.solarized.firedown.Keys;

import java.util.ArrayList;


public class DeleteDownloadsDialogFragment extends BaseDialogFragment {

    private static final String TAG = DeleteDownloadsDialogFragment.class.getSimpleName();

    private ArrayList<DownloadEntity> mDownloadEntities;
    private TaskViewModel mTaskViewModel;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        mTaskViewModel = new ViewModelProvider(mActivity).get(TaskViewModel.class);

        Bundle bundle = getArguments();

        if (bundle == null) {
            throw new IllegalArgumentException("DownloadEntities can not be null");
        }

        if (bundle.containsKey(Keys.ITEM_ID)) {
            DownloadEntity downloadEntity = bundle.getParcelable(Keys.ITEM_ID);
            mDownloadEntities = new ArrayList<>();
            mDownloadEntities.add(downloadEntity);
        } else if (bundle.containsKey(Keys.ITEM_LIST_ID)) {
            mDownloadEntities = bundle.getParcelableArrayList(Keys.ITEM_LIST_ID);
        } else {
            mDownloadEntities = new ArrayList<>();
        }
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {

        int themeResId = mIsIncognito
                ? R.style.Theme_FireDown_VaultDialogTheme
                : getTheme(); // or just use the default

        return new MaterialAlertDialogBuilder(requireContext(), themeResId)
                .setTitle(getString(R.string.delete_downloads))
                .setMessage(getString(R.string.delete_all_downloads))
                .setPositiveButton(getString(R.string.delete), (dialog, which) -> {

                    // Delegate to ViewModel → Repository → Service
                    mTaskViewModel.requestDelete(requireContext(), mDownloadEntities);

                    dismissAndClearActionMode();
                })
                .setNegativeButton(getString(R.string.cancel), (dialog, which) -> {
                    dismissAndClearActionMode();
                })
                .create();
    }

    private void dismissAndClearActionMode() {
        NavBackStackEntry navBackStackEntry = mNavController.getPreviousBackStackEntry();
        if (navBackStackEntry != null) {
            navBackStackEntry.getSavedStateHandle().set(IntentActions.ACTION_MODE, null);
        }
        mNavController.popBackStack();
    }
}