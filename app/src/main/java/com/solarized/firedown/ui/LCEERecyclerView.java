package com.solarized.firedown.ui;

import android.content.Context;
import android.content.res.Resources;
import android.util.AttributeSet;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.AppCompatImageView;
import androidx.constraintlayout.widget.ConstraintLayout;
import androidx.core.widget.NestedScrollView;
import androidx.recyclerview.widget.RecyclerView;
import androidx.recyclerview.widget.SimpleItemAnimator;

import com.google.android.material.button.MaterialButton;
import com.solarized.firedown.R;
import com.solarized.firedown.ui.adapters.WrapContentLinearLayoutManager;

public class LCEERecyclerView extends FrameLayout implements View.OnClickListener{

    private static final String TAG = LCEERecyclerView.class.getSimpleName();

    private final Resources mResources;

    private final RecyclerView mRecyclerView;

    private final LinearLayout mLoadingView;

    private final NestedScrollView mEmptyView;

    private final MaterialButton mEmptyButton;

    private final TextView mEmptyTextView;

    private final TextView mEmptySubTextView;

    private final View mDimView;

    private final AppCompatImageView mEmptyImageView;

    private static final int DELAY = 250;

    private OnButtonListener mEmptyCallback;

    public LCEERecyclerView(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);

        mResources = context.getResources();

        LayoutInflater inflater = (LayoutInflater) context.getSystemService(Context.LAYOUT_INFLATER_SERVICE);

        View v = inflater.inflate(R.layout.recycler_lcee_layout, this, true);

        mDimView = v.findViewById(R.id.dim_view);

        mRecyclerView = v.findViewById(R.id.recycler_view);

        mEmptyView = v.findViewById(R.id.empty_view);

        mLoadingView = v.findViewById(R.id.loading_view);

        mEmptyTextView = mEmptyView.findViewById(R.id.empty_text);

        mEmptySubTextView = mEmptyView.findViewById(R.id.empty_sub_text);

        mEmptyButton = mEmptyView.findViewById(R.id.empty_button);

        mEmptyImageView = mEmptyView.findViewById(R.id.empty_image);

        mRecyclerView.setOverScrollMode(RecyclerView.OVER_SCROLL_ALWAYS);

        if(mRecyclerView.getLayoutManager() == null)
            mRecyclerView.setLayoutManager(new WrapContentLinearLayoutManager(context));

        mEmptyButton.setOnClickListener(this);

        disableChangeAnimations();

        showLoading();
    }


    public void destroyCallbacks(){
        removeCallbacks(showEmptyRunnable);
    }

    @Override
    public void onClick(View view) {
        int id = view.getId();
        if(mEmptyCallback != null) mEmptyCallback.onClick(id);
    }

    public void hideDimView(){
        mDimView.setVisibility(View.GONE);
    }

    public void showDimView(){
        mDimView.setVisibility(View.VISIBLE);
    }

    public void setEmptyImageViewLayout(int resWidth, int resHeight){
        ConstraintLayout.LayoutParams layoutParams = (ConstraintLayout.LayoutParams) mEmptyImageView.getLayoutParams();
        layoutParams.width = resWidth > 0 ? mResources.getDimensionPixelSize(resWidth) : resWidth;
        layoutParams.height = resHeight > 0 ? mResources.getDimensionPixelSize(resHeight) : resHeight;
        mEmptyImageView.setLayoutParams(layoutParams);
        mEmptyImageView.requestLayout();
    }

    public void setEmptyImageView(int restImage){
        mEmptyImageView.setImageResource(restImage);
    }


    public void setEmptyButtonText(int resText){
        mEmptyButton.setText(resText);
    }

    public void setEmptyButtonVisibility(int visibility){
        mEmptyButton.setVisibility(visibility);
    }


    public void setButtonListener(OnButtonListener callback){
        mEmptyCallback = callback;
    }

    public RecyclerView getRecyclerView(){
        return mRecyclerView;
    }

    public void disableChangeAnimations(){
        //mRecyclerView.setItemAnimator(new NoBlinkingAnimator());
        RecyclerView.ItemAnimator animator = mRecyclerView.getItemAnimator();
        if (animator instanceof SimpleItemAnimator) {
            ((SimpleItemAnimator) animator).setSupportsChangeAnimations(false);
        }
    }

    public void hideAll() {
        removeCallbacks(showEmptyRunnable);
        mRecyclerView.setVisibility(VISIBLE);
        mEmptyView.setVisibility(GONE);
        mLoadingView.setVisibility(GONE);
    }

    public void showLock(){
        mEmptyView.setVisibility(GONE);
        mLoadingView.setVisibility(GONE);
        mRecyclerView.setVisibility(View.GONE);
    }


    public void showEmpty() {
        Log.d(TAG, "showEmpty");
        removeCallbacks(showEmptyRunnable);
        postDelayed(showEmptyRunnable, DELAY);
    }


    public void showLoading() {
        Log.d(TAG, "showLoading");
        removeCallbacks(showEmptyRunnable);
        mRecyclerView.setVisibility(View.GONE);
        mEmptyView.setVisibility(GONE);
        mLoadingView.setVisibility(VISIBLE);
    }

    public void setEmptyTextColor(int color) {
        mEmptyTextView.setTextColor(color);
    }

    public void setEmptySubTextColor(int color) {
        mEmptySubTextView.setTextColor(color);
    }

    public void setEmptyText(int resText){
        mEmptyTextView.setText(resText);
    }

    public void setEmptySubText(int resText){
        mEmptySubTextView.setText(resText);
    }

    public void setEmptySubTextVisibility(int visibility){
        mEmptySubTextView.setVisibility(visibility);
    }

    Runnable showEmptyRunnable = new Runnable() {
        @Override
        public void run() {
            mRecyclerView.setVisibility(GONE);
            mEmptyView.setVisibility(VISIBLE);
            mLoadingView.setVisibility(GONE);
        }
    };

}
