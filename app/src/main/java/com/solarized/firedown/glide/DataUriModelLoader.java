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
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

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
    private static final String BASE64_MARKER = ";base64";

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
                int comma = dataUri.indexOf(',');
                if (comma < 0) {
                    callback.onLoadFailed(new IllegalArgumentException("Malformed data URI"));
                    return;
                }
                String header = dataUri.substring(0, comma);
                String payload = dataUri.substring(comma + 1);
                byte[] decoded;
                if (header.contains(BASE64_MARKER)) {
                    decoded = Base64.decode(payload, Base64.DEFAULT);
                } else {
                    decoded = decodePercentEncoded(payload);
                }
                callback.onDataReady(new ByteArrayInputStream(decoded));
            } catch (Exception e) {
                callback.onLoadFailed(e);
            }
        }

        /**
         * Percent-decodes a data-URI text payload to bytes (RFC 2397). Unlike
         * {@code URLDecoder}, a literal {@code +} is left untouched (it is a
         * space only in {@code application/x-www-form-urlencoded}, not in a data
         * URI, and can appear literally in SVG). Runs of non-{@code %} chars are
         * flushed as UTF-8 together so multi-byte characters / surrogate pairs
         * survive, and a {@code %XX} escape contributes its raw byte (so a
         * UTF-8-encoded emoji like {@code %F0%9F%90%80} reassembles correctly).
         */
        private static byte[] decodePercentEncoded(@NonNull String payload) {
            ByteArrayOutputStream out = new ByteArrayOutputStream(payload.length());
            StringBuilder literal = new StringBuilder();
            int i = 0;
            int n = payload.length();
            while (i < n) {
                char c = payload.charAt(i);
                int hi = -1;
                int lo = -1;
                if (c == '%' && i + 2 < n) {
                    hi = Character.digit(payload.charAt(i + 1), 16);
                    lo = Character.digit(payload.charAt(i + 2), 16);
                }
                if (hi >= 0 && lo >= 0) {
                    flushLiteral(out, literal);
                    out.write((hi << 4) + lo);
                    i += 3;
                } else {
                    literal.append(c);
                    i++;
                }
            }
            flushLiteral(out, literal);
            return out.toByteArray();
        }

        /** Encodes any pending literal chars as UTF-8 into {@code out} and clears the buffer. */
        private static void flushLiteral(@NonNull ByteArrayOutputStream out,
                                         @NonNull StringBuilder literal) {
            if (literal.length() == 0) return;
            byte[] bytes = literal.toString().getBytes(StandardCharsets.UTF_8);
            out.write(bytes, 0, bytes.length);
            literal.setLength(0);
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
