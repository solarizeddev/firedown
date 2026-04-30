package com.solarized.firedown.phone.dialogs;


import android.annotation.SuppressLint;
import android.content.res.TypedArray;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.RecyclerView;

import com.solarized.firedown.IntentActions;
import com.solarized.firedown.Keys;
import com.solarized.firedown.R;
import com.solarized.firedown.data.OptionItem;
import com.solarized.firedown.data.entity.GeckoStateEntity;
import com.solarized.firedown.data.entity.ShortCutsEntity;
import com.solarized.firedown.data.models.BrowserURIViewModel;
import com.solarized.firedown.data.models.GeckoStateViewModel;
import com.solarized.firedown.data.models.ShortCutsViewModel;
import com.solarized.firedown.geckoview.GeckoState;
import com.solarized.firedown.ui.adapters.OptionsAdapter;
import com.solarized.firedown.utils.NavigationUtils;
import com.solarized.firedown.utils.WebUtils;

import java.util.ArrayList;
import java.util.List;

public class ShortCutsOptionSheetDialogFragment extends BaseBottomResizedDialogFragment {

    private ShortCutsViewModel mShortCutsViewModel;

    private GeckoStateViewModel mGeckoStateViewModel;

    private BrowserURIViewModel mBrowserURIViewModel;

    private ShortCutsEntity mShortCutsEntity;


    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Bundle bundle = getArguments();

        if(bundle == null){
            throw new IllegalStateException("Bundle can not be Null " + getClass().getSimpleName());
        }

        mShortCutsViewModel = new ViewModelProvider(this).get(ShortCutsViewModel.class);

        mGeckoStateViewModel = new ViewModelProvider(this).get(GeckoStateViewModel.class);

        mBrowserURIViewModel = new ViewModelProvider(mActivity).get(BrowserURIViewModel.class);

        mShortCutsEntity = bundle.getParcelable(Keys.ITEM_ID);

        assert mShortCutsEntity != null;

    }

    @SuppressLint("InvalidSetHasFixedSize")
    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {

        mView = inflater.inflate(R.layout.fragment_dialog_options, container, false);

        RecyclerView recyclerView = mView.findViewById(R.id.recycler_view);

        List<OptionItem> items = loadOptionItems();

        OptionsAdapter optionsAdapter = new OptionsAdapter(items, (position, item) -> {
            if (position == RecyclerView.NO_POSITION)
                return;
            int id = item.getIconRes();
            if (id == R.drawable.ic_web_24) {
                String url = WebUtils.getSchemeDomainName(mShortCutsEntity.getUrl());
                GeckoState geckoState = mGeckoStateViewModel.getCurrentGeckoState();
                GeckoStateEntity geckoStateEntity = geckoState.getGeckoStateEntity();
                geckoStateEntity.setHome(false);
                geckoStateEntity.setUri(url);
                mBrowserURIViewModel.onEventSelected(geckoStateEntity, IntentActions.OPEN_URI);
                NavigationUtils.navigateSafe(mNavController, R.id.browser);
            } else if (id == R.drawable.ic_baseline_delete_24) {
                mShortCutsViewModel.delete(mShortCutsEntity);
                NavigationUtils.popBackStackSafe(mNavController, R.id.dialog_shortcuts_options);
            } else if (id == R.drawable.ic_edit_24) {
                NavigationUtils.navigateSafe(mNavController, R.id.action_shortcut_to_edit, getArguments());
            }
        });

        recyclerView.setAdapter(optionsAdapter);

        // 4. Performance: If content doesn't change size, this improves layout speed
        recyclerView.setHasFixedSize(true);

        return mView;
    }

    private List<OptionItem> loadOptionItems() {
        String[] labels = getResources().getStringArray(R.array.shortcut_options_items);
        TypedArray icons = getResources().obtainTypedArray(R.array.shortcut_options_items_icon);

        List<OptionItem> items = new ArrayList<>();

        assert labels.length == icons.length();

        int count = Math.min(labels.length, icons.length());

        for (int i = 0; i < count; i++) {
            items.add(new OptionItem(
                    labels[i],
                    icons.getResourceId(i, R.drawable.ic_draft_24)
            ));
        }

        icons.recycle();
        return items;
    }



}
