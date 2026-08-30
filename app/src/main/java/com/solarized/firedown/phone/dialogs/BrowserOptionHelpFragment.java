package com.solarized.firedown.phone.dialogs;


import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.solarized.firedown.R;
import com.solarized.firedown.phone.fragments.BaseFocusFragment;


public class BrowserOptionHelpFragment extends BaseFocusFragment {


    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {

        // get the views and attach the listener

        LayoutInflater themedInflater = container != null
                ? LayoutInflater.from(container.getContext())
                : inflater;

        // No close button any more — system Back pops the page (the holder's
        // OnBackPressedCallback) and swipe-down dismisses the sheet.
        View mView = themedInflater.inflate(R.layout.fragment_dialog_browser_options_help, container,
                false);

        return mView;

    }



}
