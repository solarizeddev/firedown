package com.solarized.firedown.sabr;

import android.util.Base64;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * YouTube SABR (Server-Adaptive Bitrate) downloader.
 * Downloads video and audio segments via YouTube's SABR protocol using
 * ANDROID_VR client identity (no PO token required), producing separate
 * video and audio files ready for muxing with FFmpeg.
 * Usage:
 *   SabrDownloader dl = new SabrDownloader(okHttpClient);
 *   dl.setStreamingUrl(sabrUrl);
 *   dl.setUstreamerConfig(base64Config);
 *   dl.setVideoFormat(new SabrMessages.FormatId(137, lmt, xtags));
 *   dl.setAudioFormat(new SabrMessages.FormatId(140, lmt, xtags));
 *   dl.setDurationMs(180000);
 *   SabrDownloader.Result result = dl.download(outputDir);
 */
public class SabrDownloader {
    private static final String TAG = "SabrDownloader";

    private static final int MAX_RETRIES = 10;
    private static final int MAX_BACKOFF_MS = 8000;
    private static final int BACKOFF_MULTIPLIER_MS = 500;
    private static final int REQUEST_TIMEOUT_MS = 60000;
    private static final MediaType PROTO_TYPE = MediaType.get("application/x-protobuf");

    private final OkHttpClient client;

    // Configuration (set before calling download())
    private String streamingUrl;
    private byte[] ustreamerConfig;
    private byte[] poToken;
    private SabrMessages.FormatId videoFormat;
    private SabrMessages.FormatId audioFormat;
    private long durationMs;
    private String audioTrackId = "";
    private int targetResolution = 1080;
    private int clientName = SabrMessages.CLIENT_WEB;
    private String clientVersion = null;

    // Session state (managed internally)
    private int requestNumber = 0;
    private SabrMessages.NextRequestPolicy nextRequestPolicy;
    private final Map<Integer, SabrMessages.SabrContextUpdate> sabrContexts = new HashMap<>();
    private final Set<Integer> activeSabrContextTypes = new HashSet<>();

    // Format tracking
    private final Map<String, FormatState> initializedFormats = new HashMap<>();
    private String videoFormatKey;
    private String audioFormatKey;

    // Cancellation
    private volatile boolean aborted = false;
    private volatile boolean attestationRequired = false;

    // Fatal protocol-level error raised during UMP parsing. Set by the
    // SABR_ERROR and RELOAD_PLAYER_RESPONSE cases inside the parser lambda;
    // inspected by processUmpResponse after readStream returns. Used instead
    // of throwing RuntimeException out of the lambda, which previously
    // escaped all the way up and killed the worker thread.
    private SabrException fatalError;

    /** Callback for download progress */
    public interface ProgressListener {
        void onProgress(long downloadedMs, long totalMs, int videoSegments, int audioSegments);
    }

    private ProgressListener progressListener;

    public SabrDownloader(OkHttpClient client) {
        this.client = client.newBuilder()
                .readTimeout(REQUEST_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build();
    }

    // --- Configuration ---

    public void setStreamingUrl(String url) { this.streamingUrl = url; }
    public void setUstreamerConfig(String base64) {
        this.ustreamerConfig = Base64.decode(base64, Base64.URL_SAFE | Base64.NO_WRAP);
    }

    public void setPoToken(String base64) {
        // Original base64url string for URL pot= parameter
        this.poToken = base64 != null ? Base64.decode(base64, Base64.URL_SAFE | Base64.NO_WRAP) : null;
    }
    public void setVideoFormat(SabrMessages.FormatId fmt) { this.videoFormat = fmt; }
    public void setAudioFormat(SabrMessages.FormatId fmt) { this.audioFormat = fmt; }
    public void setDurationMs(long ms) { this.durationMs = ms; }
    public void setAudioTrackId(String id) { this.audioTrackId = id; }
    public void setTargetResolution(int height) { this.targetResolution = height; }
    public void setClientInfo(int clientName, String clientVersion) {
        this.clientName = clientName;
        this.clientVersion = clientVersion;
    }
    public void setProgressListener(ProgressListener l) { this.progressListener = l; }
    public void abort() { this.aborted = true; }

    // --- Download result ---

    public static class Result {
        public final File videoFile;
        public final File audioFile;
        public final long durationMs;
        public final int videoSegments;
        public final int audioSegments;

        public Result(File videoFile, File audioFile, long durationMs, int videoSegs, int audioSegs) {
            this.videoFile = videoFile;
            this.audioFile = audioFile;
            this.durationMs = durationMs;
            this.videoSegments = videoSegs;
            this.audioSegments = audioSegs;
        }
    }

    // --- Format state tracking ---

    private static class FormatState {
        SabrMessages.FormatInitMetadata metadata;
        long downloadedDurationMs;
        int lastSegmentNumber = -1;
        final Set<Integer> downloadedSegments = new HashSet<>();
    }

    // --- Main download loop ---

    /**
     * Download the video+audio via SABR protocol.
     *
     * @param outputDir Directory to write output files
     * @return Result with paths to video and audio files
     * @throws IOException on network or file errors
     * @throws SabrException on protocol errors
     */
    public Result download(File outputDir) throws IOException, SabrException {
        if (streamingUrl == null) throw new SabrException("Streaming URL not set");
        if (ustreamerConfig == null) throw new SabrException("Ustreamer config not set");
        if (videoFormat == null || audioFormat == null) throw new SabrException("Formats not set");

        outputDir.mkdirs();
        File videoFile = new File(outputDir, "video_sabr.mp4");
        File audioFile = new File(outputDir, "audio_sabr.m4a");

        aborted = false;
        requestNumber = 0;
        initializedFormats.clear();
        sabrContexts.clear();
        activeSabrContextTypes.clear();
        nextRequestPolicy = null;
        videoFormatKey = null;
        audioFormatKey = null;
        fatalError = null;

        // Auto-detect client identity from c= param in the streaming URL.
        // The serverAbrStreamingUrl already has c=WEB or c=MWEB baked in by YouTube.
        // The protobuf body's clientInfo.clientName MUST match this, or we get 403.
        if (streamingUrl.contains("&c=WEB&") || streamingUrl.contains("?c=WEB&")) {
            if (clientName != SabrMessages.CLIENT_WEB) {
                Log.d(TAG, "URL has c=WEB, overriding client identity from MWEB to WEB");
                clientName = SabrMessages.CLIENT_WEB;
            }
        } else if (streamingUrl.contains("&c=MWEB&") || streamingUrl.contains("?c=MWEB&")) {
            if (clientName != SabrMessages.CLIENT_MWEB) {
                Log.d(TAG, "URL has c=MWEB, overriding client identity from WEB to MWEB");
                clientName = SabrMessages.CLIENT_MWEB;
            }
        }
        Log.d(TAG, "Using client identity: " + (clientName == SabrMessages.CLIENT_MWEB ? "MWEB" : "WEB")
                + " (cver=" + clientVersion + ")");

        try (FileOutputStream videoOut = new FileOutputStream(videoFile);
             FileOutputStream audioOut = new FileOutputStream(audioFile)) {

            long playerTimeMs = 0;
            int totalVideoSegments = 0;
            int totalAudioSegments = 0;
            int noProgressCount = 0;
            int redirectCount = 0;

            while (playerTimeMs < durationMs && !aborted && !attestationRequired) {
                Log.d(TAG, "Fetching segments at position " + playerTimeMs + "ms / " + durationMs + "ms");

                // Respect server backoff
                if (nextRequestPolicy != null && nextRequestPolicy.backoffTimeMs > 0) {
                    Log.d(TAG, "Server backoff: " + nextRequestPolicy.backoffTimeMs + "ms");
                    sleep(nextRequestPolicy.backoffTimeMs);
                }

                // Build and send request
                SegmentResult segResult;
                try {
                    segResult = fetchWithRetry(playerTimeMs);
                } catch (SabrException se) {
                    // Protocol-level error from the server (sabr.malformed_config,
                    // reload requested, etc.). Not transient — stop downloading,
                    // flush what we have, and surface the failure to the caller.
                    Log.w(TAG, "SABR protocol error, stopping download: " + se.getMessage());
                    videoOut.flush();
                    audioOut.flush();
                    throw se;
                }
                if (segResult == null) {
                    Log.w(TAG, "Failed to fetch segments after retries");
                    break;
                }

                // Write segments to files
                int newSegments = 0;
                for (CompletedSegment seg : segResult.segments) {
                    if (seg.isAudio) {
                        audioOut.write(seg.data);
                        totalAudioSegments++;
                    } else {
                        videoOut.write(seg.data);
                        totalVideoSegments++;
                    }
                    newSegments++;
                }

                // Update player time from format state
                long prevPlayerTime = playerTimeMs;
                playerTimeMs = getDownloadedDuration();

                // Report progress
                if (progressListener != null) {
                    progressListener.onProgress(playerTimeMs, durationMs,
                            totalVideoSegments, totalAudioSegments);
                }

                // Detect completion:
                // 1. Within 5 seconds of duration → close enough (rounding in ticks→ms)
                if (durationMs - playerTimeMs < 5000 && playerTimeMs > 0) {
                    Log.d(TAG, "Within 5s of duration, treating as complete ("
                            + playerTimeMs + "/" + durationMs + "ms)");
                    break;
                }

                // 2. Got a redirect → use new URL, don't count as stall
                if (segResult.gotRedirect) {
                    Log.d(TAG, "Got redirect, continuing with new URL");
                    // Don't count redirects as stalls, but limit redirect chains
                    redirectCount++;
                    if (redirectCount > 10) {
                        Log.w(TAG, "Too many redirects (" + redirectCount + "), aborting");
                        break;
                    }
                    continue;
                }

                // 3. Got segments → reset stall counter, continue
                if (newSegments > 0 && playerTimeMs > prevPlayerTime) {
                    noProgressCount = 0;
                    redirectCount = 0;
                    continue;
                }

                // 4. No new segments and no redirect → count as stall
                noProgressCount++;
                if (noProgressCount >= 5) {
                    Log.d(TAG, "No progress after " + noProgressCount + " attempts, download complete");
                    break;
                }
                Log.d(TAG, "No new segments (attempt " + noProgressCount + "/5), retrying...");
            }

            videoOut.flush();
            audioOut.flush();

            if (attestationRequired) {
                Log.w(TAG, "Download stopped: attestation required by server. "
                        + totalVideoSegments + " video + " + totalAudioSegments
                        + " audio segments saved (" + playerTimeMs + "ms / " + durationMs + "ms)");
            } else {
                Log.i(TAG, "Download complete: " + totalVideoSegments + " video + "
                        + totalAudioSegments + " audio segments, "
                        + playerTimeMs + "ms / " + durationMs + "ms");
            }

            return new Result(videoFile, audioFile, durationMs,
                    totalVideoSegments, totalAudioSegments);
        }
    }

    // --- Request building ---

    private byte[] buildRequestBody(long playerTimeMs) {
        // Collect active SABR contexts
        List<SabrMessages.SabrContextUpdate> activeContexts = new ArrayList<>();
        List<Integer> unsentTypes = new ArrayList<>();
        for (SabrMessages.SabrContextUpdate ctx : sabrContexts.values()) {
            if (activeSabrContextTypes.contains(ctx.type)) {
                activeContexts.add(ctx);
            } else {
                unsentTypes.add(ctx.type);
            }
        }

        // Build buffered ranges from format state
        List<byte[]> bufferedRanges = buildBufferedRanges();

        // Selected formats (only after initialization)
        SabrMessages.FormatId[] selected = null;
        if (!initializedFormats.isEmpty()) {
            selected = new SabrMessages.FormatId[]{ videoFormat, audioFormat };
        }

        return SabrMessages.buildAbrRequest(
                playerTimeMs,
                targetResolution,
                audioTrackId,
                new SabrMessages.FormatId[]{ videoFormat },
                new SabrMessages.FormatId[]{ audioFormat },
                selected,
                bufferedRanges.toArray(new byte[0][]),
                ustreamerConfig,
                poToken,
                nextRequestPolicy != null ? nextRequestPolicy.playbackCookieRaw : null,
                activeContexts.toArray(new SabrMessages.SabrContextUpdate[0]),
                unsentTypes.stream().mapToInt(i -> i).toArray(),
                clientName,
                clientVersion
        );
    }

    private List<byte[]> buildBufferedRanges() {
        List<byte[]> ranges = new ArrayList<>();
        for (FormatState state : initializedFormats.values()) {
            if (state.downloadedSegments.isEmpty() || state.metadata == null) continue;

            // Use accumulated duration and actual segment range
            long totalDurationMs = state.downloadedDurationMs;
            if (totalDurationMs <= 0) continue;

            // Find min/max segment numbers (excluding init segment 0)
            int startSeg = Integer.MAX_VALUE;
            int endSeg = 0;
            for (int seg : state.downloadedSegments) {
                if (seg == 0) continue; // skip init segment
                startSeg = Math.min(startSeg, seg);
                endSeg = Math.max(endSeg, seg);
            }
            if (startSeg == Integer.MAX_VALUE) continue; // only had init segment

            SabrMessages.TimeRange tr = new SabrMessages.TimeRange();
            tr.startTicks = 0;
            tr.durationTicks = totalDurationMs;
            tr.timescale = 1000;

            ranges.add(SabrMessages.buildBufferedRange(
                    state.metadata.formatId,
                    0, totalDurationMs,
                    startSeg, endSeg, tr
            ));
        }
        return ranges;
    }

    // --- Network ---


    private static class SegmentResult {
        List<CompletedSegment> segments = new ArrayList<>();
        boolean gotMediaHeaders = false;
        boolean gotRedirect = false;
    }

    private static class CompletedSegment {
        byte[] data;
        boolean isAudio;
        int segmentNumber;
    }

    private SegmentResult fetchWithRetry(long playerTimeMs) throws IOException, SabrException {
        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            if (aborted) return null;

            try {
                SegmentResult result = fetchAndProcess(playerTimeMs);
                return result;
            } catch (SabrException se) {
                // Protocol error (server rejected us). Not transient; don't retry.
                // Caller (download()) catches and stops gracefully.
                throw se;
            } catch (IOException e) {
                String msg = e.getMessage() != null ? e.getMessage() : "";
                boolean isDns = msg.contains("resolve host") || msg.contains("No address");
                boolean isConnectionReset = msg.contains("connection abort")
                        || msg.contains("Connection reset") || msg.contains("ECONNRESET");

                if (attempt == MAX_RETRIES) throw e;

                // DNS errors: short retry — usually resolves within seconds
                // Connection resets: medium retry — CDN may have dropped us
                // Other errors: standard exponential backoff
                int backoff;
                if (isDns) {
                    backoff = Math.min(1000 * attempt, 5000); // 1s, 2s, 3s, 4s, 5s cap
                } else if (isConnectionReset) {
                    backoff = Math.min(500 * (1 << (attempt - 1)), 4000);
                } else {
                    backoff = Math.min(BACKOFF_MULTIPLIER_MS * (1 << (attempt - 1)), MAX_BACKOFF_MS);
                }

                Log.w(TAG, "Attempt " + attempt + "/" + MAX_RETRIES + " failed"
                        + (isDns ? " (DNS)" : isConnectionReset ? " (reset)" : "")
                        + ", retrying in " + backoff + "ms: " + msg);
                sleep(backoff);
            }
        }
        return null;
    }

    private SegmentResult fetchAndProcess(long playerTimeMs) throws IOException, SabrException {
        byte[] body = buildRequestBody(playerTimeMs);
        String url = streamingUrl;

        if (clientVersion == null) {
            throw new SabrException("No client version set — cannot build SABR request");
        }

        // Add params that the real MWEB YouTube player adds dynamically.
        // From HAR capture of working browser SABR request:
        //   rn (request number), cver (client version), alr=yes, cpn (client playback nonce)
        // NOTE: pot= is NOT in the browser's URL — PO token is only in the protobuf body.
        String sep = url.contains("?") ? "&" : "?";
        url += sep + "rn=" + requestNumber
                + "&cver=" + clientVersion
                + "&alr=yes"
                + "&cpn=" + generateCpn();
        requestNumber++;

        // Log full URL on first request only
        if (requestNumber == 1) {
            int mid = Math.min(url.length(), 500);
            Log.d(TAG, "SABR URL[0]: " + url.substring(0, mid));
            if (url.length() > 500) Log.d(TAG, "SABR URL[1]: " + url.substring(500));
        }
        Log.d(TAG, "SABR request to: " + url.substring(0, Math.min(url.length(), 120))
                + " (" + body.length + " bytes)");

        // Headers must match the client that generated the streaming URL.
        // From HAR: the browser uses mobile Firefox UA with m.youtube.com origin.
        String origin, referer, userAgent;
        if (clientName == SabrMessages.CLIENT_MWEB) {
            origin = "https://m.youtube.com";
            referer = "https://m.youtube.com/";
            // Must match the UA the browser uses on m.youtube.com
            userAgent = "Mozilla/5.0 (Android 16; Mobile; rv:149.0) Gecko/149.0 Firefox/149.0";
        } else {
            origin = "https://www.youtube.com";
            referer = "https://www.youtube.com/";
            userAgent = "Mozilla/5.0 (X11; Linux x86_64; rv:149.0) Gecko/20100101 Firefox/149.0";
        }

        Request request = new Request.Builder()
                .url(url)
                .post(RequestBody.create(body, PROTO_TYPE))
                .addHeader("User-Agent", userAgent)
                .addHeader("Accept", "*/*")
                .addHeader("Accept-Language", "en-US")
                .addHeader("Accept-Encoding", "identity")
                .addHeader("Origin", origin)
                .addHeader("Referer", referer)
                .addHeader("Sec-Fetch-Dest", "empty")
                .addHeader("Sec-Fetch-Mode", "cors")
                .addHeader("Sec-Fetch-Site", "cross-site")
                .build();

        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("SABR request failed: " + response.code() + " " + response.message());
            }
            return processUmpResponse(response.body().byteStream());
        }
    }

    // --- UMP response processing ---

    private SegmentResult processUmpResponse(InputStream in) throws IOException, SabrException {
        SegmentResult result = new SegmentResult();

        // Partial segment accumulation: headerId -> (formatKey, chunks, mediaHeader)
        Map<Integer, PartialSegment> partials = new HashMap<>();

        // Previously, SABR_ERROR and RELOAD_PLAYER_RESPONSE threw RuntimeException
        // wrapping SabrException to escape the functional-interface lambda (which
        // can't throw checked exceptions). That RuntimeException then propagated
        // through readStream → fetchAndProcess → fetchWithRetry (whose catch was
        // IOException-only) → download → the worker thread, crashing the process.
        //
        // The fix: use UmpReader's existing "return true = stop" contract. The
        // lambda writes the error to the `fatalError` field and returns true;
        // readStream returns cleanly; we inspect the field after and throw
        // SabrException as a checked exception. No thread-killing runtime leaks.
        fatalError = null;

        UmpReader.readStream(in, (partType, data, offset, length) -> {
            try {
                switch (partType) {
                    case UmpReader.FORMAT_INITIALIZATION_METADATA:
                        handleFormatInit(data, offset, length);
                        break;

                    case UmpReader.NEXT_REQUEST_POLICY:
                        nextRequestPolicy = SabrMessages.NextRequestPolicy.decode(data, offset, length);
                        Log.d(TAG, "NextRequestPolicy: backoff=" + nextRequestPolicy.backoffTimeMs + "ms");
                        break;

                    case UmpReader.MEDIA_HEADER:
                        handleMediaHeader(data, offset, length, partials);
                        result.gotMediaHeaders = true;
                        break;

                    case UmpReader.MEDIA:
                        handleMediaData(data, offset, length, partials);
                        break;

                    case UmpReader.MEDIA_END:
                        handleMediaEnd(data, offset, length, partials, result);
                        break;

                    case UmpReader.SABR_REDIRECT:
                        SabrMessages.SabrRedirect redirect = SabrMessages.SabrRedirect.decode(data, offset, length);
                        if (redirect.url != null && !redirect.url.isEmpty()) {
                            Log.i(TAG, "SABR redirect to: " + redirect.url);
                            streamingUrl = redirect.url;
                            result.gotRedirect = true;
                        }
                        break;

                    case UmpReader.SABR_ERROR:
                        SabrMessages.SabrError error = SabrMessages.SabrError.decode(data, offset, length);
                        Log.e(TAG, "SABR error: type=" + error.type + " code=" + error.code);
                        fatalError = new SabrException(
                                "SABR error: " + error.type + " (code " + error.code + ")");
                        return true; // stop reading — caller will throw fatalError

                    case UmpReader.SABR_CONTEXT_UPDATE:
                        SabrMessages.SabrContextUpdate ctx = SabrMessages.SabrContextUpdate.decode(data, offset, length);
                        if (ctx.value != null && ctx.value.length > 0) {
                            // writePolicy 1 = KEEP_EXISTING
                            if (ctx.writePolicy != 1 || !sabrContexts.containsKey(ctx.type)) {
                                sabrContexts.put(ctx.type, ctx);
                            }
                            if (ctx.sendByDefault) {
                                activeSabrContextTypes.add(ctx.type);
                            }
                        }
                        break;

                    case UmpReader.SABR_CONTEXT_SENDING_POLICY:
                        SabrMessages.SabrContextSendingPolicy policy =
                                SabrMessages.SabrContextSendingPolicy.decode(data, offset, length);
                        for (int t : policy.startPolicy) activeSabrContextTypes.add(t);
                        for (int t : policy.stopPolicy) activeSabrContextTypes.remove(t);
                        for (int t : policy.discardPolicy) sabrContexts.remove(t);
                        break;

                    case UmpReader.STREAM_PROTECTION_STATUS:
                        SabrMessages.StreamProtectionStatus sps =
                                SabrMessages.StreamProtectionStatus.decode(data, offset, length);
                        if (sps.status == 3) {
                            // Attestation required — YouTube demands PO token proof.
                            // We can't provide it, so stop downloading gracefully.
                            // The segments we already have are still valid.
                            Log.w(TAG, "Stream protection: attestation required (status=3), stopping");
                            attestationRequired = true;
                        } else if (sps.status == 2) {
                            Log.w(TAG, "Stream protection: attestation pending");
                        }
                        break;

                    case UmpReader.RELOAD_PLAYER_RESPONSE:
                        Log.w(TAG, "Server requested player reload");
                        fatalError = new SabrException("Player reload requested");
                        return true; // stop reading — caller will throw fatalError

                    default:
                        // Known parts we intentionally ignore (player-only, not needed for download)
                        if (partType != UmpReader.SELECTABLE_FORMATS
                                && partType != UmpReader.PLAYBACK_START_POLICY
                                && partType != UmpReader.REQUEST_IDENTIFIER
                                && partType != UmpReader.START_BW_SAMPLING_HINT
                                && partType != UmpReader.ALLOWED_CACHED_FORMATS
                                && partType != UmpReader.SERVER_DRIVEN_CANCELLATION) {
                            Log.d(TAG, "Unhandled UMP part: " + UmpReader.partTypeName(partType)
                                    + " (" + length + " bytes)");
                        }
                        break;
                }
            } catch (RuntimeException re) {
                // Swallow parser-level failures (malformed bytes, bounds violations,
                // schema drift) so one bad part doesn't kill the whole download.
                // Fatal protocol errors now go through fatalError + return true,
                // so anything RuntimeException reaching here is genuinely unexpected.
                Throwable cause = re.getCause() != null ? re.getCause() : re;
                if (cause instanceof IllegalStateException
                        || cause instanceof IndexOutOfBoundsException
                        || cause instanceof NumberFormatException
                        || re instanceof IllegalStateException
                        || re instanceof IndexOutOfBoundsException) {
                    Log.w(TAG, "Failed to decode UMP part "
                            + UmpReader.partTypeName(partType)
                            + " (" + length + " bytes): " + cause.getMessage());
                } else {
                    // Unknown runtime failure — don't silently swallow, but don't
                    // crash either. Log with stack so it's visible if it happens.
                    Log.e(TAG, "Unexpected RuntimeException in UMP part "
                            + UmpReader.partTypeName(partType), re);
                }
            }
            return false; // continue reading
        });

        // If the parser stopped for a fatal protocol reason, surface it as a
        // checked SabrException so the retry loop and caller can handle it.
        if (fatalError != null) {
            SabrException toThrow = fatalError;
            fatalError = null;
            throw toThrow;
        }

        return result;
    }

    // --- UMP part handlers ---

    private void handleFormatInit(byte[] data, int offset, int length) {
        SabrMessages.FormatInitMetadata meta = SabrMessages.FormatInitMetadata.decode(data, offset, length);
        String key = meta.formatKey();

        FormatState state = new FormatState();
        state.metadata = meta;

        initializedFormats.put(key, state);

        // Correct duration if metadata provides a reasonable value
        long metaDuration = meta.durationMs();
        if (metaDuration > 0) {
            // Sanity: metadata duration should be within 10x of innertube duration
            // If wildly different, the calculation is likely wrong — keep original
            if (durationMs <= 0 || (metaDuration < durationMs * 10 && metaDuration > durationMs / 10)) {
                if (metaDuration != durationMs) {
                    Log.d(TAG, "Correcting duration: " + durationMs + " -> " + metaDuration);
                    durationMs = metaDuration;
                }
            } else {
                Log.w(TAG, "Ignoring suspicious metadata duration: " + metaDuration
                        + "ms (innertube says " + durationMs + "ms)");
            }
        }

        // Track which format key is video vs audio
        if (meta.formatId != null) {
            if (meta.mimeType.startsWith("audio/")) {
                audioFormatKey = key;
            } else {
                videoFormatKey = key;
            }
        }

        Log.d(TAG, "Format initialized: " + key + " (" + meta.mimeType
                + ") segments=" + meta.endSegmentNumber);
    }

    private static class PartialSegment {
        String formatKey;
        SabrMessages.MediaHeader header;
        ByteArrayOutputStream chunks = new ByteArrayOutputStream();
    }

    private void handleMediaHeader(byte[] data, int offset, int length,
                                   Map<Integer, PartialSegment> partials) {
        SabrMessages.MediaHeader header = SabrMessages.MediaHeader.decode(data, offset, length);
        String formatKey = header.formatKey();

        FormatState state = initializedFormats.get(formatKey);
        if (state == null) {
            Log.w(TAG, "MediaHeader for unknown format: " + formatKey);
            return;
        }

        // Skip segments we've already downloaded
        int segNum = header.isInitSeg ? 0 : header.sequenceNumber;
        if (state.downloadedSegments.contains(segNum)) {
            Log.d(TAG, "Skipping already-downloaded segment " + segNum + " for " + formatKey);
            return;
        }

        PartialSegment partial = new PartialSegment();
        partial.formatKey = formatKey;
        partial.header = header;
        partials.put(header.headerId, partial);
    }

    private void handleMediaData(byte[] data, int offset, int length,
                                 Map<Integer, PartialSegment> partials) {
        if (length < 1) return;
        int headerId = data[offset] & 0xFF;
        PartialSegment partial = partials.get(headerId);
        if (partial == null) return;
        // Skip first byte (headerId), rest is media data
        partial.chunks.write(data, offset + 1, length - 1);
    }

    private void handleMediaEnd(byte[] data, int offset, int length,
                                Map<Integer, PartialSegment> partials,
                                SegmentResult result) {
        if (length < 1) return;
        int headerId = data[offset] & 0xFF;
        PartialSegment partial = partials.remove(headerId);
        if (partial == null) return;

        FormatState state = initializedFormats.get(partial.formatKey);
        if (state == null) return;

        byte[] segmentData = partial.chunks.toByteArray();

        // Verify content length
        if (partial.header.contentLength > 0 && segmentData.length != partial.header.contentLength) {
            Log.w(TAG, "Content length mismatch: expected " + partial.header.contentLength
                    + " got " + segmentData.length + " for segment " + partial.header.sequenceNumber);
            return;
        }

        int segNum = partial.header.isInitSeg ? 0 : partial.header.sequenceNumber;

        // Skip if already downloaded (belt-and-suspenders with handleMediaHeader check)
        if (state.downloadedSegments.contains(segNum)) {
            return;
        }

        // Calculate duration: use durationMs if available, otherwise compute from timeRange
        long segDurationMs = partial.header.durationMs;
        if (segDurationMs <= 0 && partial.header.timeRange != null
                && partial.header.timeRange.timescale > 0
                && partial.header.timeRange.durationTicks > 0) {
            segDurationMs = (long) Math.ceil(
                    (double) partial.header.timeRange.durationTicks
                            / partial.header.timeRange.timescale * 1000.0);
        }

        // Build completed segment
        CompletedSegment seg = new CompletedSegment();
        seg.data = segmentData;
        seg.isAudio = partial.formatKey.equals(audioFormatKey) || partial.header.isAudio();
        seg.segmentNumber = segNum;
        result.segments.add(seg);

        // Update format state
        state.downloadedSegments.add(segNum);
        state.lastSegmentNumber = Math.max(state.lastSegmentNumber, segNum);
        state.downloadedDurationMs += segDurationMs;

        Log.d(TAG, (seg.isAudio ? "Audio" : "Video") + " segment " + segNum
                + " (" + segmentData.length + " bytes, " + segDurationMs + "ms)"
                + " total=" + state.downloadedDurationMs + "ms");
    }

    // --- Helpers ---

    private long getDownloadedDuration() {
        // Use video format duration as the main progress indicator
        for (FormatState state : initializedFormats.values()) {
            if (state.metadata.mimeType != null && state.metadata.mimeType.startsWith("video/")) {
                return state.downloadedDurationMs;
            }
        }
        // Fall back to any format
        long max = 0;
        for (FormatState state : initializedFormats.values()) {
            max = Math.max(max, state.downloadedDurationMs);
        }
        return max;
    }

    private void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    /** Generate a client playback nonce (cpn) — 16 random chars from [A-Za-z0-9_-] */
    private static final String CPN_CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";
    private String cpn = null;
    private String generateCpn() {
        if (cpn == null) {
            StringBuilder sb = new StringBuilder(16);
            java.util.Random rng = new java.util.Random();
            for (int i = 0; i < 16; i++) sb.append(CPN_CHARS.charAt(rng.nextInt(CPN_CHARS.length())));
            cpn = sb.toString();
        }
        return cpn;
    }

    // --- Exception ---

    public static class SabrException extends Exception {
        public SabrException(String message) { super(message); }
        public SabrException(String message, Throwable cause) { super(message, cause); }
    }
}