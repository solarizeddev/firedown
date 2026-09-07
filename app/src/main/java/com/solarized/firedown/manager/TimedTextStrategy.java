package com.solarized.firedown.manager;

import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.Nullable;

import com.solarized.firedown.BuildConfig;
import com.solarized.firedown.data.Download;
import com.solarized.firedown.geckoview.PoTokenGenerator;
import com.solarized.firedown.utils.FileUriHelper;
import com.solarized.firedown.utils.JsonToSrtConverter;
import com.solarized.firedown.utils.MessageHelper;
import com.solarized.firedown.utils.WebUtils;

import org.apache.commons.io.FilenameUtils;

import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;

import com.solarized.firedown.okhttp.SafeHeaders;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Download and convert YouTube timed text (JSON) to SRT subtitle format.
 */
public class TimedTextStrategy implements DownloadStrategy {

    private static final String TAG = TimedTextStrategy.class.getSimpleName();
    private static final int BYTE_SIZE = 8192;
    private static final long UPDATE_RATE = 1500;

    private volatile boolean stopped;
    private long lastUpdated;

    @Override
    public void execute(DownloadRequest request, DownloadContext context, DownloadCallback callback) throws IOException {

        Response httpResponse = null;
        ResponseBody body = null;
        InputStream input = null;
        OutputStream output = null;

        try {
            String downloadUrl = request.getUrl();

            if (TextUtils.isEmpty(downloadUrl)) {
                callback.onError(MessageHelper.IOEXCEPTION);
                return;
            }

            // YouTube timedtext is PoToken-gated the same way SABR streams
            // are: without &pot= the endpoint replies HTTP 200 with an
            // empty text/html body (silent reject). Mint a token via the
            // same PoTokenGenerator that SabrStrategy uses, then append
            // it to the URL. JS-side already plumbs sabrVideoId +
            // sabrVisitorData on the request under the existing schema.
            String poToken = mintPoToken(request, context);
            if (!TextUtils.isEmpty(poToken)) {
                downloadUrl = appendPoToken(downloadUrl, poToken);
            }

            File file = context.getOutputFile();

            Request httpRequest = new Request.Builder()
                    .url(downloadUrl)
                    .headers(SafeHeaders.of(context.getHeaders()))
                    .build();

            httpResponse = context.getOkHttpClient().newCall(httpRequest).execute();

            int status = httpResponse.code();
            if (status >= HttpURLConnection.HTTP_BAD_REQUEST
                    && status <= HttpURLConnection.HTTP_VERSION) {
                callback.onError(status);
                return;
            }

            if (TextUtils.isEmpty(request.getDescription())) {
                callback.onDescriptionResolved(WebUtils.getTitle(request.getOrigin()));
            }

            String mimeType = request.getMimeType();
            if (FileUriHelper.isMimeTypeForced(mimeType)) {
                mimeType = FileUriHelper.getMimeTypeFromFile(file.getAbsolutePath());
            }
            callback.onMimeResolved(mimeType);

            // Resolve file extension
            String ext = FileUriHelper.getFileExtensionFromMimeType(mimeType);
            if (TextUtils.isEmpty(ext)) ext = "srt";
            file = new File(file.getParent(), FilenameUtils.getBaseName(file.getName()) + "." + ext);
            String resolvedPath = callback.onFilePathResolved(file.getAbsolutePath());
            file = new File(resolvedPath);

            body = httpResponse.body();
            long totalLength = request.getFileLength();

            // Read the JSON response and convert to SRT
            String inputTimeJson = body.string();
            if (BuildConfig.DEBUG) {
                Log.d(TAG, "fetched timedtext: status=" + status
                        + " bytes=" + (inputTimeJson != null ? inputTimeJson.length() : -1)
                        + " ct=" + httpResponse.header("Content-Type")
                        + " preview=" + (inputTimeJson != null
                            ? inputTimeJson.substring(0, Math.min(200, inputTimeJson.length()))
                            : "null"));
            }
            String srtContent = JsonToSrtConverter.convert(inputTimeJson);
            input = new ByteArrayInputStream(srtContent.getBytes());

            output = new BufferedOutputStream(new FileOutputStream(file, false));

            byte[] data = new byte[BYTE_SIZE];
            long downloaded = 0;
            int count;
            while ((count = input.read(data)) != -1) {
                if (stopped || context.isInterrupted()) return;
                downloaded += count;
                output.write(data, 0, count);
                reportProgress(callback, downloaded, totalLength);
            }
            output.flush();
            output.close();
            output = null;

            callback.onFileSizeKnown(file.length());
            callback.onStatusChanged(Download.FINISHED);

        } finally {
            try { if (input != null) input.close(); } catch (IOException ignored) {}
            try { if (output != null) { output.flush(); output.close(); } } catch (IOException ignored) {}
            if (body != null) body.close();
            if (httpResponse != null) httpResponse.close();
        }
    }

    @Override
    public void stop() {
        stopped = true;
    }

    /**
     * Obtain a PoToken via the same {@link PoTokenGenerator} SabrStrategy
     * uses — but the VIDEO-bound one ({@code generate}, yt-dlp's SUBS
     * context binds to the video id), not the visitor-bound stream token
     * the SABR download carries; the two are different tokens the server
     * checks against different requests. The generator caches it by
     * videoId for the life of its BotGuard session, so a second subtitle
     * track of the same video reuses it. videoId + visitorData are plumbed on the
     * request via the existing SABR schema (background.js sets a minimal
     * {videoId, visitorData} sabr block on timedtext messages). Returns
     * null on any failure — caller falls back to fetching without a token,
     * which YouTube will refuse with an empty body, but no point throwing.
     */
    @Nullable
    private String mintPoToken(DownloadRequest request, DownloadContext context) {
        PoTokenGenerator gen = context.getPoTokenGenerator();
        String videoId = request.getSabrVideoId();
        String visitorData = request.getSabrVisitorData();

        if (gen == null || TextUtils.isEmpty(videoId) || TextUtils.isEmpty(visitorData)) {
            Log.d(TAG, "mintPoToken: skipping (gen=" + (gen != null)
                    + " videoId=" + !TextUtils.isEmpty(videoId)
                    + " visitorData=" + !TextUtils.isEmpty(visitorData) + ")");
            return null;
        }

        long t0 = System.currentTimeMillis();
        try {
            String token = gen.generate(videoId, visitorData);
            long dt = System.currentTimeMillis() - t0;
            if (!TextUtils.isEmpty(token)) {
                Log.d(TAG, "minted PoToken: " + token.length() + " chars (" + dt + "ms)");
                return token;
            }
            Log.w(TAG, "PoToken mint returned empty after " + dt + "ms");
        } catch (Exception e) {
            Log.w(TAG, "PoToken mint failed: " + e.getMessage());
        }
        return null;
    }

    private static String appendPoToken(String url, String token) {
        String sep = url.contains("?") ? "&" : "?";
        // potc=1 signals "this URL carries a PoToken"; without it the
        // server can route to a no-pot path that ignores the token.
        return url + sep + "potc=1&pot=" + token;
    }

    private void reportProgress(DownloadCallback callback, long downloaded, long total) {
        long now = System.currentTimeMillis();
        if (now - lastUpdated > UPDATE_RATE) {
            lastUpdated = now;
            int percent = total > 0 ? (int) ((downloaded * 100) / total) : 0;
            callback.onProgress(percent, downloaded, total);
        }
    }
}