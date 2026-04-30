package com.solarized.firedown.phone.dialogs;


import android.annotation.SuppressLint;
import android.content.res.TypedArray;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import androidx.annotation.Nullable;
import androidx.core.app.ShareCompat;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.RecyclerView;
import com.solarized.firedown.R;
import com.solarized.firedown.data.OptionItem;
import com.solarized.firedown.data.models.WebBookmarkViewModel;
import com.solarized.firedown.data.models.WebHistoryViewModel;
import com.solarized.firedown.Keys;
import com.solarized.firedown.ui.adapters.OptionsAdapter;
import com.solarized.firedown.utils.NavigationUtils;

import java.util.ArrayList;
import java.util.List;


public class WebOptionSheetDialogFragment extends BaseBottomResizedDialogFragment implements OptionsAdapter.OnItemClickListener {

    private WebBookmarkViewModel mWebBookmarkViewModel;

    private WebHistoryViewModel mWebHistoryViewModel;

    private String mCurrentUrl;

    private int mId;

    private boolean mEdit;

    @Override
    public void onCreate(Bundle savedInstanceState){
        super.onCreate(savedInstanceState);

        Bundle bundle = getArguments();

        if(bundle == null)
            throw new IllegalStateException("Bundle can not be Null " + getClass().getSimpleName());

        mId = bundle.getInt(Keys.ITEM_ID, 0);

        mCurrentUrl = bundle.getString(Keys.SHARE_URL, null);

        mEdit = bundle.getBoolean(Keys.EDIT, false);

        mWebBookmarkViewModel = new ViewModelProvider(this).get(WebBookmarkViewModel.class);

        mWebHistoryViewModel = new ViewModelProvider(this).get(WebHistoryViewModel.class);

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

        OptionsAdapter optionsAdapter = new OptionsAdapter(optionItemList, this);

        recyclerView.setAdapter(optionsAdapter);
        recyclerView.setHasFixedSize(true);
        return mView;

    }


    public List<OptionItem> buildOptionItems() {
        TypedArray imgs = getResources().obtainTypedArray(
                mEdit ? R.array.web_options_edit_items_icon : R.array.web_options_items_icon);
        String[] labels = getResources().getStringArray(
                mEdit ? R.array.web_options_edit_items : R.array.web_options_items);
        List<OptionItem> optionItemList = new ArrayList<>(imgs.length());
        try {
            for (int i = 0; i < labels.length; i++) {
                int iconResId = imgs.getResourceId(i, R.drawable.ic_draft_24);
                optionItemList.add(new OptionItem(labels[i], iconResId));
            }
        } finally {
            imgs.recycle();
        }
        return optionItemList;
    }


    @Override
    public void onItemClick(int position, OptionItem item) {
        if (position == RecyclerView.NO_POSITION)
            return;
        int id = item.getIconRes();
        if (id == R.drawable.ic_web_24) {
            setSessionResult(mCurrentUrl);
        } else if (id == R.drawable.ic_baseline_delete_24) {
            mWebBookmarkViewModel.delete(mId);
            mWebHistoryViewModel.delete(mId);
            NavigationUtils.popBackStackSafe(mNavController, R.id.dialog_web_options);
        } else if (id == R.drawable.ic_share_24) {
            new ShareCompat.IntentBuilder(mActivity)
                    .setType("text/plain")
                    .setChooserTitle(getString(R.string.share_url))
                    .setText(mCurrentUrl)
                    .startChooser();
            NavigationUtils.popBackStackSafe(mNavController, R.id.dialog_web_options);
        } else if(id == R.drawable.ic_edit_24){
            Bundle bundle = getArguments();
            NavigationUtils.navigateSafe(mNavController, R.id.web_bookmark_edit, bundle);
        }
    }



}
