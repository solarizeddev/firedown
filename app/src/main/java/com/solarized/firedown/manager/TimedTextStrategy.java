package com.solarized.firedown.manager;

import android.text.TextUtils;

import com.solarized.firedown.data.Download;
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

import okhttp3.Headers;
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

            File file = context.getOutputFile();

            Request httpRequest = new Request.Builder()
                    .url(downloadUrl)
                    .headers(Headers.of(context.getHeaders()))
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

    private void reportProgress(DownloadCallback callback, long downloaded, long total) {
        long now = System.currentTimeMillis();
        if (now - lastUpdated > UPDATE_RATE) {
            lastUpdated = now;
            int percent = total > 0 ? (int) ((downloaded * 100) / total) : 0;
            callback.onProgress(percent, downloaded, total);
        }
    }
}