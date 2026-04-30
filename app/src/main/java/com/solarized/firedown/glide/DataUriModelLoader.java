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

public class DataUriModelLoader implements ModelLoader<String, InputStream> {

    private static final String DATA_PREFIX = "data:";
    private static final String BASE64_MARKER = ";base64,";

    @Nullable
    @Override
    public LoadData<InputStream> buildLoadData(
            @NonNull String model, int width, int height, @NonNull Options options) {
        if (!handles(model)) return null;
        return new LoadData<>(new ObjectKey(model), new DataUriFetcher(model));
    }

    @Override
    public boolean handles(@NonNull String model) {
        return model.startsWith(DATA_PREFIX) && model.contains(BASE64_MARKER);
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
                int markerIndex = dataUri.indexOf(BASE64_MARKER);
                String base64Data = dataUri.substring(markerIndex + BASE64_MARKER.length());
                byte[] decoded = Base64.decode(base64Data, Base64.DEFAULT);
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
