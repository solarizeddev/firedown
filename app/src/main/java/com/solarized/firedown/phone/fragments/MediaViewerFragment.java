package com.solarized.firedown.phone.fragments;

import android.content.Context;

import android.graphics.Rect;
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
import androidx.media3.common.Player;
import androidx.media3.common.VideoSize;
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

    /**
     * Argument key set by {@link PlayerActivity} on the onNewIntent
     * (singleTask reuse) path. Android does not re-run shared-element
     * activity transitions for a reused activity instance, so the
     * fragment cannot rely on the window's enter-transition firing
     * onTransitionEnd to reveal the player surface. When this flag is
     * true the fragment uses the no-transition layout path that the
     * encrypted / safe-folder code already exercises.
     */
    public static final String ARG_SKIP_TRANSITION = "skip_transition";

    private DownloadEntity mDownloadEntity;

    private PlayerActivity mActivity;

    private PlayerView mPlayerView;

    private ExoPlayer mExoPlayer;

    private AppCompatImageView mPhotoView;

    private Drawable mFallbackDrawable;

    private boolean mAvoidTransition;

    /**
     * Controller (and chrome) auto-hide timeout while playing. VLC /
     * Plex use 5 s; PlayerView's default is ~3 s which feels rushed
     * for reaching the scrubber on a phone screen.
     */
    private static final int CONTROLLER_TIMEOUT_MS = 5000;

    /**
     * Cached so {@link #setChromeVisible(boolean)} can fire without
     * re-resolving from the activity each time. Nulled out by the
     * view-creation path being re-entered on configuration change.
     */
    private WindowInsetsControllerCompat mWindowInsetsController;



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

        // Skip the activity-transition dance entirely on three paths:
        //  - encrypted file (no thumbnail decryption mid-flight)
        //  - safe-folder file (same reason)
        //  - singleTask reuse via onNewIntent — Android does not re-run
        //    activity scene transitions for a reused instance even
        //    though the caller passed makeSceneTransitionAnimation
        //    options. Without this branch onTransitionEnd would never
        //    fire on the reused activity and player_view would stay GONE
        //    forever, leaving the user with audio playing under a
        //    static thumbnail.
        mAvoidTransition = mDownloadEntity.isFileEncrypted()
                || mDownloadEntity.isFileSafe()
                || bundle.getBoolean(ARG_SKIP_TRANSITION, false);

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

        // Explicit configuration so the tap-toggle behaviour stays the
        // same regardless of which PlayerView default the bundled Media3
        // version ships with.
        mPlayerView.setUseController(true);
        mPlayerView.setControllerAutoShow(true);
        mPlayerView.setControllerHideOnTouch(true);
        // Default is ~3 s. 5 s matches VLC / Plex and gives the user a
        // realistic window to reach the play/pause / scrubber without
        // feeling rushed. Tap-on-empty hides immediately as before.
        mPlayerView.setControllerShowTimeoutMs(CONTROLLER_TIMEOUT_MS);

        mWindowInsetsController = WindowCompat.getInsetsController(
                mActivity.getWindow(), mActivity.getWindow().getDecorView());

        // Configure the behavior of the hidden system bars.
        mWindowInsetsController.setSystemBarsBehavior(
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


        // Single sink for the chrome-visibility decision: PlayerView's
        // controller visibility drives whether the system bars + action
        // bar are shown. Keeping all three in one helper avoids the
        // "show bars but actionbar lags one frame" race the inline
        // listener used to have if the listener was invoked re-entrantly.
        mPlayerView.setControllerVisibilityListener(
                (PlayerView.ControllerVisibilityListener) visibility ->
                        setChromeVisible(visibility == View.VISIBLE));

        return v;

    }

    /**
     * Show or hide the activity chrome — system bars and action bar —
     * in lockstep with the PlayerView controller. Called from the
     * controller-visibility listener; safe to call from any tap path
     * if we add one later (e.g. drag-down-to-dismiss).
     */
    private void setChromeVisible(boolean visible) {
        if (mWindowInsetsController == null) return;
        ActionBar actionBar = (mActivity != null) ? mActivity.getSupportActionBar() : null;
        if (visible) {
            mWindowInsetsController.show(WindowInsetsCompat.Type.systemBars());
            if (actionBar != null) actionBar.show();
        } else {
            if (actionBar != null) actionBar.hide();
            mWindowInsetsController.hide(WindowInsetsCompat.Type.systemBars());
        }
    }

    @OptIn(markerClass = UnstableApi.class)
    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        long interval = mDownloadEntity.getThumbnailDuration();

        String url = mDownloadEntity.getFileUrl();

        String mimeType = mDownloadEntity.getFileMimeType();

        mExoPlayer = new ExoPlayer.Builder(mActivity).build();

        // Notify the activity when play-state or video size changes so
        // it can refresh the PiP action icon / aspect ratio. Listener is
        // released in onDestroy along with the player.
        mExoPlayer.addListener(new Player.Listener() {
            @Override
            public void onIsPlayingChanged(boolean isPlaying) {
                if (mActivity != null) mActivity.updatePipParams();
            }

            @Override
            public void onVideoSizeChanged(@NonNull VideoSize videoSize) {
                if (mActivity != null) mActivity.updatePipParams();
            }
        });

        mPlayerView.setPlayer(mExoPlayer);

        final DataSource.Factory dataSourceFactory = new FileDataSource.Factory();

        ExtractorsFactory extractorsFactory = new DefaultExtractorsFactory()
                .setConstantBitrateSeekingEnabled(true)
                .setConstantBitrateSeekingAlwaysEnabled(true);

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
        // While the activity is in PiP it still receives onPause but
        // playback must keep running — that's the whole point of PiP.
        // onStop / onDestroy still pause/release when PiP is dismissed.
        if (mExoPlayer != null && !isActivityInPip())
            mExoPlayer.pause();
    }

    @Override
    public void onStop() {
        super.onStop();
        Glide.with(App.getAppContext()).clear(mPhotoView);
        // No PiP guard here: while the floating window is visible the
        // activity sits in PAUSED, not STOPPED — onStop only fires when
        // PiP is being torn down (X button or another app covers it),
        // and in both cases we want playback to stop. The earlier guard
        // also broke the X-close path because isInPictureInPictureMode()
        // can still report true at onStop time on the finish path, so
        // the guard skipped stop() and the player kept emitting audio
        // until release().
        if (mExoPlayer != null)
            mExoPlayer.stop();
    }

    private boolean isActivityInPip() {
        return mActivity != null && mActivity.isInPictureInPictureMode();
    }

    /**
     * True when the fragment is rendering a video file (as opposed to
     * audio with cover art). PiP entry is gated on this — entering PiP
     * for a pure audio file would just show a static thumbnail.
     */
    public boolean isVideoMime() {
        return mDownloadEntity != null
                && FileUriHelper.isVideo(mDownloadEntity.getFileMimeType());
    }

    public boolean isPlaying() {
        return mExoPlayer != null && mExoPlayer.isPlaying();
    }

    /**
     * Toggle play / pause from the PiP action receiver. Called on the
     * main thread (BroadcastReceiver dispatch runs there by default).
     */
    public void togglePlayPause() {
        if (mExoPlayer == null) return;
        if (mExoPlayer.isPlaying()) mExoPlayer.pause();
        else mExoPlayer.play();
    }

    /**
     * Current video size, in pixels, for PiP aspect-ratio calculation.
     * Returns null until ExoPlayer has decoded the first frame — the
     * activity falls back to 16:9 when null.
     */
    @Nullable
    public Rect getVideoBounds() {
        if (mExoPlayer == null) return null;
        VideoSize size = mExoPlayer.getVideoSize();
        if (size.width <= 0 || size.height <= 0) return null;
        return new Rect(0, 0, size.width, size.height);
    }

    /**
     * Activity callback that flips between PiP-mode UI (no controller,
     * no chrome) and inline UI. Setting setUseController(false) while
     * in PiP is the documented way to suppress the floating controls
     * Android renders separately inside the PiP window.
     */
    @OptIn(markerClass = UnstableApi.class)
    public void onPipModeChanged(boolean inPip) {
        if (mPlayerView == null) return;
        if (inPip) {
            mPlayerView.hideController();
            mPlayerView.setUseController(false);
            setChromeVisible(false);
        } else {
            mPlayerView.setUseController(true);
            // Don't force the controller back on exit — let the user tap
            // to bring it up. Chrome visibility follows the controller
            // listener as before.
        }
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
        mWindowInsetsController = null;
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
