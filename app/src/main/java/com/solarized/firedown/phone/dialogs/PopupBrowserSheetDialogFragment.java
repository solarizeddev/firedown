package com.solarized.firedown.phone.dialogs;


import android.content.res.TypedArray;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.ViewModelProvider;
import androidx.preference.PreferenceManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.materialswitch.MaterialSwitch;
import com.solarized.firedown.Keys;
import com.solarized.firedown.Preferences;
import com.solarized.firedown.R;
import com.solarized.firedown.data.entity.OptionEntity;
import com.solarized.firedown.data.models.BrowserDialogViewModel;
import com.solarized.firedown.data.models.GeckoStateViewModel;
import com.solarized.firedown.data.models.IncognitoStateViewModel;
import com.solarized.firedown.geckoview.GeckoState;
import com.solarized.firedown.ui.OnItemClickListener;
import com.solarized.firedown.ui.browser.BackwardBrowserButton;
import com.solarized.firedown.ui.browser.BasicBrowserButton;
import com.solarized.firedown.ui.browser.ForwardBrowserButton;
import com.solarized.firedown.ui.browser.ReloadBrowserButton;
import com.solarized.firedown.utils.NavigationUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class PopupBrowserSheetDialogFragment extends BaseBottomResizedDialogFragment implements OnItemClickListener, View.OnClickListener {

    private BrowserDialogViewModel mBrowserDialogViewModel;
    private boolean mHasBookmark;
    private boolean mHasShortCut;
    private ReloadBrowserButton mReloadBrowserButton;
    private CustomAdapter mCustomAdapter;
    private GeckoState mGeckoState;

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        mCustomAdapter = null;
        mReloadBrowserButton = null;
    }


    @Nullable
    @Override
    public View onCreateView(LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {

        // get the views and attach the listener



        List<Integer> mLocalDrawables = new ArrayList<>();
        List<Integer> mLocalIdsSet = new ArrayList<>();

        mView = inflater.inflate(R.layout.fragment_dialog_browser_popup, container,
                false);

        // Guard: peekCurrentGeckoState can return null if the popup was
        // opened in an inconsistent state (process restoration, tab
        // closed externally). Dismiss rather than NPE.
        if (mGeckoState == null) {
            NavigationUtils.popBackStackSafe(mNavController, R.id.dialog_browser_popup);
            return mView;
        }

        View headerView = mView.findViewById(R.id.popup_header);


        for (int i = 0; i < ((ViewGroup)headerView).getChildCount(); i++) {
            View v = ((ViewGroup) headerView).getChildAt(i);
            if (v instanceof BasicBrowserButton) {
                v.setOnClickListener(this);
                if(v instanceof ReloadBrowserButton){
                    mReloadBrowserButton = (ReloadBrowserButton) v;
                }else if(v instanceof BackwardBrowserButton backwardBrowserButton){
                    backwardBrowserButton.setClickable(mGeckoState.canGoBackward());
                    backwardBrowserButton.setEnabled(mGeckoState.canGoBackward());
                }else if(v instanceof ForwardBrowserButton forwardBrowserButton){
                    forwardBrowserButton.setClickable(mGeckoState.canGoForward());
                    forwardBrowserButton.setEnabled(mGeckoState.canGoForward());
                }
            }
        }

        boolean isDesktop = mGeckoState.isDesktop();

        boolean quitEnabled =  PreferenceManager.getDefaultSharedPreferences(mActivity).getBoolean(Preferences.SETTINGS_QUIT_PREF, false);

        RecyclerView recyclerView = mView.findViewById(R.id.recycler_view);

        TypedArray imgs = getResources().obtainTypedArray(R.array.popup_browser_icon);

        for(int i = 0; i < imgs.length(); i++){
            mLocalDrawables.add(imgs.getResourceId(i, R.drawable.ic_draft_24));
        }

        imgs.recycle();

        TypedArray ids = getResources().obtainTypedArray(R.array.popup_browser_ids);

        for(int i = 0; i < imgs.length(); i++){
            mLocalIdsSet.add(imgs.getResourceId(i, R.drawable.ic_draft_24));
        }

        ids.recycle();

        String[] stringArray = getResources().getStringArray(R.array.popup_browser_text);

        List<String> mLocalStrings = new ArrayList<>(Arrays.asList(stringArray));

        if(quitEnabled){
            mLocalDrawables.add(R.drawable.ic_logout_24);
            mLocalIdsSet.add(R.id.popup_quit);
            mLocalStrings.add(getString(R.string.delete_browsing_data_on_quit_action));
        }

        if(mHasBookmark){
            int index = mLocalIdsSet.indexOf(R.id.popup_bookmark_add);
            if(index >= 0){
                mLocalDrawables.set(index, R.drawable.ic_bookmark_24);
                mLocalIdsSet.set(index, R.id.popup_bookmark_edit);
                mLocalStrings.set(index, getString(R.string.browser_menu_edit_bookmark));
            }
        }

        if(mIsIncognito){
            int index = mLocalIdsSet.indexOf(R.id.popup_vault);
            if(index >= 0){
                mLocalDrawables.set(index, R.drawable.download_24);
                mLocalIdsSet.set(index, R.id.popup_downloads);
                mLocalStrings.set(index, getString(R.string.navigation_downloads));
            }
        }

        if(mHasShortCut){
            int index = mLocalIdsSet.indexOf(R.id.popup_pin_add);
            if(index >= 0){
                mLocalDrawables.set(index, R.drawable.ic_keep_off_24);
                mLocalIdsSet.set(index, R.id.popup_pin_edit);
                mLocalStrings.set(index, getString(R.string.browser_menu_remove_from_shortcuts));
            }
        }

        mCustomAdapter = new CustomAdapter(mLocalStrings, mLocalDrawables, mLocalIdsSet, this, isDesktop);

        recyclerView.setAdapter(mCustomAdapter);

        return mView;

    }



    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        //Change the reload button on the bottomsheet
        mBrowserDialogViewModel.getLoadingEvent().observe(getViewLifecycleOwner(), mObservableLoadingEvent ->{
            if(mReloadBrowserButton != null){
                mReloadBrowserButton.setLoading(mObservableLoadingEvent);
            }
        });
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Bundle bundle = getArguments();

        if(bundle == null)
            throw new IllegalArgumentException("Bundle can not be null");

        mHasBookmark = bundle.getBoolean(Keys.ITEM_BOOKMARK, false);
        mHasShortCut = bundle.getBoolean(Keys.ITEM_SHORTCUT, false);
        mBrowserDialogViewModel = new ViewModelProvider(mActivity).get(BrowserDialogViewModel.class);

        GeckoStateViewModel geckoStateViewModel =  new ViewModelProvider(mActivity).get(GeckoStateViewModel.class);

        IncognitoStateViewModel incognitoStateViewModel = new ViewModelProvider(mActivity).get(IncognitoStateViewModel.class);

        if(mIsIncognito){
            mGeckoState = incognitoStateViewModel.peekCurrentGeckoState();
        }else{
            mGeckoState = geckoStateViewModel.peekCurrentGeckoState();
        }


    }

    @Override
    public void onItemClick(int position, int resId) {
        if (position == RecyclerView.NO_POSITION)
            return;

        int id = mCustomAdapter.getResourceId(position);

        OptionEntity optionEntity = new OptionEntity();

        optionEntity.setId(id);

        NavigationUtils.popBackStackSafe(mNavController, R.id.dialog_browser_popup);

        mBrowserDialogViewModel.onOptionSelected(optionEntity);

    }

    @Override
    public void onLongClick(int position, int resId) {

    }

    @Override
    public void onItemVariantClick(int position, int variant, int resId) {

    }

    @Override
    public void onClick(View view) {
        int id = view.getId();

        OptionEntity optionEntity = new OptionEntity();

        optionEntity.setId(id);

        NavigationUtils.popBackStackSafe(mNavController, R.id.dialog_browser_popup);

        mBrowserDialogViewModel.onOptionSelected(optionEntity);
    }


    private static class CustomAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

        private static final int ITEM = 0;

        private static final int SWITCH = 1;

        private static final int FINAL = 2;

        private final OnItemClickListener mOnItemClickListener;

        private final List<String> mLocalDataSet;

        private final List<Integer> mLocalIconSet;

        private final List<Integer> mLocalIdsSet;

        private final boolean mIsDesktop;


        /**
         * Provide a reference to the type of views that you are using
         * (custom ViewHolder).
         */
        public static class ItemViewHolder extends RecyclerView.ViewHolder implements View.OnClickListener{
            private final TextView textView;
            private final OnItemClickListener mOnItemClickListener;

            public ItemViewHolder(View view, OnItemClickListener onItemClickListener) {
                super(view);
                // Define click listener for the ViewHolder's View
                textView = view.findViewById(R.id.item_options);
                textView.setOnClickListener(this);
                mOnItemClickListener = onItemClickListener;
            }

            @Override
            public void onClick(View view) {
                int position = getAbsoluteAdapterPosition();
                if(mOnItemClickListener != null){
                    mOnItemClickListener.onItemClick(position, view.getId());
                }
            }

        }


        public static class ItemSwitchViewHolder extends RecyclerView.ViewHolder implements View.OnClickListener{

            private final TextView textView;
            private final MaterialSwitch mSwitchView;
            private final OnItemClickListener mOnItemClickListener;

            public ItemSwitchViewHolder(View view, OnItemClickListener onItemClickListener) {
                super(view);
                // Define click listener for the ViewHolder's View
                View itemView = view.findViewById(R.id.item_options_switch);
                mSwitchView = view.findViewById(R.id.item_options_switch_button);
                textView = view.findViewById(R.id.item_options);
                mSwitchView.setOnClickListener(this);
                itemView.setOnClickListener(this);
                mOnItemClickListener = onItemClickListener;
            }

            @Override
            public void onClick(View view) {
                int position = getAbsoluteAdapterPosition();
                if(mOnItemClickListener != null){
                    mOnItemClickListener.onItemClick(position, view.getId());
                }
            }

        }


        /**
         * Initialize the dataset of the Adapter.
         *
         * @param stringSet List<String></String> containing the data to populate views to be used
         * by RecyclerView.
         */
        public CustomAdapter(List<String> stringSet, List<Integer> iconSet, List<Integer> idSet, OnItemClickListener onItemClickListener, boolean isDesktop) {
            mLocalDataSet = stringSet;
            mLocalIconSet = iconSet;
            mLocalIdsSet = idSet;
            mIsDesktop = isDesktop;
            mOnItemClickListener = onItemClickListener;
        }

        // Create new views (invoked by the layout manager)
        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup viewGroup, int viewType) {
            // Create a new view, which defines the UI of the list item
            if(viewType == ITEM){
                View view = LayoutInflater.from(viewGroup.getContext())
                        .inflate(R.layout.fragment_dialog_options_item, viewGroup, false);
                return new ItemViewHolder(view, mOnItemClickListener);
            }else if(viewType == FINAL){
                View view = LayoutInflater.from(viewGroup.getContext())
                        .inflate(R.layout.fragment_dialog_options_item_final, viewGroup, false);
                return new ItemViewHolder(view, mOnItemClickListener);
            }else{
                View view = LayoutInflater.from(viewGroup.getContext())
                        .inflate(R.layout.fragment_dialog_options_item_switch, viewGroup, false);
                return new ItemSwitchViewHolder(view, mOnItemClickListener);
            }
        }

        @Override
        public int getItemViewType(int position) {
            if(mLocalIconSet.get(position) == R.drawable.ic_computer_24){
                return SWITCH;
            }else if(mLocalIconSet.get(position) == R.drawable.ic_logout_24){
                return FINAL;
            }else{
                return ITEM;
            }
        }

        // Replace the contents of a view (invoked by the layout manager)
        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder viewHolder, final int position) {

            // Get element from your dataset at this position and replace the
            // contents of the view with that element
            //viewHolder.textView.setEnabled(FileUriHelper.isVideo(mMimeType));
            if(viewHolder instanceof ItemViewHolder itemViewHolder){
                itemViewHolder.textView.setCompoundDrawablesRelativeWithIntrinsicBounds(mLocalIconSet.get(position), 0, 0, 0);
                itemViewHolder.textView.setText(mLocalDataSet.get(position));
            }else if(viewHolder instanceof ItemSwitchViewHolder itemSwitchViewHolder){
                itemSwitchViewHolder.mSwitchView.setChecked(mIsDesktop);
                itemSwitchViewHolder.textView.setCompoundDrawablesRelativeWithIntrinsicBounds(mLocalIconSet.get(position), 0, 0, 0);
                itemSwitchViewHolder.textView.setText(mLocalDataSet.get(position));
            }


        }

        @Override
        public int getItemCount() {
            return mLocalDataSet.size();
        }

        public int getResourceId(int position){
            return mLocalIdsSet.get(position);
        }


    }

}