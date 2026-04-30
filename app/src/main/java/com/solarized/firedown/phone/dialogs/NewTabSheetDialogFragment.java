package com.solarized.firedown.phone.dialogs;

import android.annotation.SuppressLint;
import android.content.res.TypedArray;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import androidx.annotation.Nullable;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.RecyclerView;

import com.solarized.firedown.Keys;
import com.solarized.firedown.R;
import com.solarized.firedown.data.OptionItem;
import com.solarized.firedown.data.entity.OptionEntity;
import com.solarized.firedown.data.models.BrowserDialogViewModel;
import com.solarized.firedown.ui.adapters.OptionsAdapter;
import com.solarized.firedown.utils.NavigationUtils;

import java.util.ArrayList;
import java.util.List;


public class NewTabSheetDialogFragment extends BaseBottomResizedDialogFragment implements OptionsAdapter.OnItemClickListener {


    private BrowserDialogViewModel mBrowserDialogViewModel;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mBrowserDialogViewModel = new ViewModelProvider(mActivity).get(BrowserDialogViewModel.class);
    }

    @SuppressLint("InvalidSetHasFixedSize")
    @Nullable
    @Override
    public View onCreateView(LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {

        // get the views and attach the listener
        mView = inflater.inflate(R.layout.fragment_dialog_options, container, false);

        RecyclerView recyclerView = mView.findViewById(R.id.recycler_view);

        List<OptionItem> optionItemList = buildOptionItems();

        OptionsAdapter optionsAdapter = new OptionsAdapter(optionItemList, this, false);

        recyclerView.setAdapter(optionsAdapter);
        recyclerView.setHasFixedSize(true);
        return mView;

    }


    public List<OptionItem> buildOptionItems() {
        TypedArray imgs = getResources().obtainTypedArray(R.array.new_tab_items_icon);
        TypedArray ids = getResources().obtainTypedArray(R.array.new_tab_items_id);
        String[] labels = getResources().getStringArray(R.array.new_tab_options_items);
        List<OptionItem> optionItemList = new ArrayList<>(imgs.length());
        try {
            for (int i = 0; i < labels.length; i++) {
                int id = ids.getResourceId(i, 0);
                int iconResId = imgs.getResourceId(i, R.drawable.ic_draft_24);
                optionItemList.add(new OptionItem(labels[i], iconResId, id));
            }
        } finally {
            imgs.recycle();
            ids.recycle();
        }
        return optionItemList;
    }


    @Override
    public void onItemClick(int position, OptionItem item) {
        if (position == RecyclerView.NO_POSITION)
            return;
        NavigationUtils.popBackStackSafe(mNavController, R.id.dialog_new_tabs);
        int id = item.getLabelRes();
        OptionEntity optionEntity = new OptionEntity();
        optionEntity.setId(id);
        mBrowserDialogViewModel.onOptionSelected(optionEntity);

    }



}
