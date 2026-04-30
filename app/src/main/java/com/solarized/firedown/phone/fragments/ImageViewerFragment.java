package com.solarized.firedown.phone.fragments;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.ActionBar;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.fragment.app.Fragment;
import com.bumptech.glide.Glide;
import com.bumptech.glide.load.DataSource;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.load.engine.GlideException;
import com.bumptech.glide.request.RequestListener;
import com.bumptech.glide.request.RequestOptions;
import com.bumptech.glide.request.target.Target;
import com.google.android.material.progressindicator.CircularProgressIndicator;
import com.google.android.material.snackbar.Snackbar;
import com.solarized.firedown.App;
import com.solarized.firedown.GlideRequestOptions;
import com.solarized.firedown.phone.PlayerActivity;
import com.solarized.firedown.R;
import com.solarized.firedown.data.entity.DownloadEntity;
import com.solarized.firedown.ui.ZoomableImageView;
import com.solarized.firedown.utils.FileUriHelper;
import com.solarized.firedown.Keys;

public class ImageViewerFragment extends Fragment {

    private static final String TAG = ImageViewerFragment.class.getSimpleName();

    private static final int GIF_DELAY = 1500;

    private DownloadEntity mDownloadEntity;

    private PlayerActivity mActivity;

    private ZoomableImageView mPhotoView;

    private CircularProgressIndicator mProgress;

    private Handler mHandler;

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        mPhotoView= null;
        mProgress = null;
    }

    @Override
    public void onAttach(@NonNull Context context) {
        super.onAttach(context);
        if (context instanceof PlayerActivity)
            mActivity = (PlayerActivity) context;
    }

    @Override
    public void onDetach() {
        super.onDetach();
        mActivity = null;
    }


    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        mHandler = new Handler();

        Bundle bundle = getArguments();

        if (bundle == null)
            throw new IllegalArgumentException();

        mDownloadEntity = bundle.getParcelable(Keys.ITEM_ID);


    }

    @Override
    public void onStop() {
        super.onStop();
        mHandler.removeCallbacksAndMessages(null);
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {

        postponeEnterTransition();

        Log.d(TAG, "onCreateView");
        // Inflate the layout for this fragment
        View v = inflater.inflate(R.layout.fragment_image_viewer, container, false);

        mProgress = v.findViewById(R.id.photo_progress);

        mPhotoView = v.findViewById(R.id.photo_view);

        ViewCompat.setTransitionName(mPhotoView, "image_view");

        WindowInsetsControllerCompat windowInsetsController = WindowCompat.getInsetsController(mActivity.getWindow(), mActivity.getWindow().getDecorView());

        // Configure the behavior of the hidden system bars.
        windowInsetsController.setSystemBarsBehavior(
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        );

        mPhotoView.setOnClickListener(v1 -> {
            View decorView = mActivity.getWindow().getDecorView();
            // Hide the status bar.
            int uiOptions = View.SYSTEM_UI_FLAG_FULLSCREEN;
            decorView.setSystemUiVisibility(uiOptions);
            // Remember that you should never show the action bar if the
            // status bar is hidden, so hide that too if necessary.
            ActionBar actionBar = mActivity.getSupportActionBar();

            if(actionBar != null){

               // actionBar.setBackgroundDrawable(new ColorDrawable(ContextCompat.getColor(mActivity, R.color.black_black_transparent)));

                if(actionBar.isShowing()){
                    windowInsetsController.hide(WindowInsetsCompat.Type.systemBars());
                    actionBar.hide();
                }else{
                    windowInsetsController.show(WindowInsetsCompat.Type.systemBars());
                    actionBar.show();
                }
            }
        });


        return v;

    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        String filePath = mDownloadEntity.getFilePath();

        String mimeType = mDownloadEntity.getFileMimeType();

        long interval = mDownloadEntity.getThumbnailDuration();

        if(FileUriHelper.isGIF(mimeType)) {
            mProgress.setVisibility(View.VISIBLE);
            mPhotoView.setVisibility(View.GONE);
            mHandler.postDelayed(() -> {
                RequestOptions options = new RequestOptions().frame(interval)
                        .set(GlideRequestOptions.MIMETYPE, mimeType).set(GlideRequestOptions.FILEPATH, filePath);
                Glide.with(App.getAppContext())
                        .load(filePath)
                        .apply(options)
                        .listener(mRequestListener)
                        .fallback(R.drawable.ic_baseline_image_24)
                        .error(R.drawable.ic_baseline_image_24)
                        .into(mPhotoView);
                mPhotoView.setVisibility(View.VISIBLE);
                mProgress.setVisibility(View.GONE);
            }, GIF_DELAY);
        }else if ((FileUriHelper.isSVG(mimeType)  ||
                FileUriHelper.isWEP(mimeType)) && !mDownloadEntity.isFileEncrypted()) {
            RequestOptions options = new RequestOptions().frame(interval)
                    .set(GlideRequestOptions.MIMETYPE, mimeType).set(GlideRequestOptions.FILEPATH, filePath);
            Glide.with(App.getAppContext())
                    .load(filePath)
                    .apply(options)
                    .listener(mRequestListener)
                    .fallback(R.drawable.ic_baseline_image_24)
                    .error(R.drawable.ic_baseline_image_24)
                    .into(mPhotoView);
        } else {
            RequestOptions options =
                    new RequestOptions()
                            .set(GlideRequestOptions.MIMETYPE, mDownloadEntity.getFileMimeType())
                            .set(GlideRequestOptions.FILEPATH, mDownloadEntity.getFilePath())
                            .set(GlideRequestOptions.LENGTH, mDownloadEntity.getFileSize())
                            .set(GlideRequestOptions.FRAME, mDownloadEntity.getThumbnailDuration());

            Glide.with(App.getAppContext()).load(mDownloadEntity)
                    .dontTransform()
                    .override(Target.SIZE_ORIGINAL, Target.SIZE_ORIGINAL)
                    .diskCacheStrategy(DiskCacheStrategy.NONE)
                    .listener(mRequestListener)
                    .apply(options)
                    .into(mPhotoView);
        }

    }

    private final RequestListener<Drawable> mRequestListener = new RequestListener<>() {
        @Override
        public boolean onLoadFailed(@Nullable GlideException e, Object model, @NonNull Target<Drawable> target, boolean isFirstResource) {
            Log.d(TAG, "onLoadFailed", e);
            startPostponedEnterTransition();
            if(mActivity != null){
                Snackbar snackbar = Snackbar.make(mActivity.getWindow().getDecorView(), R.string.error_file, Snackbar.LENGTH_LONG);
                snackbar.show();
            }
            return false;
        }

        @Override
        public boolean onResourceReady(@NonNull Drawable resource, @NonNull Object model, Target<Drawable> target, @NonNull DataSource dataSource, boolean isFirstResource) {
            Log.d(TAG, "onResourceReady");
            startPostponedEnterTransition();
            return false;
        }
    };


}
