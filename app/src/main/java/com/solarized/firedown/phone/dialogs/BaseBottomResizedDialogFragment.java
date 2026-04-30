package com.solarized.firedown.phone.dialogs;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.fragment.app.Fragment;
import androidx.navigation.NavController;
import androidx.navigation.fragment.NavHostFragment;

import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import com.solarized.firedown.BaseActivity;
import com.solarized.firedown.Keys;
import com.solarized.firedown.R;
import com.solarized.firedown.geckoview.GeckoRuntimeHelper;

import javax.inject.Inject;

import dagger.hilt.android.AndroidEntryPoint;

@AndroidEntryPoint
public class BaseBottomResizedDialogFragment extends BottomSheetDialogFragment {

    private static final String TAG = BaseBottomResizedDialogFragment.class.getName();

    private static final double SIZE = 0.95;

    protected BaseActivity mActivity;

    protected View mView;

    protected NavController mNavController;

    protected boolean mIsIncognito;

    @Inject
    protected GeckoRuntimeHelper mGeckoRuntimeHelper;


    @Override
    public int getTheme() {
        return mIsIncognito
                ? R.style.Theme_FireDown_BottomSheetVaultDialogTheme
                : super.getTheme();
    }

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
        mView = null;
        super.onDestroy();
        mNavController = null;
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mIsIncognito = getArguments() != null && getArguments().getBoolean(Keys.IS_INCOGNITO, false);
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
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        ViewCompat.setOnApplyWindowInsetsListener(mView, (v, windowInsets) -> {
            Insets insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars() |
                    WindowInsetsCompat.Type.displayCutout());
            // Apply the insets as a margin to the view. This solution sets only the
            // bottom, left, and right dimensions, but you can apply whichever insets are
            // appropriate to your layout. You can also update the view padding if that's
            // more appropriate.
            v.setPadding(insets.left, 0, insets.right, insets.bottom);
            return WindowInsetsCompat.CONSUMED;
        });

    }

    @Override
    public void onStart() {
        super.onStart();
        DisplayMetrics displayMetrics = new DisplayMetrics();
        mActivity.getWindowManager().getDefaultDisplay().getMetrics(displayMetrics);
        int height = displayMetrics.heightPixels;
        int width = displayMetrics.widthPixels;
        int value = Math.min(width, height);
        Log.d(TAG, "onStart: " + width + " height: " + height);
        if(mView != null){
            BottomSheetBehavior<View> mBottomBehavior = BottomSheetBehavior.from((View) mView.getParent());
            //mBottomBehavior.setMaxWidth((int) (SIZE * Math.min(height, width)));
            mBottomBehavior.setMaxHeight((int) (height * 0.70));
            mBottomBehavior.setState(BottomSheetBehavior.STATE_EXPANDED);
        }
    }

    public void setSessionResult(String url){
        Intent resultIntent = new Intent(Intent.ACTION_VIEW);
        resultIntent.setData(Uri.parse(url));
        mActivity.setResult(Activity.RESULT_OK, resultIntent);
        mActivity.finish();
    }


}
