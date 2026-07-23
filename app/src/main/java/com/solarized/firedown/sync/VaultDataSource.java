package com.solarized.firedown.sync;

import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.BaseDataSource;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;

import java.io.IOException;

/**
 * A media3 {@link DataSource} that reads a backed-up vault object through a
 * {@link VaultObjectReader} — decrypting each chunk on demand — so a cloud file
 * can be STREAMED into ExoPlayer without a full restore to disk. It's the media3
 * adapter over the reader: {@code open}/{@code read} translate the player's
 * position + range requests into {@link VaultObjectReader#readAt} calls.
 *
 * <p>The {@link Factory} holds ONE shared reader (the object's DEK is unwrapped
 * once) and hands it to every {@code DataSource} media3 creates for the same
 * item — the reader is chunk-cached and thread-safe per read, and its lifecycle
 * (DEK wipe) is owned by whoever built the factory (the streaming activity),
 * NOT by {@code close()} here, which only ends the transfer.</p>
 */
@UnstableApi
public final class VaultDataSource extends BaseDataSource {

    private final VaultObjectReader reader;
    private final Uri uri;

    private long position;
    private long bytesRemaining;
    private boolean opened;

    private VaultDataSource(VaultObjectReader reader, Uri uri) {
        super(/* isNetwork= */ true);
        this.reader = reader;
        this.uri = uri;
    }

    @Override
    public long open(@NonNull DataSpec dataSpec) throws IOException {
        transferInitializing(dataSpec);
        position = dataSpec.position;
        if (dataSpec.length != C.LENGTH_UNSET) {
            bytesRemaining = dataSpec.length;
        } else {
            bytesRemaining = reader.length() - position;
        }
        if (bytesRemaining < 0) {
            throw new IOException("position " + position + " past end " + reader.length());
        }
        opened = true;
        transferStarted(dataSpec);
        return bytesRemaining;
    }

    @Override
    public int read(@NonNull byte[] buffer, int offset, int length) throws IOException {
        if (length == 0) {
            return 0;
        }
        if (bytesRemaining == 0) {
            return C.RESULT_END_OF_INPUT;
        }
        int toRead = (int) Math.min(length, bytesRemaining);
        int n = reader.readAt(position, buffer, offset, toRead);
        if (n < 0) {
            return C.RESULT_END_OF_INPUT;
        }
        position += n;
        bytesRemaining -= n;
        bytesTransferred(n);
        return n;
    }

    @Nullable
    @Override
    public Uri getUri() {
        return uri;
    }

    @Override
    public void close() {
        // Only ends the media3 transfer bookkeeping — the shared reader (and its
        // DEK) is released by the owner (the streaming activity), because a single
        // playback session opens/closes this DataSource many times.
        if (opened) {
            opened = false;
            transferEnded();
        }
    }

    /** Builds {@link VaultDataSource}s over one shared reader + uri. */
    @UnstableApi
    public static final class Factory implements DataSource.Factory {
        private final VaultObjectReader reader;
        private final Uri uri;

        public Factory(VaultObjectReader reader, Uri uri) {
            this.reader = reader;
            this.uri = uri;
        }

        @NonNull
        @Override
        public DataSource createDataSource() {
            return new VaultDataSource(reader, uri);
        }
    }
}
