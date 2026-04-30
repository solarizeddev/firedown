package com.solarized.firedown.phone.dialogs;


import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.AppCompatButton;
import androidx.lifecycle.ViewModelProvider;

import com.solarized.firedown.R;
import com.solarized.firedown.data.entity.OptionEntity;
import com.solarized.firedown.data.models.FragmentsOptionsViewModel;
import com.solarized.firedown.phone.fragments.BaseFocusFragment;


public class BrowserOptionHelpFragment extends BaseFocusFragment {


    private FragmentsOptionsViewModel mFragmentsViewModel;


    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        mFragmentsViewModel = new ViewModelProvider(mActivity).get(FragmentsOptionsViewModel.class);

    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {

        // get the views and attach the listener

        LayoutInflater themedInflater = container != null
                ? LayoutInflater.from(container.getContext())
                : inflater;

        View mView = themedInflater.inflate(R.layout.fragment_dialog_browser_options_help, container,
                false);

        AppCompatButton appCompatButton = mView.findViewById(R.id.cancel_button);


        appCompatButton.setOnClickListener(v -> {
            int id = v.getId();
            OptionEntity optionEntity = new OptionEntity();
            optionEntity.setId(id);
            mFragmentsViewModel.onOptionsSelected(optionEntity);
        });

        return mView;

    }



}
