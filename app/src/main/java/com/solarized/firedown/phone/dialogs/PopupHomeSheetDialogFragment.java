package com.solarized.firedown.phone.dialogs;

import android.annotation.SuppressLint;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import androidx.annotation.Nullable;
import androidx.lifecycle.ViewModelProvider;
import androidx.preference.PreferenceManager;
import androidx.recyclerview.widget.RecyclerView;

import com.solarized.firedown.Keys;
import com.solarized.firedown.Preferences;
import com.solarized.firedown.R;
import com.solarized.firedown.data.entity.OptionEntity;
import com.solarized.firedown.data.models.BrowserDialogViewModel;
import com.solarized.firedown.data.OptionItem;
import com.solarized.firedown.ui.adapters.OptionsAdapter;
import com.solarized.firedown.utils.NavigationUtils;

import java.util.ArrayList;
import java.util.List;

import android.content.res.TypedArray;

public class PopupHomeSheetDialogFragment extends BaseBottomResizedDialogFragment
        implements OptionsAdapter.OnItemClickListener {

    private BrowserDialogViewModel mBrowserDialogViewModel;

    private boolean mIsIncognito;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mBrowserDialogViewModel = new ViewModelProvider(mActivity).get(BrowserDialogViewModel.class);
        mIsIncognito = getArguments() != null && getArguments().getBoolean(Keys.IS_INCOGNITO, false);
    }

    @SuppressLint("InvalidSetHasFixedSize")
    @Nullable
    @Override
    public View onCreateView(LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        mView = inflater.inflate(R.layout.fragment_dialog_home_popup, container, false);

        RecyclerView recyclerView = mView.findViewById(R.id.recycler_view);

        List<OptionItem> optionItemList = buildOptionItems();

        OptionsAdapter optionsAdapter = new OptionsAdapter(optionItemList, this, false);
        recyclerView.setAdapter(optionsAdapter);
        recyclerView.setHasFixedSize(true);
        return mView;
    }

    @Override
    public void onItemClick(int position, OptionItem item) {
        if (position == RecyclerView.NO_POSITION) return;

        OptionEntity optionEntity = new OptionEntity();
        optionEntity.setId(item.getIconRes());

        NavigationUtils.popBackStackSafe(mNavController, R.id.dialog_home_popup);
        mBrowserDialogViewModel.onOptionSelected(optionEntity);
    }

    private List<OptionItem> buildOptionItems() {
        boolean quitEnabled = PreferenceManager.getDefaultSharedPreferences(mActivity)
                .getBoolean(Preferences.SETTINGS_QUIT_PREF, false);
        TypedArray imgs = getResources().obtainTypedArray(
                quitEnabled ? R.array.popup_home_quit_icon : R.array.popup_home_icon);
        String[] labels = getResources().getStringArray(
                quitEnabled ? R.array.popup_home_quit_text : R.array.popup_home_text);

        List<OptionItem> items = new ArrayList<>(labels.length);
        try {
            for (int i = 0; i < labels.length; i++) {
                int iconResId;
                String label;
                if (i == 0 && mIsIncognito) {
                    iconResId = R.drawable.download_24;       // your downloads drawable
                    label = getString(R.string.navigation_downloads); // your downloads string
                } else {
                    iconResId = imgs.getResourceId(i, R.drawable.ic_draft_24);
                    label = labels[i];
                }
                items.add(new OptionItem(label, iconResId));
            }
        } finally {
            imgs.recycle();
        }
        return items;
    }
}