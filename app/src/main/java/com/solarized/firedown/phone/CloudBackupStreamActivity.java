package com.solarized.firedown.phone;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.ProgressBar;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.OptIn;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.source.ProgressiveMediaSource;
import androidx.media3.extractor.DefaultExtractorsFactory;
import androidx.media3.ui.PlayerView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.google.android.material.snackbar.Snackbar;
import com.solarized.firedown.Preferences;
import com.solarized.firedown.R;
import com.solarized.firedown.glide.MimeTypeThumbnail;
import com.solarized.firedown.glide.VaultObjectModel;
import com.solarized.firedown.sync.StorageApiClient;
import com.solarized.firedown.sync.SyncSecrets;
import com.solarized.firedown.sync.VaultDataSource;
import com.solarized.firedown.sync.VaultObjectReader;
import com.solarized.firedown.sync.crypto.SyncIdentity;
import com.solarized.firedown.sync.model.VaultEntry;
import com.solarized.firedown.utils.BuildUtils;
import com.solarized.firedown.utils.FileUriHelper;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.inject.Inject;

import dagger.hilt.android.AndroidEntryPoint;
import okhttp3.OkHttpClient;

/**
 * Plays / shows a backed-up cloud file WITHOUT restoring it to disk first, by
 * streaming its encrypted chunks on demand through {@link VaultObjectReader}
 * (decrypt-on-read) into a media3 {@link VaultDataSource}. Only reached for
 * video/audio/image entries with NO local copy — a local copy is opened directly
 * by the item sheet, and non-media types have no in-app viewer (they must be
 * restored). The chunks are decrypted on-device; the DEK is unwrapped here from
 * the manifest's wrapped blob under the recovery-code storage master key (loaded
 * on a background thread — the server never sees a key or a plaintext byte).
 *
 * <p>Chrome mirrors the local media viewer ({@code MediaViewerFragment}): the same
 * custom PlayerView controller (±10s seek buttons + double-tap seek), immersive
 * edge-to-edge with system bars + the overlay ActionBar (carrying the file title)
 * toggled in lockstep with the controller, and the PlayerView's built-in buffering
 * spinner. It stays a self-contained screen because PlayerActivity assumes a local
 * {@code DownloadEntity} file path and would need risky changes to thread a
 * decrypt-on-read source + async key load.</p>
 */
@AndroidEntryPoint
public class CloudBackupStreamActivity extends AppCompatActivity {

    public static final String EXTRA_OBJECT_ID = "cb_stream_object_id";
    public static final String EXTRA_WRAPPED_DEK = "cb_stream_wrapped_dek";
    public static final String EXTRA_NAME = "cb_stream_name";
    public static final String EXTRA_MIME = "cb_stream_mime";
    public static final String EXTRA_SIZE = "cb_stream_size";
    public static final String EXTRA_CHUNK_COUNT = "cb_stream_chunk_count";

    /** Double-tap / button seek step (matches the local viewer). */
    private static final long SEEK_DELTA_MS = 10_000L;
    /** Controller auto-hide timeout (matches the local viewer's 5 s). */
    private static final int CONTROLLER_TIMEOUT_MS = 5000;

    @Inject
    OkHttpClient mHttpClient;

    private final Handler mMain = new Handler(Looper.getMainLooper());
    private final ExecutorService mIo = Executors.newSingleThreadExecutor();

    private PlayerView mPlayerView;
    private ImageView mImageView;
    private ProgressBar mProgress;

    private ExoPlayer mPlayer;
    private VaultObjectReader mReader;
    private WindowInsetsControllerCompat mInsetsController;
    private GestureDetector mGestureDetector;

    /** Convenience launcher so callers don't juggle the extra keys. */
    public static Intent newIntent(Context context, String objectId, String wrappedDek,
                                   String name, String mime, long size, int chunkCount) {
        Intent intent = new Intent(context, CloudBackupStreamActivity.class);
        intent.putExtra(EXTRA_OBJECT_ID, objectId);
        intent.putExtra(EXTRA_WRAPPED_DEK, wrappedDek);
        intent.putExtra(EXTRA_NAME, name);
        intent.putExtra(EXTRA_MIME, mime);
        intent.putExtra(EXTRA_SIZE, size);
        intent.putExtra(EXTRA_CHUNK_COUNT, chunkCount);
        return intent;
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Edge-to-edge immersive, same as PlayerActivity: bars stay transparent
        // over the video and WindowInsetsControllerCompat owns their visibility.
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        setContentView(R.layout.activity_cloud_backup_stream);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (BuildUtils.hasAndroidP()) {
            getWindow().getAttributes().layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }

        mPlayerView = findViewById(R.id.cb_stream_player);
        mImageView = findViewById(R.id.cb_stream_image);
        mProgress = findViewById(R.id.cb_stream_progress);
        mInsetsController = WindowCompat.getInsetsController(getWindow(),
                getWindow().getDecorView());
        mInsetsController.setSystemBarsBehavior(
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);

        String objectId = getIntent().getStringExtra(EXTRA_OBJECT_ID);
        String wrappedDek = getIntent().getStringExtra(EXTRA_WRAPPED_DEK);
        String name = getIntent().getStringExtra(EXTRA_NAME);
        final String mime = getIntent().getStringExtra(EXTRA_MIME);
        long size = getIntent().getLongExtra(EXTRA_SIZE, 0);
        int chunkCount = getIntent().getIntExtra(EXTRA_CHUNK_COUNT, 0);

        ActionBar actionBar = getSupportActionBar();
        if (actionBar != null) {
            actionBar.setTitle(name);
            // No "streaming" subtitle here, deliberately. The buffering spinner
            // (PlayerView app:show_buffering) already says it — a stall is the
            // honest, self-evident signal that the bytes are arriving over the
            // network, and it only speaks when there is something to say. A
            // permanent line of text states the same thing on every frame of
            // every playback, including the ones that never stall.
            // DISPLAY_SHOW_TITLE must be set EXPLICITLY, and this is why the
            // toolbar rendered with a back arrow and no title at all: the theme
            // points actionBarStyle at Theme.FireDown.Play.Toolbar, which is a
            // ThemeOverlay (it belongs on actionBarTheme, which is also set to
            // it). A ThemeOverlay declares none of the ActionBar WIDGET
            // attributes, so displayOptions resolves to 0 instead of the
            // showTitle default, and both the title and the subtitle are
            // suppressed. PlayerActivity has always compensated with the same
            // call; this activity never did. Order matters — setDisplayOptions
            // REPLACES the flags, so the home-as-up call has to follow it.
            actionBar.setDisplayOptions(ActionBar.DISPLAY_SHOW_TITLE);
            actionBar.setDisplayHomeAsUpEnabled(true);
        }

        if (objectId == null || wrappedDek == null) {
            fail();
            return;
        }

        if (FileUriHelper.isImage(mime)) {
            // Images decode straight through Glide's vault ModelLoader (decrypt-on-
            // read → downsampled bitmap) — no manual whole-file load, no reader to
            // manage here. Keep the chrome shown (an image isn't immersive-first).
            showImage(objectId, wrappedDek, size, chunkCount, mime);
            return;
        }
        // Video/audio: build the decrypt-on-read reader off the main thread (it
        // loads the recovery code + derives the identity), then stream it into
        // ExoPlayer through VaultDataSource.
        final VaultEntry entry = new VaultEntry(objectId, wrappedDek, name, size,
                mime, 0, chunkCount, null, null); // thumb + origin unused for streaming
        mProgress.setVisibility(View.VISIBLE);
        mIo.execute(() -> {
            byte[] code = new SyncSecrets(this).load();
            if (code == null) {
                mMain.post(this::fail);
                return;
            }
            VaultObjectReader reader;
            try {
                SyncIdentity identity = SyncIdentity.fromCode(code);
                StorageApiClient api = new StorageApiClient(mHttpClient,
                        Preferences.STORAGE_DEFAULT_BACKEND);
                reader = new VaultObjectReader(api, identity, entry);
            } catch (Exception e) {
                mMain.post(this::fail);
                return;
            } finally {
                SyncSecrets.wipe(code);
            }
            final VaultObjectReader readyReader = reader;
            mMain.post(() -> {
                if (isFinishing() || isDestroyed()) {
                    readyReader.close();
                    return;
                }
                mReader = readyReader;
                mProgress.setVisibility(View.GONE);
                startPlayer(entry);
            });
        });
    }

    private void showImage(String objectId, String wrappedDek, long size, int chunkCount,
                           String mime) {
        mImageView.setVisibility(View.VISIBLE);
        mPlayerView.setVisibility(View.GONE);
        mProgress.setVisibility(View.GONE);
        Glide.with(this)
                .load(new VaultObjectModel(objectId, wrappedDek, size, chunkCount))
                // Never persist decrypted vault bytes to Glide's disk cache — the
                // whole point is the file stays encrypted at rest. Memory cache
                // (session-only) still avoids a re-fetch.
                .diskCacheStrategy(DiskCacheStrategy.NONE)
                .error(MimeTypeThumbnail.generateDrawable(this,
                        mime != null ? mime : "application/octet-stream"))
                .into(mImageView);
    }

    @OptIn(markerClass = UnstableApi.class)
    private void startPlayer(VaultEntry entry) {
        mPlayerView.setVisibility(View.VISIBLE);
        mImageView.setVisibility(View.GONE);

        // Controller behaviour mirrors the local viewer: no auto-show at launch
        // (fully immersive), a 5 s auto-hide, and the tap gesture reproduced so
        // double-tap-to-seek wins over the built-in tap-to-toggle.
        mPlayerView.setUseController(true);
        mPlayerView.setControllerAutoShow(false);
        mPlayerView.setControllerHideOnTouch(false);
        mPlayerView.setControllerShowTimeoutMs(CONTROLLER_TIMEOUT_MS);
        mPlayerView.setControllerVisibilityListener(
                (PlayerView.ControllerVisibilityListener) visibility ->
                        setChromeVisible(visibility == View.VISIBLE));
        // Audio has no video surface — show the mime glyph as artwork (like the
        // local viewer) instead of a black screen behind the controls.
        if (FileUriHelper.isAudio(entry.mime)) {
            mPlayerView.setDefaultArtwork(MimeTypeThumbnail.generateDrawable(this, entry.mime));
        }
        setupBottomBarInsets();
        setupSeekButtons();
        setupTapGestures();

        Uri uri = new Uri.Builder().scheme("vault").authority(entry.objectId).build();
        DataSource.Factory factory = new VaultDataSource.Factory(mReader, uri);
        MediaSource source = new ProgressiveMediaSource.Factory(factory, new DefaultExtractorsFactory())
                .createMediaSource(MediaItem.fromUri(uri));
        mPlayer = new ExoPlayer.Builder(this).build();
        mPlayer.addListener(new Player.Listener() {
            @Override
            public void onPlayerError(@NonNull PlaybackException error) {
                snackbar(getString(R.string.error_file_type_unknown));
            }
        });
        mPlayerView.setPlayer(mPlayer);
        mPlayer.setMediaSource(source);
        mPlayer.prepare();
        mPlayer.setPlayWhenReady(true);
        // Start fully immersive (bars + ActionBar hidden); the first tap shows the
        // controller → the visibility listener brings the chrome back.
        setChromeVisible(false);
    }

    /** Pads the controller's bottom bar clear of the nav bar / cutout, like the
     *  local viewer, so the scrubber isn't under the system bar. */
    @OptIn(markerClass = UnstableApi.class)
    private void setupBottomBarInsets() {
        final View bottomBar = mPlayerView.findViewById(R.id.exo_bottom_bar);
        if (bottomBar == null) {
            return;
        }
        final int xmlPaddingTop = bottomBar.getPaddingTop();
        ViewCompat.setOnApplyWindowInsetsListener(bottomBar, (v, windowInsets) -> {
            Insets navBars = windowInsets.getInsets(WindowInsetsCompat.Type.navigationBars());
            Insets cutout = windowInsets.getInsets(WindowInsetsCompat.Type.displayCutout());
            v.setPadding(Math.max(navBars.left, cutout.left), xmlPaddingTop,
                    Math.max(navBars.right, cutout.right), Math.max(navBars.bottom, cutout.bottom));
            return windowInsets;
        });
    }

    private void setupSeekButtons() {
        View back = mPlayerView.findViewById(R.id.media_viewer_btn_seek_back);
        View forward = mPlayerView.findViewById(R.id.media_viewer_btn_seek_forward);
        if (back != null) {
            back.setOnClickListener(v -> applySeek(-SEEK_DELTA_MS));
        }
        if (forward != null) {
            forward.setOnClickListener(v -> applySeek(SEEK_DELTA_MS));
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    @OptIn(markerClass = UnstableApi.class)
    private void setupTapGestures() {
        mGestureDetector = new GestureDetector(this,
                new GestureDetector.SimpleOnGestureListener() {
                    @Override
                    public boolean onDoubleTap(@NonNull MotionEvent e) {
                        if (mPlayerView == null || mPlayer == null) {
                            return false;
                        }
                        boolean leftHalf = e.getX() < mPlayerView.getWidth() / 2f;
                        applySeek(leftHalf ? -SEEK_DELTA_MS : SEEK_DELTA_MS);
                        return true;
                    }

                    @Override
                    public boolean onSingleTapConfirmed(@NonNull MotionEvent e) {
                        if (mPlayerView == null) {
                            return false;
                        }
                        if (mPlayerView.isControllerFullyVisible()) {
                            mPlayerView.hideController();
                        } else {
                            mPlayerView.showController();
                        }
                        return true;
                    }
                });
        mPlayerView.setOnTouchListener((view, event) -> {
            mGestureDetector.onTouchEvent(event);
            return false;
        });
    }

    private void applySeek(long deltaMs) {
        if (mPlayer == null) {
            return;
        }
        long pos = mPlayer.getCurrentPosition();
        long dur = mPlayer.getDuration();
        long upper = dur > 0 ? dur : Long.MAX_VALUE;
        mPlayer.seekTo(Math.max(0L, Math.min(upper, pos + deltaMs)));
    }

    /** Shows/hides the system bars + overlay ActionBar in lockstep with the
     *  PlayerView controller (the local viewer's setChromeVisible). */
    private void setChromeVisible(boolean visible) {
        if (mInsetsController == null) {
            return;
        }
        ActionBar actionBar = getSupportActionBar();
        if (visible) {
            mInsetsController.show(WindowInsetsCompat.Type.systemBars());
            if (actionBar != null) {
                actionBar.show();
            }
        } else {
            if (actionBar != null) {
                actionBar.hide();
            }
            mInsetsController.hide(WindowInsetsCompat.Type.systemBars());
        }
    }

    @Override
    public boolean onSupportNavigateUp() {
        finish();
        return true;
    }

    private void fail() {
        // A background failure can be posted after the activity is torn down
        // (mIo.shutdownNow interrupts an in-flight fetch) — don't touch a dead
        // window then.
        if (isFinishing() || isDestroyed()) {
            return;
        }
        snackbar(getString(R.string.error_file_type_unknown));
        finish();
    }

    private void snackbar(String text) {
        View root = findViewById(android.R.id.content);
        if (root != null) {
            Snackbar.make(root, text, Snackbar.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (mPlayer != null) {
            mPlayer.pause();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (mPlayerView != null) {
            mPlayerView.setPlayer(null);
        }
        if (mPlayer != null) {
            mPlayer.release();
            mPlayer = null;
        }
        if (mReader != null) {
            mReader.close();
            mReader = null;
        }
        mIo.shutdownNow();
    }
}
