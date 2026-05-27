package com.solarized.firedown.phone.dialogs;


import android.annotation.SuppressLint;
import android.app.Dialog;
import android.content.res.TypedArray;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.ShareCompat;
import androidx.lifecycle.ViewModelProvider;
import androidx.navigation.NavOptions;
import androidx.recyclerview.widget.RecyclerView;
import com.solarized.firedown.IntentActions;
import com.solarized.firedown.R;
import com.solarized.firedown.data.OptionItem;
import com.solarized.firedown.data.entity.GeckoStateEntity;
import com.solarized.firedown.data.models.BrowserURIViewModel;
import com.solarized.firedown.data.models.WebBookmarkViewModel;
import com.solarized.firedown.data.models.WebHistoryViewModel;
import com.solarized.firedown.Keys;
import com.solarized.firedown.ui.adapters.OptionsAdapter;
import com.solarized.firedown.utils.NavigationUtils;

import java.util.ArrayList;
import java.util.List;


public class WebOptionSheetDialogFragment extends BaseBottomSheetDialogFragment implements OptionsAdapter.OnItemClickListener {

    private WebBookmarkViewModel mWebBookmarkViewModel;

    private WebHistoryViewModel mWebHistoryViewModel;

    private BrowserURIViewModel mBrowserURIViewModel;

    private String mCurrentUrl;

    private int mId;

    private boolean mEdit;

    private boolean mIncognito;

    private boolean mArgsMissing;

    @Override
    public void onCreate(Bundle savedInstanceState){
        super.onCreate(savedInstanceState);

        Bundle bundle = getArguments();

        if (bundle == null) {
            // Args lost on restore — onCreateDialog dismisses on show.
            mArgsMissing = true;
            return;
        }

        mId = bundle.getInt(Keys.ITEM_ID, 0);

        mCurrentUrl = bundle.getString(Keys.SHARE_URL, null);

        mEdit = bundle.getBoolean(Keys.EDIT, false);

        mIncognito = bundle.getBoolean(Keys.IS_INCOGNITO, false);

        mWebBookmarkViewModel = new ViewModelProvider(this).get(WebBookmarkViewModel.class);

        mWebHistoryViewModel = new ViewModelProvider(this).get(WebHistoryViewModel.class);

        // Activity-scoped so BrowserFragment's OPEN_EXTERNAL_URI observer
        // picks up the event we publish from 'Open in New Tab'.
        mBrowserURIViewModel = new ViewModelProvider(mActivity).get(BrowserURIViewModel.class);

    }

    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
        if (mArgsMissing) {
            Dialog dialog = new Dialog(requireContext());
            dialog.setOnShowListener(d -> dismissAllowingStateLoss());
            return dialog;
        }
        return super.onCreateDialog(savedInstanceState);
    }

    @SuppressLint("InvalidSetHasFixedSize")
    @Nullable
    @Override
    public View onCreateView(LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {

        if (mArgsMissing) return null;

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
            // 'Open in New Tab' — mirrors IntentHandler.handleExternalUri:
            // a fresh GeckoStateEntity flagged external so BrowserFragment
            // spawns a new tab for it, then OPEN_EXTERNAL_URI on the
            // activity-scoped ViewModel + navigate to browser popping back
            // to home so the bookmark / history surface doesn't linger.
            // Old flow used setResult(ACTION_VIEW) + finish on
            // BookmarkActivity / HistoryActivity, which closed the whole
            // app once those Activities were dropped.
            // GeckoStateEntity(boolean, String) is the (home, uri)
            // constructor — not incognito. We're loading a real URL, so
            // home=false; incognito is set explicitly below.
            GeckoStateEntity geckoStateEntity = new GeckoStateEntity(false, mCurrentUrl);
            geckoStateEntity.setIncognito(mIncognito);
            geckoStateEntity.setExternal(true);
            mBrowserURIViewModel.onEventSelected(geckoStateEntity, IntentActions.OPEN_EXTERNAL_URI);
            NavOptions navOptions = new NavOptions.Builder()
                    .setPopUpTo(mIncognito ? R.id.home_incognito : R.id.home, false)
                    .build();
            NavigationUtils.navigateSafe(mNavController, R.id.browser, null, navOptions);
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
