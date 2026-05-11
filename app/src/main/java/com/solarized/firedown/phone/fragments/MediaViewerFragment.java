package com.solarized.firedown.phone.fragments;

import android.content.Context;
import android.content.res.Configuration;

import android.graphics.Rect;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
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

    /**
     * Fragment root. Padding is toggled here in lockstep with the
     * system bars so the PlayerView controller is never clipped by the
     * navigation bar. See {@link #setChromeVisible(boolean)}.
     */
    private View mRootView;



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

        // player_view stays VISIBLE from the start regardless of how the
        // activity was launched. The previous "GONE until onTransitionEnd"
        // dance relied on the shared-element scene transition firing —
        // but with android:launchMode="singleTask" the framework does not
        // re-run that transition when the activity is reused via
        // onNewIntent (e.g. open a second video after closing PiP). The
        // listener attached to nothing, the TextureView was never laid
        // out, ExoPlayer played audio with no rendering surface, and the
        // user was left with a static thumbnail.
        //
        // Layout safety: the PlayerView's shutter is configured
        // transparent (app:shutter_background_color in
        // fragment_media_viewer.xml) so player_view doesn't flash black
        // before the first video frame. photo_view sits underneath and
        // shows through until frames render — exactly the placeholder
        // role it already had, just without the transition dependency.
        // onRenderedFirstFrame (below) hides photo_view once the
        // TextureView has something opaque to display.
        mPlayerView.setVisibility(View.VISIBLE);

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


        // Pad the fragment root by the nav-bar height so the PlayerView
        // controller (and on the cold-launch path the thumbnail) clears
        // the system navigation. We deliberately AVOID the WindowInsets
        // path here: the Play theme sets
        // android:windowTranslucentNavigation=true (legacy edge-to-edge
        // flag), and the combination with the modern
        // WindowInsetsControllerCompat path makes
        // getRootWindowInsets().getInsets(systemBars()).bottom report 0
        // on real devices (observed on Samsung One UI with 3-button nav).
        // Per-view OnApplyWindowInsetsListener also races against the
        // async FragmentTransaction.replace commit on cold launch.
        //
        // Reading android.R.dimen.navigation_bar_height bypasses both
        // issues — it's the same value the system reserves for the
        // nav bar regardless of the translucent flag or dispatch state.
        // The padding is then toggled in setChromeVisible(visible) so we
        // only pad while the nav bar is actually showing.
        mRootView = v;
        v.setPadding(0, 0, 0, getNavigationBarHeight());


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
            // Reserve space for the nav bar so the PlayerView controller
            // (progress bar / play buttons) doesn't sit behind it.
            if (mRootView != null) {
                mRootView.setPadding(0, 0, 0, getNavigationBarHeight());
            }
            mWindowInsetsController.show(WindowInsetsCompat.Type.systemBars());
            if (actionBar != null) actionBar.show();
        } else {
            if (actionBar != null) actionBar.hide();
            mWindowInsetsController.hide(WindowInsetsCompat.Type.systemBars());
            // Nav bar is gone — let the video reclaim the full screen.
            if (mRootView != null) {
                mRootView.setPadding(0, 0, 0, 0);
            }
        }
    }

    /**
     * Resolve the bottom inset to reserve so the PlayerView controller
     * isn't clipped by the system navigation. Two-tier strategy:
     *
     * 1. WindowInsets.Type.navigationBars().bottom — the modern,
     *    orientation-aware source of truth. Returns 0 in landscape on
     *    devices that route the nav bar to a side edge (correct — we
     *    don't want bottom padding there). Returns the gesture-pill
     *    height on devices using full gesture nav (~24dp — correct,
     *    keeps controls clear of the gesture area). Returns the
     *    3-button nav height when the bar is at the bottom.
     *
     * 2. android.R.dimen.navigation_bar_height resource — used ONLY
     *    when (1) returned 0 *and* the device is in portrait. This is
     *    the legacy-translucent fallback: Theme.FireDown.Play sets
     *    android:windowTranslucentNavigation=true, which makes the
     *    framework tell us "the app is handling the nav bar" and
     *    report navigationBars().bottom = 0 — even though the bar is
     *    still drawn as an opaque strip over the content (observed on
     *    Samsung One UI with 3-button nav). The resource always
     *    returns the OS-reserved strip height.
     *
     * The check is restricted to portrait so we don't apply a bogus
     * bottom inset in landscape when the nav bar is on a side edge —
     * WindowInsets correctly returns 0 for bottom there and we trust
     * it.
     *
     * Not cached — re-read on each chrome toggle so configuration
     * changes (rotation, fold/unfold) pick up the right value.
     */
    private int getNavigationBarHeight() {
        if (mActivity == null) return 0;
        WindowInsetsCompat insets = ViewCompat.getRootWindowInsets(
                mActivity.getWindow().getDecorView());
        if (insets != null) {
            int bottom = insets.getInsets(
                    WindowInsetsCompat.Type.navigationBars()).bottom;
            if (bottom > 0) return bottom;
        }
        boolean portrait = getResources().getConfiguration().orientation
                == Configuration.ORIENTATION_PORTRAIT;
        if (!portrait) return 0;
        int resourceId = getResources()
                .getIdentifier("navigation_bar_height", "dimen", "android");
        return (resourceId > 0)
                ? getResources().getDimensionPixelSize(resourceId)
                : 0;
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

            /**
             * Hide the thumbnail placeholder once the video surface has
             * actually painted a frame. Before this point the
             * TextureView is transparent and photo_view shows through;
             * after this point the TextureView is opaque so photo_view
             * is redundant. Hiding it also frees the decoded bitmap.
             */
            @Override
            public void onRenderedFirstFrame() {
                if (mPhotoView != null) mPhotoView.setVisibility(View.GONE);
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
        mRootView = null;
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


}
