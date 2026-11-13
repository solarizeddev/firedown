package com.solarized.firedown.manager;

import android.text.TextUtils;
import android.util.Log;

import com.solarized.firedown.data.Download;
import com.solarized.firedown.data.di.NetworkModule;
import com.solarized.firedown.ffmpegutils.FFmpegDownloader;
import com.solarized.firedown.ffmpegutils.FFmpegErrors;
import com.solarized.firedown.ffmpegutils.FFmpegListener;
import com.solarized.firedown.ffmpegutils.FFmpegUtils;
import com.solarized.firedown.utils.BrowserHeaders;
import com.solarized.firedown.utils.FileUriHelper;
import com.solarized.firedown.utils.MessageHelper;
import com.solarized.firedown.utils.WebUtils;

import org.apache.commons.io.FilenameUtils;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import com.solarized.firedown.okhttp.SafeHeaders;
import okhttp3.Call;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.BufferedSource;

/**
 * Single-input FFmpeg download for HLS, DASH, and TS manifests.
 * OkHttp probes the URL first for mime/content info, then FFmpeg handles segment downloading.
 * Falls back to raw byte copy if FFmpeg reports UNSUPPORTED.
 */
public class FFmpegMuxStrategy implements DownloadStrategy, FFmpegListener {

    private static final String TAG = FFmpegMuxStrategy.class.getSimpleName();
    private static final int BYTE_SIZE = 8192;
    private static final long UPDATE_RATE = 1500;
    /** A playlist larger than this is not read for liveness (a long EVENT
     *  playlist is a few hundred KB). */
    private static final long PLAYLIST_MAX_BYTES = 8L * 1024 * 1024;
    /** How much of a DASH manifest is read: its MPD element opens it. */
    private static final long DASH_HEAD_BYTES = 64 * 1024;
    /** Bound on the extra fetch of a master's first rendition. */
    private static final long VARIANT_FETCH_TIMEOUT_S = 10;

    private FFmpegDownloader downloader;
    private DownloadCallback callback;
    private long lastUpdated;
    private volatile boolean stopped;

    @Override
    public void execute(DownloadRequest request, DownloadContext context, DownloadCallback callback) throws IOException {
        this.callback = callback;

        Response httpResponse = null;
        ResponseBody body = null;
        BufferedInputStream input = null;
        BufferedOutputStream output = null;

        try {
            String contentAddress = WebUtils.deParameterize(request.getUrl());

            if (TextUtils.isEmpty(contentAddress)) {
                callback.onError(MessageHelper.IOEXCEPTION);
                return;
            }

            OkHttpClient client = context.getOkHttpClient();

            // Probe
            Request httpRequest = new Request.Builder()
                    .url(contentAddress)
                    .headers(SafeHeaders.of(context.getHeaders()))
                    .build();

            httpResponse = client.newCall(httpRequest).execute();

            int status = httpResponse.code();
            if (status >= HttpURLConnection.HTTP_BAD_REQUEST
                    && status <= HttpURLConnection.HTTP_VERSION) {
                Log.d(TAG, "BadRequest: " + status);
                callback.onError(status);
                return;
            }

            body = httpResponse.body();
            long totalLength = body.contentLength();

            // ICY detection
            if (isIcyStream(httpResponse)) {
                totalLength = -1;
                callback.onLiveStream(true);
            } else if (isLivePlaylist(client, httpResponse, body)) {
                // Said from the playlist text before FFmpeg opens it: the
                // native downloader can only infer it, and not at all for a
                // live EVENT stream downloaded through its master at a quality
                // other than the first (see PlaylistLiveness).
                callback.onLiveStream(true);
            }

            // Content-Disposition
            String location = httpResponse.header(BrowserHeaders.LOCATION);
            if (!TextUtils.isEmpty(location)) {
                callback.onNameResolved(WebUtils.getFileNameFromURL(location));
            }

            if (!request.isFileNameForced()) {
                String contentDisposition = httpResponse.header(BrowserHeaders.CONTENT_DISPOSITION);
                if (contentDisposition != null) {
                    String dispositionName = WebUtils.getFileNameFromDisposition(contentDisposition);
                    if (dispositionName != null) {
                        callback.onNameResolved(FileUriHelper.sanitizeFileName(dispositionName));
                    }
                }
            }

            // Mime
            String mimeType = request.getMimeType();
            if (TextUtils.isEmpty(mimeType)) mimeType = WebUtils.getMimeType(body);
            callback.onMimeResolved(mimeType);

            if (TextUtils.isEmpty(request.getDescription())) {
                callback.onDescriptionResolved(WebUtils.getTitle(request.getOrigin()));
            }

            // Resolve FFmpeg mime and file extension
            String ffmpegMime = resolveFFmpegMime(mimeType);
            callback.onMimeResolved(ffmpegMime);

            File file = context.getOutputFile();
            String ext = FileUriHelper.getFileExtensionFromMimeType(ffmpegMime);
            if (TextUtils.isEmpty(ext)) ext = "mp4";
            file = new File(file.getParent(), FilenameUtils.getBaseName(file.getName()) + "." + ext);
            String resolvedPath = callback.onFilePathResolved(file.getAbsolutePath());
            file = new File(resolvedPath);

            // A Stop or Delete that landed during the probe or the liveness
            // read reached no downloader (stop() only flags until one exists),
            // and an interrupted read answers false there; FFmpeg must not
            // start after it.
            if (stopped || context.isInterrupted()) {
                return;
            }

            // The probe is not read again: release it (and the playlist a
            // liveness read buffered in it) before a download that can run
            // for hours.
            body.close();
            body = null;
            httpResponse.close();
            httpResponse = null;

            // FFmpeg download
            downloader = new FFmpegDownloader();
            downloader.addListener(this);

            // FFmpegDownloader.start() runs the headers through
            // buildFFmpegOptions itself — passing a pre-wrapped dict
            // here meant buildFFmpegOptions ran twice. The second
            // pass's headersToFFmpegString stripped all the \r\n
            // separators out of the already-joined 'headers' value
            // (see FFmpegUtils.headersToFFmpegString line 120), so
            // FFmpegOkhttp ended up reading back a single garbage
            // header named 'headers' whose value was every original
            // request header concatenated without delimiters. The
            // upstream HTTP/2 server then RSTed the stream with
            // PROTOCOL_ERROR, killing the m3u8 download with
            // 'End of file' / 'StreamResetException'. Hand start()
            // the raw header map; it will wrap exactly once.
            StreamSelection selection = request.toStreamSelection();
            int videoStream = selection.getVideoNumber() >= 0 ? selection.getVideoNumber() : -1;
            int audioStream = selection.getAudioNumber() >= 0 ? selection.getAudioNumber() : -1;

            int error = downloader.start(context.getHeaders(), null, contentAddress,
                    file.getAbsolutePath(), videoStream, audioStream, totalLength);

            // Free after start() returns (C threads have exited)
            downloader.free();

            if (error < 0) {
                Log.e(TAG, "FFmpegDownloader error: " + error);

                if (error == FFmpegErrors.UNSUPPORTED || error == FFmpegErrors.MUX_HEADER_ERROR) {
                    // Fresh request for raw byte copy fallback
                    Request fallbackRequest = new Request.Builder()
                            .url(contentAddress)
                            .headers(SafeHeaders.of(context.getHeaders()))
                            .build();
                    httpResponse = client.newCall(fallbackRequest).execute();
                    body = httpResponse.body();
                    totalLength = body.contentLength();

                    input = new BufferedInputStream(body.byteStream());
                    output = new BufferedOutputStream(new FileOutputStream(file, false));
                    copyStream(input, output, totalLength);
                } else if (error == FFmpegErrors.ENOENT) {
                    callback.onError(MessageHelper.EXTERNAL_STORAGE);
                    return;
                } else {
                    callback.onError(MessageHelper.IOEXCEPTION);
                    return;
                }
            }

            if (context.isInterrupted()) {
                // User stopped the stream — file may still be valid (trailer written).
                if (file.exists() && file.length() > 0) {
                    callback.onImgResolved(file.getAbsolutePath());
                    callback.onFileSizeKnown(file.length());
                }
                return;
            }

            // Close output before status change so file is fully flushed for Glide
            if (output != null) {
                output.flush();
                output.close();
                output = null;
            }

            callback.onImgResolved(file.getAbsolutePath());
            callback.onFileSizeKnown(file.length());
            callback.onStatusChanged(Download.FINISHED);

        } finally {
            if (body != null) body.close();
            if (httpResponse != null) httpResponse.close();
            try { if (input != null) input.close(); } catch (IOException ignored) {}
            // Separate try blocks: if flush() throws (disk full mid-write —
            // exactly when this finally matters), the previous single-try
            // variant skipped close() and leaked the underlying FileOutputStream's
            // file descriptor until GC finalised it.
            try { if (output != null) output.flush(); } catch (IOException ignored) {}
            try { if (output != null) output.close(); } catch (IOException ignored) {}
            // User cancel: ffmpeg has unwound (downloader.start() returned) and
            // closed its FFmpegOkhttp connections, but on HTTP/2 those closes
            // only RST the *stream* — the pooled *connection* stays open, and a
            // live-stream CDN that keeps pushing segments for the dead stream
            // traps OkHttp in a DATA→RST_STREAM discard loop until the pool's
            // 5-minute idle eviction (see NetworkModule.evictIdleConnections()).
            // The cancelled connections are idle by now, so evict them. Gated:
            // normal completion never touches the pool. This gate is needed
            // here (not only in FFmpegOkhttp) because cancelAll/stop() signals
            // through the NATIVE interrupt flag — the worker thread's Java
            // interrupt status is never set on that path.
            if (stopped || context.isInterrupted()) {
                NetworkModule.evictIdleConnections();
            }
        }
    }

    @Override
    public void stop() {
        stopped = true;
        if (downloader != null) {
            downloader.stop();
        }
    }

    private void copyStream(BufferedInputStream in, BufferedOutputStream out, long totalLength) throws IOException {
        byte[] data = new byte[BYTE_SIZE];
        long downloaded = 0;
        int count;
        while ((count = in.read(data)) != -1) {
            if (stopped) return;
            downloaded += count;
            out.write(data, 0, count);
            reportProgress(downloaded, totalLength);
        }
        out.flush();
    }

    private void reportProgress(long downloaded, long total) {
        if (callback == null) return;
        long now = System.currentTimeMillis();
        if (now - lastUpdated > UPDATE_RATE) {
            lastUpdated = now;
            // AV_NOPTS_VALUE (Long.MIN_VALUE) signals unknown duration (live stream)
            if (downloaded == Long.MIN_VALUE || total == Long.MIN_VALUE) {
                callback.onProgress(0, 0, -1);
            } else if (total > 0) {
                int percent = (int) ((downloaded * 100) / total);
                callback.onProgress(percent, downloaded, total);
            } else {
                callback.onProgress(0, downloaded, total);
            }
        }
    }

    private String resolveFFmpegMime(String mimeType) {
        if (FileUriHelper.isFFmpeg(mimeType)) return FileUriHelper.MIMETYPE_MP4;
        if (FileUriHelper.isAudio(mimeType)) return mimeType;
        if (FileUriHelper.isImage(mimeType)) return mimeType;
        if (FileUriHelper.isUnkown(mimeType)) return FileUriHelper.MIMETYPE_MP4;
        return mimeType;
    }

    private static boolean isIcyStream(Response response) {
        for (int i = 0; i < response.headers().size(); i++) {
            // Locale.ROOT — Turkish locale otherwise lowercases "I" to "ı"
            // and this startsWith comparison would silently miss "icy-*" headers.
            if (response.headers().name(i).toLowerCase(Locale.ROOT).startsWith("icy-")) {
                return true;
            }
        }
        return false;
    }

    // FFmpegListener
    @Override
    public void onProgress(long downloadedLength, long totalLength) {
        reportProgress(downloadedLength, totalLength);
    }

    @Override
    public void onStarted() {}

    @Override
    public void onFinished() {}

    /**
     * Whether the probed response is a live HLS or DASH playlist. Reads the
     * body through a peek (nothing is consumed), only when its first bytes are
     * a playlist; an HLS playlist is scanned line by line up to
     * {@link #PLAYLIST_MAX_BYTES}. For a master, the first rendition is
     * fetched once, resolved against the URL that answered (a redirect), with
     * the same headers and a bounded call: every rendition of a stream is live
     * or finished together. Any failure answers false and leaves the decision
     * to the native downloader; an interrupt (a Stop) is checked by the caller.
     */
    private static boolean isLivePlaylist(OkHttpClient client, Response probe, ResponseBody body) {
        try {
            String[] variant = new String[1];
            int state = playlistState(body, variant);
            if (state != PlaylistLiveness.MASTER) {
                return state == PlaylistLiveness.LIVE;
            }
            HttpUrl url = variant[0] != null ? probe.request().url().resolve(variant[0]) : null;
            if (url == null) {
                return false;
            }
            Request request = probe.request().newBuilder().url(url).build();
            Call call = client.newCall(request);
            call.timeout().timeout(VARIANT_FETCH_TIMEOUT_S, TimeUnit.SECONDS);
            try (Response response = call.execute()) {
                if (!response.isSuccessful()) {
                    return false;
                }
                return playlistState(response.body(), null) == PlaylistLiveness.LIVE;
            }
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    /**
     * {@link PlaylistLiveness} for a body, read through a peek: the first 16
     * bytes decide whether it is a playlist at all, a DASH manifest is read up
     * to its first 64 KB (the MPD element opens it), and an HLS playlist is
     * fed to {@link PlaylistLiveness.Scan} one line at a time. A master's first
     * rendition URI goes to {@code variantOut[0]} when asked for.
     */
    private static int playlistState(ResponseBody body, String[] variantOut) throws IOException {
        if (body == null) {
            return PlaylistLiveness.UNKNOWN;
        }
        BufferedSource head = body.source().peek();
        head.request(16);
        String start = head.readUtf8(Math.min(16, head.getBuffer().size()));
        if (!PlaylistLiveness.looksLikePlaylist(start)) {
            return PlaylistLiveness.UNKNOWN;
        }
        if (PlaylistLiveness.looksLikeDash(start)) {
            BufferedSource dash = body.source().peek();
            dash.request(DASH_HEAD_BYTES);
            return PlaylistLiveness.of(dash.readUtf8(Math.min(DASH_HEAD_BYTES, dash.getBuffer().size())));
        }
        BufferedSource lines = body.source().peek();
        PlaylistLiveness.Scan scan = new PlaylistLiveness.Scan();
        boolean pendingVariant = false;
        long read = 0;
        String line;
        while ((line = lines.readUtf8Line()) != null) {
            read += line.length() + 1;
            if (read > PLAYLIST_MAX_BYTES) {
                return PlaylistLiveness.UNKNOWN;
            }
            boolean master = scan.feed(line);
            if (master) {
                if (variantOut == null) {
                    return PlaylistLiveness.MASTER;
                }
                pendingVariant = true;
                continue;
            }
            if (pendingVariant) {
                String uri = line.trim();
                if (!uri.isEmpty() && !uri.startsWith("#")) {
                    variantOut[0] = uri;
                    return PlaylistLiveness.MASTER;
                }
            }
        }
        return scan.result();
    }
}