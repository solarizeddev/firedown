package com.solarized.firedown.phone.fragments;

import android.content.Context;

import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.transition.Transition;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.OptIn;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.widget.AppCompatImageView;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.OnApplyWindowInsetsListener;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.fragment.app.Fragment;
import androidx.media3.common.MediaItem;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.FileDataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.source.ProgressiveMediaSource;
import androidx.media3.extractor.DefaultExtractorsFactory;
import androidx.media3.extractor.ExtractorsFactory;
import androidx.media3.ui.PlayerView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.load.engine.GlideException;
import com.bumptech.glide.request.RequestListener;
import com.bumptech.glide.request.RequestOptions;
import com.bumptech.glide.request.target.Target;
import com.bumptech.glide.signature.ObjectKey;
import com.google.android.material.snackbar.Snackbar;
import com.solarized.firedown.App;
import com.solarized.firedown.GlideRequestOptions;
import com.solarized.firedown.glide.MimeTypeThumbnail;
import com.solarized.firedown.phone.PlayerActivity;
import com.solarized.firedown.R;
import com.solarized.firedown.data.entity.DownloadEntity;
import com.solarized.firedown.utils.FileUriHelper;
import com.solarized.firedown.Keys;

public class MediaViewerFragment extends Fragment {

    private static final String TAG = MediaViewerFragment.class.getSimpleName();

    private DownloadEntity mDownloadEntity;

    private PlayerActivity mActivity;

    private PlayerView mPlayerView;

    private ExoPlayer mExoPlayer;

    private AppCompatImageView mPhotoView;

    private Drawable mFallbackDrawable;

    private boolean mAvoidTransition;



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

        Bundle bundle = getArguments();

        if (bundle == null)
            throw new IllegalArgumentException();

        mDownloadEntity = bundle.getParcelable(Keys.ITEM_ID);

        if(mDownloadEntity == null)
            mDownloadEntity = new DownloadEntity();

        mAvoidTransition = mDownloadEntity.isFileEncrypted() || mDownloadEntity.isFileSafe();

        if (!mAvoidTransition) {
            addTransitionListener();
        }


    }


    @OptIn(markerClass = UnstableApi.class)
    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {

        postponeEnterTransition();

        Log.d(TAG, "onCreateView");
        // Inflate the layout for this fragment
        View v = inflater.inflate(R.layout.fragment_media_viewer, container, false);

        mPlayerView = v.findViewById(R.id.player_view);

        mPhotoView = v.findViewById(R.id.photo_view);

        mPlayerView.setVisibility(!mAvoidTransition ? View.GONE : View.VISIBLE);

        mPhotoView.setVisibility(!mAvoidTransition ? View.VISIBLE : View.GONE);

        ViewCompat.setTransitionName(mPhotoView, "video_view");

        String fileMime = mDownloadEntity.getFileMimeType();

        int width = mPlayerView.getWidth();
        int height = mPlayerView.getHeight();
        if (width <= 0) width = (int) (getResources().getDisplayMetrics().density * 256);
        if (height <= 0) height = (int) (getResources().getDisplayMetrics().density * 180);

        mFallbackDrawable = new BitmapDrawable(getResources(),
                MimeTypeThumbnail.generate(mActivity, fileMime, width, height));

        if (FileUriHelper.isAudio(fileMime)) {
            mPlayerView.setDefaultArtwork(mFallbackDrawable);
        }

        WindowInsetsControllerCompat windowInsetsController = WindowCompat.getInsetsController(mActivity.getWindow(), mActivity.getWindow().getDecorView());

        // Configure the behavior of the hidden system bars.
        windowInsetsController.setSystemBarsBehavior(
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        );


        ViewCompat.setOnApplyWindowInsetsListener(mPlayerView, new OnApplyWindowInsetsListener() {
            @NonNull
            @Override
            public WindowInsetsCompat onApplyWindowInsets(@NonNull View v, @NonNull WindowInsetsCompat insets) {
                Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
                mPlayerView.setPadding(0,0,0, systemBars.bottom);
                mPlayerView.requestLayout();
                mPhotoView.setPadding(0,0,0, systemBars.bottom);
                mPhotoView.requestLayout();
                return insets;
            }
        });



        ActionBar actionBar = mActivity.getSupportActionBar();

        mPlayerView.setControllerVisibilityListener((PlayerView.ControllerVisibilityListener) visibility -> {
            Log.d(TAG, "onVisibilityChange: " + (visibility == View.VISIBLE));
            boolean isControllerVisible = visibility == View.VISIBLE;
            if (isControllerVisible) {
                windowInsetsController.show(WindowInsetsCompat.Type.systemBars());
                if (actionBar != null)
                    actionBar.show();
            } else {
                if (actionBar != null)
                    actionBar.hide();
                windowInsetsController.hide(WindowInsetsCompat.Type.systemBars());
            }
        });

        return v;

    }

    @OptIn(markerClass = UnstableApi.class)
    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        long interval = mDownloadEntity.getThumbnailDuration();

        String url = mDownloadEntity.getFileUrl();

        String mimeType = mDownloadEntity.getFileMimeType();

        mExoPlayer = new ExoPlayer.Builder(mActivity).build();

        mPlayerView.setPlayer(mExoPlayer);

        final DataSource.Factory dataSourceFactory = new FileDataSource.Factory();

        ExtractorsFactory extractorsFactory = new DefaultExtractorsFactory();

        MediaItem mediaItem = MediaItem.fromUri(Uri.parse(mDownloadEntity.getFilePath()));

        MediaSource videoSource = new ProgressiveMediaSource.Factory(dataSourceFactory, extractorsFactory).createMediaSource(mediaItem);

        mExoPlayer.setMediaSource(videoSource);

        mExoPlayer.prepare();

        mExoPlayer.setPlayWhenReady(true);

        if(!mAvoidTransition){
            if (FileUriHelper.isAudio(mimeType)) {
                Glide.with(App.getAppContext()).load(mFallbackDrawable)
                        .dontTransform()
                        .override(Target.SIZE_ORIGINAL, Target.SIZE_ORIGINAL)
                        .diskCacheStrategy(DiskCacheStrategy.NONE)
                        .listener(mRequestListener)
                        .into(mPhotoView);
            } else {
                RequestOptions options =
                        new RequestOptions().frame(interval)
                                .set(GlideRequestOptions.MIMETYPE, mDownloadEntity.getFileMimeType())
                                .set(GlideRequestOptions.FILEPATH, mDownloadEntity.getFilePath())
                                .set(GlideRequestOptions.LENGTH, mDownloadEntity.getFileSize())
                                .set(GlideRequestOptions.FRAME, mDownloadEntity.getThumbnailDuration());

                Glide.with(App.getAppContext()).load(mDownloadEntity)
                        .dontTransform()
                        .override(Target.SIZE_ORIGINAL, Target.SIZE_ORIGINAL)
                        .diskCacheStrategy(DiskCacheStrategy.NONE)
                        .signature(new ObjectKey(interval + url.hashCode()))
                        .listener(mRequestListener)
                        .apply(options)
                        .into(mPhotoView);
            }
        }else{
            if (FileUriHelper.isAudio(mimeType)) {
                setErrorRes(R.drawable.ill_small_audio);
            }
        }

    }


    @Override
    public void onPause() {
        super.onPause();
        if (mExoPlayer != null)
            mExoPlayer.pause();
    }

    @Override
    public void onStop() {
        super.onStop();
        Glide.with(App.getAppContext()).clear(mPhotoView);
        if (mExoPlayer != null)
            mExoPlayer.stop();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        mFallbackDrawable = null;
        if (mPlayerView != null)
            mPlayerView.setPlayer(null);
        if (mExoPlayer != null)
            mExoPlayer.release();
        mExoPlayer = null;
        mPlayerView = null;
    }


    @OptIn(markerClass = UnstableApi.class)
    private void setErrorRes(int res){
        Drawable drawable = ContextCompat.getDrawable(mActivity, res);
        mPlayerView.setDefaultArtwork(drawable);
    }

    private final RequestListener<Drawable> mRequestListener = new RequestListener<>() {
        @Override
        public boolean onLoadFailed(@Nullable GlideException e, Object model, @NonNull Target<Drawable> target, boolean isFirstResource) {
            Log.d(TAG, "onLoadFailed", e);
            if(mActivity == null)
                return false;
            startPostponedEnterTransition();
            setErrorRes(R.drawable.ill_small_audio);
            Snackbar snackbar = Snackbar.make(mActivity.getWindow().getDecorView(), R.string.error_file, Snackbar.LENGTH_LONG);
            snackbar.show();
            return false;
        }

        @Override
        public boolean onResourceReady(@NonNull Drawable resource, @NonNull Object model, Target<Drawable> target, @NonNull com.bumptech.glide.load.DataSource dataSource, boolean isFirstResource) {
            Log.d(TAG, "onResourceReady");
            if(mActivity == null)
                return false;
            startPostponedEnterTransition();
            return false;
        }
    };

    private void addTransitionListener() {
        final Transition transition = mActivity.getWindow().getSharedElementEnterTransition();

        if (transition != null) {
            // There is an entering shared element transition so add a listener to it
            transition.addListener(new Transition.TransitionListener() {
                @Override
                public void onTransitionEnd(Transition transition) {
                    // As the transition has ended, we can now load the full-size image
                    if(mPlayerView != null) {
                        mPlayerView.post(() -> mPlayerView.setVisibility(View.VISIBLE));
                    }
                    // Make sure we remove ourselves as a listener
                    transition.removeListener(this);
                }

                @Override
                public void onTransitionStart(Transition transition) {
                    // No-op
                }

                @Override
                public void onTransitionCancel(Transition transition) {
                    // Make sure we remove ourselves as a listener
                    transition.removeListener(this);
                }

                @Override
                public void onTransitionPause(Transition transition) {
                    // No-op
                }

                @Override
                public void onTransitionResume(Transition transition) {
                    // No-op
                }
            });
        }
    }

}
