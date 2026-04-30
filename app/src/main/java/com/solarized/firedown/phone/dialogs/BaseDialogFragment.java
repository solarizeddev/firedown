package com.solarized.firedown.phone.dialogs;

import android.app.Activity;
import android.content.Context;
import android.os.Bundle;
import android.view.View;
import android.view.inputmethod.InputMethodManager;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.DialogFragment;
import androidx.navigation.NavController;

import com.solarized.firedown.BaseActivity;
import com.solarized.firedown.Keys;
import com.solarized.firedown.R;
import com.solarized.firedown.data.entity.BrowserDownloadEntity;
import com.solarized.firedown.geckoview.GeckoRuntimeHelper;

import javax.inject.Inject;

import dagger.hilt.android.AndroidEntryPoint;


@AndroidEntryPoint
public abstract class BaseDialogFragment extends DialogFragment {

    private static final String TAG = BaseDialogFragment.class.getName();

    protected BaseActivity mActivity;

    protected NavController mNavController;
    protected boolean mIsIncognito;
    @Inject
    protected GeckoRuntimeHelper mGeckoRuntimeHelper;


    @Override
    public void onAttach(@NonNull Context context) {
        super.onAttach(context);
        if (context instanceof Activity)
            mActivity = (BaseActivity) context;
    }

    @Override
    public void onDetach() {
        super.onDetach();
        mActivity = null;
    }

    @Override
    public void onDestroy(){
        super.onDestroy();
        mNavController = null;
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mIsIncognito = getArguments() != null && getArguments().getBoolean(Keys.IS_INCOGNITO, false);
        mNavController = mActivity.getNavController();
        if(mIsIncognito){
            setStyle(STYLE_NORMAL, R.style.Theme_FireDown_VaultDialogTheme);
        }
    }


    protected void startDownload(BrowserDownloadEntity browserDownloadEntity, View anchorView){

        if(mActivity == null)
            return;

        mActivity.startDownload(browserDownloadEntity, anchorView);


    }

    protected void startDownload(BrowserDownloadEntity browserDownloadEntity, View anchorView, int anchorId){

        if(mActivity == null)
            return;

        mActivity.startDownload(browserDownloadEntity, anchorView, anchorId);


    }



    protected void showKeyboard(View view){
        InputMethodManager inputMethodManager = (InputMethodManager) mActivity.getSystemService(Context.INPUT_METHOD_SERVICE);
        if(inputMethodManager != null) {
            inputMethodManager.showSoftInput(view, 0);
        }
    }


}
