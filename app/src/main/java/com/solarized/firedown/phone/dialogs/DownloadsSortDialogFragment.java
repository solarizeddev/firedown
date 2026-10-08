package com.solarized.firedown.phone.dialogs;


import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.Nullable;
import androidx.appcompat.widget.AppCompatRadioButton;
import androidx.lifecycle.ViewModelProvider;
import androidx.navigation.NavBackStackEntry;

import com.solarized.firedown.R;
import com.solarized.firedown.data.entity.OptionEntity;
import com.solarized.firedown.IntentActions;
import com.solarized.firedown.Sorting;
import com.solarized.firedown.data.models.DownloadsViewModel;


import dagger.hilt.android.AndroidEntryPoint;


@AndroidEntryPoint
public class DownloadsSortDialogFragment extends BaseBottomSheetDialogFragment implements View.OnClickListener {

    private static final String TAG = DownloadsSortDialogFragment.class.getName();

    private DownloadsViewModel mDownloadsViewModel;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mDownloadsViewModel = new ViewModelProvider(mActivity).get(DownloadsViewModel.class);
    }

    @Nullable
    @Override
    public View onCreateView(LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {

        // get the views and attach the listener
        mView = inflater.inflate(R.layout.fragment_dialog_download_sort, container,
                false);

        AppCompatRadioButton mSortByDateButton = bindCard(R.id.button_sort_filedate_card, R.id.button_sort_filedate);

        AppCompatRadioButton mSortBySizeButton = bindCard(R.id.button_sort_filesize_card, R.id.button_sort_filesize);

        AppCompatRadioButton mSortAlphabet = bindCard(R.id.button_sort_alphabet_card, R.id.button_sort_alphabet);

        AppCompatRadioButton mSortDomain = bindCard(R.id.button_sort_origin_card, R.id.button_sort_origin);

        int mCurrentSortLocalType = mDownloadsViewModel.getCurrentSorting();

        Log.d(TAG, "SharedPreferences SORT_TYPE: " + mCurrentSortLocalType);

        mSortByDateButton.setChecked(mCurrentSortLocalType == Sorting.SORT_DATE);

        mSortBySizeButton.setChecked(mCurrentSortLocalType == Sorting.SORT_SIZE);

        mSortAlphabet.setChecked(mCurrentSortLocalType == Sorting.SORT_ALPHABET);

        mSortDomain.setChecked(mCurrentSortLocalType == Sorting.SORT_DOMAIN);


        return mView;
    }


    /**
     * Each option is a grouped sheet card holding a NON-clickable radio
     * (the card's full surface is the one touch target); a tap on the card
     * checks the radio and dispatches through {@link #onClick} with the
     * RADIO's id, so the id switch below is unchanged.
     */
    private AppCompatRadioButton bindCard(int cardId, int radioId) {
        AppCompatRadioButton radio = mView.findViewById(radioId);
        View card = mView.findViewById(cardId);
        card.setOnClickListener(v -> {
            radio.setChecked(true);
            onClick(radio);
        });
        return radio;
    }


    @Override
    public void onClick(View view) {
        int id = view.getId();
        int type;
        if(id == R.id.button_sort_filedate){
            mDownloadsViewModel.saveCurrentSorting(Sorting.SORT_DATE);
            type = Sorting.SORT_DATE;
        }else if(id == R.id.button_sort_filesize){
            mDownloadsViewModel.saveCurrentSorting(Sorting.SORT_SIZE);
            type = Sorting.SORT_SIZE;
        }else if(id == R.id.button_sort_alphabet){
            mDownloadsViewModel.saveCurrentSorting(Sorting.SORT_ALPHABET);
            type = Sorting.SORT_ALPHABET;
        }else if(id == R.id.button_sort_origin){
            mDownloadsViewModel.saveCurrentSorting(Sorting.SORT_DOMAIN);
            type = Sorting.SORT_DOMAIN;
        }else{
            mDownloadsViewModel.saveCurrentSorting(Sorting.SORT_DATE);
            type = Sorting.SORT_DATE;
        }

        NavBackStackEntry navBackStackEntry = mNavController.getPreviousBackStackEntry();

        if(navBackStackEntry != null){
            OptionEntity optionEntity = new OptionEntity();
            optionEntity.setId(type);
            navBackStackEntry.getSavedStateHandle().set(IntentActions.DOWNLOAD_SORT, optionEntity);
        }

        mNavController.popBackStack();

    }
}