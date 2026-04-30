package com.solarized.firedown.settings;

import android.os.Bundle;
import android.text.Html;
import android.text.method.LinkMovementMethod;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;

import androidx.annotation.Nullable;
import androidx.recyclerview.widget.DividerItemDecoration;
import androidx.recyclerview.widget.RecyclerView;

import com.solarized.firedown.R;
import com.solarized.firedown.ui.EqualSpacingItemDecoration;
import com.solarized.firedown.ui.OnItemClickListener;

public class LicenseFragment extends BasePreferenceFragment implements OnItemClickListener{

    private static final String TAG = LicenseFragment.class.getName();


    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        RecyclerView mRecyclerView = getListView();

        String[] mLocalDataSet = getResources().getStringArray(R.array.settings_license);

        LicenseAdapter licenseAdapter = new LicenseAdapter(mLocalDataSet, this);

        mRecyclerView.setAdapter(licenseAdapter);

        mRecyclerView.setHasFixedSize(true);

        mRecyclerView.addItemDecoration(new EqualSpacingItemDecoration(mActivity, R.dimen.list_spacing));

        DividerItemDecoration dividerItemDecoration = new DividerItemDecoration(mActivity,
                DividerItemDecoration.VERTICAL);

        mRecyclerView.addItemDecoration(dividerItemDecoration);
    }

    @Override
    public void onItemClick(int position, int resId) {

    }

    @Override
    public void onLongClick(int position, int resId) {

    }

    @Override
    public void onItemVariantClick(int position, int variant, int resId) {

    }


    private static class LicenseAdapter extends RecyclerView.Adapter<LicenseAdapter.ViewHolder> {

        private final String[] mLocalDataSet;

        private final OnItemClickListener mOnItemClickListener;


        public LicenseAdapter(String[] dataSet, OnItemClickListener onItemClickListener) {
            mLocalDataSet = dataSet;
            mOnItemClickListener = onItemClickListener;
        }

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.fragment_settings_licenses_item, parent, false);

            return new LicenseAdapter.ViewHolder(view, mOnItemClickListener);
        }

        @Override
        public void onBindViewHolder(@NonNull LicenseAdapter.ViewHolder holder, int position) {

            holder.textView.setText(Html.fromHtml(mLocalDataSet[position]));
            holder.textView.setMovementMethod(LinkMovementMethod.getInstance());
        }

        @Override
        public int getItemCount() {
            return mLocalDataSet.length;
        }

        public static class ViewHolder extends RecyclerView.ViewHolder implements View.OnClickListener{
            private final TextView textView;
            private final OnItemClickListener mOnItemClickListener;

            public ViewHolder(View view, OnItemClickListener onItemClickListener) {
                super(view);
                // Define click listener for the ViewHolder's View
                textView = view.findViewById(R.id.text);
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




    }




}