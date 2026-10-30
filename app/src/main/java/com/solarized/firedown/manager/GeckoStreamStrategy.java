package com.solarized.firedown.manager;

import android.util.Log;

import com.solarized.firedown.data.Download;
import com.solarized.firedown.data.di.RepositoryEntryPoint;
import com.solarized.firedown.data.repository.GeckoStateDataRepository;
import com.solarized.firedown.data.repository.IncognitoStateRepository;
import com.solarized.firedown.geckoview.GeckoState;
import com.solarized.firedown.utils.BrowserHeaders;
import com.solarized.firedown.utils.DataUris;
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
import java.util.HashMap;
import java.util.Map;

import dagger.hilt.android.EntryPointAccessors;
import com.solarized.firedown.okhttp.SafeHeaders;
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

            // A data: URL arrives as its header only (BrowserDownloadEntity);
            // nothing to decode a preview from, the file itself is the picture.
            if (FileUriHelper.isImage(mimeType) && !DataUris.isDataUri(request.getUrl())) {
                callback.onImgResolved(request.getUrl());
            }

            long totalLength = request.getFileLength();

            Log.d(TAG, "execute: url=" + request.getUrl()
                    + " file=" + file.getAbsolutePath()
                    + " mime=" + mimeType
                    + " requestFileLength=" + totalLength
                    + " sessionId=" + request.getSessionId());

            // GeckoState lives in the Hilt graph — fetched via EntryPoint because
            // this strategy is instantiated with `new` (not injected).
            RepositoryEntryPoint repositories = EntryPointAccessors.fromApplication(
                    context.getContext(), RepositoryEntryPoint.class);
            GeckoStateDataRepository geckoStateRepo = repositories.getGeckoStateDataRepository();
            IncognitoStateRepository incognitoRepo = repositories.getIncognitoStateRepository();

            // A private tab's download is in the private list.
            geckoState = request.isSaveToVault()
                    ? incognitoRepo.getGeckoState(request.getSessionId())
                    : geckoStateRepo.getGeckoState(request.getSessionId());
            if (geckoState == null) {
                geckoState = request.isSaveToVault()
                        ? geckoStateRepo.getGeckoState(request.getSessionId())
                        : incognitoRepo.getGeckoState(request.getSessionId());
            }

            // A data: URL's bytes were the page's WebResponse body and the
            // row keeps only the header: without that body (a retry of the
            // row, the tab gone) there is nothing to fetch — the file is
            // gone, not the network.
            boolean dataUrl = DataUris.isDataUri(request.getUrl());

            if (geckoState == null) {
                callback.onError(dataUrl ? MessageHelper.FILE_NOT_FOUND : MessageHelper.IOEXCEPTION);
                return;
            }

            // Taken, not read: the tab may start another download, or close,
            // while this one streams, and neither may close this stream.
            WebResponse webResponse = geckoState.takeWebResponse();

            if (webResponse != null && webResponse.body != null) {
                Log.d(TAG, "execute: source=WebResponse"
                        + " statusCode=" + webResponse.statusCode
                        + " uri=" + webResponse.uri);
                input = new BufferedInputStream(webResponse.body);
            } else if (dataUrl) {
                callback.onError(MessageHelper.FILE_NOT_FOUND);
                return;
            } else {
                Log.d(TAG, "execute: source=OkHttp fallback"
                        + " (webResponse=" + (webResponse == null ? "null" : "non-null")
                        + " body=" + (webResponse == null ? "n/a" : (webResponse.body == null ? "null" : "non-null")) + ")");
                // Fall back to OkHttp — pulled from the context, not a static field.
                // Strip any inherited Range header so we don't download the
                // tiny slice the original player request was asking for; we
                // want the whole file from byte 0.
                Map<String, String> headers = new HashMap<>(context.getHeaders());
                headers.remove(BrowserHeaders.RANGES);
                Request httpRequest = new Request.Builder()
                        .url(request.getUrl())
                        .headers(SafeHeaders.of(headers))
                        .build();

                httpResponse = context.getOkHttpClient().newCall(httpRequest).execute();

                int status = httpResponse.code();
                Log.d(TAG, "execute: OkHttp response status=" + status
                        + " contentLength=" + (httpResponse.body() != null ? httpResponse.body().contentLength() : -1)
                        + " contentRange=" + httpResponse.header("Content-Range")
                        + " transferEncoding=" + httpResponse.header("Transfer-Encoding"));
                if (status >= HttpURLConnection.HTTP_BAD_REQUEST) {
                    Log.w(TAG, "execute: HTTP error status=" + status + ", aborting");
                    callback.onError(status);
                    return;
                }

                body = httpResponse.body();
                totalLength = body.contentLength();
                input = new BufferedInputStream(body.byteStream());
            }

            Log.d(TAG, "execute: starting copy file=" + file.getAbsolutePath()
                    + " totalLength=" + totalLength);

            output = new BufferedOutputStream(new FileOutputStream(file, false));

            byte[] data = new byte[BYTE_SIZE];
            long downloaded = 0;
            long readCalls = 0;
            int count;
            while ((count = input.read(data)) != -1) {
                if (stopped || context.isInterrupted()) {
                    Log.w(TAG, "execute: loop aborted"
                            + " stopped=" + stopped
                            + " interrupted=" + context.isInterrupted()
                            + " downloaded=" + downloaded
                            + " totalLength=" + totalLength
                            + " readCalls=" + readCalls);
                    return;
                }
                downloaded += count;
                readCalls++;
                output.write(data, 0, count);
                reportProgress(callback, downloaded, totalLength);
            }

            Log.d(TAG, "execute: read loop ended (EOF)"
                    + " downloaded=" + downloaded
                    + " totalLength=" + totalLength
                    + " readCalls=" + readCalls
                    + " truncated=" + (totalLength > 0 && downloaded < totalLength));

            output.flush();
            output.close();
            output = null;

            long onDiskLen = file.length();
            Log.d(TAG, "execute: finished file=" + file.getAbsolutePath()
                    + " onDiskLen=" + onDiskLen
                    + " expected=" + totalLength
                    + " match=" + (totalLength <= 0 || onDiskLen == totalLength));

            callback.onFileSizeKnown(onDiskLen);
            callback.onStatusChanged(Download.FINISHED);

        } catch (IOException e) {
            Log.e(TAG, "execute: IOException for url=" + request.getUrl(), e);
            throw e;
        } finally {
            try { if (input != null) input.close(); } catch (IOException ignored) {}
            try { if (output != null) { output.flush(); output.close(); } } catch (IOException ignored) {}
            if (body != null) body.close();
            if (httpResponse != null) httpResponse.close();
        }
    }

    @Override
    public void stop() {
        Log.d(TAG, "stop: invoked by " + Thread.currentThread().getName(),
                new Throwable("stop trace"));
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