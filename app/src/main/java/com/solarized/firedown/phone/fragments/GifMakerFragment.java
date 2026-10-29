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

import androidx.navigation.NavBackStackEntry;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.slider.Slider;
import com.google.android.material.snackbar.Snackbar;
import com.solarized.firedown.IntentActions;
import com.solarized.firedown.Keys;
import com.solarized.firedown.R;
import com.solarized.firedown.ffmpegutils.FFmpegThumbnailer;
import com.solarized.firedown.data.entity.DownloadEntity;
import com.solarized.firedown.ffmpegutils.FFmpegGifMaker;
import com.solarized.firedown.manager.tasks.TaskManager;
import com.solarized.firedown.ui.FilmstripTrimSlider;
import com.solarized.firedown.utils.BuildUtils;
import com.solarized.firedown.utils.FragmentArgs;
import com.solarized.firedown.utils.NavigationUtils;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
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

    /* The strip's slots need both the source duration and the filmstrip's
     * measured width. Both arrive asynchronously and in arbitrary order —
     * duration when the player reaches STATE_READY, width when the view is
     * laid out. Track whichever is missing and set the slots up the moment
     * we have both, but only once. */
    private boolean mLayoutReady;
    private boolean mThumbnailsRequested;

    @Nullable
    private ExecutorService mThumbnailExecutor;
    /* Set on onDestroy so the worker thread doesn't post results back
     * into a torn-down view. */
    private volatile boolean mDestroyed;

    /* The slots the strip asked for and the ones already tried. The worker
     * takes the next untried slot of the asked range, so a scroll is
     * answered before the slots it left behind. */
    private final Object mThumbLock = new Object();
    private int mSlotCount;
    @Nullable
    private boolean[] mSlotTried;
    private int mWantFirst;
    private int mWantLast = -1;
    private boolean mThumbWorkerActive;

    /* Thumbnail worker thread only. MediaMetadataRetriever is the device's
     * own decoder; when it gives nothing (a codec the device lacks, AV1 on
     * older phones), one frame from the bundled FFmpeg fills every cell. */
    private int mRetrieverFrames;
    private int mRetrieverMisses;
    private boolean mRetrieverFailed;
    private boolean mFallbackTried;
    @Nullable
    private Bitmap mFallbackFrame;

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

        mDownloadEntity = FragmentArgs.parcelable(
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

        // Uri.fromFile, never Uri.parse(filePath) — parse() reads the raw path
        // as an already-encoded uri, so a '%' in the filename decodes into
        // U+FFFD and the source open fails on an existing file (the
        // MediaViewerFragment bug, same line shape). See its comment.
        MediaItem mediaItem = MediaItem.fromUri(Uri.fromFile(new File(mDownloadEntity.getFilePath())));
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
        mFilmstrip.setMaxTrimMs(FFmpegGifMaker.MAX_DURATION_MS);
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
            mLayoutReady = true;
            maybeExtractThumbnails();
        });
        mFilmstrip.setOnThumbnailsNeededListener(this::requestThumbnails);
    }

    private void applyDuration(long durationMs) {
        /* Round duration down to 100 ms — keeps the same step granularity
         * the old RangeSlider needed, and keeps the time label clean. */
        long rounded = (durationMs / 100L) * 100L;
        if (rounded < 100L) rounded = 100L;
        mDurationMs = rounded;
        mLastStartMs = 0L;
        mLastEndMs = Math.min(rounded, FFmpegGifMaker.MAX_DURATION_MS);

        mFilmstrip.setDuration(rounded);
        updateRangeLabel();

        if (mCreateButton != null) mCreateButton.setEnabled(true);

        maybeExtractThumbnails();
    }

    /** Sets the strip's slots up once both prerequisites are known.
     *  Either side may complete first: duration arrives via STATE_READY,
     *  the width via the filmstrip's OnLayoutReadyListener. The strip then
     *  asks for the slots on screen through {@link #requestThumbnails}. */
    private void maybeExtractThumbnails() {
        if (mThumbnailsRequested) return;
        if (mDurationMs <= 0 || !mLayoutReady) return;

        mThumbnailsRequested = true;

        int count = mFilmstrip.thumbnailSlotCount();
        synchronized (mThumbLock) {
            mSlotCount = count;
            mSlotTried = new boolean[count];
        }
        mFilmstrip.setThumbnailCount(count);
    }

    /** Main thread: the strip wants slots {@code first..last}. */
    private void requestThumbnails(int first, int last) {
        if (mDestroyed) return;
        synchronized (mThumbLock) {
            mWantFirst = first;
            mWantLast = last;
            if (mSlotTried == null || mThumbWorkerActive) {
                return;
            }
            mThumbWorkerActive = true;
        }
        if (mThumbnailExecutor == null) {
            mThumbnailExecutor = Executors.newSingleThreadExecutor();
        }
        String filePath = mDownloadEntity.getFilePath();
        long durationMs = mDurationMs;
        mThumbnailExecutor.execute(() -> runThumbnailWorker(filePath, durationMs));
    }

    /** The next untried slot of the asked range, nearest its middle, marked
     *  tried; -1 when there is none, and then the worker is done. */
    private int nextThumbnailSlot() {
        synchronized (mThumbLock) {
            int first = Math.max(0, mWantFirst);
            int last = Math.min(mSlotCount - 1, mWantLast);
            if (mSlotTried != null && !mDestroyed && first <= last) {
                int middle = (first + last) / 2;
                for (int d = 0; middle - d >= first || middle + d <= last; d++) {
                    int below = middle - d;
                    if (below >= first && !mSlotTried[below]) {
                        mSlotTried[below] = true;
                        return below;
                    }
                    int above = middle + d;
                    if (above <= last && !mSlotTried[above]) {
                        mSlotTried[above] = true;
                        return above;
                    }
                }
            }
            mThumbWorkerActive = false;
            return -1;
        }
    }

    /** Runs on the executor until the asked slots are all tried. Each frame
     *  is posted on its own, so the strip fills in as it comes. Failures are
     *  logged but never crash — a slot with no frame stays a placeholder. */
    private void runThumbnailWorker(String filePath, long durationMs) {
        MediaMetadataRetriever retriever = null;
        try {
            while (true) {
                int slot = nextThumbnailSlot();
                if (slot < 0) {
                    return;
                }
                if (!mRetrieverFailed && retriever == null) {
                    retriever = openRetriever(filePath);
                }
                Bitmap frame;
                if (mRetrieverFailed) {
                    frame = fallbackFrame(filePath);
                } else {
                    frame = retrieverFrame(retriever, slotTimeUs(slot, durationMs));
                }
                if (frame == null) {
                    continue;
                }
                mLoopHandler.post(() -> {
                    if (mDestroyed || mFilmstrip == null) return;
                    mFilmstrip.setThumbnailAt(slot, frame);
                });
            }
        } catch (Throwable t) {
            Log.e(TAG, "thumbnail extraction failed", t);
            synchronized (mThumbLock) {
                mThumbWorkerActive = false;
            }
        } finally {
            if (retriever != null) {
                try { retriever.release(); } catch (Throwable ignored) { }
            }
        }
    }

    /** The middle of a slot's share of the video. */
    private long slotTimeUs(int slot, long durationMs) {
        return (2L * slot + 1L) * durationMs * 1000L / (2L * mSlotCount);
    }

    private @Nullable MediaMetadataRetriever openRetriever(String filePath) {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(filePath);
            return retriever;
        } catch (Throwable t) {
            Log.w(TAG, "MediaMetadataRetriever can't open the file", t);
            try { retriever.release(); } catch (Throwable ignored) { }
            switchToFallback();
            return null;
        }
    }

    /** A slot's frame from MediaMetadataRetriever. Three misses with no hit
     *  mean it can't decode this file at all: every slot then takes the
     *  FFmpeg frame, the missed ones included. */
    private @Nullable Bitmap retrieverFrame(@Nullable MediaMetadataRetriever retriever,
                                            long posUs) {
        if (retriever == null) {
            return null;
        }
        Bitmap frame = null;
        try {
            frame = extractFrame(retriever, posUs);
        } catch (Throwable t) {
            Log.w(TAG, "thumbnail failed at pos " + posUs, t);
        }
        if (frame != null) {
            mRetrieverFrames++;
            return scaleDown(frame);
        }
        Log.w(TAG, "thumbnail null at pos " + posUs);
        mRetrieverMisses++;
        if (mRetrieverFrames == 0 && mRetrieverMisses >= 3) {
            switchToFallback();
        }
        return null;
    }

    private void switchToFallback() {
        Log.w(TAG, "MediaMetadataRetriever returned no frames; falling back to FFmpegThumbnailer");
        mRetrieverFailed = true;
        synchronized (mThumbLock) {
            if (mSlotTried != null) {
                Arrays.fill(mSlotTried, false);
            }
        }
    }

    private @Nullable Bitmap fallbackFrame(String filePath) {
        if (!mFallbackTried) {
            mFallbackTried = true;
            mFallbackFrame = extractWithFFmpegThumbnailer(filePath);
        }
        return mFallbackFrame;
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

    /** Single-frame fallback via the project's FFmpeg-based decoder, used
     *  only when MediaMetadataRetriever returned nothing — most often AV1
     *  on devices where MediaCodec can't decode it but the bundled libdav1d
     *  can. One frame from the file head, repeated in every cell; beats an
     *  empty grey strip. */
    private @Nullable Bitmap extractWithFFmpegThumbnailer(String filePath) {
        FFmpegThumbnailer thumb =
                new FFmpegThumbnailer();
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
        String spanText = formatDuration(span);
        // On a video longer than the limit, say so: dragging a handle past
        // it moves the other one.
        if (mDurationMs > FFmpegGifMaker.MAX_DURATION_MS) {
            spanText = getString(R.string.gif_maker_span_with_max, spanText,
                    formatDuration(FFmpegGifMaker.MAX_DURATION_MS));
        }
        mRangeLabel.setText(String.format(Locale.getDefault(),
                "%s → %s (%s)",
                formatTime(mLastStartMs), formatTime(mLastEndMs), spanText));
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

        // Hand the params back to DownloadFragment via the previous
        // back-stack entry's SavedStateHandle instead of starting the
        // service from here. Why: this fragment sits on top of
        // DownloadFragment, so DownloadFragment's TaskEvent observer is
        // PAUSED while we're foregrounded. If we fired the intent here
        // and popped back, TaskEvent.Started would land on the shared
        // LiveData with no active observer; by the time DownloadFragment
        // resumes the value might have been overwritten by a Progress
        // event (FFmpeg gif encoding fires Progress within a few
        // hundred ms of Started), and handleTaskStart — the only thing
        // that calls mBottomProgressView.setVisibility(VISIBLE) — never
        // runs. Net visible bug: bottom progress bar never appears for
        // gif tasks even though it does for audio.
        //
        // Mirror the audio-encode path instead: DownloadFragment kicks
        // the service intent itself, while still resumed, so the
        // Started event is delivered live to the active observer.
        Bundle gifArgs = new Bundle();
        gifArgs.putParcelableArrayList(Keys.ITEM_LIST_ID, entities);
        gifArgs.putLong(Keys.GIF_START_MS, start);
        gifArgs.putLong(Keys.GIF_END_MS, end);
        gifArgs.putInt(Keys.GIF_FPS, currentFps());
        gifArgs.putInt(Keys.GIF_WIDTH, FFmpegGifMaker.DEFAULT_WIDTH);

        NavBackStackEntry previous = mNavController.getPreviousBackStackEntry();
        if (previous != null) {
            previous.getSavedStateHandle().set(IntentActions.DOWNLOAD_START_MAKE_GIF, gifArgs);
        } else {
            // Defensive fallback: nothing to return to (deep link?). Fire
            // the intent directly. The bottom bar may not show in this
            // edge case but the gif still gets made.
            Intent intent = new Intent(requireContext(), TaskManager.class);
            intent.setAction(IntentActions.DOWNLOAD_START_MAKE_GIF);
            intent.putExtra(Keys.ITEM_LIST_ID, entities);
            intent.putExtra(Keys.GIF_START_MS, start);
            intent.putExtra(Keys.GIF_END_MS, end);
            intent.putExtra(Keys.GIF_FPS, currentFps());
            intent.putExtra(Keys.GIF_WIDTH, FFmpegGifMaker.DEFAULT_WIDTH);
            requireContext().startService(intent);
        }

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
