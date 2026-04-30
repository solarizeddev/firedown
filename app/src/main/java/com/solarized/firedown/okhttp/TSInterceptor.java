package com.solarized.firedown.okhttp;

import android.util.Log;

import androidx.annotation.NonNull;

import com.solarized.firedown.utils.BrowserHeaders;
import com.solarized.firedown.utils.FileUriHelper;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.SequenceInputStream;

import okhttp3.Headers;
import okhttp3.Interceptor;
import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.BufferedSource;
import okio.Okio;

public class TSInterceptor implements Interceptor {

    private static final String TAG = TSInterceptor.class.getName();

    private static final int PNG_HEADER_SIZE = 8;

    private static final byte[] PNG_HEADER_SIGNATURE = new byte[]{
            (byte) 0x89, (byte) 0x50, (byte) 0x4E, (byte) 0x47,
            (byte) 0x0D, (byte) 0x0A, (byte) 0x1A, (byte) 0x0A
    };

    /** TS packet size is always 188 bytes. */
    private static final int TS_PACKET_SIZE = 188;

    /** TS sync byte at the start of every 188-byte packet. */
    private static final byte TS_SYNC_BYTE = (byte) 0x47;

    /*
     * We need enough room to find 3 consecutive sync bytes.
     * Worst case: first sync right after PNG header (byte 8),
     * then +188, +376 ⇒ minimum buffer = PNG_HEADER_SIZE + 376 + 1 = 385 bytes.
     * 1024 gives comfortable margin.
     */
    private static final int CHECK_BUFFER_SIZE = 1024;

    /** Number of aligned sync bytes required to confirm a TS stream. */
    private static final int REQUIRED_SYNC_COUNT = 3;

    @NonNull
    @Override
    public Response intercept(@NonNull Chain chain) throws IOException {
        Request request = chain.request();
        String url = request.url().toString();

        Response originalResponse = chain.proceed(request);

        // OkHttp's Headers.get(name) is case-insensitive. The previous code
        // called .toLowerCase() on the constant, which did nothing useful and
        // had a latent Turkish-locale bug. Just look it up directly.
        Headers headers = originalResponse.headers();
        String contentType = headers.get(BrowserHeaders.CONTENT_TYPE);

        if (originalResponse.isSuccessful() && FileUriHelper.isPng(contentType)) {
            Log.d(TAG, "Interceptor: Checking response for TS-in-PNG at: " + url);

            ResponseBody body = originalResponse.body();
            if (body == null) {
                return originalResponse;
            }

            try {
                return originalResponse.newBuilder()
                        .body(new StrippingResponseBody(body))
                        .build();
            } catch (IOException e) {
                // Detection failed with no bytes consumed — the pre-read
                // couldn't start. Propagate so the caller can retry.
                originalResponse.close();
                throw e;
            }
        }

        return originalResponse;
    }

    /**
     * Checks whether 0x47 appears at {@code REQUIRED_SYNC_COUNT} consecutive
     * 188-byte-aligned positions starting from a candidate offset.
     *
     * @param buffer the pre-read byte buffer
     * @param length the number of valid bytes in the buffer
     * @return true if the buffer contains a valid TS sync pattern
     */
    private static boolean containsTsSyncPattern(byte[] buffer, int length) {
        // Maximum offset where we can still fit REQUIRED_SYNC_COUNT aligned packets.
        int maxFirstSync = length - (REQUIRED_SYNC_COUNT - 1) * TS_PACKET_SIZE - 1;

        for (int i = PNG_HEADER_SIZE; i <= maxFirstSync; i++) {
            if (buffer[i] != TS_SYNC_BYTE) continue;

            boolean aligned = true;
            for (int n = 1; n < REQUIRED_SYNC_COUNT; n++) {
                if (buffer[i + n * TS_PACKET_SIZE] != TS_SYNC_BYTE) {
                    aligned = false;
                    break;
                }
            }

            if (aligned) return true;
        }

        return false;
    }

    /**
     * Custom ResponseBody that conditionally strips the PNG header when the
     * underlying data is detected to be an obfuscated MPEG-TS stream.
     */
    private static class StrippingResponseBody extends ResponseBody {

        private final ResponseBody originalBody;
        private final BufferedSource bufferedSource;
        private final boolean headerStripped;

        StrippingResponseBody(ResponseBody originalBody) throws IOException {
            this.originalBody = originalBody;

            InputStream originalInputStream = originalBody.byteStream();

            byte[] checkBuffer = new byte[CHECK_BUFFER_SIZE];
            int totalBytesRead = 0;

            try {
                while (totalBytesRead < CHECK_BUFFER_SIZE) {
                    int readCount = originalInputStream.read(
                            checkBuffer, totalBytesRead, CHECK_BUFFER_SIZE - totalBytesRead);
                    if (readCount == -1) break;
                    totalBytesRead += readCount;
                }
            } catch (IOException e) {
                // The previous implementation fell back to originalBody.source()
                // here, but that source is positioned *after* the bytes already
                // consumed into checkBuffer — the caller would silently get
                // truncated data. Instead, re-emit whatever we did manage to
                // read (as passthrough) and propagate the error only if we
                // read nothing. This preserves as much data as we safely can
                // without corruption.
                Log.e(TAG, "Error while pre-reading for TS detection: " + e.getMessage(), e);
                if (totalBytesRead == 0) {
                    // Nothing consumed — safe to let caller retry.
                    throw e;
                }
                // Fall through with what we have; passthrough branch below.
            }

            // Check 1: PNG header signature
            boolean isPngHeader = totalBytesRead >= PNG_HEADER_SIZE;
            if (isPngHeader) {
                for (int i = 0; i < PNG_HEADER_SIZE; i++) {
                    if (checkBuffer[i] != PNG_HEADER_SIGNATURE[i]) {
                        isPngHeader = false;
                        break;
                    }
                }
            }

            // Check 2: Three 188-byte-aligned TS sync bytes after the PNG header
            boolean isTsStream = isPngHeader && containsTsSyncPattern(checkBuffer, totalBytesRead);

            if (isTsStream) {
                Log.d(TAG, "TS-in-PNG confirmed (3 aligned sync bytes). Stripping PNG header.");
                this.headerStripped = true;

                InputStream contentStream = new SequenceInputStream(
                        new ByteArrayInputStream(checkBuffer, PNG_HEADER_SIZE,
                                totalBytesRead - PNG_HEADER_SIZE),
                        originalInputStream
                );
                this.bufferedSource = Okio.buffer(Okio.source(contentStream));
            } else {
                Log.d(TAG, "Not a TS stream. Passthrough (" + totalBytesRead + " bytes buffered).");
                this.headerStripped = false;

                InputStream passthroughStream = new SequenceInputStream(
                        new ByteArrayInputStream(checkBuffer, 0, totalBytesRead),
                        originalInputStream
                );
                this.bufferedSource = Okio.buffer(Okio.source(passthroughStream));
            }
        }

        @Override
        public MediaType contentType() {
            return headerStripped ? MediaType.parse("video/mp2t") : originalBody.contentType();
        }

        @Override
        public long contentLength() {
            long originalLength = originalBody.contentLength();
            if (headerStripped && originalLength != -1) {
                return originalLength - PNG_HEADER_SIZE;
            }
            return originalLength;
        }

        @NonNull
        @Override
        public BufferedSource source() {
            return bufferedSource;
        }

        @Override
        public void close() {
            // Closing the buffered source closes the wrapped
            // SequenceInputStream which in turn closes the original
            // InputStream (and therefore originalBody). Belt-and-braces:
            // also close the original body explicitly.
            try {
                bufferedSource.close();
            } catch (IOException e) {
                Log.d(TAG, "close bufferedSource", e);
            }
            originalBody.close();
        }
    }
}