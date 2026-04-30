package com.solarized.firedown.settings;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.view.ActionMode;
import androidx.fragment.app.Fragment;
import androidx.navigation.NavController;
import androidx.navigation.fragment.NavHostFragment;
import androidx.recyclerview.widget.RecyclerView;

import com.solarized.firedown.BaseActivity;
import com.solarized.firedown.R;
import com.solarized.firedown.ui.LCEERecyclerView;


public class BaseFocusFragment extends Fragment {

    private static final String TAG = BaseFocusFragment.class.getSimpleName();

    protected BaseActivity mActivity;

    protected RecyclerView mRecyclerView;

    protected View mScrollUpView;

    protected LCEERecyclerView mLCEERecyclerView;

    protected NavController mNavController;

    protected ActionMode mActionMode;


    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mNavController = getNavController();
    }

    @NonNull
    public NavController getNavController() {
        Fragment fragment = mActivity.getSupportFragmentManager().findFragmentById(R.id.content_frame);
        if (!(fragment instanceof NavHostFragment)) {
            throw new IllegalStateException("Activity " + this
                    + " does not have a NavHostFragment");
        }
        return ((NavHostFragment) fragment).getNavController();
    }

    @Override
    public void onAttach(@NonNull Context context) {
        super.onAttach(context);
        if(context instanceof BaseActivity)
            mActivity = (BaseActivity) context;
    }

    @Override
    public void onDetach(){
        super.onDetach();
        mActivity = null;
        mNavController = null;
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if(mActionMode != null){
            mActionMode.finish();
            mActionMode = null;
        }
        if(mLCEERecyclerView != null) mLCEERecyclerView.destroyCallbacks();
        mScrollUpView = null;
        mLCEERecyclerView = null;
        mRecyclerView = null;
    }


    public void setSessionResult(String url){
        Intent resultIntent = new Intent(Intent.ACTION_VIEW);
        resultIntent.setData(Uri.parse(url));
        mActivity.setResult(Activity.RESULT_OK, resultIntent);
        mActivity.finish();
    }



}
