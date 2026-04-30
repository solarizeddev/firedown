package com.solarized.firedown.phone.dialogs;

import android.app.Dialog;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.ViewModelProvider;
import androidx.navigation.NavBackStackEntry;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.solarized.firedown.R;
import com.solarized.firedown.data.entity.TabStateArchivedEntity;
import com.solarized.firedown.data.models.TabsArchiveViewModel;
import com.solarized.firedown.IntentActions;
import com.solarized.firedown.Keys;

import java.util.ArrayList;

public class DeleteTabsDialogFragment extends BaseDialogFragment {

    private TabsArchiveViewModel mTabsArchiveViewModel;

    private ArrayList<TabStateArchivedEntity> mList;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mTabsArchiveViewModel = new ViewModelProvider(this).get(TabsArchiveViewModel.class);
        Bundle bundle = getArguments();
        if(bundle != null) mList = bundle.getParcelableArrayList(Keys.ITEM_LIST_ID);
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
        return new MaterialAlertDialogBuilder(requireContext())
                .setTitle(getString(R.string.delete_tabs_archive))
                .setMessage(getString(mList != null ? R.string.delete_partial_tabs : R.string.delete_all_tabs ))
                .setPositiveButton(getString(R.string.delete), (dialog, which) -> {
                    if(mList != null){
                        for(TabStateArchivedEntity tabStateArchivedEntity : mList){
                            mTabsArchiveViewModel.delete(tabStateArchivedEntity);
                        }
                    }else{
                        mTabsArchiveViewModel.deleteAll();
                    }
                    NavBackStackEntry navBackStackEntry = mNavController.getPreviousBackStackEntry();

                    if(navBackStackEntry != null){
                        navBackStackEntry.getSavedStateHandle().set(IntentActions.ACTION_MODE, null);
                    }

                    mNavController.popBackStack();
                } )
                .setNegativeButton(getString(R.string.cancel), (dialog, which) -> {
                    NavBackStackEntry navBackStackEntry = mNavController.getPreviousBackStackEntry();

                    if(navBackStackEntry != null){
                        navBackStackEntry.getSavedStateHandle().set(IntentActions.ACTION_MODE, null);
                    }

                    mNavController.popBackStack();
                } )
                .create();
    }

}
