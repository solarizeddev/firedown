package com.solarized.firedown.phone.fragments;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
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
import com.solarized.firedown.data.entity.DownloadEntity;
import com.solarized.firedown.manager.tasks.TaskManager;
import com.solarized.firedown.utils.FragmentArgs;
import com.solarized.firedown.utils.NavigationUtils;

import java.io.File;
import java.util.ArrayList;
import java.util.Locale;

/**
 * Lightweight single-still picker: a video preview plus a scrub slider and
 * a "Save frame" button. The captured frame is whatever is currently shown
 * (the player's current position), so what you see is what you get. Hands
 * the chosen position back to DownloadFragment via the previous back-stack
 * entry's SavedStateHandle (same reason GifMakerFragment does — so the
 * bottom progress bar shows for the resulting task).
 */
public class FrameGrabberFragment extends BaseFocusFragment {

    private static final String TAG = FrameGrabberFragment.class.getSimpleName();

    private DownloadEntity mDownloadEntity;

    private PlayerView mPlayerView;
    private ExoPlayer mExoPlayer;
    private Slider mScrubSlider;
    private TextView mPositionLabel;
    private MaterialButton mSaveButton;

    private long mDurationMs;
    /* True while the user is dragging the slider, so the follow-loop
     * doesn't fight their drag by resetting the thumb under their finger. */
    private boolean mUserScrubbing;

    private static final long FOLLOW_INTERVAL_MS = 200L;
    private final Handler mLoopHandler = new Handler(Looper.getMainLooper());
    private final Runnable mLoopTask = new Runnable() {
        @Override
        public void run() {
            if (mExoPlayer != null && mScrubSlider != null && mDurationMs > 0 && !mUserScrubbing) {
                long pos = Math.max(0L, Math.min(mExoPlayer.getCurrentPosition(), mDurationMs));
                mScrubSlider.setValue(pos);
                updatePositionLabel(pos);
            }
            mLoopHandler.postDelayed(this, FOLLOW_INTERVAL_MS);
        }
    };

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mDownloadEntity = FragmentArgs.parcelable(
                this, Keys.ITEM_ID, DownloadEntity.class);
        if (mDownloadEntity == null && mNavController != null) {
            mNavController.popBackStack();
        }
    }

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        if (mDownloadEntity == null) return null;
        View view = inflater.inflate(R.layout.fragment_frame_grabber, container, false);
        mToolbar = view.findViewById(R.id.toolbar);
        mAppBarLayout = view.findViewById(R.id.appbar_layout);
        mPlayerView = view.findViewById(R.id.player_view);
        mScrubSlider = view.findViewById(R.id.scrub_slider);
        mPositionLabel = view.findViewById(R.id.position_label);
        return view;
    }

    @OptIn(markerClass = UnstableApi.class)
    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        mToolbar.setNavigationOnClickListener(v ->
                NavigationUtils.popBackStackSafe(mNavController, R.id.frame_grabber));

        mSaveButton = view.findViewById(R.id.save_button);
        mSaveButton.setOnClickListener(v -> saveFrame());
        mSaveButton.setEnabled(false);

        int baseBottomMargin = ((ViewGroup.MarginLayoutParams) mSaveButton.getLayoutParams()).bottomMargin;
        ViewCompat.setOnApplyWindowInsetsListener(mSaveButton, (v, windowInsets) -> {
            Insets bars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars());
            ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) v.getLayoutParams();
            lp.bottomMargin = baseBottomMargin + bars.bottom;
            v.setLayoutParams(lp);
            return windowInsets;
        });

        configurePlayer();
        configureSlider();
        mLoopHandler.postDelayed(mLoopTask, FOLLOW_INTERVAL_MS);
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
        mExoPlayer.setPlayWhenReady(false);

        mExoPlayer.addListener(new Player.Listener() {
            @Override
            public void onPlaybackStateChanged(int state) {
                if (state == Player.STATE_READY && mDurationMs <= 0) {
                    long duration = mExoPlayer.getDuration();
                    if (duration > 0) applyDuration(duration);
                }
            }
        });
    }

    private void configureSlider() {
        mScrubSlider.addOnSliderTouchListener(new Slider.OnSliderTouchListener() {
            @Override public void onStartTrackingTouch(@NonNull Slider slider) { mUserScrubbing = true; }
            @Override public void onStopTrackingTouch(@NonNull Slider slider) { mUserScrubbing = false; }
        });
        mScrubSlider.addOnChangeListener((slider, value, fromUser) -> {
            if (fromUser && mExoPlayer != null) {
                mExoPlayer.seekTo((long) value);
            }
            updatePositionLabel((long) value);
        });
    }

    private void applyDuration(long durationMs) {
        long rounded = (durationMs / 100L) * 100L;
        if (rounded < 100L) rounded = 100L;
        mDurationMs = rounded;
        mScrubSlider.setValueFrom(0f);
        mScrubSlider.setValueTo((float) rounded);
        mScrubSlider.setValue(0f);
        updatePositionLabel(0L);
        if (mSaveButton != null) mSaveButton.setEnabled(true);
    }

    private void updatePositionLabel(long posMs) {
        if (mPositionLabel == null) return;
        mPositionLabel.setText(String.format(Locale.getDefault(), "%s / %s",
                formatTime(posMs), formatTime(mDurationMs)));
    }

    private static String formatTime(long ms) {
        long s = ms / 1000L;
        return String.format(Locale.getDefault(), "%02d:%02d", s / 60L, s % 60L);
    }

    private void saveFrame() {
        if (mDownloadEntity == null) return;
        if (mDurationMs <= 0 || mExoPlayer == null) {
            Snackbar.make(requireView(), R.string.gif_maker_not_ready, Snackbar.LENGTH_SHORT).show();
            return;
        }

        // Capture the frame currently on screen.
        long posMs = Math.max(0L, Math.min(mExoPlayer.getCurrentPosition(), mDurationMs));

        ArrayList<DownloadEntity> entities = new ArrayList<>(1);
        entities.add(mDownloadEntity);

        // Mirror GifMakerFragment: hand the params back to DownloadFragment
        // (resumed) so TaskEvent.Started reaches an active observer and the
        // bottom progress bar shows. See the comment there for the why.
        Bundle args = new Bundle();
        args.putParcelableArrayList(Keys.ITEM_LIST_ID, entities);
        args.putLong(Keys.FRAME_POSITION_MS, posMs);

        NavBackStackEntry previous = mNavController.getPreviousBackStackEntry();
        if (previous != null) {
            previous.getSavedStateHandle().set(IntentActions.DOWNLOAD_START_SAVE_FRAME, args);
        } else {
            Intent intent = new Intent(requireContext(), TaskManager.class);
            intent.setAction(IntentActions.DOWNLOAD_START_SAVE_FRAME);
            intent.putExtra(Keys.ITEM_LIST_ID, entities);
            intent.putExtra(Keys.FRAME_POSITION_MS, posMs);
            requireContext().startService(intent);
        }

        NavigationUtils.popBackStackSafe(mNavController, R.id.frame_grabber);
    }

    @Override
    public void onPause() {
        super.onPause();
        if (mExoPlayer != null) mExoPlayer.pause();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        mLoopHandler.removeCallbacks(mLoopTask);
        if (mPlayerView != null) mPlayerView.setPlayer(null);
        if (mExoPlayer != null) mExoPlayer.release();
        mExoPlayer = null;
        mPlayerView = null;
        mScrubSlider = null;
        mSaveButton = null;
    }
}
