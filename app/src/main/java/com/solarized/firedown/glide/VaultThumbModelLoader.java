package com.solarized.firedown.glide;

import android.util.Base64;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.bumptech.glide.Priority;
import com.bumptech.glide.load.DataSource;
import com.bumptech.glide.load.Options;
import com.bumptech.glide.load.data.DataFetcher;
import com.bumptech.glide.load.model.ModelLoader;
import com.bumptech.glide.load.model.ModelLoaderFactory;
import com.bumptech.glide.load.model.MultiModelLoaderFactory;
import com.bumptech.glide.signature.ObjectKey;

import java.io.ByteArrayInputStream;
import java.io.InputStream;

/**
 * Glide {@link ModelLoader} for a {@link VaultThumbModel} — the stored base64
 * JPEG preview carried inside the encrypted Cloud Backup manifest. Glide's
 * built-in stream decoders take it from there.
 *
 * <p>Replaces a hand-rolled {@code LruCache} in {@code CloudBackupFileAdapter}
 * that existed only to amortise a base64+JPEG decode the bind was doing on the
 * MAIN thread. With this registered the decode happens on Glide's threads, the
 * result lives in Glide's device-sized memory cache and its bitmap pool, and the
 * adapter keeps no bitmaps of its own.
 *
 * <p><b>Disk cache: never.</b> Callers must load this model with
 * {@code DiskCacheStrategy.NONE}. The bytes are a plaintext preview of an
 * end-to-end-encrypted backup, and unlike the local-file previews (which are
 * thumbnails of files sitting unencrypted in Downloads anyway) a CLOUD-ONLY
 * entry's preview has no plaintext counterpart on the device — writing it to
 * Glide's disk cache would create one. The fetch itself is a memory operation,
 * so a disk cache would buy nothing even if it were harmless.
 */
public final class VaultThumbModelLoader implements ModelLoader<VaultThumbModel, InputStream> {

    @Nullable
    @Override
    public LoadData<InputStream> buildLoadData(@NonNull VaultThumbModel model, int width,
                                               int height, @NonNull Options options) {
        // Keyed by objectId alone — server-random and immutable, so it can
        // neither collide nor go stale. Deliberately NOT the base64 payload:
        // that is a large string to hash on every bind, and it is derived from
        // the id anyway.
        return new LoadData<>(new ObjectKey(model.objectId), new Fetcher(model.thumbBase64));
    }

    @Override
    public boolean handles(@NonNull VaultThumbModel model) {
        return true;
    }

    /** Decodes the base64 to a stream. No IO, no network — just the string. */
    private static final class Fetcher implements DataFetcher<InputStream> {

        private final String base64;
        @Nullable
        private InputStream stream;

        Fetcher(String base64) {
            this.base64 = base64;
        }

        @Override
        public void loadData(@NonNull Priority priority,
                             @NonNull DataCallback<? super InputStream> callback) {
            try {
                // NO_WRAP must match VaultThumbnail's encode side (it rides in
                // JSON, so it carries no newlines).
                byte[] bytes = Base64.decode(base64, Base64.NO_WRAP);
                stream = new ByteArrayInputStream(bytes);
                callback.onDataReady(stream);
            } catch (IllegalArgumentException e) {
                // A corrupt/truncated payload — fail the load so the caller's
                // error drawable (the mime glyph) shows, rather than throwing
                // out of Glide's thread.
                callback.onLoadFailed(e);
            }
        }

        @Override
        public void cleanup() {
            if (stream != null) {
                try {
                    stream.close();
                } catch (Exception ignored) {
                    // ByteArrayInputStream.close is a no-op; nothing to salvage.
                }
                stream = null;
            }
        }

        @Override
        public void cancel() {
            // Nothing to cancel — the decode is synchronous and allocation-only.
        }

        @NonNull
        @Override
        public Class<InputStream> getDataClass() {
            return InputStream.class;
        }

        @NonNull
        @Override
        public DataSource getDataSource() {
            // LOCAL: the bytes came from the already-pulled manifest, not the
            // network. This is what lets Glide treat it as a cheap re-fetch.
            return DataSource.LOCAL;
        }
    }

    public static final class Factory implements ModelLoaderFactory<VaultThumbModel, InputStream> {

        @NonNull
        @Override
        public ModelLoader<VaultThumbModel, InputStream> build(
                @NonNull MultiModelLoaderFactory multiFactory) {
            return new VaultThumbModelLoader();
        }

        @Override
        public void teardown() {
            // No resources held.
        }
    }
}
