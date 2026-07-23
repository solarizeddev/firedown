package com.solarized.firedown.phone;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.ImageView;
import android.widget.ProgressBar;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.OptIn;
import androidx.appcompat.app.AppCompatActivity;
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
 * the manifest's wrapped blob under the recovery-code-derived storage master key
 * (loaded on a background thread — the server never sees a key or a plaintext
 * byte).
 *
 * <p>Self-contained on purpose: {@code PlayerActivity}/{@code MediaViewerFragment}
 * assume a local {@code DownloadEntity} file path and would need risky changes to
 * thread a decrypt-on-read source + async key load, so streaming a cloud file
 * gets its own minimal player screen.</p>
 */
@AndroidEntryPoint
public class CloudBackupStreamActivity extends AppCompatActivity {

    public static final String EXTRA_OBJECT_ID = "cb_stream_object_id";
    public static final String EXTRA_WRAPPED_DEK = "cb_stream_wrapped_dek";
    public static final String EXTRA_NAME = "cb_stream_name";
    public static final String EXTRA_MIME = "cb_stream_mime";
    public static final String EXTRA_SIZE = "cb_stream_size";
    public static final String EXTRA_CHUNK_COUNT = "cb_stream_chunk_count";

    @Inject
    OkHttpClient mHttpClient;

    private final Handler mMain = new Handler(Looper.getMainLooper());
    private final ExecutorService mIo = Executors.newSingleThreadExecutor();

    private PlayerView mPlayerView;
    private ImageView mImageView;
    private ProgressBar mProgress;

    private ExoPlayer mPlayer;
    private VaultObjectReader mReader;

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
        setContentView(R.layout.activity_cloud_backup_stream);
        mPlayerView = findViewById(R.id.cb_stream_player);
        mImageView = findViewById(R.id.cb_stream_image);
        mProgress = findViewById(R.id.cb_stream_progress);

        String objectId = getIntent().getStringExtra(EXTRA_OBJECT_ID);
        String wrappedDek = getIntent().getStringExtra(EXTRA_WRAPPED_DEK);
        String name = getIntent().getStringExtra(EXTRA_NAME);
        final String mime = getIntent().getStringExtra(EXTRA_MIME);
        long size = getIntent().getLongExtra(EXTRA_SIZE, 0);
        int chunkCount = getIntent().getIntExtra(EXTRA_CHUNK_COUNT, 0);

        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle(name);
        }

        if (objectId == null || wrappedDek == null) {
            fail();
            return;
        }

        if (FileUriHelper.isImage(mime)) {
            // Images decode straight through Glide's vault ModelLoader (decrypt-on-
            // read → downsampled bitmap) — no manual whole-file load, no reader to
            // manage here.
            showImage(objectId, wrappedDek, size, chunkCount, mime);
            return;
        }
        // Video/audio: build the decrypt-on-read reader off the main thread (it
        // loads the recovery code + derives the identity), then stream it into
        // ExoPlayer through VaultDataSource.
        final VaultEntry entry = new VaultEntry(objectId, wrappedDek, name, size,
                mime, 0, chunkCount, null);
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
