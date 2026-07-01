package com.solarized.firedown.phone.fragments;

import android.annotation.SuppressLint;
import android.content.Context;

import android.content.res.Resources;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Bundle;
import android.transition.Transition;
import android.util.Log;
import android.view.GestureDetector;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.OptIn;
import androidx.appcompat.app.ActionBar;
import androidx.core.graphics.Insets;
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
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.datasource.FileDataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.source.ProgressiveMediaSource;
import androidx.media3.extractor.DefaultExtractorsFactory;
import androidx.media3.extractor.ExtractorsFactory;
import androidx.media3.ui.AspectRatioFrameLayout;
import androidx.media3.ui.PlayerView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.load.engine.GlideException;
import com.bumptech.glide.request.RequestListener;
import com.bumptech.glide.request.RequestOptions;
import com.bumptech.glide.request.target.Target;
import com.bumptech.glide.signature.ObjectKey;
import com.solarized.firedown.App;
import com.solarized.firedown.BuildConfig;
import com.solarized.firedown.GlideRequestOptions;
import com.solarized.firedown.glide.MimeTypeThumbnail;
import com.solarized.firedown.phone.PlayerActivity;
import com.solarized.firedown.ui.AspectRatioImageView;
import com.solarized.firedown.R;
import com.solarized.firedown.data.RestoredFileAccess;
import com.solarized.firedown.data.entity.DownloadEntity;
import com.solarized.firedown.utils.FileUriHelper;
import com.solarized.firedown.Keys;
import com.solarized.firedown.utils.FragmentArgs;

public class MediaViewerFragment extends Fragment {

    private static final String TAG = MediaViewerFragment.class.getSimpleName();

    private DownloadEntity mDownloadEntity;

    private PlayerActivity mActivity;

    private PlayerView mPlayerView;

    private ExoPlayer mExoPlayer;

    private AspectRatioImageView mPhotoView;

    private Drawable mFallbackDrawable;

    private boolean mAvoidTransition;

    /**
     * Controller (and chrome) auto-hide timeout while playing. VLC /
     * Plex use 5 s; PlayerView's default is ~3 s which feels rushed
     * for reaching the scrubber on a phone screen.
     */
    private static final int CONTROLLER_TIMEOUT_MS = 5000;

    /**
     * How many ms to seek per double-tap. Matches the YouTube / VLC
     * convention; small enough that a single tap is meaningful, large
     * enough that repeated taps cover ground quickly.
     */
    private static final long SEEK_DELTA_MS = 10_000L;

    /**
     * Cached so {@link #setChromeVisible(boolean)} can fire without
     * re-resolving from the activity each time. Nulled out by the
     * view-creation path being re-entered on configuration change.
     */
    private WindowInsetsControllerCompat mWindowInsetsController;

    private GestureDetector mPlayerGestureDetector;



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

        mDownloadEntity = FragmentArgs.parcelable(
                this, Keys.ITEM_ID, DownloadEntity.class);

        if (mDownloadEntity == null) {
            // Args lost on process-death restore: PlayerActivity is just
            // a shell for this viewer, finish() to return the user to
            // wherever they launched from.
            mDownloadEntity = new DownloadEntity();
            if (mActivity != null) mActivity.finish();
        }

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

        // Resolution-independent fallback: paints to whatever bounds
        // the host (PlayerView artwork slot / mPhotoView) gives it,
        // so we don't bake a fixed raster that fitCenters to a thin
        // band when the player view's pixel size isn't known yet at
        // onCreateView time (getWidth/getHeight are still 0 here).
        mFallbackDrawable = MimeTypeThumbnail.generateDrawable(mActivity, fileMime);

        if (!mAvoidTransition) {
            // Bound photo_view to a centred centerCrop card. The
            // AspectRatioImageView computes its height from its width and
            // the ratio, and photo_view is declared layout_height=
            // "wrap_content" (NOT match_parent) precisely so that computed
            // band IS the view's size — there is no EXACTLY match_parent
            // height spec being violated for the shared-element activity
            // transition to trip over. (With match_parent the view
            // reported a 450px band but the transition composited it at
            // the full match_parent height, so centerCrop filled the whole
            // screen — the "maximized image in the background".)
            //
            // 16:10 here is only a FALLBACK ratio. For VIDEO,
            // presetVideoAspectRatio() overrides it in onViewCreated with
            // the file's real aspect ratio, so the card coincides exactly
            // with the player's letterbox (nothing peeks around a wide
            // clip). AUDIO keeps 16:10 — its art isn't a video frame, and
            // 16:10 matches the downloads grid cell. The poster is shown
            // until onRenderedFirstFrame swaps in the real video frame
            // (for video it sits above an opaque shutter — see below).
            mPhotoView.setScaleType(ImageView.ScaleType.CENTER_CROP);
            mPhotoView.setAspectRatio(16f / 10f);
        }

        if (FileUriHelper.isAudio(fileMime)) {
            // Single artwork layer: mPhotoView owns the visible album
            // art for audio (steady state, not just transition). Turn
            // off PlayerView's own artwork slot so the metadata-driven
            // cover doesn't paint on top of ours. The fallback is
            // installed immediately so the transition has something to
            // land on; Glide's load below replaces it with the
            // embedded cover when present, .error() restores it if
            // extraction fails.
            mPlayerView.setUseArtwork(false);
            mPhotoView.setImageDrawable(mFallbackDrawable);
        } else {
            // VIDEO: re-enable media3's shutter (its default is opaque
            // black; fragment_media_viewer.xml overrides it transparent).
            //
            // Why this is THE fix for the "maximized/stretched frame in
            // the background": PlayerView.setPlayer() calls updateAspect-
            // Ratio() with the fresh player's still-UNKNOWN video size,
            // which resets the inner exo_content_frame to aspect 0 (=
            // MATCH_PARENT). Until onVideoSizeChanged fires (around first-
            // frame decode) the content frame is full-screen, so a frame
            // that decodes early — the 498x334, 1.4s, audio-less BINGO
            // clip decodes instantly, mid shared-element transition —
            // paints STRETCHED (fitXY) to the whole screen. media3's guard
            // is the shutter, hidden only in onRenderedFirstFrame, which
            // fires AFTER onVideoSizeChanged has resized the frame; an
            // opaque shutter covers the surface through exactly that
            // stretched window. Making it transparent (to reveal the
            // poster) is what exposed the stretch. Confirmed against
            // media3 1.10.1 PlayerView source.
            //
            // Keep the poster visible by drawing photo_view ABOVE the
            // shutter (bringToFront): the poster band sits on top, the
            // black shutter fills the letterbox area around it, so there
            // is no stretch AND no black-instead-of-poster. photo_view is
            // hidden in onRenderedFirstFrame, uncovering the (now
            // correctly letterboxed) video. Audio is untouched: it has no
            // video surface to stretch, and its artwork photo_view must
            // keep showing through the transparent shutter.
            mPlayerView.setShutterBackgroundColor(Color.BLACK);
            if (!mAvoidTransition) {
                mPhotoView.bringToFront();
            }
        }

        // PlayerView / controller behaviour. autoShow is deliberately
        // false: at launch we want the activity fully immersive (no
        // system bars, no controller — same UX as YouTube, VLC,
        // Netflix). The user taps to bring the controller up;
        // setControllerVisibilityListener below mirrors that into the
        // system bars. With autoShow=true Media3 re-shows the
        // controller on every player-state change (buffering ↔ ready)
        // which kept resetting the auto-hide timeout — that was the
        // "auto-hide doesn't work" symptom reported on #95/#96.
        mPlayerView.setUseController(true);
        mPlayerView.setControllerAutoShow(false);
        // Double-tap-to-seek needs to win over the default
        // tap-toggles-controller behaviour. We turn the built-in
        // toggle off and reproduce it via onSingleTapConfirmed in
        // mPlayerGestureDetector below, which only fires once the
        // GestureDetector has ruled out a double-tap. Net UX is the
        // same single-tap controller toggle with a ~300 ms delay
        // (one DOUBLE_TAP_TIMEOUT) — imperceptible in practice.
        mPlayerView.setControllerHideOnTouch(false);
        mPlayerView.setControllerShowTimeoutMs(CONTROLLER_TIMEOUT_MS);

        setupDoubleTapSeek();
        setupSeekButtons();

        mWindowInsetsController = WindowCompat.getInsetsController(
                mActivity.getWindow(), mActivity.getWindow().getDecorView());
        mWindowInsetsController.setSystemBarsBehavior(
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);

        // Lockstep: controller visibility ↔ system-bar visibility.
        // First user tap shows the controller, the listener fires
        // with VISIBLE and we show the bars. The auto-hide timeout
        // (or another tap) hides the controller, the listener fires
        // with GONE and we hide the bars.
        mPlayerView.setControllerVisibilityListener(
                (PlayerView.ControllerVisibilityListener) visibility ->
                        setChromeVisible(visibility == View.VISIBLE));

        // Inset handling. The custom controller layout follows Media3's
        // <merge>-rooted structure now (#PR), so there's no single
        // controller-root view to pad. The only region that needs the
        // navigation-bar inset is exo_bottom_bar (time + scrubber);
        // exo_top_controls is at top|end and exo_center_controls is
        // centred, neither needs bottom clearance. While bars are
        // hidden (cold launch / after auto-hide) navigationBars().bottom
        // is 0 and the bar isn't visible anyway, so the write is a
        // no-op. When the user taps and the bars are shown, the
        // framework re-dispatches insets with the real nav-bar height
        // and we pad the bar up by exactly that much.
        // Inset handling on exo_bottom_bar. The bar lives at the
        // bottom of PlayerControlView (layout_gravity=bottom), so it
        // only needs the navigation-bar inset on its bottom edge — and
        // the display-cutout left/right insets in landscape so the
        // scrubber doesn't slide under a notch.
        //
        // DO NOT write systemBars().top here. systemBars() includes
        // the STATUS BAR height as top inset. Writing that to
        // paddingTop grows the bar by ~status-bar-height pixels — and
        // because the bar is anchored bottom, its TOP edge moves up by
        // that much, which looks like the bar "sliding up". That's
        // exactly what was happening after PiP → maximize: the
        // post-exit insets re-dispatch arrives with both top and
        // bottom non-zero, the old listener wrote both, the bar grew.
        // (Pre-PiP it looked OK only because in fully-immersive launch
        // state both insets were 0.)
        //
        // Returning windowInsets (not CONSUMED) keeps the dispatch
        // alive for the action bar and any other listeners further
        // down the tree.
        final View bottomBar = mPlayerView.findViewById(R.id.exo_bottom_bar);
        if (bottomBar != null) {
            final int xmlPaddingTop = bottomBar.getPaddingTop();
            ViewCompat.setOnApplyWindowInsetsListener(bottomBar, (v1, windowInsets) -> {
                Insets navBars = windowInsets.getInsets(WindowInsetsCompat.Type.navigationBars());
                Insets cutout = windowInsets.getInsets(WindowInsetsCompat.Type.displayCutout());
                int leftInset = Math.max(navBars.left, cutout.left);
                int rightInset = Math.max(navBars.right, cutout.right);
                int bottomInset = Math.max(navBars.bottom, cutout.bottom);
                Log.d(TAG, "[exo_bottom_bar inset] navBars=" + navBars
                        + " cutout=" + cutout
                        + " writing padding L=" + leftInset
                        + " T=" + xmlPaddingTop
                        + " R=" + rightInset
                        + " B=" + bottomInset
                        + " | barH(pre)=" + v1.getHeight()
                        + " topY(pre)=" + v1.getTop()
                        + " bottomY(pre)=" + v1.getBottom());
                v1.setPadding(leftInset, xmlPaddingTop, rightInset, bottomInset);
                if(BuildConfig.DEBUG){
                    dumpBottomBarStructure("[inset-post]");
                }
                return windowInsets;
            });
        }




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
             * Video only: hide the first-frame poster once the
             * TextureView has something opaque to draw. For audio
             * mPhotoView is the steady-state artwork renderer (see
             * onCreateView), not a placeholder — it never hides.
             */
            @Override
            public void onRenderedFirstFrame() {
                if (mPhotoView != null) mPhotoView.setVisibility(View.GONE);
            }
        });

        // Default: read the raw path with a FileDataSource (owned files; the
        // vault's encrypted/safe entries keep this exact path untouched).
        DataSource.Factory dataSourceFactory = new FileDataSource.Factory();
        Uri playUri = Uri.parse(mDownloadEntity.getFilePath());

        // A foreign-owned RESTORED file can't be opened by path (EACCES). When
        // it resolves to the persisted SAF content:// grant, play THAT through
        // a DefaultDataSource (which speaks content://). Scoped to non-vault
        // entries so encrypted/safe playback is byte-identical to before.
        if (!mDownloadEntity.isFileEncrypted() && !mDownloadEntity.isFileSafe()) {
            Uri openable = RestoredFileAccess.openableUri(mActivity, mDownloadEntity.getFilePath());
            if (openable != null && "content".equals(openable.getScheme())) {
                playUri = openable;
                dataSourceFactory = new DefaultDataSource.Factory(mActivity);
            }
        }

        ExtractorsFactory extractorsFactory = new DefaultExtractorsFactory()
                .setConstantBitrateSeekingEnabled(true)
                .setConstantBitrateSeekingAlwaysEnabled(true);

        MediaItem mediaItem = MediaItem.fromUri(playUri);

        MediaSource videoSource = new ProgressiveMediaSource.Factory(dataSourceFactory, extractorsFactory).createMediaSource(mediaItem);

        mExoPlayer.setMediaSource(videoSource);

        mExoPlayer.prepare();

        mPlayerView.setPlayer(mExoPlayer);

        // Pre-set PlayerView's inner AspectRatioFrameLayout from the
        // file's own video dimensions so the content frame is already the
        // right shape when the FIRST frame paints — otherwise it stays at
        // MATCH_PARENT until Player.Listener.onVideoSizeChanged fires,
        // which can land AFTER the first frame has painted to the
        // TextureView at full-screen dimensions: the frame is drawn
        // STRETCHED (fitXY) to the whole screen and only snaps to the
        // letterbox when the async size event arrives. On a tiny clip
        // (the 498x334, 1.4s, audio-less BINGO case) the first frame
        // decodes instantly, mid shared-element transition, so that
        // stretched frame is exactly the "maximized image in the
        // background" — while a larger clip with audio reaches its first
        // frame only after the transition, hiding the flash. See
        // androidx/media#536 (closed as "question", no upstream fix).
        //
        // CRUCIAL ordering: this MUST run AFTER setPlayer(). setPlayer()
        // attaches the fresh player whose video size is still UNKNOWN
        // (0x0), and PlayerView reacts by resetting its content frame to
        // aspect 0 (= MATCH_PARENT). Running the preset before setPlayer
        // (as it used to) had it silently undone, so the frame stretched
        // anyway. setPlayWhenReady() below hasn't started decoding yet, so
        // the aspect we set here is in place before the first frame.
        if (!FileUriHelper.isAudio(mimeType)) {
            presetVideoAspectRatio(playUri);
        }

        mExoPlayer.setPlayWhenReady(true);

        if(!mAvoidTransition){
            long interval = mDownloadEntity.getThumbnailDuration();
            String url = mDownloadEntity.getFileUrl();
            RequestOptions options =
                    new RequestOptions().frame(interval)
                            .set(GlideRequestOptions.MIMETYPE, mDownloadEntity.getFileMimeType())
                            .set(GlideRequestOptions.FILEPATH, mDownloadEntity.getFilePath())
                            .set(GlideRequestOptions.LENGTH, mDownloadEntity.getFileSize())
                            .set(GlideRequestOptions.FRAME, mDownloadEntity.getThumbnailDuration());

            // Same model + options the downloads list uses, so the
            // shared-element transition lands on the same picture the
            // list cell showed. For audio the FFmpeg decoder pulls
            // embedded album art (ID3 APIC, M4A covr, FLAC PICTURE);
            // .error() falls back to the mime-tinted music-note when
            // the file has no embedded art so we don't end up with
            // mPhotoView stretched-orange behind PlayerView's
            // letterboxed real cover.
            Glide.with(App.getAppContext()).load(mDownloadEntity)
                    .dontTransform()
                    .override(Target.SIZE_ORIGINAL, Target.SIZE_ORIGINAL)
                    .diskCacheStrategy(DiskCacheStrategy.NONE)
                    .signature(new ObjectKey(interval + url.hashCode()))
                    .error(mFallbackDrawable)
                    .listener(mRequestListener)
                    .apply(options)
                    .into(mPhotoView);
        }

        // Start fully immersive. The controller is already hidden
        // (setControllerAutoShow(false) in onCreateView), this matches
        // the system bars to it. First user tap shows the controller
        // → setChromeVisible(true) via the visibility listener → bars
        // come back.
        setChromeVisible(false);
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
     * True when the fragment is rendering a video file.
     */
    public boolean isVideoMime() {
        return mDownloadEntity != null
                && FileUriHelper.isVideo(mDownloadEntity.getFileMimeType());
    }

    /**
     * True when the fragment is rendering an audio file. Audio PiP is
     * supported — the PlayerView's defaultArtwork (mime-generated
     * thumbnail) sits behind the static mPhotoView overlay and stays
     * visible because onRenderedFirstFrame never fires for audio.
     */
    public boolean isAudioMime() {
        return mDownloadEntity != null
                && FileUriHelper.isAudio(mDownloadEntity.getFileMimeType());
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

    // ── Double-tap-to-seek ───────────────────────────────────────────

    /**
     * Wire a GestureDetector on the PlayerView so a double-tap on the
     * left half seeks back {@value #SEEK_DELTA_MS} ms and a double-tap
     * on the right half seeks forward by the same amount. The seek is
     * silent — the scrubber jump (and the visible ±10 s buttons in the
     * controller) provide sufficient feedback. A single confirmed tap
     * toggles the playback controller (replacing the built-in
     * PlayerView behaviour we disabled).
     *
     * <p>The listener returns {@code false} from
     * {@code onTouch} so PlayerView's children (notably the scrubber
     * inside the controller) keep receiving touches — only the
     * top-level tap/double-tap decisions are routed through the
     * GestureDetector.</p>
     */
    @SuppressLint("ClickableViewAccessibility")
    private void setupDoubleTapSeek() {
        mPlayerGestureDetector = new GestureDetector(mActivity,
                new GestureDetector.SimpleOnGestureListener() {
                    @Override
                    public boolean onDoubleTap(@NonNull MotionEvent e) {
                        if (mPlayerView == null || mExoPlayer == null) return false;
                        boolean leftHalf = e.getX() < mPlayerView.getWidth() / 2f;
                        applySeek(leftHalf ? -SEEK_DELTA_MS : SEEK_DELTA_MS);
                        spinSeekIcon(leftHalf);
                        return true;
                    }

                    @OptIn(markerClass = UnstableApi.class)
                    @Override
                    public boolean onSingleTapConfirmed(@NonNull MotionEvent e) {
                        if (mPlayerView == null) return false;
                        if (mPlayerView.isControllerFullyVisible()) {
                            mPlayerView.hideController();
                        } else {
                            mPlayerView.showController();
                        }
                        return true;
                    }
                });

        mPlayerView.setOnTouchListener((view, event) -> {
            mPlayerGestureDetector.onTouchEvent(event);
            return false;
        });
    }

    /**
     * Wire the ±10 s seek buttons that flank exo_play_pause in the
     * controller. Button taps seek silently — the button itself is
     * the feedback.
     */
    private void setupSeekButtons() {
        View btnBack = mPlayerView.findViewById(R.id.media_viewer_btn_seek_back);
        View btnForward = mPlayerView.findViewById(R.id.media_viewer_btn_seek_forward);
        if (btnBack != null) {
            btnBack.setOnClickListener(v -> applySeek(-SEEK_DELTA_MS));
        }
        if (btnForward != null) {
            btnForward.setOnClickListener(v -> applySeek(SEEK_DELTA_MS));
        }
    }

    /**
     * Apply {@code deltaMs} to the current playback position, clamped
     * to {@code [0, duration]}. Shared by the ±10 s buttons and the
     * double-tap gesture; both seek silently.
     */
    private void applySeek(long deltaMs) {
        if (mExoPlayer == null) return;
        long pos = mExoPlayer.getCurrentPosition();
        long dur = mExoPlayer.getDuration();
        long upper = dur > 0 ? dur : Long.MAX_VALUE;
        long target = Math.max(0L, Math.min(upper, pos + deltaMs));
        mExoPlayer.seekTo(target);
    }

    /**
     * Spin the ±10 s icon a quarter turn in the direction of the seek as
     * feedback for the double-tap gesture (replay_10 curls
     * counter-clockwise, forward_10 clockwise — the rotation reads as
     * the arrow continuing its swirl). 90 ° out, snaps back to 0 °.
     */
    private void spinSeekIcon(boolean leftSide) {
        if (mPlayerView == null) return;
        View icon = mPlayerView.findViewById(leftSide
                ? R.id.media_viewer_btn_seek_back
                : R.id.media_viewer_btn_seek_forward);
        if (icon == null) return;
        icon.animate().cancel();
        icon.setRotation(0f);
        icon.animate()
                .rotationBy(leftSide ? -45f : 45f)
                .setDuration(220L)
                .withEndAction(() -> {
                    if (icon.getParent() == null) return;
                    icon.animate().rotation(0f).setDuration(180L).start();
                })
                .start();
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
        Log.d(TAG, "[onPipModeChanged] inPip=" + inPip);
        if (mPlayerView == null) return;
        if (inPip) {
            mPlayerView.hideController();
            setChromeVisible(false);
        }
        // No reset on exit. The bottom-bar inner row is pinned at
        // android:layout_height="44dp" + layout_gravity="bottom" in
        // exo_media_viewer_controller.xml. The bar's
        // PlayerControlView-driven inflation can't pull the controls
        // upward as long as the row is anchored to the bar's bottom
        // edge — any setLayoutParams() call here that wrote
        // WRAP_CONTENT back would re-open the wrap_content
        // vulnerability and the bar would re-inflate, as #104's log
        // showed when resetBottomBarSizing() was still wired up.
        dumpBottomBarStructure("[onPipModeChanged inPip=" + inPip + "]");
    }

    /**
     * Diagnostic — dumps the height + child tree of exo_bottom_bar and
     * one level deeper (the inner LinearLayout's children) so the
     * "bar grows after PiP exit" symptom can be tracked on-device.
     * Records measured / laid-out heights, padding, minHeight, and
     * LayoutParams.height for each node — the four signals that
     * collectively pin down where the inflation is coming from.
     * Filter with `adb logcat -s MediaViewerFragment`. Strip the
     * helpers once a fix sticks.
     */
    private void dumpBottomBarStructure(@NonNull String tag) {
        if (mPlayerView == null) return;
        final View bottomBar = mPlayerView.findViewById(R.id.exo_bottom_bar);
        if (!(bottomBar instanceof ViewGroup bottomBarGroup)) return;
        StringBuilder sb = new StringBuilder();
        sb.append(tag).append(" exo_bottom_bar h=").append(bottomBar.getHeight())
                .append(" measuredH=").append(bottomBar.getMeasuredHeight())
                .append(" minH=").append(bottomBar.getMinimumHeight())
                .append(" topY=").append(bottomBar.getTop())
                .append(" bottomY=").append(bottomBar.getBottom())
                .append(" pT=").append(bottomBar.getPaddingTop())
                .append(" pB=").append(bottomBar.getPaddingBottom())
                .append(" lpH=").append(lpHName(
                        bottomBar.getLayoutParams() == null ? Integer.MIN_VALUE
                                : bottomBar.getLayoutParams().height))
                .append(" cc=").append(bottomBarGroup.getChildCount());
        for (int i = 0; i < bottomBarGroup.getChildCount(); i++) {
            View c = bottomBarGroup.getChildAt(i);
            appendViewSummary(sb, "child[" + i + "]", c);
            if (c instanceof ViewGroup) {
                ViewGroup cg = (ViewGroup) c;
                for (int j = 0; j < cg.getChildCount(); j++) {
                    appendViewSummary(sb, "  inner[" + j + "]", cg.getChildAt(j));
                }
            }
        }
        Log.d(TAG, sb.toString());
    }

    private void appendViewSummary(StringBuilder sb, String prefix, View v) {
        sb.append(" | ").append(prefix).append(" ")
                .append(v.getClass().getSimpleName())
                .append(" id=").append(idName(v.getId()))
                .append(" h=").append(v.getHeight())
                .append(" measuredH=").append(v.getMeasuredHeight())
                .append(" minH=").append(v.getMinimumHeight());
        ViewGroup.LayoutParams lp = v.getLayoutParams();
        if (lp != null) {
            sb.append(" lpH=").append(lpHName(lp.height));
        }
        if (v instanceof ViewGroup) {
            sb.append(" cc=").append(((ViewGroup) v).getChildCount());
        }
    }

    private String lpHName(int v) {
        if (v == ViewGroup.LayoutParams.MATCH_PARENT) return "MATCH";
        if (v == ViewGroup.LayoutParams.WRAP_CONTENT) return "WRAP";
        if (v == Integer.MIN_VALUE) return "n/a";
        return String.valueOf(v);
    }

    private String idName(int id) {
        if (id == View.NO_ID) return "no-id";
        try {
            return getResources().getResourceEntryName(id);
        } catch (Resources.NotFoundException e) {
            return "0x" + Integer.toHexString(id);
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


    /**
     * Reach into PlayerView, find its inner exo_content_frame
     * (an AspectRatioFrameLayout), and set the aspect ratio
     * synchronously from the file's metadata so the layout pass
     * with the correct dimensions runs BEFORE ExoPlayer starts
     * decoding. Eliminates the stretched first-frame flash caused
     * by media3 only updating the aspect from onVideoSizeChanged
     * (which can land after the surface has already painted) — the
     * frame paints to the still-MATCH_PARENT TextureView at full
     * screen and only snaps to the letterbox once the async size
     * event arrives. On a landscape clip that flash is the whole
     * screen (a portrait phone letterboxes it to a thin band), and
     * on a short clip the player can run to its end before the
     * resize lands, so the stretched "still" frame just sits there
     * until it settles — the reported "this one video fills the
     * screen during the transition" symptom.
     *
     * The preset only helps if it actually resolves the dimensions —
     * see {@link #readVideoDimensions} for the load-bearing detail
     * (picking the right setDataSource overload for the uri's scheme;
     * the wrong one is why this silently no-op'd for every owned video
     * until now). A MediaExtractor fallback additionally covers the
     * rarer files whose container metadata MMR drops but ExoPlayer
     * still plays.
     *
     * Both readers are a few-ms metadata read (no frame decode), safe
     * on the UI thread for cold launch. If both fail we still fall
     * through to media3's normal runtime resize.
     */
    @OptIn(markerClass = UnstableApi.class)
    private void presetVideoAspectRatio(Uri uri) {
        if (uri == null || mPlayerView == null || mActivity == null) return;

        // {width, height, rotation}, adjusted for rotation below.
        int[] dims = readVideoDimensions(uri);
        if (dims == null) return;
        int w = dims[0];
        int h = dims[1];
        int rotation = dims[2];
        // 90 / 270 rotation means the displayed aspect is the
        // inverse of the encoded one.
        if (rotation == 90 || rotation == 270) {
            int tmp = w; w = h; h = tmp;
        }
        if (w <= 0 || h <= 0) return;
        float aspect = (float) w / (float) h;

        View contentFrame = mPlayerView.findViewById(androidx.media3.ui.R.id.exo_content_frame);
        if (contentFrame instanceof AspectRatioFrameLayout) {
            ((AspectRatioFrameLayout) contentFrame).setAspectRatio(aspect);
        }

        // Match the transition poster to the SAME aspect as the video.
        // photo_view sits BEHIND the player and shows through the
        // transparent shutter until the first frame renders; if its shape
        // differs from the video's letterbox it peeks out above/below the
        // frame (the 16:10 card set in onCreateView did exactly that on a
        // wide 2.35:1 clip). The 16:10 there is only a pre-metadata
        // fallback — override it with the real video aspect here, which
        // runs in onViewCreated BEFORE the postponed shared-element
        // transition captures photo_view's end bounds. For a video-frame
        // thumbnail this makes centerCrop a no-op crop, so the poster
        // coincides exactly with where the player will draw — nothing
        // peeks, and there is no full-screen fill (the poster stays a
        // band, not the whole screen).
        if (mPhotoView != null && !mAvoidTransition) {
            mPhotoView.setAspectRatio(aspect);
        }
    }

    /**
     * Resolve the video's coded {width, height, rotation} from the
     * file. Tries MediaMetadataRetriever first, then MediaExtractor;
     * returns null only when neither reader can read a positive size.
     *
     * The uri is the same source ExoPlayer plays, and its scheme
     * decides which setDataSource overload is correct — this is the
     * crux of the "only this file fills the screen" bug:
     *  • Owned files arrive as {@code Uri.parse(filePath)} — a
     *    SCHEMELESS path uri (no file://). The (Context, Uri) overload
     *    resolves through ContentResolver and CANNOT open a bare path,
     *    so it threw and the preset silently no-op'd for every owned
     *    video — the content frame stayed MATCH_PARENT and the first
     *    frame painted full-screen. It was invisible on portrait clips
     *    (a portrait video fills a portrait screen anyway) and only
     *    showed on a landscape clip, which letterboxes to a band. Raw
     *    paths must go through the String overload.
     *  • A foreign-owned RESTORED file resolves to a SAF content://
     *    grant, which only the (Context, Uri) overload can open.
     */
    @Nullable
    private int[] readVideoDimensions(@NonNull Uri uri) {
        String scheme = uri.getScheme();
        boolean isContent = "content".equals(scheme);
        String path = uri.getPath();

        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            if (isContent) {
                retriever.setDataSource(mActivity, uri);
            } else if (path != null) {
                retriever.setDataSource(path);
            }
            String wStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH);
            String hStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT);
            String rStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION);
            if (wStr != null && hStr != null) {
                int w = Integer.parseInt(wStr);
                int h = Integer.parseInt(hStr);
                if (w > 0 && h > 0) {
                    int rotation = 0;
                    if (rStr != null) {
                        try { rotation = Integer.parseInt(rStr); } catch (NumberFormatException ignored) {}
                    }
                    return new int[]{w, h, rotation};
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "readVideoDimensions: MMR failed", e);
        } finally {
            try { retriever.release(); } catch (Exception ignored) {}
        }

        // Fallback: read the track-level MediaFormat directly (recovers
        // files whose container metadata MMR drops). Same scheme rule.
        MediaExtractor extractor = new MediaExtractor();
        try {
            if (isContent) {
                extractor.setDataSource(mActivity, uri, null);
            } else if (path != null) {
                extractor.setDataSource(path);
            } else {
                return null;
            }
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat format = extractor.getTrackFormat(i);
                String mime = format.getString(MediaFormat.KEY_MIME);
                if (mime == null || !mime.startsWith("video/")) continue;
                if (!format.containsKey(MediaFormat.KEY_WIDTH)
                        || !format.containsKey(MediaFormat.KEY_HEIGHT)) continue;
                int w = format.getInteger(MediaFormat.KEY_WIDTH);
                int h = format.getInteger(MediaFormat.KEY_HEIGHT);
                if (w <= 0 || h <= 0) continue;
                int rotation = 0;
                // KEY_ROTATION is API 23+; guard with containsKey since
                // not every extractor populates it.
                if (format.containsKey(MediaFormat.KEY_ROTATION)) {
                    rotation = format.getInteger(MediaFormat.KEY_ROTATION);
                }
                return new int[]{w, h, rotation};
            }
        } catch (Exception e) {
            Log.w(TAG, "readVideoDimensions: MediaExtractor failed", e);
        } finally {
            try { extractor.release(); } catch (Exception ignored) {}
        }
        return null;
    }

    private final RequestListener<Drawable> mRequestListener = new RequestListener<>() {
        @Override
        public boolean onLoadFailed(@Nullable GlideException e, Object model, @NonNull Target<Drawable> target, boolean isFirstResource) {
            Log.d(TAG, "onLoadFailed", e);
            if(mActivity == null)
                return false;
            startPostponedEnterTransition();
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
