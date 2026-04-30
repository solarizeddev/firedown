package com.solarized.firedown.manager;

import com.solarized.firedown.data.Download;
import com.solarized.firedown.data.di.RepositoryEntryPoint;
import com.solarized.firedown.data.repository.GeckoStateDataRepository;
import com.solarized.firedown.geckoview.GeckoState;
import com.solarized.firedown.utils.FileUriHelper;
import com.solarized.firedown.utils.MessageHelper;

import org.mozilla.geckoview.WebResponse;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;

import dagger.hilt.android.EntryPointAccessors;
import okhttp3.Headers;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Download using GeckoView's WebResponse body stream.
 * Falls back to OkHttp (from the DownloadContext) if the WebResponse is null.
 */
public class GeckoStreamStrategy implements DownloadStrategy {

    private static final String TAG = GeckoStreamStrategy.class.getSimpleName();
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
        GeckoState geckoState = null;

        try {
            File file = context.getOutputFile();

            String mimeType = request.getMimeType();
            if (FileUriHelper.isMimeTypeForced(mimeType)) {
                mimeType = FileUriHelper.getMimeTypeFromFile(file.getAbsolutePath());
            }
            callback.onMimeResolved(mimeType);

            String resolvedPath = callback.onFilePathResolved(file.getAbsolutePath());
            file = new File(resolvedPath);

            if (FileUriHelper.isImage(mimeType)) {
                callback.onImgResolved(request.getUrl());
            }

            long totalLength = request.getFileLength();

            // GeckoState lives in the Hilt graph — fetched via EntryPoint because
            // this strategy is instantiated with `new` (not injected).
            GeckoStateDataRepository geckoStateRepo = EntryPointAccessors.fromApplication(
                    context.getContext(), RepositoryEntryPoint.class
            ).getGeckoStateDataRepository();

            geckoState = geckoStateRepo.getGeckoState(request.getSessionId());

            if (geckoState == null) {
                callback.onError(MessageHelper.IOEXCEPTION);
                return;
            }

            WebResponse webResponse = geckoState.getWebResponse();

            if (webResponse != null && webResponse.body != null) {
                input = new BufferedInputStream(webResponse.body);
            } else {
                // Fall back to OkHttp — pulled from the context, not a static field.
                Request httpRequest = new Request.Builder()
                        .url(request.getUrl())
                        .headers(Headers.of(context.getHeaders()))
                        .build();

                httpResponse = context.getOkHttpClient().newCall(httpRequest).execute();

                int status = httpResponse.code();
                if (status >= HttpURLConnection.HTTP_BAD_REQUEST) {
                    callback.onError(status);
                    return;
                }

                body = httpResponse.body();
                totalLength = body.contentLength();
                input = new BufferedInputStream(body.byteStream());
            }

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
            if (geckoState != null) geckoState.setWebResponse(null);
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