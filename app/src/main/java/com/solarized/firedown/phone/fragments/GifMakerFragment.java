package com.solarized.firedown.phone.fragments;

import android.content.Intent;
import android.graphics.Bitmap;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.OptIn;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.FileDataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.source.ProgressiveMediaSource;
import androidx.media3.extractor.DefaultExtractorsFactory;
import androidx.media3.extractor.ExtractorsFactory;
import androidx.media3.ui.PlayerView;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.slider.Slider;
import com.google.android.material.snackbar.Snackbar;
import com.solarized.firedown.IntentActions;
import com.solarized.firedown.Keys;
import com.solarized.firedown.R;
import com.solarized.firedown.data.entity.DownloadEntity;
import com.solarized.firedown.ffmpegutils.FFmpegGifMaker;
import com.solarized.firedown.manager.tasks.TaskManager;
import com.solarized.firedown.ui.FilmstripTrimSlider;
import com.solarized.firedown.utils.BuildUtils;
import com.solarized.firedown.utils.NavigationUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class GifMakerFragment extends BaseFocusFragment {

    private static final String TAG = GifMakerFragment.class.getSimpleName();

    private DownloadEntity mDownloadEntity;

    private PlayerView mPlayerView;
    private ExoPlayer mExoPlayer;

    private FilmstripTrimSlider mFilmstrip;
    private Slider mSpeedSlider;
    private TextView mSpeedValue;
    private TextView mRangeLabel;

    /* Slider position → fps mapping. Indexed by (int) slider value. */
    private static final int[] SPEED_FPS = {6, 8, 12, 18, 25};

    /* Below this, the encode either produces an empty GIF (start == end)
     * or a single-frame one that's barely a GIF — ffmpeg's gif muxer
     * rejects degenerate inputs and the native side guards too, but
     * we want to fail fast in the UI rather than start a doomed task. */
    private static final long MIN_TRIM_MS = 200L;

    /* Long-edge cap for extracted thumbnails. 256 px keeps each frame
     * under ~256 KB so a strip of 12 stays around 3 MB even on 16:9
     * sources, while still giving the filmstrip enough resolution to
     * read at the 64 dp strip height. */
    private static final int THUMB_MAX_DIM = 256;

    private MaterialButton mCreateButton;

    private long mDurationMs;
    private long mLastStartMs;
    private long mLastEndMs;

    /* Thumbnail extraction needs both the source duration (for evenly
     * spaced positions) and the filmstrip's measured width (for count).
     * Both arrive asynchronously and in arbitrary order — duration when
     * the player reaches STATE_READY, count when the view is laid out.
     * Track whichever is missing and kick off extraction the moment we
     * have both, but only once. */
    private int mPendingThumbnailCount = -1;
    private boolean mThumbnailsRequested;

    @Nullable
    private ExecutorService mThumbnailExecutor;
    /* Set on onDestroy so the worker thread doesn't post results back
     * into a torn-down view. */
    private volatile boolean mDestroyed;

    private static final long PREVIEW_LOOP_INTERVAL_MS = 250L;
    private final Handler mLoopHandler = new Handler(Looper.getMainLooper());
    private final Runnable mLoopTask = new Runnable() {
        @Override
        public void run() {
            if (mExoPlayer != null) {
                long pos = mExoPlayer.getCurrentPosition();
                if (mLastEndMs > mLastStartMs && (pos >= mLastEndMs || pos < mLastStartMs)) {
                    mExoPlayer.seekTo(mLastStartMs);
                    pos = mLastStartMs;
                }
                if (mFilmstrip != null) mFilmstrip.setPlayhead(pos);
            }
            mLoopHandler.postDelayed(this, PREVIEW_LOOP_INTERVAL_MS);
        }
    };


    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        mDownloadEntity = com.solarized.firedown.utils.FragmentArgs.parcelable(
                this, Keys.ITEM_ID, DownloadEntity.class);
        // Null on restore: nothing to edit, pop back to caller. onCreateView
        // returns null for the same reason.
        if (mDownloadEntity == null && mNavController != null) {
            mNavController.popBackStack();
        }
    }

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        if (mDownloadEntity == null) return null;
        View view = inflater.inflate(R.layout.fragment_gif_maker, container, false);

        mToolbar = view.findViewById(R.id.toolbar);
        mAppBarLayout = view.findViewById(R.id.appbar_layout);
        mPlayerView = view.findViewById(R.id.player_view);
        mFilmstrip = view.findViewById(R.id.filmstrip);
        mSpeedSlider = view.findViewById(R.id.speed_slider);
        mSpeedValue = view.findViewById(R.id.speed_value);
        mRangeLabel = view.findViewById(R.id.range_label);

        return view;
    }

    @OptIn(markerClass = UnstableApi.class)
    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        /* super attaches the inset listeners on mToolbar / mNavScrim
         * (BaseFocusFragment handles top + bottom system bars), so the
         * field bindings have to happen in onCreateView before this. */
        super.onViewCreated(view, savedInstanceState);

        mToolbar.setNavigationOnClickListener(v ->
                NavigationUtils.popBackStackSafe(mNavController, R.id.gif_maker));

        mCreateButton = view.findViewById(R.id.create_button);
        mCreateButton.setOnClickListener(v -> startGifMakerTask());
        mCreateButton.setEnabled(false);

        /* The create button is constrained to the body's bottom, which is
         * the same screen edge that navigation_scrim grows up from. On
         * gesture-bar devices the scrim is ~24dp tall, taller than the
         * button's own 16dp bottom margin, so the button sits behind it.
         * Add the system bottom inset to the button's bottom margin —
         * same approach BaseFocusFragment uses for mFab. */
        int baseBottomMargin = ((ViewGroup.MarginLayoutParams) mCreateButton.getLayoutParams())
                .bottomMargin;
        ViewCompat.setOnApplyWindowInsetsListener(mCreateButton, (v, windowInsets) -> {
            Insets bars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars());
            ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) v.getLayoutParams();
            lp.bottomMargin = baseBottomMargin + bars.bottom;
            v.setLayoutParams(lp);
            return windowInsets;
        });

        configurePlayer();
        configureFilmstrip();
        configureSpeedSlider();

        mLoopHandler.postDelayed(mLoopTask, PREVIEW_LOOP_INTERVAL_MS);
    }

    @OptIn(markerClass = UnstableApi.class)
    private void configurePlayer() {
        mExoPlayer = new ExoPlayer.Builder(requireContext()).build();
        mPlayerView.setPlayer(mExoPlayer);

        DataSource.Factory dataSourceFactory = new FileDataSource.Factory();
        ExtractorsFactory extractorsFactory = new DefaultExtractorsFactory()
                .setConstantBitrateSeekingEnabled(true)
                .setConstantBitrateSeekingAlwaysEnabled(true);

        MediaItem mediaItem = MediaItem.fromUri(Uri.parse(mDownloadEntity.getFilePath()));
        MediaSource source = new ProgressiveMediaSource.Factory(dataSourceFactory, extractorsFactory)
                .createMediaSource(mediaItem);

        mExoPlayer.setMediaSource(source);
        mExoPlayer.prepare();
        /* Don't autoplay — the user opens this screen to set up the trim,
         * not to immediately blast audio. They can hit play in the player
         * controls when they want to verify the loop. */
        mExoPlayer.setPlayWhenReady(false);

        mExoPlayer.addListener(new Player.Listener() {
            @Override
            public void onPlaybackStateChanged(int state) {
                if (state == Player.STATE_READY && mDurationMs <= 0) {
                    long duration = mExoPlayer.getDuration();
                    if (duration > 0) {
                        applyDuration(duration);
                    }
                }
            }
        });
    }

    private void configureFilmstrip() {
        mFilmstrip.setMinTrimMs(MIN_TRIM_MS);
        mFilmstrip.setOnTrimChangedListener((startMs, endMs, fromUser) -> {
            mLastStartMs = startMs;
            mLastEndMs = endMs;
            updateRangeLabel();
            if (fromUser && mExoPlayer != null) {
                /* Seek to the handle the user just moved. The filmstrip
                 * doesn't tell us which one changed, but mLast* shadowed
                 * the previous values so a comparison would work — for
                 * simplicity, always seek to the start handle on drag.
                 * The live-preview loop will cycle the player back into
                 * the trim region anyway. */
                mExoPlayer.seekTo(startMs);
            }
        });
        mFilmstrip.setOnLayoutReadyListener(count -> {
            mPendingThumbnailCount = count;
            maybeExtractThumbnails();
        });
    }

    private void applyDuration(long durationMs) {
        /* Round duration down to 100 ms — keeps the same step granularity
         * the old RangeSlider needed, and keeps the time label clean. */
        long rounded = (durationMs / 100L) * 100L;
        if (rounded < 100L) rounded = 100L;
        mDurationMs = rounded;
        mLastStartMs = 0L;
        mLastEndMs = rounded;

        mFilmstrip.setDuration(rounded);
        updateRangeLabel();

        if (mCreateButton != null) mCreateButton.setEnabled(true);

        maybeExtractThumbnails();
    }

    /** Kick off async extraction once both prerequisites are known.
     *  Either side may complete first: duration arrives via STATE_READY,
     *  count arrives via the filmstrip's OnLayoutReadyListener. */
    private void maybeExtractThumbnails() {
        if (mThumbnailsRequested) return;
        if (mDurationMs <= 0 || mPendingThumbnailCount <= 0) return;

        mThumbnailsRequested = true;

        int count = mPendingThumbnailCount;
        long durationMs = mDurationMs;
        String filePath = mDownloadEntity.getFilePath();

        if (mThumbnailExecutor == null) {
            mThumbnailExecutor = Executors.newSingleThreadExecutor();
        }
        mThumbnailExecutor.execute(() -> extractThumbnailsBlocking(filePath, durationMs, count));
    }

    /** Runs on the executor. Tries {@link MediaMetadataRetriever} for
     *  evenly-spaced frames first; if MMR can't decode the clip at all
     *  (codec not natively supported by Android — AV1 on older devices
     *  is the common case), falls back to {@link FFmpegThumbnailer} for
     *  a single representative frame at position 0.
     *
     *  Why MMR is primary: its time-based seek does the
     *  seek-then-decode-from-keyframe dance correctly for mid-clip
     *  positions, which gives a real filmstrip across the source. The
     *  project's FFmpegThumbnailer uses AVSEEK_FLAG_ANY which lands on
     *  non-keyframes, so its decoder bails to EOF for any non-zero
     *  position — that's why we don't use it as the primary path.
     *
     *  Why FFmpeg is the fallback: the bundled build pulls in libdav1d
     *  and the standard codec set, so it can decode AV1 / VP9 / etc.
     *  even when the device's MediaCodec can't. With stream_pos=0 the
     *  seek path is skipped (the native code only seeks for pos>0), so
     *  decode starts cleanly from the file head and produces one valid
     *  frame. Better than an empty grey strip.
     *
     *  Each successful MMR frame is posted to the filmstrip
     *  individually instead of batched, so the user sees the strip
     *  fill in left-to-right rather than stay grey for the whole
     *  extraction window. setThumbnailCount pre-allocates the slots
     *  with nulls; the view renders them as placeholder cells until
     *  setThumbnailAt replaces each one.
     *
     *  Failures are logged but never crash — the filmstrip falls
     *  back to grey placeholder cells if both paths fail. */
    private void extractThumbnailsBlocking(String filePath, long durationMs, int count) {
        /* Tell the strip how many slots to reserve before extraction
         * starts so it can render placeholders for the cells that
         * haven't been filled yet. */
        mLoopHandler.post(() -> {
            if (mDestroyed || mFilmstrip == null) return;
            mFilmstrip.setThumbnailCount(count);
        });

        boolean anyMmr = extractWithMediaMetadataRetriever(filePath, durationMs, count);

        if (!anyMmr && !mDestroyed) {
            Log.w(TAG, "MediaMetadataRetriever returned no frames; falling back to FFmpegThumbnailer");
            Bitmap fallback = extractWithFFmpegThumbnailer(filePath);
            if (fallback != null) {
                /* Single bitmap covers the whole strip — drawFilmstrip
                 * partitions width by list size, so size=1 means one
                 * cell spanning everything. */
                List<Bitmap> single = new ArrayList<>(1);
                single.add(fallback);
                mLoopHandler.post(() -> {
                    if (mDestroyed || mFilmstrip == null) return;
                    mFilmstrip.setThumbnails(single);
                });
            }
        }
    }

    /** @return true if at least one frame was extracted and posted. */
    private boolean extractWithMediaMetadataRetriever(String filePath, long durationMs, int count) {
        boolean anySuccess = false;
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(filePath);

            long durationUs = durationMs * 1000L;
            for (int i = 0; i < count; i++) {
                if (mDestroyed) return anySuccess;

                /* Anchor at 5% into the clip and stop at 95% so we don't
                 * pick black frames at the very start/end of fade-in /
                 * fade-out edits. Spread evenly across the 90% middle. */
                long posUs;
                if (count == 1) {
                    posUs = durationUs / 2;
                } else {
                    long startUs = durationUs / 20;
                    long endUs = durationUs - startUs;
                    posUs = startUs + (long) i * (endUs - startUs) / (count - 1);
                }

                Bitmap frame = extractFrame(retriever, posUs);
                if (frame == null) {
                    Log.w(TAG, "thumbnail null at pos " + posUs);
                    continue;
                }

                Bitmap small = scaleDown(frame);
                anySuccess = true;
                final int slot = i;
                mLoopHandler.post(() -> {
                    if (mDestroyed || mFilmstrip == null) return;
                    mFilmstrip.setThumbnailAt(slot, small);
                });
            }
        } catch (Throwable t) {
            Log.e(TAG, "MediaMetadataRetriever extraction failed", t);
        } finally {
            try { retriever.release(); } catch (Throwable ignored) { }
        }
        return anySuccess;
    }

    /** OPTION_CLOSEST_SYNC instead of OPTION_CLOSEST: jumps to the
     *  nearest keyframe directly instead of decoding forward from the
     *  previous keyframe to the exact time. Typical speedup on H.264
     *  with 2-second keyframe intervals is 5–10× per frame. The
     *  filmstrip is a navigation aid showing "what's around here", so
     *  exact frame precision isn't worth the cost.
     *
     *  getScaledFrameAtTime (API 27+) decodes at the target resolution
     *  internally, saving a full-res allocation and a downstream
     *  scaleDown pass. The output bitmap is at least the target
     *  dimensions in both axes (Android sizing contract), so we still
     *  run scaleDown afterwards as a safety cap. */
    private static Bitmap extractFrame(MediaMetadataRetriever retriever, long posUs) {
        if (BuildUtils.hasAndroid27()) {
            return retriever.getScaledFrameAtTime(posUs,
                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                    THUMB_MAX_DIM, THUMB_MAX_DIM);
        }
        return retriever.getFrameAtTime(posUs,
                MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
    }

    /** Single-frame fallback via the project's FFmpeg-based decoder.
     *  Called only when MediaMetadataRetriever returned nothing — most
     *  often AV1 on devices where MediaCodec can't decode it but the
     *  bundled libdav1d can. Uses stream_pos=0 to skip the native seek
     *  path (which is wired with AVSEEK_FLAG_ANY and lands on
     *  non-keyframes for mid-clip positions); this gives one clean
     *  frame from the file head, which the filmstrip view tiles across
     *  the whole strip. Beats an empty grey strip. */
    private @Nullable Bitmap extractWithFFmpegThumbnailer(String filePath) {
        com.solarized.firedown.ffmpegutils.FFmpegThumbnailer thumb =
                new com.solarized.firedown.ffmpegutils.FFmpegThumbnailer();
        try {
            int err = thumb.setDataSource(filePath, null);
            if (err < 0) {
                Log.w(TAG, "FFmpegThumbnailer setDataSource failed: " + err);
                return null;
            }
            Bitmap full = thumb.getBitmap(0L);
            if (full == null) {
                Log.w(TAG, "FFmpegThumbnailer null bitmap");
                return null;
            }
            return scaleDown(full);
        } catch (Throwable t) {
            Log.e(TAG, "FFmpegThumbnailer fallback failed", t);
            return null;
        } finally {
            try { thumb.release(); } catch (Throwable ignored) { }
        }
    }

    private static Bitmap scaleDown(Bitmap src) {
        int w = src.getWidth();
        int h = src.getHeight();
        int max = Math.max(w, h);
        if (max <= THUMB_MAX_DIM) return src;
        float scale = THUMB_MAX_DIM / (float) max;
        return Bitmap.createScaledBitmap(src,
                Math.max(1, (int) (w * scale)),
                Math.max(1, (int) (h * scale)),
                true);
    }

    private void configureSpeedSlider() {
        mSpeedSlider.setLabelFormatter(value -> speedLabel((int) value));
        mSpeedSlider.addOnChangeListener((slider, value, fromUser) ->
                mSpeedValue.setText(speedLabel((int) value)));
        mSpeedValue.setText(speedLabel((int) mSpeedSlider.getValue()));
    }

    private String speedLabel(int index) {
        switch (clampSpeedIndex(index)) {
            case 0: return getString(R.string.gif_maker_speed_very_slow);
            case 1: return getString(R.string.gif_maker_speed_slow);
            case 3: return getString(R.string.gif_maker_speed_fast);
            case 4: return getString(R.string.gif_maker_speed_very_fast);
            default: return getString(R.string.gif_maker_speed_medium);
        }
    }

    private static int clampSpeedIndex(int index) {
        if (index < 0) return 0;
        if (index >= SPEED_FPS.length) return SPEED_FPS.length - 1;
        return index;
    }

    private void updateRangeLabel() {
        if (mDurationMs <= 0) {
            mRangeLabel.setText(formatTime(0) + " → " + formatTime(0) + " (0s)");
            return;
        }
        long span = Math.max(0L, mLastEndMs - mLastStartMs);
        mRangeLabel.setText(String.format(Locale.getDefault(),
                "%s → %s (%s)",
                formatTime(mLastStartMs), formatTime(mLastEndMs), formatDuration(span)));
    }

    private static String formatTime(long ms) {
        long s = ms / 1000L;
        return String.format(Locale.getDefault(), "%02d:%02d", s / 60L, s % 60L);
    }

    private static String formatDuration(long ms) {
        /* "500s" reads as nonsense in the trim label — switch to clock
         * format so the duration matches the start/end markers above
         * it. Sub-minute durations stay readable as 0:30 (with the
         * leading zero on seconds), and we fall through to H:MM:SS for
         * very long clips. */
        long totalSeconds = (ms + 500L) / 1000L;
        long h = totalSeconds / 3600L;
        long m = (totalSeconds % 3600L) / 60L;
        long s = totalSeconds % 60L;
        if (h > 0) {
            return String.format(Locale.getDefault(), "%d:%02d:%02d", h, m, s);
        }
        return String.format(Locale.getDefault(), "%d:%02d", m, s);
    }

    private int currentFps() {
        return SPEED_FPS[clampSpeedIndex((int) mSpeedSlider.getValue())];
    }

    private void startGifMakerTask() {
        if (mDownloadEntity == null) return;

        if (mDurationMs <= 0) {
            Snackbar.make(requireView(), R.string.gif_maker_not_ready,
                    Snackbar.LENGTH_SHORT).show();
            return;
        }

        long start = mLastStartMs;
        long end = mLastEndMs;

        if (end - start < MIN_TRIM_MS) {
            Snackbar.make(requireView(), R.string.gif_maker_invalid_range,
                    Snackbar.LENGTH_LONG).show();
            return;
        }

        ArrayList<DownloadEntity> entities = new ArrayList<>(1);
        entities.add(mDownloadEntity);

        Intent intent = new Intent(requireContext(), TaskManager.class);
        intent.setAction(IntentActions.DOWNLOAD_START_MAKE_GIF);
        intent.putExtra(Keys.ITEM_LIST_ID, entities);
        intent.putExtra(Keys.GIF_START_MS, start);
        intent.putExtra(Keys.GIF_END_MS, end);
        intent.putExtra(Keys.GIF_FPS, currentFps());
        intent.putExtra(Keys.GIF_WIDTH, FFmpegGifMaker.DEFAULT_WIDTH);
        requireContext().startService(intent);

        NavigationUtils.popBackStackSafe(mNavController, R.id.gif_maker);
    }

    @Override
    public void onPause() {
        super.onPause();
        if (mExoPlayer != null) mExoPlayer.pause();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        mDestroyed = true;
        mLoopHandler.removeCallbacks(mLoopTask);
        if (mThumbnailExecutor != null) {
            mThumbnailExecutor.shutdownNow();
            mThumbnailExecutor = null;
        }
        if (mPlayerView != null) mPlayerView.setPlayer(null);
        if (mExoPlayer != null) mExoPlayer.release();
        mExoPlayer = null;
        mPlayerView = null;
        mFilmstrip = null;
        mCreateButton = null;
    }
}
