package com.solarized.firedown.glide;

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
import com.solarized.firedown.data.FaviconFetch;
import com.solarized.firedown.data.FaviconStore;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;

import okhttp3.Call;
import okhttp3.OkHttpClient;
import okhttp3.Response;

/**
 * Glide {@link ModelLoader} for a {@link FaviconModel}: the stored bytes when the
 * favicon store has them (no network, ever, to display a stored icon), else one
 * fetch, stored unless the model is ephemeral (a private-tab surface). Glide's
 * stream decoders take it from there, the SVG decoder included (the caller sets
 * the FILEPATH option to the icon URL, as before).
 *
 * <p>Callers load this with {@code DiskCacheStrategy.NONE}: the store IS the disk
 * cache, and a Glide copy would outlive a history clear that empties the store.
 * Refreshing a stale icon is not this loader's job — that happens on a page
 * VISIT ({@code IconsRepository}), like the big browsers; a display only ever
 * fills a gap.
 */
public final class FaviconModelLoader implements ModelLoader<FaviconModel, InputStream> {

    private final FaviconStore mStore;
    private final OkHttpClient mClient;

    FaviconModelLoader(FaviconStore store, OkHttpClient client) {
        mStore = store;
        mClient = client;
    }

    @Nullable
    @Override
    public LoadData<InputStream> buildLoadData(@NonNull FaviconModel model, int width,
                                               int height, @NonNull Options options) {
        return new LoadData<>(new ObjectKey(model.cacheKey()), new Fetcher(model, mStore, mClient));
    }

    @Override
    public boolean handles(@NonNull FaviconModel model) {
        return true;
    }

    private static final class Fetcher implements DataFetcher<InputStream> {

        private final FaviconModel model;
        private final FaviconStore store;
        private final OkHttpClient client;
        @Nullable
        private volatile Call call;
        @Nullable
        private InputStream stream;
        private DataSource source = DataSource.LOCAL;

        Fetcher(FaviconModel model, FaviconStore store, OkHttpClient client) {
            this.model = model;
            this.store = store;
            this.client = client;
        }

        @Override
        public void loadData(@NonNull Priority priority,
                             @NonNull DataCallback<? super InputStream> callback) {
            File file = store.fileFor(model.iconUrl);
            if (file.isFile()) {
                try {
                    stream = new FileInputStream(file);
                    source = DataSource.LOCAL;
                    callback.onDataReady(stream);
                    return;
                } catch (IOException e) {
                    // Pruned or cleared between the check and the open: fetch.
                }
            }
            if (!FaviconFetch.isFetchable(model.iconUrl)) {
                callback.onLoadFailed(new IOException("favicon not fetchable: " + model.iconUrl));
                return;
            }
            try {
                Call current = client.newCall(FaviconFetch.request(model.iconUrl, model.pageUrl));
                call = current;
                byte[] bytes;
                try (Response response = current.execute()) {
                    bytes = FaviconFetch.readBounded(response);
                }
                if (bytes == null || !FaviconStore.looksLikeImage(bytes)) {
                    callback.onLoadFailed(new IOException("favicon fetch gave no image: " + model.iconUrl));
                    return;
                }
                if (model.persist) {
                    store.store(model.iconUrl, bytes, System.currentTimeMillis());
                }
                stream = new ByteArrayInputStream(bytes);
                source = DataSource.REMOTE;
                callback.onDataReady(stream);
            } catch (IOException | RuntimeException e) {
                callback.onLoadFailed(e);
            }
        }

        @Override
        public void cleanup() {
            InputStream current = stream;
            stream = null;
            if (current != null) {
                try {
                    current.close();
                } catch (IOException ignored) {
                    // Nothing to salvage on close.
                }
            }
        }

        @Override
        public void cancel() {
            Call current = call;
            if (current != null) {
                current.cancel();
            }
        }

        @NonNull
        @Override
        public Class<InputStream> getDataClass() {
            return InputStream.class;
        }

        @NonNull
        @Override
        public DataSource getDataSource() {
            return source;
        }
    }

    public static final class Factory implements ModelLoaderFactory<FaviconModel, InputStream> {

        private final FaviconStore mStore;
        private final OkHttpClient mClient;

        public Factory(FaviconStore store, OkHttpClient client) {
            mStore = store;
            mClient = client;
        }

        @NonNull
        @Override
        public ModelLoader<FaviconModel, InputStream> build(@NonNull MultiModelLoaderFactory multiFactory) {
            return new FaviconModelLoader(mStore, mClient);
        }

        @Override
        public void teardown() {
            // Nothing held beyond the shared store and client.
        }
    }
}
