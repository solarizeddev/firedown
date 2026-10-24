package com.solarized.firedown.manager;

import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.solarized.firedown.StoragePaths;
import com.solarized.firedown.data.Download;
import com.solarized.firedown.geckoview.PoTokenGenerator;
import com.solarized.firedown.ffmpegutils.FFmpegDownloader;
import com.solarized.firedown.ffmpegutils.FFmpegEntity;
import com.solarized.firedown.ffmpegutils.FFmpegErrors;
import com.solarized.firedown.ffmpegutils.FFmpegListener;
import com.solarized.firedown.ffmpegutils.FFmpegMetaData;
import com.solarized.firedown.ffmpegutils.FFmpegMetaDataReader;
import com.solarized.firedown.ffmpegutils.FFmpegStreamInfo;
import com.solarized.firedown.sabr.Fmp4Muxer;
import com.solarized.firedown.sabr.SabrDownloader;
import com.solarized.firedown.sabr.SabrMessages;
import com.solarized.firedown.utils.FileUriHelper;
import com.solarized.firedown.utils.MessageHelper;
import com.solarized.firedown.utils.WebUtils;

import java.util.ArrayList;
import java.util.Map;
import org.apache.commons.io.FilenameUtils;

import java.io.File;
import java.io.IOException;

/**
 * SABR (Server-Adaptive Bitrate) download strategy.
 *
 * Downloads video and audio segments via YouTube's SABR protocol using
 * MWEB client identity with BotGuard PO token for attestation, interleaving
 * them into ONE fragmented MP4 as they arrive ({@link Fmp4Muxer}). That file
 * is the download: no raw per-track copy is kept, FFmpeg only ever
 * stream-copies the inline file itself when its probe refuses it.
 */
public class SabrStrategy implements DownloadStrategy {

    private static final String TAG = SabrStrategy.class.getSimpleName();
    private static final long UPDATE_RATE = 1500;

    // The download phase fills the whole 0–100% bar; the subsequent FFmpeg
    // mux is shown as an indeterminate "Finishing…" state, not a determinate
    // slice, so it no longer needs a reserved weight.
    private static final int DOWNLOAD_WEIGHT = 100;

    /** Minimum time the "Finishing…" state is held on screen. The mux can be
     *  sub-second (stream copy), too fast for the UI to ever render the state
     *  before FINISHED replaces it; this guarantees it's perceptible. */
    private static final long MIN_FINISHING_VISIBLE_MS = 700;

    private SabrDownloader sabrDownloader;
    private DownloadCallback callback;
    private DownloadContext context;
    private long lastUpdated;
    private volatile boolean stopped;
    private Fmp4Muxer muxer;

    @Override
    public void execute(DownloadRequest request, DownloadContext context, DownloadCallback callback)
            throws IOException {
        this.callback = callback;
        this.context = context;

        // ====================================================================
        // 1. Validate SABR parameters
        // ====================================================================

        String sabrUrl = request.getSabrUrl();
        String sabrConfig = request.getSabrConfig();

        if (TextUtils.isEmpty(sabrUrl) || TextUtils.isEmpty(sabrConfig)) {
            Log.e(TAG, "Missing SABR data, cannot proceed");
            callback.onError(MessageHelper.IOEXCEPTION);
            return;
        }

        // ====================================================================
        // 2. Resolve output file path
        // ====================================================================

        String mimeType = request.getMimeType();
        if (TextUtils.isEmpty(mimeType)) mimeType = FileUriHelper.MIMETYPE_MP4;
        callback.onMimeResolved(mimeType);

        File file = context.getOutputFile();
        String ext = FileUriHelper.getFileExtensionFromMimeType(mimeType);
        if (TextUtils.isEmpty(ext)) ext = "mp4";
        file = new File(file.getParent(),
                FilenameUtils.getBaseName(file.getName()) + "." + ext);
        String resolvedPath = callback.onFilePathResolved(file.getAbsolutePath());
        file = new File(resolvedPath);

        if (TextUtils.isEmpty(request.getDescription())) {
            callback.onDescriptionResolved(WebUtils.getTitle(request.getOrigin()));
        }

        // ====================================================================
        // 3. Configure and run SABR downloader
        // ====================================================================

        // try/finally so the inline muxer's file handle is closed on every
        // exit path, including the uncaught IOException from
        // sabrDownloader.download() (network error). close() is idempotent —
        // the normal path also calls it before validation.
        try {
            executeInternal(request, file);
        } finally {
            if (muxer != null) {
                muxer.close();
            }
        }
    }


    private void executeInternal(DownloadRequest request, File file)
            throws IOException {
        sabrDownloader = new SabrDownloader(context.getOkHttpClient());
        sabrDownloader.setStreamingUrl(request.getSabrUrl());
        sabrDownloader.setUstreamerConfig(request.getSabrConfig());

        // PO token resolution — two paths in order of preference:
        //
        //   1. NATIVE — PoTokenGenerator owns a long-lived hidden GeckoSession
        //      and mints a fresh per-video token over a native port. This
        //      bypasses the WebExtension Tabs API fragility (the
        //      `webProgress is undefined` / `WindowEventDispatcher win is
        //      null` cascade that's been killing tabs mid-mint) and uses
        //      Java CountDownLatch / CompletableFuture timeouts that don't
        //      die alongside the JS macrotask scheduler.
        //
        //   2. JS-EMBEDDED — token attached to the DownloadRequest from the
        //      youtube WebExtension's background.js path. Kept as a fallback
        //      until the native path is proven across all repro cases.
        //
        // Per-video binding matters: each PO token is bound to a specific
        // videoId (the BotGuard `contentBinding`), so we always want a fresh
        // mint for the video being downloaded. The native path mints fresh;
        // the JS path may return a cached token bound to a different video
        // (a latent bug in the JS cache).
        String poToken = mintPoToken(request);
        if (!TextUtils.isEmpty(poToken)) {
            sabrDownloader.setPoToken(poToken);
            Log.d(TAG, "PO token applied: " + poToken.length() + " chars");
        } else {
            Log.w(TAG, "No PO token available — SABR will hit the attestation wall");
        }

        // Mid-stream attestation recovery: when the server answers
        // STREAM_PROTECTION_STATUS 3 (seen in the wild ~60 s into a download
        // whose start-time token it had accepted), the downloader re-mints
        // through this callback and resumes from position instead of
        // stopping. Set UNCONDITIONALLY — a download whose initial mint
        // failed (branch above) gets its second chance here too. Runs on
        // this same worker thread, the thread the initial mint used.
        //
        // Goes through mintFreshPoToken, NOT mintPoToken: the plain mint is
        // cache-first and on this path the cache holds exactly the token the
        // server just refused, so the recovery re-applied the rejected token
        // twice in ~4s ("cache hit ... 0ms") and failed the download having
        // never asked the page to mint anything.
        sabrDownloader.setPoTokenRefresher(() -> mintFreshPoToken(request));

        // Dynamic MWEB client version from HTML — CDN validates cver= matches
        String clientVersion = request.getSabrClientVersion();
        if (!TextUtils.isEmpty(clientVersion)) {
            sabrDownloader.setClientInfo(SabrMessages.CLIENT_MWEB, clientVersion);
            Log.d(TAG, "Client version: " + clientVersion);
        }

        SabrMessages.FormatId videoFmt = new SabrMessages.FormatId(
                request.getSabrVideoItag(),
                parseLongSafe(request.getSabrVideoLastModified()),
                request.getSabrVideoXtags()
        );
        SabrMessages.FormatId audioFmt = new SabrMessages.FormatId(
                request.getSabrAudioItag(),
                parseLongSafe(request.getSabrAudioLastModified()),
                request.getSabrAudioXtags()
        );

        sabrDownloader.setVideoFormat(videoFmt);
        sabrDownloader.setAudioFormat(audioFmt);

        // DownloadRequest carries durationTime in microseconds (sourced from
        // FFprobe metadata or the JS path's ms→µs conversion). SabrDownloader
        // expects milliseconds, so convert once here.
        long durationMs = request.getDurationTime() / 1000;
        sabrDownloader.setDurationMs(durationMs);
        sabrDownloader.setTargetResolution(request.getSabrTargetHeight());

        String audioTrackId = request.getSabrAudioTrackId();
        if (!TextUtils.isEmpty(audioTrackId)) {
            sabrDownloader.setAudioTrackId(audioTrackId);
        }

        // Unified progress: SABR download = 0-95%
        sabrDownloader.setProgressListener((downloadedMs, totalMs, videoSegs, audioSegs) -> {
            if (stopped) return;
            int dlPct = totalMs > 0
                    ? Math.min((int)(downloadedMs * DOWNLOAD_WEIGHT / totalMs), DOWNLOAD_WEIGHT)
                    : 0;
            // The inline muxer writes the output file as the segments arrive,
            // so its length IS the downloaded-so-far figure a direct-to-file
            // download reports (0 until both inits are in and the header is
            // written — a beat, not the whole download).
            reportProgress(dlPct, downloadedMs, totalMs, file.length());
        });

        // Inline fMP4 muxer: interleaves video+audio into `file` as segments
        // arrive, so the playable file is ready the instant the download ends.
        // It is the ONLY copy of the download — no raw per-track streams are
        // kept beside it (they doubled the disk writes and the peak disk space
        // of every download). It self-fails on anything it can't handle, and
        // the end of the download probe-validates `file`; see step 5.
        muxer = new Fmp4Muxer(file, durationMs);
        sabrDownloader.setMuxSink(muxer);

        Log.d(TAG, "Starting SABR download: video=" + request.getSabrVideoItag()
                + " audio=" + request.getSabrAudioItag()
                + " duration=" + durationMs + "ms"
                + " target=" + request.getSabrTargetHeight() + "p"
                + (poToken != null ? " poToken=yes" : " poToken=no"));

        // Report initial progress so UI shows 0%
        reportProgress(0, 0, durationMs);

        SabrDownloader.Result result;
        try {
            result = sabrDownloader.download();
        } catch (SabrDownloader.SabrException e) {
            Log.e(TAG, "SABR download failed: " + e.getMessage(), e);

            // A download that ended at the attestation wall leaves a stale
            // token in PoTokenGenerator's per-video cache: on the rejected
            // path it is the token fresh mints kept reproducing, and on the
            // mint-failed (network) path it is the token the server just
            // answered status-3 to. Either way the ERROR row's obvious next
            // step — the user hitting retry — must not start from it, or the
            // retry burns another ~60 s of media reaching the same wall.
            // Drop it so the retry mints from scratch.
            if (sabrDownloader.isAttestationRejected()) {
                invalidatePoToken(request);
            }

            if (isDeleted()) {
                if (file.exists()) file.delete();
                return;
            }

            // A server-refused download ERRORS — no salvage. This used to
            // fall through and mux whatever segments existed, which
            // finalized an attestation-stopped download (62 s of a
            // 100-minute video) as a FINISHED entry: a broken-looking file
            // with no honest error anywhere (reported on-device — the row
            // said complete, playback showed nothing sensible). The
            // deliberate partial path is the USER's stop/finish, handled
            // below via `stopped`; a SabrException (attestation after the
            // re-mint attempts, malformed config, player reload) is the
            // server ending the download, and the honest outcome is an
            // ERROR row the user can retry.
            callback.onError(MessageHelper.IOEXCEPTION);
            return;
        }

        // ====================================================================
        // 4. Handle delete — abort, no mux
        // ====================================================================

        if (isDeleted()) {
            Log.d(TAG, "Download deleted, cleaning up");
            if (file.exists()) file.delete();
            return;
        }

        // Check if anything arrived
        if (result.videoSegments == 0 || result.videoBytes == 0) {
            Log.w(TAG, "No video data downloaded");
            if (file.exists()) file.delete();
            if (!stopped) {
                callback.onError(MessageHelper.IOEXCEPTION);
            }
            return;
        }

        Log.d(TAG, "SABR download phase complete: "
                + result.videoBytes / 1024 + "KB video + "
                + result.audioBytes / 1024 + "KB audio"
                + (stopped ? " (user stopped, partial)" : ""));

        // ====================================================================
        // 5. Validate the inline mux. A user finish included: close() patches
        //    the partial file to its real length, and that IS the playable
        //    partial the user asked for.
        // ====================================================================

        // Validation (and the rare remux below) runs after the bytes are in.
        // Rather than freeze the bar near the top, flip the row into an
        // indeterminate "Finishing…" state for its duration. onProcessing() is
        // honoured even when sealed, so this also covers the user-finish path
        // (which seals to FINISHED first).
        callback.onProcessing();
        long finishingShownAt = System.currentTimeMillis();

        // The inline fMP4 is the download. close() writes the seek index and
        // patches the durations; the probe then has to read it as a real
        // container with a duration and a video stream. A file the muxer gave
        // up on AFTER its header (a torn fragment, an index that did not fit)
        // is still ISO-BMFF up to that point, so FFmpeg stream-copies it into a
        // classic MP4 and the copy is probed the same way. Nothing else is left
        // to try: an unusable result is an ERROR row, never a FINISHED row over
        // a broken file.
        muxer.close();
        long inlineDuration = 0;
        if (muxer.isHeaderWritten() && file.exists() && file.length() > 0) {
            inlineDuration = probePlayableDuration(file);
        }
        if (inlineDuration <= 0 && muxer.hasHeader() && !isDeleted()
                && file.exists() && file.length() > 0) {
            Log.w(TAG, "Inline fMP4 " + (muxer.isFailed() ? "muxer gave up" : "failed the probe")
                    + " — remuxing it with FFmpeg");
            inlineDuration = remuxInline(file);
        }

        // Delete arrived during the download or the validation
        if (isDeleted()) {
            if (file.exists()) file.delete();
            return;
        }

        if (inlineDuration <= 0) {
            Log.e(TAG, "SABR output unusable (muxer failed=" + muxer.isFailed()
                    + " header=" + muxer.hasHeader() + " bytes=" + file.length() + ")");
            // A broken file must not sit behind a FINISHED row. The user-finish
            // path has sealed FINISHED already and onError is a no-op on a
            // sealed task, so the file is removed: onRunComplete then turns a
            // FINISHED row with no file into ERROR/FILE_NOT_FOUND, retryable.
            if (file.exists()) file.delete();
            callback.onError(MessageHelper.IOEXCEPTION);
            return;
        }
        Log.d(TAG, "Inline fMP4 validated: " + inlineDuration + "ms, " + file.length() + " bytes");

        // Hand the just-probed file duration to the task so its post-download
        // refreshMetadataFromFile can skip re-probing the SAME file seconds
        // later. The duplicate probe was pure waste on every SABR finish, and
        // in the user-finish repro the second probe was the step that never
        // returned (row stuck on "Finishing…" forever).
        callback.onFileDurationProbed(inlineDuration);

        // The validation is a probe of a local file, so for a small clip it
        // completes in well under a second — fast enough that the "Finishing…"
        // state would be written and then overwritten by FINISHED within one
        // frame, and the Paging differ coalesces the two so the user never
        // sees it. Hold the state on screen for a short minimum so the
        // transition is always perceptible. Applies to the user-finish path
        // too (a tiny partial probes just as fast).
        long shownFor = System.currentTimeMillis() - finishingShownAt;
        if (shownFor < MIN_FINISHING_VISIBLE_MS) {
            sleep(MIN_FINISHING_VISIBLE_MS - shownFor);
        }

        // ====================================================================
        // 6. Done
        // ====================================================================

        // Stopped (user finish): the partial file is the download.
        if (stopped) {
            if (file.exists() && file.length() > 0) {
                callback.onImgResolved(file.getAbsolutePath());
                callback.onFileSizeKnown(file.length());
            }
            // Boundary log — everything after this line is DownloadTask/
            // RunnableManager territory (onRunComplete → recycleTask), so a
            // "Finishing…" row stuck with this line printed puts the wedge
            // there, not in the strategy.
            Log.d(TAG, "user finish: partial file finalized ("
                    + file.length() + " bytes)");
            return;
        }

        // Normal completion
        reportProgress(100, file.length(), file.length());
        callback.onImgResolved(file.getAbsolutePath());
        callback.onFileSizeKnown(file.length());
        callback.onStatusChanged(Download.FINISHED);
        Log.d(TAG, "download finished: " + file.length() + " bytes");
    }

    // ========================================================================
    // Stop / Delete
    // ========================================================================

    @Override
    public void stop() {
        stopped = true;
        if (sabrDownloader != null) {
            sabrDownloader.abort();
        }
    }

    private boolean isDeleted() {
        return context != null && context.isDeleted();
    }

    // ========================================================================
    // Helpers
    // ========================================================================

    /**
     * Mint a PO token for this download via {@link PoTokenGenerator}. Returns
     * {@code null} on failure — the SABR download will start without one and
     * YouTube will cut it at ~60s with status=3 (attestation required), which
     * is the correct fail-mode: better to fail fast than to ship a cold-start
     * placeholder that fakes a head start and dies the same way.
     */
    @Nullable
    private String mintPoToken(@NonNull DownloadRequest request) {
        return mintPoToken(request, false);
    }

    /**
     * Mint a PO token the server has not already refused — used only by the
     * mid-stream attestation recovery. Bypasses every cache layer; see
     * {@link PoTokenGenerator#generateFresh}.
     */
    @Nullable
    private String mintFreshPoToken(@NonNull DownloadRequest request) {
        return mintPoToken(request, true);
    }

    /** Drop the cached STREAM token (the visitor-bound one this download
     *  carried on videoplayback) so the next mint re-attests in the page.
     *  The video-bound token is a different token for a different request
     *  (timedtext) and is left alone. */
    private void invalidatePoToken(@NonNull DownloadRequest request) {
        PoTokenGenerator gen = context.getPoTokenGenerator();
        String visitorData = request.getSabrVisitorData();
        if (gen != null && !TextUtils.isEmpty(visitorData)) {
            gen.invalidateStream(visitorData);
        }
    }

    @Nullable
    private String mintPoToken(@NonNull DownloadRequest request, boolean forceFresh) {
        PoTokenGenerator gen = context.getPoTokenGenerator();
        String videoId = request.getSabrVideoId();
        String visitorData = request.getSabrVisitorData();

        if (gen == null) {
            Log.w(TAG, "mintPoToken: PoTokenGenerator unavailable");
            return null;
        }
        if (TextUtils.isEmpty(videoId) || TextUtils.isEmpty(visitorData)) {
            Log.w(TAG, "mintPoToken: missing videoId/visitorData on request");
            return null;
        }

        long t0 = System.currentTimeMillis();
        try {
            // The STREAM token: bound to visitorData, which is what the
            // Google Video Server checks on videoplayback. generate() (the
            // video-bound one) is the timedtext token — sending that here
            // shipped as the intermittent status-3 death at ~60 s.
            String token = forceFresh
                    ? gen.generateFreshForStream(videoId, visitorData)
                    : gen.generateForStream(videoId, visitorData);
            long dt = System.currentTimeMillis() - t0;
            if (!TextUtils.isEmpty(token)) {
                Log.d(TAG, "Native PO token: " + token.length() + " chars (" + dt + "ms"
                        + (forceFresh ? ", fresh" : "") + ")");
                return token;
            }
            Log.w(TAG, "Native mint returned empty after " + dt + "ms");
        } catch (Exception e) {
            long dt = System.currentTimeMillis() - t0;
            Log.w(TAG, "Native mint failed after " + dt + "ms: " + e.getMessage());
        }
        return null;
    }

    /** Interruptible sleep — re-flags the thread interrupt on wake so the
     *  download thread's cancellation path still sees it. */
    private static void sleep(long ms) {
        if (ms <= 0) return;
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void reportProgress(int percent, long downloaded, long total) {
        reportProgress(percent, downloaded, total, -1);
    }

    /**
     * As {@link #reportProgress(int, long, long)}, but also publishes the
     * current downloaded byte count ({@code bytes <= 0} = unknown, skipped).
     * Throttled together with the progress tick so the size update rides the
     * same ~1.5s cadence rather than firing a DB write per segment callback.
     */
    private void reportProgress(int percent, long downloaded, long total, long bytes) {
        if (callback == null) return;
        long now = System.currentTimeMillis();
        if (now - lastUpdated > UPDATE_RATE || percent >= 100 || percent == 0) {
            lastUpdated = now;
            if (bytes > 0) callback.onFileSizeKnown(bytes);
            callback.onProgress(Math.min(percent, 100), downloaded, total);
        }
    }

    /**
     * Probe-validates a muxed file before it is trusted as the download: it
     * must read as a real container with a positive duration and at least one
     * video stream. A muxer bug that produced a malformed file fails here, and
     * the caller remuxes or errors instead — so an unverified muxer can never
     * ship a broken download.
     *
     * @return the probed duration when the file is a playable mux, {@code 0}
     *         otherwise. Returning the duration (rather than a boolean) lets the
     *         caller forward it via {@code onFileDurationProbed} so the task's
     *         post-download refresh doesn't probe the same file a second time.
     */
    private static long probePlayableDuration(File file) {
        FFmpegMetaDataReader reader = new FFmpegMetaDataReader();
        try {
            FFmpegMetaData meta = reader.getStreamInfo(file.getAbsolutePath(), null, false);
            if (meta == null || meta.getDuration() <= 0) {
                return 0;
            }
            ArrayList<FFmpegEntity> streams = reader.getStreams();
            if (streams == null) {
                return 0;
            }
            for (FFmpegEntity stream : streams) {
                if (stream != null
                        && stream.getCodecType() == FFmpegStreamInfo.CodecType.VIDEO.getValue()) {
                    return meta.getDuration();
                }
            }
            return 0;
        } catch (Exception e) {
            return 0;
        } finally {
            reader.stop();
            reader.release();
        }
    }

    /**
     * Stream-copies the inline fragmented MP4 at {@code file} into a classic
     * MP4 beside it and moves the copy over the original. The input is the
     * muxer's own output, so no second copy of the download is needed: a header
     * plus the fragments written so far is enough for FFmpeg to rebuild an index
     * from, which is exactly the state a muxer that gave up after its header
     * leaves. The copy is probed like the original before it replaces it.
     *
     * @return the probed duration of the remuxed file, {@code 0} when FFmpeg
     *         refused the input or the copy did not probe either (the original
     *         is left as it was; the caller decides)
     */
    private long remuxInline(File file) {
        File tmp = StoragePaths.sabrRemuxTempFor(file);
        if (tmp.exists() && !tmp.delete()) {
            Log.e(TAG, "remux: stale temp in the way: " + tmp);
            return 0;
        }
        FFmpegDownloader ffmpegDownloader = new FFmpegDownloader();
        ffmpegDownloader.addListener(new FFmpegListener() {
            @Override
            public void onStarted() {}

            @Override
            public void onProgress(long downloaded, long total) {
                // No per-tick progress — the row stays in the indeterminate
                // "Finishing…" state the caller set.
            }

            @Override
            public void onFinished() {}
        });
        int muxResult;
        try {
            muxResult = ffmpegDownloader.start(
                    new String[]{ file.getAbsolutePath() },
                    (Map<String, String>) null,
                    null,
                    tmp.getAbsolutePath(),
                    file.length());
        } finally {
            ffmpegDownloader.free();
        }
        long duration = 0;
        if (muxResult >= 0 && tmp.exists() && tmp.length() > 0) {
            duration = probePlayableDuration(tmp);
        }
        if (duration > 0 && tmp.renameTo(file)) {
            Log.d(TAG, "remux: classic MP4 in place (" + file.length() + " bytes)");
            return duration;
        }
        Log.e(TAG, "remux failed: ffmpeg=" + muxResult
                + (muxResult == FFmpegErrors.ENOENT ? " (storage)" : "")
                + " probed=" + duration);
        if (tmp.exists()) tmp.delete();
        return 0;
    }

    private static long parseLongSafe(String value) {
        if (value == null || value.isEmpty()) return 0;
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}