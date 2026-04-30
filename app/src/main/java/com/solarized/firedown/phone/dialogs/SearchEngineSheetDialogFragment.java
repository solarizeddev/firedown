package com.solarized.firedown.phone.dialogs;

import android.content.res.TypedArray;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.RadioButton;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.AppCompatImageView;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;
import androidx.recyclerview.widget.SimpleItemAnimator;

import com.solarized.firedown.R;
import com.solarized.firedown.data.entity.AutoCompleteEntity;
import com.solarized.firedown.autocomplete.AutoCompleteViewModel;
import com.solarized.firedown.data.repository.SearchRepository;
import com.solarized.firedown.ui.OnItemClickListener;
import com.solarized.firedown.ui.diffs.SearchDiffCallback;
import com.solarized.firedown.utils.NavigationUtils;


import java.util.ArrayList;
import java.util.List;

import javax.inject.Inject;

import dagger.hilt.android.AndroidEntryPoint;


@AndroidEntryPoint
public class SearchEngineSheetDialogFragment extends BaseBottomResizedDialogFragment implements OnItemClickListener{

    private static final String TAG = SearchEngineSheetDialogFragment.class.getName();

    @Inject
    SearchRepository mSearchRepository;

    private SearchEngineAdapter mSearchEngineAdapter;

    private AutoCompleteViewModel mAutoCompleteViewModel;



    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mAutoCompleteViewModel = new ViewModelProvider(mActivity).get(AutoCompleteViewModel.class);
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        mSearchEngineAdapter = null;
    }



    @Nullable
    @Override
    public View onCreateView(LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {

        // get the views and attach the listener

        mView = inflater.inflate(R.layout.fragment_dialog_search_engine, container,
                false);

        List<AutoCompleteEntity> mList = new ArrayList<>();

        String[] mNameArray = getResources().getStringArray(R.array.settings_search);

        TypedArray imgs = getResources().obtainTypedArray(R.array.settings_search_icon);

        for (int i = 0; i < mNameArray.length; i++) {
            AutoCompleteEntity searchEntity = new AutoCompleteEntity();
            searchEntity.setDrawableId(imgs.getResourceId(i, 0));
            searchEntity.setTitle(mNameArray[i]);
            searchEntity.setUid(i);
            mList.add(searchEntity);
        }

        imgs.recycle();

        RecyclerView recyclerView = mView.findViewById(R.id.recycler_view);

        mSearchEngineAdapter = new SearchEngineAdapter(new SearchDiffCallback(), this, mSearchRepository.getSearchType());

        recyclerView.setAdapter(mSearchEngineAdapter);

        RecyclerView.ItemAnimator animator = recyclerView.getItemAnimator();

        if (animator instanceof SimpleItemAnimator) {
            ((SimpleItemAnimator) animator).setSupportsChangeAnimations(false);
        }

        mSearchEngineAdapter.submitList(mList);

        return mView;


    }




    @Override
    public void onItemClick(int position, int resId) {

        String[] mNameArray = getResources().getStringArray(R.array.settings_search);

        mSearchRepository.setSearchEngine(mNameArray[position]);

        mSearchEngineAdapter.setEngine(mNameArray[position]);

        mAutoCompleteViewModel.resetEngines();

        NavigationUtils.popBackStackSafe(mNavController, R.id.dialog_search_engine);
    }

    @Override
    public void onLongClick(int position, int resId) {

    }

    @Override
    public void onItemVariantClick(int position, int variant, int resId) {

    }


    private static class SearchEngineAdapter extends ListAdapter<AutoCompleteEntity, RecyclerView.ViewHolder> {


        private final OnItemClickListener mOnItemClickListener;

        private String mCurrentEngine;

        static class SearchViewHolder extends RecyclerView.ViewHolder implements View.OnClickListener{


            OnItemClickListener mOnItemClickListener;

            AppCompatImageView icon;

            RadioButton radioButton;

            View radioHolder;


            public SearchViewHolder(@NonNull View itemView, OnItemClickListener onItemClickListener) {
                super(itemView);
                mOnItemClickListener = onItemClickListener;
                icon = itemView.findViewById(R.id.radio_icon);
                radioButton = itemView.findViewById(R.id.radio);
                radioHolder = itemView.findViewById(R.id.radio_holder);
                radioHolder.setOnClickListener(this);
            }


            @Override
            public void onClick(View v) {
                int position = getAbsoluteAdapterPosition();
                if(mOnItemClickListener != null){
                    mOnItemClickListener.onItemClick(position, v.getId());
                }
            }
        }

        protected SearchEngineAdapter(@NonNull DiffUtil.ItemCallback<AutoCompleteEntity> diffCallback, OnItemClickListener onItemClickListener, String currentEngine) {
            super(diffCallback);
            mOnItemClickListener = onItemClickListener;
            mCurrentEngine = currentEngine;
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.fragment_dialog_search_engine_item, parent, false);
            return new SearchViewHolder(view, mOnItemClickListener);
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            AutoCompleteEntity entity = getItem(position);

            SearchViewHolder searchViewHolder = (SearchViewHolder) holder;

            searchViewHolder.icon.setImageResource(entity.getDrawableId());

            searchViewHolder.radioButton.setText(entity.getTitle());

            searchViewHolder.radioButton.setChecked(entity.getTitle().equals(mCurrentEngine));
        }

        public void setEngine(String engine){
            mCurrentEngine = engine;
            notifyItemRangeChanged(0, getItemCount());
        }
    }



}
