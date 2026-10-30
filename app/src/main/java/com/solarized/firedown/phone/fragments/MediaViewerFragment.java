package com.solarized.firedown.phone.fragments;

import android.annotation.SuppressLint;
import android.content.ActivityNotFoundException;
import android.content.ContentResolver;
import android.content.Context;
import android.content.SharedPreferences;

import android.content.res.Resources;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.Environment;
import android.os.SystemClock;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import android.text.TextUtils;
import android.transition.Transition;
import android.util.Log;
import android.view.GestureDetector;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.OptIn;
import androidx.appcompat.app.ActionBar;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.fragment.app.DialogFragment;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.lifecycle.Lifecycle;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.PlaybackParameters;
import androidx.media3.common.Player;
import androidx.media3.common.TrackSelectionOverride;
import androidx.media3.common.TrackSelectionParameters;
import androidx.media3.common.Tracks;
import androidx.media3.common.VideoSize;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.SeekParameters;
import androidx.media3.exoplayer.mediacodec.MediaCodecRenderer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.extractor.DefaultExtractorsFactory;
import androidx.media3.extractor.ExtractorsFactory;
import androidx.media3.ui.AspectRatioFrameLayout;
import androidx.media3.ui.PlayerView;
import androidx.media3.ui.TimeBar;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.load.engine.GlideException;
import com.bumptech.glide.request.RequestListener;
import com.bumptech.glide.request.RequestOptions;
import com.bumptech.glide.request.target.Target;
import com.bumptech.glide.signature.ObjectKey;
import com.google.android.material.snackbar.Snackbar;
import com.solarized.firedown.App;
import com.solarized.firedown.BuildConfig;
import com.solarized.firedown.GlideRequestOptions;
import com.solarized.firedown.Preferences;
import com.solarized.firedown.data.Download;
import com.solarized.firedown.data.di.RepositoryEntryPoint;
import com.solarized.firedown.data.repository.DownloadDataRepository;
import com.solarized.firedown.manager.UrlType;
import com.solarized.firedown.phone.dialogs.PlaybackSpeedSheetDialogFragment;
import com.solarized.firedown.phone.dialogs.SubtitleSheetDialogFragment;
import com.solarized.firedown.glide.MimeTypeThumbnail;
import com.solarized.firedown.phone.PlayerActivity;
import com.solarized.firedown.phone.player.HoldToSpeedUp;
import com.solarized.firedown.phone.player.PlaybackDebugInfo;
import com.solarized.firedown.phone.player.PlaybackHub;
import com.solarized.firedown.phone.player.PlaybackSpeeds;
import com.solarized.firedown.phone.player.PlayerControls;
import com.solarized.firedown.phone.player.PlayerSubtitles;
import com.solarized.firedown.ui.AspectRatioImageView;
import com.solarized.firedown.R;
import com.solarized.firedown.data.RestoredFileAccess;
import com.solarized.firedown.data.entity.DownloadEntity;
import com.solarized.firedown.ffmpegutils.FFmpegMetaData;
import com.solarized.firedown.ffmpegutils.FFmpegMetaDataReader;
import com.solarized.firedown.utils.FileUriHelper;
import com.solarized.firedown.Keys;
import com.solarized.firedown.utils.FragmentArgs;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import dagger.hilt.android.EntryPointAccessors;

public class MediaViewerFragment extends Fragment implements SubtitleSheetDialogFragment.Host,
        PlaybackSpeedSheetDialogFragment.Host {

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
     * After a double-tap seek, further taps within this window continue
     * the streak (one more ±10 s each, window restarted per tap) — the
     * YouTube model. YouTube's own continuation window is ~650-800 ms;
     * the badge fade-out is tied to the same value so the feedback
     * disappears exactly when the streak arms down.
     */
    private static final long SEEK_STREAK_WINDOW_MS = 800L;

    /**
     * Cached so {@link #setChromeVisible(boolean)} can fire without
     * re-resolving from the activity each time. Nulled out by the
     * view-creation path being re-entered on configuration change.
     */
    private WindowInsetsControllerCompat mWindowInsetsController;

    private GestureDetector mPlayerGestureDetector;

    // ── Double-tap seek streak state (main thread only) ──────────────
    /** -1 = seeking back, +1 = forward, 0 = no streak. */
    private int mSeekStreakDir;
    /** uptimeMillis deadline after which the streak is over. */
    private long mSeekStreakUntilMs;
    /** Total nominal ms seeked this streak — drives the "−20 s" badge. */
    private long mSeekStreakAccumMs;
    /**
     * Set when onSingleTapUp consumed a tap as a streak continuation so
     * the same tap's later onSingleTapConfirmed doesn't ALSO toggle the
     * controller (the detector fires both for a tap with no follow-up).
     */
    private boolean mStreakTapConsumed;

    private TextView mSeekFeedbackLeft;
    private TextView mSeekFeedbackRight;

    // ── Bottom-row playback controls (main thread only) ──────────────
    /** Press and hold the video for 2× (see {@link HoldToSpeedUp}). */
    private HoldToSpeedUp mHoldToSpeedUp;
    /** The row's speed value; GONE for a file that isn't video or audio. */
    @Nullable
    private TextView mSpeedButton;
    /** The line under the Subtitles button, lit while a track is showing. */
    @Nullable
    private View mSubtitlesOn;

    // ── Scrub preview state (main thread only) ───────────────────────
    /**
     * How often a drag is allowed to seek. Media3 only seeks when the
     * finger LIFTS, so without this the video sat on one frame for the
     * whole drag and the user was scrubbing blind against the position
     * text. ~10 preview frames/s is enough to read the video while
     * dragging and cheap because the preview seeks are keyframe-only
     * (CLOSEST_SYNC, restored to exact on lift).
     */
    private static final long SCRUB_PREVIEW_INTERVAL_MS = 100L;

    private boolean mScrubbing;
    /** Play state to restore on lift — a drag pauses playback. */
    private boolean mResumeAfterScrub;
    /** Position the drag started from, to restore on a CANCELLED scrub. */
    private long mScrubStartPositionMs = C.TIME_UNSET;
    /** Latest dragged-to position, consumed by {@link #flushScrubPreview()}. */
    private long mPendingScrubMs = C.TIME_UNSET;
    /** uptimeMillis of the last preview seek, for the fixed interval. */
    private long mLastPreviewSeekAtMs;
    private boolean mScrubPreviewScheduled;
    private final Handler mScrubHandler = new Handler(Looper.getMainLooper());
    private final Runnable mScrubPreviewTask = this::flushScrubPreview;

    // ── Subtitles (main thread only) ─────────────────────────────────
    /**
     * Prefix of a side-loaded subtitle's id: the rest is its file name,
     * which the Subtitles sheet shows under the language. Read back from
     * the track's Format, so an ADOPTED player's sheet still has it.
     */
    private static final String SUBTITLE_ID_PREFIX = "fdsub:";

    /**
     * The media item's mime. Any non-adaptive mime pins
     * DefaultMediaSourceFactory to the progressive source; without one it
     * guesses from the uri, and a content:// uri or an odd extension is
     * not something to guess about.
     */
    private static final String PROGRESSIVE_MIME = "application/octet-stream";

    /** A subtitle file larger than this is refused (they are kilobytes). */
    private static final long MAX_SUBTITLE_BYTES = 8L * 1024 * 1024;

    private static final SecureRandom ID_RANDOM = new SecureRandom();

    private final Handler mMainHandler = new Handler(Looper.getMainLooper());

    @Nullable
    private DownloadDataRepository mDownloadRepository;

    @Nullable
    private SharedPreferences mSharedPreferences;

    /** The video's openable uri and data source, kept to rebuild the media. */
    private Uri mPlayUri;
    private DataSource.Factory mDataSourceFactory;

    /** Bumped per subtitle lookup: a slower, older lookup never lands. */
    private int mSubtitleLoadGen;

    /** Id of a subtitle the user just added: selected once its track exists. */
    @Nullable
    private String mPendingSubtitleId;

    /** Whether to resume playback after the file picker closes. */
    private boolean mResumeAfterPick;

    /**
     * "Add a subtitle file". A list of mimes, not one: providers disagree on
     * what a .srt is (application/x-subrip, text/plain, octet-stream), so a
     * single type would hide the very file the user is looking for. A picker
     * that ignores the list just shows everything; the import checks the
     * file either way.
     */
    private final ActivityResultLauncher<String[]> mPickSubtitle =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(),
                    this::onSubtitlePicked);

    /**
     * A field (not an inline anonymous listener) so it can be REMOVED
     * without releasing the player — background playback can outlive this
     * fragment (see onDestroy's ownership transfer), and a listener left
     * behind would leak the fragment + activity through mActivity.
     */
    private final Player.Listener mPlayerListener = new Player.Listener() {
        @Override
        public void onIsPlayingChanged(boolean isPlaying) {
            if (mActivity != null) mActivity.updatePipParams();
        }

        @Override
        public void onVideoSizeChanged(@NonNull VideoSize videoSize) {
            if (mActivity != null) mActivity.updatePipParams();
            // Runtime backstop for the first-frame stretch. When the
            // synchronous preset can't resolve the size — a RESTORED
            // content:// clip that MMR can't read and that has no stored
            // resolution (ffmpeg can't open its foreign-owned path) — the
            // content frame is left MATCH_PARENT and the first frame paints
            // fitXY-stretched. The decoder ALWAYS reports the true size
            // here (e.g. 498x334), so correct the content frame the instant
            // it does. media3 does this itself, but doing it explicitly
            // also fixes the POSTER band's aspect so, if the poster is still
            // showing, it matches the letterbox exactly (no peek). Applied
            // to the width/height as reported (already display-oriented via
            // pixelWidthHeightRatio).
            if (videoSize.width > 0 && videoSize.height > 0) {
                float par = videoSize.pixelWidthHeightRatio > 0f
                        ? videoSize.pixelWidthHeightRatio : 1f;
                float aspect = (videoSize.width * par) / videoSize.height;
                applyVideoAspect(aspect);
            }
        }

        /**
         * Video only: hide the first-frame poster once the
         * TextureView has something opaque to draw. For audio
         * mPhotoView is the steady-state artwork renderer (see
         * onCreateView), not a placeholder — it never hides. Fires
         * again for each NEW surface (media3 per-surface semantics),
         * which is what re-hides the poster when a relaunched fragment
         * ADOPTS the already-playing background player.
         */
        @Override
        public void onRenderedFirstFrame() {
            if (mPhotoView != null) mPhotoView.setVisibility(View.GONE);
        }

        /** Selects a subtitle the user just added, once its track exists. */
        @Override
        public void onTracksChanged(@NonNull Tracks tracks) {
            if (mPendingSubtitleId != null) {
                selectPendingSubtitle(tracks);
            }
            updateSubtitlesIndicator(tracks);
        }

        /** The sheet, a hold and its release all land here. */
        @Override
        public void onPlaybackParametersChanged(@NonNull PlaybackParameters playbackParameters) {
            updateSpeedButton();
        }

        /**
         * A failed playback must be VISIBLE — without this a decode
         * failure (e.g. an AV1 download on a device whose MediaCodec
         * can't decode AV1) left a silent black screen that read as
         * "the video doesn't open", with the real cause only in logcat.
         * Decode-class failures (the 4xxx PlaybackException family) get
         * the specific "can't decode this format" string, suffixed with
         * the codec name when the failure carries one — the on-device
         * report was a generic "Unknown error occurred" on an AV1 clip,
         * which named neither the problem nor the way out (re-download
         * at ≤1080p, where YouTube serves H264). Everything else keeps
         * the generic string; the full typed cause is always logged.
         */
        @OptIn(markerClass = UnstableApi.class)
        @Override
        public void onPlayerError(@NonNull PlaybackException error) {
            Log.e(TAG, "onPlayerError: " + error.getErrorCodeName(), error);
            if (mPlayerView == null || !isAdded()) return;
            int code = error.errorCode;
            boolean decodeFailure =
                    code == PlaybackException.ERROR_CODE_DECODER_INIT_FAILED
                    || code == PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED
                    || code == PlaybackException.ERROR_CODE_DECODING_FAILED
                    || code == PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES
                    || code == PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED;
            String message;
            if (decodeFailure) {
                message = getString(R.string.player_error_unsupported_format);
                String label = null;
                if (error.getCause() instanceof
                        MediaCodecRenderer.DecoderInitializationException die) {
                    label = codecLabel(die.mimeType);
                }
                if (label != null) {
                    // Appended in code so the string stays argument-free
                    // across the 16 locales.
                    message = message + " (" + label + ")";
                }
            } else {
                message = getString(R.string.error_unknown);
            }
            // "Copy" puts the full diagnosis on the clipboard — error code,
            // cause chain, decoder, the container's track formats with their
            // init data — for a release build on a device with no adb, where
            // this snackbar is the only channel. Indefinite: the player is
            // dead anyway, and a LONG timeout raced the tap.
            final String debug = PlaybackDebugInfo.describe(error, mExoPlayer,
                    mDownloadEntity.getFileName(), mDownloadEntity.getFileSize(),
                    mDownloadEntity.getFileMimeType());
            Snackbar.make(mPlayerView, message, Snackbar.LENGTH_INDEFINITE)
                    .setAction(R.string.settings_sync_code_copy, v -> {
                        PlaybackDebugInfo.copy(v.getContext(), debug);
                        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                            // 13+ shows its own paste-preview toast.
                            Snackbar.make(v, R.string.clipboard, Snackbar.LENGTH_SHORT).show();
                        }
                    })
                    .show();
        }
    };

    /**
     * Short user-facing codec label from a decoder-failure mime type
     * ("video/av01" → "AV1"); null stays null (no suffix shown).
     */
    @Nullable
    private static String codecLabel(@Nullable String mimeType) {
        if (mimeType == null) return null;
        int slash = mimeType.indexOf('/');
        String sub = slash >= 0 ? mimeType.substring(slash + 1) : mimeType;
        switch (sub) {
            case "av01": return "AV1";
            case "avc": return "H.264";
            case "hevc": return "HEVC";
            case "x-vnd.on2.vp9": return "VP9";
            case "x-vnd.on2.vp8": return "VP8";
            default: return sub.toUpperCase(Locale.US);
        }
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

        // The activity is not a Hilt entry point, so the fragment can't be
        // one either; the singletons come through the app's entry point.
        RepositoryEntryPoint entryPoint = EntryPointAccessors.fromApplication(
                App.getAppContext(), RepositoryEntryPoint.class);
        mDownloadRepository = entryPoint.getDownloadDataRepository();
        mSharedPreferences = entryPoint.getSharedPreferences();

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

        mSeekFeedbackLeft = v.findViewById(R.id.media_viewer_seek_feedback_left);

        mSeekFeedbackRight = v.findViewById(R.id.media_viewer_seek_feedback_right);

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
            // Bound photo_view to a 16:10 centred centerCrop card. This
            // is the SHARED ELEMENT (video_view), and the source it flies
            // from is the list/grid R.id.image — a centerCrop thumbnail in
            // a bounded cell. Giving photo_view the SAME scaleType
            // (centerCrop) and a BOUNDED band — the AspectRatioImageView
            // self-measures to the ratio, so its captured shared-element
            // target is a band, not the full screen — leaves the
            // ChangeImageTransform a clean bounds-grow to animate instead
            // of interpolating a centerCrop-in-cell matrix toward a
            // fitCenter-full-screen one. That interpolation is what
            // briefly blows a LANDSCAPE first frame up to fill the whole
            // screen before it snaps to the letterbox — the reported "one
            // landscape video fills the screen during the transition".
            //
            // This MUST cover video as well as audio (a previous
            // consolidation moved it into the audio-only branch, leaving
            // photo_view unbounded/fitCenter for video — its own kind of
            // balloon). Together with the OPAQUE video shutter below they
            // address the two DISTINCT layers seen mid-transition in the
            // reported recording: the shutter hides the fitXY-stretched
            // TextureView surface, and this keeps the poster (the shared
            // element itself) a clean bounded band instead of a fitCenter-
            // full-screen target. 16:10 is only the fallback shape —
            // presetVideoAspectRatio() overrides it with the video's real
            // aspect so the band coincides with the letterbox. For video
            // the poster is only shown until onRenderedFirstFrame swaps in
            // the real, letterboxed frame; audio keeps it as steady-state
            // artwork.
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
            // extraction fails. The shutter is left transparent (XML
            // default) so this artwork shows through — there is no video
            // surface to stretch.
            mPlayerView.setUseArtwork(false);
            mPhotoView.setImageDrawable(mFallbackDrawable);
        } else {
            // VIDEO: re-enable media3's OPAQUE shutter (the XML overrides
            // it transparent). This is THE guard for the "fullscreen
            // portrait frame during the transition" symptom, confirmed
            // frame-by-frame in the reported recording: PlayerView.set-
            // Player() resets the inner exo_content_frame to aspect 0 (=
            // MATCH_PARENT) because the fresh player's video size is still
            // unknown, so the FIRST decoded frame paints STRETCHED (fitXY)
            // to the whole screen until onVideoSizeChanged corrects the
            // aspect. presetVideoAspectRatio() tries to pre-size the frame
            // but the first frame can paint before that relayout lands, so
            // it is not sufficient on its own. media3's own guard is the
            // shutter — it is hidden only in onRenderedFirstFrame, which
            // fires AFTER onVideoSizeChanged has resized the frame, so an
            // opaque shutter covers the surface through exactly the
            // stretched window. Making it transparent (a previous attempt,
            // to reveal the poster) is what EXPOSED the stretch through the
            // shutter — the recording showed the stretched fullscreen frame
            // behind the correctly-sized poster band.
            //
            // Do NOT bringToFront() photo_view. photo_view is the poster
            // and must stay BEHIND player_view (its natural child order):
            // during the shared-element transition it is drawn on top
            // anyway via the transition overlay, so bringing it to front
            // buys nothing there — but it MOVES the poster above the video
            // for steady state, so if onRenderedFirstFrame is ever slow
            // (a large/slow decode) or never runs (the singleTask /
            // onNewIntent reuse path fires NO transition), the poster sits
            // OVER the player and the video plays hidden behind it — the
            // exact "static poster on top, video in the background"
            // regression reported. Behind the video, a poster that fails
            // to hide is harmless (the video covers it). The tiny window
            // between transition-end and first-frame is a brief black
            // shutter, not a stretch — and in practice the first frame
            // renders mid-transition, so there is no visible gap.
            mPlayerView.setShutterBackgroundColor(Color.BLACK);
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
        setupScrubPreview();
        setupPlaybackButtons(v);

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
        // navigation-bar inset is exo_bottom_bar (scrubber + the time and
        // playback row); exo_center_controls is centred and needs no
        // bottom clearance. While bars are
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

        // A relaunch onto the same file while background playback runs
        // (notification tap) ADOPTS the live player instead of building a
        // second one — two players over one file is a double-audio leak.
        // The hub only matches the same file path; anything else builds
        // fresh (the previous fragment's teardown handled the old player).
        ExoPlayer adopted = PlaybackHub.adopt(mDownloadEntity);
        boolean adopting = adopted != null;

        if (adopting) {
            mExoPlayer = adopted;
        } else {
            // Audio focus (handleAudioFocus=true) + becoming-noisy +
            // wake mode are what make BACKGROUND playback well-behaved:
            // media3 runs the full focus state machine (pause on loss,
            // resume on transient regain, duck) and pauses on headphone
            // unplug, so PlayerPlaybackService needs neither hand-rolled —
            // don't re-add the GeckoMediaPlaybackService focus machinery
            // there. WAKE_MODE_LOCAL holds a CPU wake lock only while
            // playing (local files — no wifi lock needed), which is what
            // keeps audio running with the screen off.
            AudioAttributes audioAttributes = new AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(FileUriHelper.isAudio(mimeType)
                            ? C.AUDIO_CONTENT_TYPE_MUSIC
                            : C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build();
            // APPLICATION context, never mActivity: the Builder's
            // lazily-built components (DefaultRenderersFactory & co.)
            // retain the raw context they were given, and this player is
            // DESIGNED to outlive the activity — it sits in the static
            // PlaybackHub and keeps playing under PlayerPlaybackService
            // after the system reclaims the backgrounded activity. Built
            // on mActivity it would pin the destroyed PlayerActivity for
            // the whole background session.
            mExoPlayer = new ExoPlayer.Builder(App.getAppContext())
                    .setAudioAttributes(audioAttributes, true)
                    .setHandleAudioBecomingNoisy(true)
                    .setWakeMode(C.WAKE_MODE_LOCAL)
                    .build();
        }

        // Notify the activity when play-state or video size changes so
        // it can refresh the PiP action icon / aspect ratio. A field
        // listener (see its comment) so ownership transfer can remove it.
        mExoPlayer.addListener(mPlayerListener);

        // Register with the hub either way: attach() refreshes the entity
        // and records THIS fragment as the release owner (after an adopt,
        // that supersedes the predecessor fragment — whose teardown may
        // run AFTER this, see onDestroy's isOwner gate).
        PlaybackHub.attach(mExoPlayer, mDownloadEntity, this);

        // An adopted player arrives with its speed and tracks already set.
        updateSpeedButton();
        updateSubtitlesIndicator(mExoPlayer.getCurrentTracks());

        // Default: read the raw path with a FileDataSource (owned files; the
        // vault's encrypted/safe entries keep this exact path untouched).
        //
        // Uri.fromFile, NEVER Uri.parse(filePath): parse() treats the raw
        // path as an ALREADY-ENCODED uri string, so a '%' in the filename
        // acts as an escape introducer — "4% Of" decodes through
        // Uri.getPath() into U+FFFD replacement chars and FileDataSource
        // throws FileNotFoundException("Invalid file path") on a file that
        // exists (shipped: a YouTube title with '%' was unplayable on every
        // device and the mojibake path in the log was the tell; a '#' would
        // truncate the path as a fragment marker the same way). fromFile()
        // ENCODES the path, so getPath() round-trips it verbatim.
        Uri playUri = Uri.fromFile(new File(mDownloadEntity.getFilePath()));

        // A foreign-owned RESTORED file can't be opened by path (EACCES). When
        // it resolves to the persisted SAF content:// grant, play THAT. Scoped
        // to non-vault entries so encrypted/safe playback reads the path as
        // it always did.
        if (!mDownloadEntity.isFileEncrypted() && !mDownloadEntity.isFileSafe()) {
            Uri openable = RestoredFileAccess.openableUri(mActivity, mDownloadEntity.getFilePath());
            if (openable != null && "content".equals(openable.getScheme())) {
                playUri = openable;
            }
        }
        mPlayUri = playUri;
        // DefaultDataSource for every file: it reads a file:// uri through
        // the same FileDataSource the player always used, and also speaks
        // content://, which a subtitle file may need even when the video
        // doesn't (a restored subtitle next to an owned video). App context
        // for the same reason as the Builder above — the factory rides
        // inside the media source, inside the player, inside the static hub.
        mDataSourceFactory = new DefaultDataSource.Factory(App.getAppContext());

        if (!adopting) {
            // A video waits for its subtitle lookup (one bounded query, a few
            // ms) so the player is prepared ONCE, with them; preparing first
            // and adding them after would restart the extractor under the
            // first frame. The lookup sets the media and prepares; onStart
            // prepares too if it lands first. Audio carries no subtitles.
            applySubtitlePreference();
            if (FileUriHelper.isAudio(mimeType)) {
                setMediaWithSubtitles(Collections.emptyList(), C.TIME_UNSET, null, false);
            } else {
                loadMediaWithSubtitles(C.TIME_UNSET, null, false);
            }
        }

        // Pre-set PlayerView's inner AspectRatioFrameLayout from the
        // file's own video dimensions so the content frame is the right
        // shape when the FIRST frame paints. Otherwise it stays at
        // MATCH_PARENT until Player.Listener.onVideoSizeChanged fires,
        // which can land AFTER the first frame has painted to the
        // TextureView at full-screen dimensions — the frame is drawn
        // STRETCHED (fitXY) to the whole screen until the async size event
        // arrives. See androidx/media#536.
        //
        // This MUST run AFTER setPlayer(). PlayerView.setPlayer() calls its
        // own updateAspectRatio() with the fresh player's still-UNKNOWN
        // video size, which resets exo_content_frame to aspect 0 (=
        // MATCH_PARENT); presetting before setPlayer (as the code used to)
        // is silently undone. setPlayWhenReady() below hasn't started
        // decoding yet, so the aspect set here is in place for the first
        // frame. (Verified against media3 1.10.1 PlayerView source.)
        mPlayerView.setPlayer(mExoPlayer);

        if (!FileUriHelper.isAudio(mimeType)) {
            presetVideoAspectRatio(playUri);
        }

        if (!adopting) {
            mExoPlayer.setPlayWhenReady(true);
        }

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


    // There is deliberately NO onPause pause any more. It used to pause
    // whenever the activity lost the resumed state (screen off, lock, a
    // dialog over the player, split-screen), which is exactly what
    // background playback must NOT do. The pause/stop decision now lives
    // one level up: PlayerActivity.onStop arms background playback (the
    // PlaybackHub flag + PlayerPlaybackService) BEFORE the fragment's
    // onStop runs, and this fragment stops the player only when that
    // didn't happen. Transient onPause states (permission dialog,
    // split-screen focus loss) keep playing — the media-player norm.
    // Audio-focus loss pauses via media3's own handling (the player is
    // built with handleAudioFocus=true), which covers the "another app
    // started playing" case onPause used to approximate.

    @Override
    public void onStart() {
        super.onStart();
        // Resume path for a player that onStop stopped (paused + screen
        // off): stop() parks it in IDLE with position + playWhenReady
        // retained, so prepare() restores the frame at the same position
        // without starting playback the user had paused. Without this the
        // returning activity showed a dead player (the pre-background-
        // playback bug: nothing ever re-prepared after onStop).
        // A player still waiting for its first media (the subtitle lookup
        // hasn't landed) is left alone: the lookup prepares it.
        if (mExoPlayer != null && mExoPlayer.getPlaybackState() == Player.STATE_IDLE
                && mExoPlayer.getMediaItemCount() > 0) {
            mExoPlayer.prepare();
        }
    }

    @Override
    public void onStop() {
        super.onStop();
        // Before the stop() below: a drag in flight when the activity
        // goes away never gets its lift event, and the pause + keyframe
        // seek parameters it installed would otherwise survive into
        // background playback. A hold is the same: its lift never comes.
        abortScrubPreview();
        mHoldToSpeedUp.end();
        Glide.with(App.getAppContext()).clear(mPhotoView);
        // No PiP guard here: while the floating window is visible the
        // activity sits in PAUSED, not STOPPED — onStop only fires when
        // PiP is being torn down (X button or another app covers it),
        // and in both cases we want playback to stop. The earlier guard
        // also broke the X-close path because isInPictureInPictureMode()
        // can still report true at onStop time on the finish path, so
        // the guard skipped stop() and the player kept emitting audio
        // until release().
        //
        // The background-playback gate is SAFE against that same X-close
        // trap because it is not derived from PiP state: PlayerActivity
        // arms it only when the activity is NOT finishing (its onStop runs
        // before super dispatches this one), so every finish path lands
        // here with the flag false and stops the player exactly as before.
        if (mExoPlayer != null && !PlaybackHub.isBackgroundActive())
            mExoPlayer.stop();
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
     * Whether this entity may keep playing after the activity backgrounds.
     * Vault (safe/encrypted) entries are excluded on purpose: background
     * playback puts the file's NAME on the lock screen and in the
     * notification shade, and vault content is device-auth gated — the same
     * "the vault never leaves its trust domain" contract as the backup
     * mirror and P2P send. Those keep the old stop-on-background behavior.
     */
    public boolean isBackgroundPlaybackEligible() {
        return mDownloadEntity != null
                && !mDownloadEntity.isFileEncrypted()
                && !mDownloadEntity.isFileSafe()
                && (isVideoMime() || isAudioMime());
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

    // ── Subtitles ────────────────────────────────────────────────────

    /**
     * The mimes the "Add a subtitle file" picker asks for (see
     * {@link #mPickSubtitle}).
     */
    private static final String[] SUBTITLE_PICK_MIMES = {
            "application/x-subrip", "text/*", "application/octet-stream",
            "application/ttml+xml", "application/xml"
    };

    /**
     * Honours "Off" from an earlier session: a downloaded subtitle still
     * attaches (one tap away in the sheet) but no longer shows by itself.
     * Only the DEFAULT flag is ignored, which is how this player marks the
     * subtitle it would show; forced subtitles and the system caption
     * settings are left to the track selector as always.
     */
    private void applySubtitlePreference() {
        boolean on = mSharedPreferences == null
                || mSharedPreferences.getBoolean(Preferences.PLAYER_SUBTITLES_ON, true);
        mExoPlayer.setTrackSelectionParameters(mExoPlayer.getTrackSelectionParameters()
                .buildUpon()
                .setIgnoredTextSelectionFlags(on ? 0 : C.SELECTION_FLAG_DEFAULT)
                .build());
    }

    /**
     * Finds the video's subtitles on a worker thread, then hands the player
     * the video with them. {@code positionMs} is where to continue
     * (C.TIME_UNSET = from the start); {@code selectId} a subtitle to select
     * once its track exists; {@code resume} plays when the media is set. A
     * lookup overtaken by a newer one, or landing after this fragment let go
     * of the player, does nothing.
     */
    private void loadMediaWithSubtitles(long positionMs, @Nullable String selectId,
                                        boolean resume) {
        final int gen = ++mSubtitleLoadGen;
        final ExoPlayer player = mExoPlayer;
        final DownloadEntity video = mDownloadEntity;
        final DownloadDataRepository repository = mDownloadRepository;
        final Context context = App.getAppContext();
        new Thread(() -> {
            List<MediaItem.SubtitleConfiguration> subtitles =
                    findSubtitles(context, repository, video);
            mMainHandler.post(() -> {
                if (gen != mSubtitleLoadGen || player == null || player != mExoPlayer) {
                    return;
                }
                setMediaWithSubtitles(subtitles, positionMs, selectId, resume);
            });
        }, "subtitle-lookup").start();
    }

    /**
     * Sets the video, with {@code subtitles} side-loaded, as the player's
     * media. DefaultMediaSourceFactory parses each subtitle file during
     * extraction (its default; the legacy in-renderer decoding is off in this
     * media3) and merges it with the video. Prepares only while the fragment
     * is started — a lookup landing after onStop must not start playback in
     * the background; onStart prepares then.
     */
    private void setMediaWithSubtitles(List<MediaItem.SubtitleConfiguration> subtitles,
                                       long positionMs, @Nullable String selectId,
                                       boolean resume) {
        ExtractorsFactory extractorsFactory = new DefaultExtractorsFactory()
                .setConstantBitrateSeekingEnabled(true)
                .setConstantBitrateSeekingAlwaysEnabled(true);
        MediaItem mediaItem = new MediaItem.Builder()
                .setUri(mPlayUri)
                .setMimeType(PROGRESSIVE_MIME)
                .setSubtitleConfigurations(subtitles)
                .build();
        MediaSource source = new DefaultMediaSourceFactory(mDataSourceFactory, extractorsFactory)
                .createMediaSource(mediaItem);
        if (selectId != null) {
            mPendingSubtitleId = selectId;
        }
        if (positionMs == C.TIME_UNSET) {
            mExoPlayer.setMediaSource(source);
        } else {
            mExoPlayer.setMediaSource(source, positionMs);
        }
        if (mExoPlayer.getPlaybackState() == Player.STATE_IDLE
                && getLifecycle().getCurrentState().isAtLeast(Lifecycle.State.STARTED)) {
            mExoPlayer.prepare();
        }
        if (resume) {
            mExoPlayer.setPlayWhenReady(true);
        }
    }

    /** One subtitle file found for the video, before it becomes a config. */
    private static final class FoundSubtitle {
        final DownloadEntity row;
        final String sampleMime;
        final Uri uri;
        @Nullable
        final String tag;
        final String label;

        FoundSubtitle(DownloadEntity row, String sampleMime, Uri uri,
                      @Nullable String tag, String label) {
            this.row = row;
            this.sampleMime = sampleMime;
            this.uri = uri;
            this.tag = tag;
            this.label = label;
        }
    }

    /**
     * The video's downloaded subtitles as side-loaded configurations: every
     * subtitle file from the video's page or linked to it whose file is still
     * there, sorted by name, with the one {@link PlayerSubtitles#pickDefault}
     * chooses flagged DEFAULT (it shows by itself). Worker thread only.
     */
    private static List<MediaItem.SubtitleConfiguration> findSubtitles(
            Context context, @Nullable DownloadDataRepository repository,
            DownloadEntity video) {
        List<MediaItem.SubtitleConfiguration> configs = new ArrayList<>();
        if (repository == null || video.isFileEncrypted()) {
            return configs;
        }
        List<DownloadEntity> rows;
        try {
            rows = repository.findPlayerCompanionsSync(video.isFileSafe(), video.getId(),
                    video.getOriginUrl());
        } catch (RuntimeException e) {
            logSubtitle("lookup failed", e);
            return configs;
        }
        String origin = video.getOriginUrl();
        Locale locale = Locale.getDefault();
        int otherVideos = 0;
        List<FoundSubtitle> found = new ArrayList<>();
        for (DownloadEntity row : rows) {
            if (row.isFileEncrypted()) {
                continue;
            }
            String storedMime = row.getFileMimeType();
            if (FileUriHelper.isVideo(storedMime)) {
                if (!TextUtils.isEmpty(origin) && origin.equals(row.getOriginUrl())) {
                    otherVideos++;
                }
                continue;
            }
            String sampleMime = PlayerSubtitles.mimeFor(row.getFileName(), storedMime);
            if (sampleMime == null) {
                continue;
            }
            Uri uri = RestoredFileAccess.openableUri(context, row.getFilePath());
            if (uri == null) {
                continue; // the file is gone
            }
            String tag = PlayerSubtitles.languageTag(row.getFileName());
            String label = row.getFileLanguage();
            if (TextUtils.isEmpty(label)) {
                label = PlayerSubtitles.displayLanguage(tag, locale);
            }
            if (TextUtils.isEmpty(label)) {
                label = row.getFileName();
            }
            found.add(new FoundSubtitle(row, sampleMime, uri, tag, label));
        }
        found.sort((a, b) -> String.CASE_INSENSITIVE_ORDER.compare(a.label, b.label));
        List<PlayerSubtitles.Candidate> candidates = new ArrayList<>();
        for (FoundSubtitle subtitle : found) {
            candidates.add(new PlayerSubtitles.Candidate(subtitle.row.getFileName(), subtitle.tag,
                    subtitle.row.getParentId() == video.getId()));
        }
        int preferred = PlayerSubtitles.pickDefault(candidates, video.getFileName(), otherVideos,
                locale.getLanguage());
        for (int i = 0; i < found.size(); i++) {
            FoundSubtitle subtitle = found.get(i);
            configs.add(new MediaItem.SubtitleConfiguration.Builder(subtitle.uri)
                    .setMimeType(subtitle.sampleMime)
                    .setLanguage(PlayerSubtitles.languageOf(subtitle.tag))
                    .setLabel(subtitle.label)
                    .setId(SUBTITLE_ID_PREFIX + subtitle.row.getFileName())
                    .setSelectionFlags(i == preferred ? C.SELECTION_FLAG_DEFAULT : 0)
                    .build());
        }
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "subtitles: " + configs.size() + " attached, default=" + preferred
                    + ", other videos from the page=" + otherVideos);
        }
        return configs;
    }

    /** A subtitle track the player can show, as the sheet lists them. */
    private static final class TextTrack {
        final Tracks.Group group;
        final int index;

        TextTrack(Tracks.Group group, int index) {
            this.group = group;
            this.index = index;
        }
    }

    private List<TextTrack> textTracks() {
        List<TextTrack> tracks = new ArrayList<>();
        if (mExoPlayer == null) {
            return tracks;
        }
        for (Tracks.Group group : mExoPlayer.getCurrentTracks().getGroups()) {
            if (group.getType() != C.TRACK_TYPE_TEXT) {
                continue;
            }
            for (int i = 0; i < group.length; i++) {
                if (group.isTrackSupported(i)) {
                    tracks.add(new TextTrack(group, i));
                }
            }
        }
        return tracks;
    }

    /** The file name a side-loaded subtitle's track id carries, or null. */
    @Nullable
    private static String subtitleFileName(@Nullable String trackId) {
        if (trackId == null) {
            return null;
        }
        int at = trackId.indexOf(SUBTITLE_ID_PREFIX);
        return at < 0 ? null : trackId.substring(at + SUBTITLE_ID_PREFIX.length());
    }

    /**
     * Shows one track ({@code group} non-null) or none, and remembers which:
     * "Off" keeps downloaded subtitles from showing by themselves on the next
     * video too, picking a track turns that back on.
     */
    private void selectTextTrack(@Nullable Tracks.Group group, int trackIndex) {
        if (mExoPlayer == null) {
            return;
        }
        boolean on = group != null;
        TrackSelectionParameters.Builder builder = mExoPlayer.getTrackSelectionParameters()
                .buildUpon()
                .clearOverridesOfType(C.TRACK_TYPE_TEXT);
        if (on) {
            builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                    .setIgnoredTextSelectionFlags(0)
                    .setOverrideForType(new TrackSelectionOverride(
                            group.getMediaTrackGroup(), trackIndex));
        } else {
            builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true);
        }
        mExoPlayer.setTrackSelectionParameters(builder.build());
        if (mSharedPreferences != null) {
            mSharedPreferences.edit().putBoolean(Preferences.PLAYER_SUBTITLES_ON, on).apply();
        }
    }

    private void selectPendingSubtitle(Tracks tracks) {
        for (Tracks.Group group : tracks.getGroups()) {
            if (group.getType() != C.TRACK_TYPE_TEXT) {
                continue;
            }
            for (int i = 0; i < group.length; i++) {
                Format format = group.getTrackFormat(i);
                if (format.id != null && format.id.endsWith(mPendingSubtitleId)
                        && group.isTrackSupported(i)) {
                    mPendingSubtitleId = null;
                    selectTextTrack(group, i);
                    return;
                }
            }
        }
    }

    /** The player's Subtitles button (the controller's bottom row). */
    public void showSubtitleSheet() {
        if (!isAdded() || mExoPlayer == null) {
            return;
        }
        FragmentManager fragmentManager = getChildFragmentManager();
        if (fragmentManager.isStateSaved()
                || fragmentManager.findFragmentByTag(SubtitleSheetDialogFragment.TAG) != null) {
            return;
        }
        new SubtitleSheetDialogFragment().show(fragmentManager, SubtitleSheetDialogFragment.TAG);
    }

    /** The player's Playback speed button (the controller's bottom row). */
    public void showSpeedSheet() {
        if (!isAdded() || mExoPlayer == null) {
            return;
        }
        FragmentManager fragmentManager = getChildFragmentManager();
        if (fragmentManager.isStateSaved()
                || fragmentManager.findFragmentByTag(PlaybackSpeedSheetDialogFragment.TAG) != null) {
            return;
        }
        new PlaybackSpeedSheetDialogFragment().show(fragmentManager,
                PlaybackSpeedSheetDialogFragment.TAG);
    }

    /** Closes the Subtitles and Playback speed sheets — entering PiP leaves no room for them. */
    public void dismissPlayerSheets() {
        if (!isAdded()) {
            return;
        }
        FragmentManager fragmentManager = getChildFragmentManager();
        for (String tag : new String[]{SubtitleSheetDialogFragment.TAG,
                PlaybackSpeedSheetDialogFragment.TAG}) {
            Fragment sheet = fragmentManager.findFragmentByTag(tag);
            if (sheet instanceof DialogFragment) {
                ((DialogFragment) sheet).dismissAllowingStateLoss();
            }
        }
    }

    @Override
    public float getPlaybackSpeed() {
        return mExoPlayer != null ? mExoPlayer.getPlaybackParameters().speed
                : PlaybackSpeeds.NORMAL;
    }

    /**
     * The speed lives on the player, so it lasts as long as this file plays:
     * through rotation, PiP and background playback (the notification's
     * position follows it), and a new file starts at normal speed. Pitch is
     * kept (media3's default for setPlaybackSpeed).
     */
    @Override
    public void onPlaybackSpeedChosen(float speed) {
        if (mExoPlayer != null) {
            mExoPlayer.setPlaybackSpeed(speed);
        }
    }

    @Override
    public List<SubtitleSheetDialogFragment.Option> getSubtitleOptions() {
        List<SubtitleSheetDialogFragment.Option> trackOptions = new ArrayList<>();
        List<TextTrack> tracks = textTracks();
        Locale locale = Locale.getDefault();
        boolean anySelected = false;
        for (int n = 0; n < tracks.size(); n++) {
            TextTrack track = tracks.get(n);
            Format format = track.group.getTrackFormat(track.index);
            String label = !TextUtils.isEmpty(format.label)
                    ? format.label
                    : PlayerSubtitles.displayLanguage(format.language, locale);
            if (TextUtils.isEmpty(label)) {
                label = getString(R.string.player_subtitles_track, n + 1);
            }
            String file = subtitleFileName(format.id);
            String detail = file != null ? file : getString(R.string.player_subtitles_in_video);
            boolean selected = track.group.isTrackSelected(track.index);
            anySelected |= selected;
            trackOptions.add(new SubtitleSheetDialogFragment.Option(label, detail, selected, n));
        }
        List<SubtitleSheetDialogFragment.Option> options = new ArrayList<>();
        options.add(new SubtitleSheetDialogFragment.Option(
                getString(R.string.player_subtitles_off), null, !anySelected, -1));
        options.addAll(trackOptions);
        return options;
    }

    @Override
    public void onSubtitleChosen(int index) {
        if (index < 0) {
            selectTextTrack(null, 0);
            return;
        }
        List<TextTrack> tracks = textTracks();
        if (index < tracks.size()) {
            TextTrack track = tracks.get(index);
            selectTextTrack(track.group, track.index);
        }
    }

    @Override
    public boolean canAddSubtitles() {
        // A file opened from outside (an ACTION_VIEW with no Downloads row)
        // has no id to link an added subtitle to, and the vault's encrypted
        // files have no path a subtitle could sit beside.
        return mDownloadEntity != null && mDownloadEntity.getId() != 0
                && !mDownloadEntity.isFileEncrypted();
    }

    @Override
    public void onAddSubtitleRequested() {
        if (mExoPlayer == null) {
            return;
        }
        // Paused first: the picker covers the player, and a PLAYING player
        // reads that as leaving — PlayerActivity enters PiP on the leave hint
        // and arms background playback on onStop. Resumed when it closes.
        mResumeAfterPick = mExoPlayer.getPlayWhenReady()
                && mExoPlayer.getPlaybackState() != Player.STATE_ENDED;
        mExoPlayer.pause();
        try {
            mPickSubtitle.launch(SUBTITLE_PICK_MIMES);
        } catch (ActivityNotFoundException e) {
            resumeAfterPick();
            showSubtitleMessage(R.string.error_unknown);
        }
    }

    private void resumeAfterPick() {
        if (mResumeAfterPick && mExoPlayer != null) {
            mExoPlayer.play();
        }
        mResumeAfterPick = false;
    }

    private void showSubtitleMessage(int message) {
        if (mPlayerView != null && isAdded()) {
            Snackbar.make(mPlayerView, message, Snackbar.LENGTH_SHORT).show();
        }
    }

    private void onSubtitlePicked(@Nullable Uri uri) {
        if (uri == null || mExoPlayer == null || mDownloadRepository == null) {
            resumeAfterPick();
            return;
        }
        final ExoPlayer player = mExoPlayer;
        final DownloadEntity video = mDownloadEntity;
        final DownloadDataRepository repository = mDownloadRepository;
        final Context context = App.getAppContext();
        new Thread(() -> {
            ImportResult result = importSubtitle(context, repository, uri, video);
            mMainHandler.post(() -> {
                if (player != mExoPlayer) {
                    return;
                }
                if (result.fileName == null) {
                    resumeAfterPick();
                    showSubtitleMessage(result.unsupported
                            ? R.string.player_subtitles_unsupported : R.string.error_unknown);
                    return;
                }
                boolean resume = mResumeAfterPick;
                mResumeAfterPick = false;
                loadMediaWithSubtitles(mExoPlayer.getCurrentPosition(),
                        SUBTITLE_ID_PREFIX + result.fileName, resume);
                showSubtitleMessage(R.string.player_subtitles_added);
            });
        }, "subtitle-import").start();
    }

    /** What "Add a subtitle file" made of the picked file. */
    private static final class ImportResult {
        /** The subtitle's name in Downloads, or null when it failed. */
        @Nullable
        final String fileName;
        /** Failed because the file is no subtitle the player can show. */
        final boolean unsupported;

        ImportResult(@Nullable String fileName, boolean unsupported) {
            this.fileName = fileName;
            this.unsupported = unsupported;
        }
    }

    /**
     * Makes a picked file one of the video's subtitles, on a worker thread.
     * A file that is already a download (a subtitle saved from another page)
     * is LINKED to the video; anything else is copied next to the video as
     * "<video name> [lang].srt" and added to Downloads, linked, so it comes
     * back the next time the video plays and can be deleted like any file.
     * Returns once the row is written, so the reload that follows finds it.
     */
    private static ImportResult importSubtitle(Context context, DownloadDataRepository repository,
                                               Uri uri, DownloadEntity video) {
        ContentResolver resolver = context.getContentResolver();
        String displayName = displayNameOf(resolver, uri);
        String providerMime = null;
        try {
            providerMime = resolver.getType(uri);
        } catch (RuntimeException e) {
            logSubtitle("type", e);
        }
        String sampleMime = PlayerSubtitles.mimeFor(displayName, providerMime);
        if (sampleMime == null) {
            sampleMime = sniffSubtitle(resolver, uri);
        }
        if (sampleMime == null) {
            return new ImportResult(null, true);
        }

        File picked = fileOfDocument(uri);
        if (picked != null) {
            DownloadEntity existing = repository.findByFilePath(picked.getAbsolutePath());
            if (existing != null && existing.getId() != video.getId()
                    && existing.isFileSafe() == video.isFileSafe()
                    && existing.getFileStatus() == Download.FINISHED) {
                CountDownLatch written = new CountDownLatch(1);
                repository.setParentId(existing.getId(), video.getId(), written::countDown);
                awaitWrite(written);
                return new ImportResult(existing.getFileName(), false);
            }
        }

        File videoFile = new File(video.getFilePath());
        File dir = videoFile.getParentFile();
        if (dir == null) {
            return new ImportResult(null, false);
        }
        String tag = PlayerSubtitles.languageTag(displayName);
        String stem = withoutExtension(video.getFileName());
        if (TextUtils.isEmpty(stem)) {
            stem = withoutExtension(videoFile.getName());
        }
        String tagSuffix = tag != null ? " [" + tag + "]" : "";
        File target = freeSubtitleFile(dir, stem, tagSuffix,
                PlayerSubtitles.extensionFor(sampleMime), repository);
        if (target == null) {
            return new ImportResult(null, false);
        }
        long size = copyCapped(resolver, uri, target);
        if (size <= 0) {
            return new ImportResult(null, false);
        }

        String path = target.getAbsolutePath();
        String origin = video.getOriginUrl();
        DownloadEntity entity = new DownloadEntity();
        entity.setId(newRowId(repository));
        entity.setFileType(UrlType.FILE.getValue());
        // file_url is the file itself: the page URL belongs to the video's own
        // capture, and Captured ties a download to a capture by URL. The
        // origin is the video's, so the row reads "SUBTITLE · its site".
        entity.setFileUrl(Uri.fromFile(target).toString());
        entity.setFileOriginUrl(origin != null ? origin : "");
        entity.setFileName(target.getName());
        entity.setFileImg(path);
        entity.setFilePath(path);
        entity.setFileMimeType(PlayerSubtitles.storedMimeFor(sampleMime));
        entity.setFileLanguage(PlayerSubtitles.displayLanguage(tag, Locale.getDefault()));
        entity.setFileProgress(100);
        entity.setFileDate(System.currentTimeMillis());
        entity.setFileSize(size);
        entity.setFileStatus(Download.FINISHED);
        entity.setFileSafe(video.isFileSafe());
        entity.setFileThumbnailUnavailable(false);
        entity.setParentId(video.getId());
        CountDownLatch written = new CountDownLatch(1);
        repository.add(entity, written::countDown);
        awaitWrite(written);
        return new ImportResult(target.getName(), false);
    }

    private static void awaitWrite(CountDownLatch written) {
        try {
            if (!written.await(10, TimeUnit.SECONDS)) {
                logSubtitle("row write still queued", null);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** A row id no download holds yet (0 means "no parent" on a link). */
    private static int newRowId(DownloadDataRepository repository) {
        int id = ID_RANDOM.nextInt();
        while (id == 0 || repository.findByIdSync(id) != null) {
            id = ID_RANDOM.nextInt();
        }
        return id;
    }

    @Nullable
    private static String displayNameOf(ContentResolver resolver, Uri uri) {
        try (Cursor cursor = resolver.query(uri,
                new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst() && !cursor.isNull(0)) {
                return cursor.getString(0);
            }
        } catch (RuntimeException e) {
            logSubtitle("name", e);
        }
        return uri.getLastPathSegment();
    }

    /** The format from the file's first bytes, for a name that says nothing. */
    @Nullable
    private static String sniffSubtitle(ContentResolver resolver, Uri uri) {
        byte[] head = new byte[4096];
        int length = 0;
        try (InputStream in = resolver.openInputStream(uri)) {
            if (in == null) {
                return null;
            }
            int read = in.read(head, 0, head.length);
            while (read > 0) {
                length += read;
                if (length >= head.length) {
                    break;
                }
                read = in.read(head, length, head.length - length);
            }
        } catch (IOException | RuntimeException e) {
            logSubtitle("sniff", e);
            return null;
        }
        return PlayerSubtitles.sniffMime(head, length);
    }

    /**
     * The file behind a picked document, for the two providers whose ids are
     * paths (the phone's storage and the Downloads app); null for any other.
     * The volume mapping is FolderStore.directoryOfTree's.
     */
    @Nullable
    private static File fileOfDocument(Uri uri) {
        String docId;
        try {
            docId = DocumentsContract.getDocumentId(uri);
        } catch (IllegalArgumentException e) {
            return null;
        }
        String authority = uri.getAuthority();
        if ("com.android.externalstorage.documents".equals(authority)) {
            int colon = docId.indexOf(':');
            if (colon < 0) {
                return null;
            }
            String volume = docId.substring(0, colon);
            String relative = docId.substring(colon + 1);
            File root = "primary".equals(volume)
                    ? Environment.getExternalStorageDirectory()
                    : new File("/storage", volume);
            return new File(root, relative);
        }
        if ("com.android.providers.downloads.documents".equals(authority)
                && docId.startsWith("raw:")) {
            return new File(docId.substring("raw:".length()));
        }
        return null;
    }

    /**
     * "<stem> [en].srt", or "<stem> (2) [en].srt" when taken on disk or by a
     * download row. The counter goes BEFORE the language tag so the tag stays
     * last, where {@link PlayerSubtitles#languageTag} reads it.
     */
    @Nullable
    private static File freeSubtitleFile(File dir, String stem, String tagSuffix, String extension,
                                         DownloadDataRepository repository) {
        for (int n = 1; n < 100; n++) {
            String counter = n == 1 ? "" : " (" + n + ")";
            File candidate = new File(dir, stem + counter + tagSuffix + "." + extension);
            if (!candidate.exists()
                    && repository.findByFilePath(candidate.getAbsolutePath()) == null) {
                return candidate;
            }
        }
        return null;
    }

    /** Copies at most MAX_SUBTITLE_BYTES; the byte count, or -1 (target removed). */
    private static long copyCapped(ContentResolver resolver, Uri uri, File target) {
        long total = 0;
        boolean ok = false;
        try (InputStream in = resolver.openInputStream(uri);
             OutputStream out = new FileOutputStream(target)) {
            if (in == null) {
                return -1;
            }
            byte[] buffer = new byte[16 * 1024];
            int read = in.read(buffer);
            while (read != -1) {
                total += read;
                if (total > MAX_SUBTITLE_BYTES) {
                    return -1;
                }
                out.write(buffer, 0, read);
                read = in.read(buffer);
            }
            ok = total > 0;
        } catch (IOException | RuntimeException e) {
            logSubtitle("copy", e);
        } finally {
            if (!ok && target.exists() && !target.delete()) {
                logSubtitle("copy: partial file left", null);
            }
        }
        return ok ? total : -1;
    }

    private static void logSubtitle(String message, @Nullable Throwable error) {
        if (BuildConfig.DEBUG) {
            Log.w(TAG, "subtitles: " + message, error);
        }
    }

    private static String withoutExtension(@Nullable String name) {
        if (name == null) {
            return "";
        }
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    // ── Double-tap-to-seek ───────────────────────────────────────────

    /**
     * Wire a GestureDetector on the PlayerView. Zones are THIRDS (the
     * YouTube layout): double-tap on the left third seeks back
     * {@value #SEEK_DELTA_MS} ms, on the right third forward, and on
     * the middle toggles play/pause. A single confirmed tap toggles the
     * playback controller (replacing the built-in PlayerView behaviour
     * we disabled).
     *
     * <p><b>The streak model</b> — after a double-tap seek, EVERY
     * further tap in the same zone within {@link #SEEK_STREAK_WINDOW_MS}
     * seeks again immediately (window restarted per tap), with a
     * cumulative "−20 s" badge at the tapped edge. This is not just
     * polish: a bare onDoubleTap classifier consumes taps in PAIRS —
     * three rapid taps fired ONE seek, four fired two — which read
     * on-device as "sometimes it only seeks 10 s no matter how much I
     * tap". The streak makes tap count map 1:1 to seeks after the
     * opening pair. Continuation taps arrive on ALTERNATING callbacks
     * (odd taps as onSingleTapUp — fired for every first-of-pair tap —
     * even taps as another onDoubleTap), so BOTH handlers feed
     * {@link #continueSeekStreak()}; they can never double-count one
     * tap because a tap is classified as exactly one of the two.</p>
     *
     * <p>The touch listener CONSUMES the event stream (returns true) so
     * PlayerView's own onTouchEvent tap-toggle never runs — see the
     * comment at the listener for the show-then-instant-hide race that
     * returning false caused. The controller's children (scrubber,
     * buttons) are unaffected: a parent's OnTouchListener only sees
     * events no child claimed.</p>
     */
    @SuppressLint("ClickableViewAccessibility")
    private void setupDoubleTapSeek() {
        mPlayerGestureDetector = new GestureDetector(mActivity,
                new GestureDetector.SimpleOnGestureListener() {
                    @Override
                    public boolean onSingleTapUp(@NonNull MotionEvent e) {
                        int zone = seekZone(e);
                        if (zone != 0 && isSeekStreakActive() && zone == mSeekStreakDir) {
                            continueSeekStreak();
                            mStreakTapConsumed = true;
                            return true;
                        }
                        mStreakTapConsumed = false;
                        return false;
                    }

                    @Override
                    public boolean onDoubleTap(@NonNull MotionEvent e) {
                        if (mPlayerView == null || mExoPlayer == null) return false;
                        int zone = seekZone(e);
                        if (zone == 0) {
                            togglePlayPause();
                            return true;
                        }
                        if (isSeekStreakActive() && zone == mSeekStreakDir) {
                            continueSeekStreak();
                        } else {
                            mSeekStreakDir = zone;
                            mSeekStreakAccumMs = 0;
                            continueSeekStreak();
                        }
                        return true;
                    }

                    @OptIn(markerClass = UnstableApi.class)
                    @Override
                    public void onLongPress(@NonNull MotionEvent e) {
                        if (mPlayerView != null
                                && mHoldToSpeedUp.start(mExoPlayer, mPlayerView)) {
                            mPlayerView.hideController();
                        }
                    }

                    @OptIn(markerClass = UnstableApi.class)
                    @Override
                    public boolean onSingleTapConfirmed(@NonNull MotionEvent e) {
                        // A tap already spent on a streak continuation
                        // must not ALSO toggle the controller.
                        if (mStreakTapConsumed) {
                            mStreakTapConsumed = false;
                            return true;
                        }
                        if (mPlayerView == null) return false;
                        if (mPlayerView.isControllerFullyVisible()) {
                            mPlayerView.hideController();
                        } else {
                            mPlayerView.showController();
                        }
                        return true;
                    }
                });

        // CONSUME the event (return true) — this is load-bearing, not a
        // formality. PlayerView.onTouchEvent has its OWN tap handler
        // (performClick → toggleControllerVisibility) that SHOWS the
        // controller on every tap-up; returning false let it run in
        // parallel with this detector, so a single tap showed the
        // controller instantly (PlayerView's path) and ~300 ms later
        // onSingleTapConfirmed saw it visible and HID it again — bars
        // flashing in and out per tap. The race existed all along but was
        // MASKED while the controller had a show animation: during the
        // slide-in isControllerFullyVisible() is still false, so the
        // confirm's toggle landed as a (harmless) second show. Disabling
        // controller animation (the stranded-timebar fix) made the show
        // instant and flipped the toggle into a hide. Consuming here kills
        // PlayerView's competing handler outright; the controller and its
        // children (scrubber, buttons) are unaffected — a parent's
        // OnTouchListener only ever sees events no child claimed.
        mPlayerView.setOnTouchListener((view, event) -> {
            mPlayerGestureDetector.onTouchEvent(event);
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                mHoldToSpeedUp.end();
            }
            return true;
        });
    }

    /**
     * Which seek zone a touch falls in: -1 = left third (back),
     * +1 = right third (forward), 0 = middle third (no seek).
     */
    private int seekZone(MotionEvent e) {
        if (mPlayerView == null) return 0;
        float width = mPlayerView.getWidth();
        if (width <= 0) return 0;
        if (e.getX() < width / 3f) return -1;
        if (e.getX() > width * 2f / 3f) return 1;
        return 0;
    }

    private boolean isSeekStreakActive() {
        return mSeekStreakDir != 0
                && SystemClock.uptimeMillis() < mSeekStreakUntilMs;
    }

    /**
     * One streak step: seek ±10 s, restart the continuation window, and
     * refresh the cumulative badge. The accumulated figure is NOMINAL
     * (10 s per tap) — near the clip edges the actual seek clamps to
     * [0, duration] while the badge keeps counting, the same behavior
     * YouTube shows; the scrubber tells the clamped truth.
     */
    private void continueSeekStreak() {
        mSeekStreakUntilMs = SystemClock.uptimeMillis() + SEEK_STREAK_WINDOW_MS;
        boolean back = mSeekStreakDir < 0;
        applySeek(back ? -SEEK_DELTA_MS : SEEK_DELTA_MS);
        mSeekStreakAccumMs += SEEK_DELTA_MS;
        spinSeekIcon(back);
        showSeekFeedback();
    }

    /**
     * Show / refresh the cumulative seek badge on the streak's side and
     * arm its fade-out for when the streak window closes. Re-entrant per
     * continuation tap: cancel + full alpha + re-armed delay, so the
     * badge sits solid while the user keeps tapping and fades only once
     * they stop.
     */
    private void showSeekFeedback() {
        boolean back = mSeekStreakDir < 0;
        TextView badge = back ? mSeekFeedbackLeft : mSeekFeedbackRight;
        TextView other = back ? mSeekFeedbackRight : mSeekFeedbackLeft;
        if (badge == null) return;
        if (other != null) {
            other.animate().cancel();
            other.setVisibility(View.GONE);
        }
        badge.setText(String.format(Locale.US, "%s%d s",
                back ? "−" : "+", mSeekStreakAccumMs / 1000));
        badge.animate().cancel();
        badge.setAlpha(1f);
        badge.setVisibility(View.VISIBLE);
        badge.animate()
                .alpha(0f)
                .setStartDelay(SEEK_STREAK_WINDOW_MS)
                .setDuration(250L)
                .withEndAction(() -> badge.setVisibility(View.GONE))
                .start();
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
     * The bottom row's Subtitles and Playback speed buttons, and the hold
     * badge. Subtitles is for video only; speed for video and audio. They
     * live in the controller rather than the toolbar so speed is never
     * pushed into the overflow menu, the YouTube layout.
     */
    private void setupPlaybackButtons(@NonNull View root) {
        mHoldToSpeedUp = new HoldToSpeedUp(root.findViewById(R.id.media_viewer_hold_badge));
        View subtitlesSlot = mPlayerView.findViewById(R.id.media_viewer_subtitles_slot);
        View subtitlesButton = mPlayerView.findViewById(R.id.media_viewer_btn_subtitles);
        mSubtitlesOn = mPlayerView.findViewById(R.id.media_viewer_subtitles_on);
        mSpeedButton = mPlayerView.findViewById(R.id.media_viewer_btn_speed);
        if (subtitlesSlot != null && subtitlesButton != null && isVideoMime()) {
            subtitlesSlot.setVisibility(View.VISIBLE);
            subtitlesButton.setOnClickListener(view -> showSubtitleSheet());
        }
        if (mSpeedButton != null && (isVideoMime() || isAudioMime())) {
            mSpeedButton.setVisibility(View.VISIBLE);
            mSpeedButton.setOnClickListener(view -> showSpeedSheet());
            PlayerControls.bindSpeed(mSpeedButton, PlaybackSpeeds.NORMAL);
        }
    }

    private void updateSpeedButton() {
        if (mSpeedButton != null && mExoPlayer != null) {
            PlayerControls.bindSpeed(mSpeedButton, mExoPlayer.getPlaybackParameters().speed);
        }
    }

    /** Lights the line under Subtitles while a text track is selected (forced ones too). */
    private void updateSubtitlesIndicator(@NonNull Tracks tracks) {
        if (mSubtitlesOn == null) {
            return;
        }
        boolean showing = false;
        for (Tracks.Group group : tracks.getGroups()) {
            if (group.getType() == C.TRACK_TYPE_TEXT && group.isSelected()) {
                showing = true;
                break;
            }
        }
        mSubtitlesOn.setVisibility(showing ? View.VISIBLE : View.INVISIBLE);
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

    // ── Scrub preview ────────────────────────────────────────────────

    /**
     * Make dragging the scrubber show the video it is passing over,
     * instead of moving a marker across a frozen frame.
     *
     * <p>Media3 does not do this on its own: PlayerControlView updates
     * the position TEXT on every scrub move but only calls seekTo when
     * the finger LIFTS, so the picture stays on whatever frame was
     * showing when the drag began. Dragging back and forth was blind —
     * you could read a timestamp but not see where you were. This adds a
     * SECOND {@link TimeBar.OnScrubListener} that seeks while the finger
     * moves; the surface renders each seek target even with playback
     * paused, the same mechanism FrameGrabberFragment's slider relies
     * on, so no second decoder is needed and every source the player can
     * open (owned path, restored content:// grant, vault) previews.</p>
     *
     * <p>Three things keep it cheap enough to run on every drag:</p>
     * <ul>
     *   <li>{@link SeekParameters#CLOSEST_SYNC} while dragging, so a
     *       preview costs ONE keyframe decode instead of decoding
     *       forward from that keyframe to an exact position. Restored to
     *       {@link SeekParameters#DEFAULT} on lift, so where the user
     *       actually lands is unchanged.</li>
     *   <li>A FIXED-INTERVAL throttle, never a reset-on-every-event
     *       debounce: a debounce never fires under continuous movement,
     *       the same starvation trap the session-persistence batching
     *       documents. One seek per window, always the newest position.</li>
     *   <li>Playback is paused for the drag and restored on lift —
     *       otherwise each preview seek resumes playing and the audio
     *       stutters across the whole drag.</li>
     * </ul>
     *
     * <p>Video only. An audio file has no frame to preview and
     * seek-per-move would just chop the audio, so it keeps the media3
     * default (seek on lift).</p>
     */
    @OptIn(markerClass = UnstableApi.class)
    private void setupScrubPreview() {
        if (!isVideoMime()) return;
        View progress = mPlayerView.findViewById(R.id.exo_progress);
        if (!(progress instanceof TimeBar)) return;
        ((TimeBar) progress).addListener(new TimeBar.OnScrubListener() {
            @Override
            public void onScrubStart(@NonNull TimeBar timeBar, long position) {
                beginScrubPreview(position);
            }

            @Override
            public void onScrubMove(@NonNull TimeBar timeBar, long position) {
                previewFrameAt(position);
            }

            @Override
            public void onScrubStop(@NonNull TimeBar timeBar, long position, boolean canceled) {
                endScrubPreview(canceled ? mScrubStartPositionMs : position);
            }
        });
    }

    @OptIn(markerClass = UnstableApi.class)
    private void beginScrubPreview(long positionMs) {
        if (mExoPlayer == null) return;
        mScrubbing = true;
        mScrubStartPositionMs = mExoPlayer.getCurrentPosition();
        mResumeAfterScrub = mExoPlayer.getPlayWhenReady();
        if (mResumeAfterScrub) {
            mExoPlayer.setPlayWhenReady(false);
        }
        mExoPlayer.setSeekParameters(SeekParameters.CLOSEST_SYNC);
        // Preview the touch-down position at once: a tap on the bar is a
        // start + stop with no move in between, and making the first
        // preview wait out a window reads as lag on a slow drag.
        mLastPreviewSeekAtMs = 0L;
        previewFrameAt(positionMs);
    }

    /** Record the dragged-to position and seek at most once per window. */
    private void previewFrameAt(long positionMs) {
        if (!mScrubbing || mExoPlayer == null) return;
        mPendingScrubMs = positionMs;
        if (mScrubPreviewScheduled) return;
        long wait = mLastPreviewSeekAtMs + SCRUB_PREVIEW_INTERVAL_MS - SystemClock.uptimeMillis();
        if (wait <= 0L) {
            flushScrubPreview();
            return;
        }
        mScrubPreviewScheduled = true;
        mScrubHandler.postDelayed(mScrubPreviewTask, wait);
    }

    private void flushScrubPreview() {
        mScrubPreviewScheduled = false;
        if (!mScrubbing || mExoPlayer == null || mPendingScrubMs == C.TIME_UNSET) return;
        long target = mPendingScrubMs;
        mPendingScrubMs = C.TIME_UNSET;
        mLastPreviewSeekAtMs = SystemClock.uptimeMillis();
        mExoPlayer.seekTo(target);
    }

    /**
     * End a drag: exact seek parameters back, land on {@code targetMs}
     * (pass {@link C#TIME_UNSET} to land nowhere), resume playback if the
     * drag paused it.
     *
     * <p>PlayerControlView's own scrub-stop handler runs BEFORE this one
     * (it registered first, in its constructor) and seeks while
     * CLOSEST_SYNC is still set, so the exact seek here is what makes the
     * released position land where the user let go. A CANCELLED scrub
     * gets no seek from media3 at all, which is why the caller passes the
     * start position back rather than leaving the player on the last
     * previewed keyframe.</p>
     */
    @OptIn(markerClass = UnstableApi.class)
    private void endScrubPreview(long targetMs) {
        mScrubHandler.removeCallbacks(mScrubPreviewTask);
        mScrubPreviewScheduled = false;
        mPendingScrubMs = C.TIME_UNSET;
        mScrubStartPositionMs = C.TIME_UNSET;
        mScrubbing = false;
        if (mExoPlayer == null) return;
        mExoPlayer.setSeekParameters(SeekParameters.DEFAULT);
        if (targetMs != C.TIME_UNSET) {
            mExoPlayer.seekTo(targetMs);
        }
        if (mResumeAfterScrub) {
            mResumeAfterScrub = false;
            mExoPlayer.setPlayWhenReady(true);
        }
    }

    /**
     * A drag interrupted by the activity leaving (Home mid-scrub, PiP
     * entry, teardown) never gets its lift event, so restore the player
     * here instead of leaving it paused on a keyframe with CLOSEST_SYNC
     * still set — the state would then outlive the drag into background
     * playback.
     */
    private void abortScrubPreview() {
        if (!mScrubbing) return;
        endScrubPreview(C.TIME_UNSET);
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
            // Entering PiP hides the controller out from under a finger
            // that may still be on the scrubber (Home mid-drag). The
            // activity stays PAUSED rather than STOPPED in PiP, so
            // onStop's abort does not run for this path.
            abortScrubPreview();
            mHoldToSpeedUp.end();
            mPlayerView.hideController();
            setChromeVisible(false);
        } else {
            // Re-assert the controller ↔ chrome lockstep on exit. The
            // controller was hidden on entry, so this normally KEEPS the
            // ActionBar + system bars hidden (immersive, matching the
            // hidden controller) — the activity used to force
            // actionBar.show() here instead, leaving the title bar
            // floating alone over the video until two taps cycled it.
            // Derived from the controller's live state rather than
            // hardcoded false so a visible controller (nothing hides it
            // during PiP teardown races) keeps its bars.
            setChromeVisible(mPlayerView.isControllerFullyVisible());
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
        if (mHoldToSpeedUp != null) {
            mHoldToSpeedUp.end();
        }
        if (mPlayerView != null)
            mPlayerView.setPlayer(null);
        if (mExoPlayer != null) {
            mExoPlayer.removeListener(mPlayerListener);
            // The replace transaction runs with setReorderingAllowed(true),
            // so a SUCCESSOR fragment may already have gone through
            // onViewCreated BEFORE this teardown runs. Two distinct
            // successor cases, told apart by whether the hub still holds
            // THIS fragment's player:
            //  - same file (notification tap): the successor ADOPTED this
            //    very player — hands off entirely. Neither release (it's
            //    the live player under the new UI) nor markOwnerDetached
            //    (that flags the service to release it on shutdown, which
            //    killed the adopted player under the new fragment — every
            //    control click then hit media3's dead internal thread).
            //  - different file: the successor attached its OWN fresh
            //    player, so this one is unregistered — it must be released
            //    here or it leaks and keeps playing under the new video.
            boolean adoptedBySuccessor = !PlaybackHub.isOwner(this)
                    && PlaybackHub.player() == mExoPlayer;
            if (adoptedBySuccessor) {
                // Successor owns it now; nothing to do.
            } else if (PlaybackHub.isOwner(this)
                    && PlaybackHub.isBackgroundActive()
                    && mActivity != null
                    && (!mActivity.isFinishing() || mActivity.backgroundPlaybackOutlivesFinish())) {
                // Background playback outlives this fragment (the system
                // reclaimed the backgrounded activity, or another entry
                // closed it, see PlayerActivity.backgroundPlaybackOutlivesFinish):
                // hand release duty to PlayerPlaybackService instead of
                // killing the audio mid-stream. A later same-file relaunch
                // adopts the player back before the service ever shuts down.
                PlaybackHub.markOwnerDetached();
            } else {
                mExoPlayer.release();
                PlaybackHub.clearIfHolds(mExoPlayer);
            }
        }
        mExoPlayer = null;
        mPlayerView = null;
        mSeekFeedbackLeft = null;
        mSeekFeedbackRight = null;
        mWindowInsetsController = null;
    }


    /**
     * Reach into PlayerView, find its inner exo_content_frame
     * (an AspectRatioFrameLayout), and set its aspect ratio so the layout
     * is the right shape BEFORE the first frame paints. Without it the
     * content frame stays MATCH_PARENT until onVideoSizeChanged and the
     * first frame renders stretched to full screen (see the call site).
     *
     * Sources, in order:
     *   1. the entity's stored capture resolution ("WxH") — instant,
     *      in-memory, applied SYNCHRONOUSLY; the only reliable source for
     *      a restored content:// clip and free of main-thread jank. This
     *      is the common case (parsers and probes stamp a resolution).
     *   2. MediaMetadataRetriever — rotation-aware, covers most owned
     *      files; returns nothing for the odd container (498x334,
     *      timescale-100) and pays disk I/O over a content:// grant.
     *   3. the app's native ffmpeg reader over a ParcelFileDescriptor —
     *      parses files the platform extractors reject, and (unlike a
     *      path open) works for a foreign-owned restored file via the
     *      SAF grant, the same fd path Glide's FFmpegPfdDecoder uses.
     *
     * <p><b>Tiers 2+3 run on a WORKER thread — never re-inline them.</b>
     * They used to run synchronously in onViewCreated, and on a
     * resolution-less entity they froze the tap-to-open for seconds: the
     * StrictMode log showed ~2.2 s of main-thread disk I/O (143 skipped
     * frames) on a SABR-downloaded AV1 clip — ffmpeg's find_stream_info
     * grinds long on a codec the build can't decode, and FUSE storage
     * makes every read expensive. The user read it as "the video doesn't
     * open". The async result is applied only if the DECODER hasn't
     * reported first — onVideoSizeChanged's value is PAR-corrected and
     * authoritative, and it also remains the backstop when every tier
     * misses (the opaque shutter masks the gap). The worker captures the
     * app context + path, not fragment fields (mActivity can be nulled
     * mid-probe); it retains the fragment only for the probe's bounded
     * duration via the completion lambda, which self-guards on a dead
     * view.
     */
    @OptIn(markerClass = UnstableApi.class)
    private void presetVideoAspectRatio(Uri uri) {
        if (uri == null) return;
        float fromEntity = aspectFromResolution(mDownloadEntity.getFileResolution());
        if (fromEntity > 0f) {
            applyVideoAspect(fromEntity);
            return;
        }
        final Context appContext = App.getAppContext();
        final String filePath = mDownloadEntity.getFilePath();
        final Handler main = new Handler(Looper.getMainLooper());
        new Thread(() -> {
            float aspect = readVideoAspectRatioBlocking(appContext, uri, filePath);
            if (aspect <= 0f) return;
            main.post(() -> {
                if (mPlayerView == null || mExoPlayer == null) return;
                VideoSize reported = mExoPlayer.getVideoSize();
                if (reported.width > 0 && reported.height > 0) return;
                applyVideoAspect(aspect);
            });
        }, "aspect-probe").start();
    }

    /**
     * Size the player's content frame AND the poster band to {@code aspect}.
     * Called from the synchronous preset (onViewCreated, before the postponed
     * shared-element transition captures photo_view's end bounds) AND from
     * onVideoSizeChanged as the runtime backstop when the preset couldn't
     * resolve the size up front. Sizing the content frame stops the surface
     * painting fitXY-stretched; sizing the poster to the SAME aspect makes the
     * shared-element target coincide with the letterbox, so the poster→video
     * swap on onRenderedFirstFrame can't pop or peek. Both are idempotent
     * (AspectRatioFrameLayout / AspectRatioImageView no-op an unchanged ratio).
     */
    @OptIn(markerClass = UnstableApi.class)
    private void applyVideoAspect(float aspect) {
        if (aspect <= 0f || mPlayerView == null) return;
        View contentFrame = mPlayerView.findViewById(androidx.media3.ui.R.id.exo_content_frame);
        if (contentFrame instanceof AspectRatioFrameLayout) {
            ((AspectRatioFrameLayout) contentFrame).setAspectRatio(aspect);
        }
        if (mPhotoView != null && !mAvoidTransition) {
            mPhotoView.setAspectRatio(aspect);
        }
    }

    /**
     * File-probing aspect tiers (MMR, then native ffmpeg over a PFD) —
     * BLOCKING, called from the worker thread in
     * {@link #presetVideoAspectRatio} only. Static + parameterized on
     * purpose: it must not touch fragment fields (mActivity can be nulled
     * by onDetach mid-probe). Returns the display width/height ratio, or
     * 0 if unresolved. The stored-resolution tier lives in the caller
     * (in-memory, applied synchronously).
     */
    private static float readVideoAspectRatioBlocking(@NonNull Context context,
                                                      @NonNull Uri uri,
                                                      @Nullable String filePath) {
        // 1. MediaMetadataRetriever (rotation-aware). Pick the overload by
        //    scheme: a raw path uri (owned file) needs the String overload;
        //    a content:// SAF grant (restored file) needs (Context, Uri).
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            String scheme = uri.getScheme();
            if ("content".equals(scheme)) {
                retriever.setDataSource(context, uri);
            } else if (uri.getPath() != null) {
                retriever.setDataSource(uri.getPath());
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
                    if (rotation == 90 || rotation == 270) {
                        int tmp = w; w = h; h = tmp;
                    }
                    return (float) w / (float) h;
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "readVideoAspectRatio: MMR failed", e);
        } finally {
            try { retriever.release(); } catch (Exception ignored) {}
        }

        // 2. Native ffmpeg over a ParcelFileDescriptor — the reliable backstop
        //    for files the platform extractors reject, INCLUDING a restored
        //    content:// clip. The native reader can't open a foreign-owned
        //    *path* (EACCES), but it reads fine from a file DESCRIPTOR:
        //    RestoredFileAccess.openReadOnly() returns a PFD for both an owned
        //    file and, via the persisted SAF tree grant, a foreign-owned one —
        //    the very same fd path Glide's FFmpegPfdDecoder already uses to
        //    thumbnail these clips. Feeding that fd's stream to the InputStream
        //    metadata reader resolves the 498x334, timescale-100 case MMR can't
        //    (the caller's stored-resolution tier only covers stamped files).
        ParcelFileDescriptor pfd = RestoredFileAccess.openReadOnly(context, filePath);
        if (pfd != null) {
            FFmpegMetaDataReader reader = new FFmpegMetaDataReader();
            try (InputStream in = new ParcelFileDescriptor.AutoCloseInputStream(pfd)) {
                FFmpegMetaData meta = reader.getStreamInfo(
                        in, filePath, pfd.getStatSize(), false);
                if (meta != null) {
                    int w = meta.getWidth();
                    int h = meta.getHeight();
                    if (w > 0 && h > 0) {
                        return (float) w / (float) h;
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "readVideoAspectRatio: ffmpeg PFD failed", e);
            } finally {
                reader.stop();
                reader.release();
            }
        }
        return 0f;
    }

    /** Parse a "WxH" resolution string into a width/height ratio, or 0. */
    private float aspectFromResolution(@Nullable String resolution) {
        if (resolution == null) return 0f;
        int x = resolution.indexOf('x');
        if (x <= 0) return 0f;
        try {
            int w = Integer.parseInt(resolution.substring(0, x).trim());
            int h = Integer.parseInt(resolution.substring(x + 1).trim());
            if (w > 0 && h > 0) {
                return (float) w / (float) h;
            }
        } catch (NumberFormatException ignored) {}
        return 0f;
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
