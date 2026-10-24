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
import com.solarized.firedown.utils.DataUris;

import java.io.ByteArrayInputStream;
import java.io.InputStream;

/**
 * Glide loader for {@code data:} URIs (RFC 2397), for both encodings:
 * <ul>
 *   <li>{@code data:<mediatype>;base64,<b64>} — Base64 payload.</li>
 *   <li>{@code data:<mediatype>,<url-encoded>} — a percent-encoded (or plain)
 *       text payload. This is what emoji favicons use, e.g.
 *       {@code data:image/svg+xml,<svg …><text>%F0%9F%90%80</text></svg>}, and
 *       what Glide's built-in data-uri loader (Base64 only) drops on the
 *       floor.</li>
 * </ul>
 * The SVG payload is then picked up by {@code SvgDecoder} (the favicon request
 * flags an SVG mime so the decoder engages).
 */
public class DataUriModelLoader implements ModelLoader<String, InputStream> {

    private static final String DATA_PREFIX = "data:";

    @Nullable
    @Override
    public LoadData<InputStream> buildLoadData(
            @NonNull String model, int width, int height, @NonNull Options options) {
        if (!handles(model)) return null;
        return new LoadData<>(new ObjectKey(model), new DataUriFetcher(model));
    }

    @Override
    public boolean handles(@NonNull String model) {
        return model.startsWith(DATA_PREFIX);
    }

    public static class Factory implements ModelLoaderFactory<String, InputStream> {
        @NonNull
        @Override
        public ModelLoader<String, InputStream> build(@NonNull MultiModelLoaderFactory multiFactory) {
            return new DataUriModelLoader();
        }

        @Override
        public void teardown() {}
    }

    private static class DataUriFetcher implements DataFetcher<InputStream> {

        private final String dataUri;

        DataUriFetcher(@NonNull String dataUri) {
            this.dataUri = dataUri;
        }

        @Override
        public void loadData(@NonNull Priority priority,
                             @NonNull DataCallback<? super InputStream> callback) {
            try {
                // DataUris reads both encodings the way a browser does
                // (percent-decoded, then base64 when the URL says so).
                byte[] decoded = DataUris.decode(dataUri);
                if (decoded == null) {
                    callback.onLoadFailed(new IllegalArgumentException("Malformed data URI"));
                    return;
                }
                callback.onDataReady(new ByteArrayInputStream(decoded));
            } catch (Exception e) {
                callback.onLoadFailed(e);
            }
        }

        @Override
        public void cleanup() {}

        @Override
        public void cancel() {}

        @NonNull
        @Override
        public Class<InputStream> getDataClass() {
            return InputStream.class;
        }

        @NonNull
        @Override
        public DataSource getDataSource() {
            return DataSource.LOCAL;
        }
    }
}
