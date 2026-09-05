package com.solarized.firedown.manager;

import android.net.Uri;
import android.text.TextUtils;
import android.util.Log;

import com.solarized.firedown.data.Download;
import com.solarized.firedown.data.di.NetworkModule;
import com.solarized.firedown.utils.FileUriHelper;
import com.solarized.firedown.utils.MessageHelper;

import org.apache.commons.io.FilenameUtils;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.Closeable;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;


import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Deezer full-track download.
 *
 * <p>The captured synthetic URL — {@code https://www.deezer.com/track/<SNG_ID>?fmt=<FMT>}
 * — carries the two durable inputs the parser gathered: the {@code SNG_ID} (the
 * whole decrypt input, see {@link DeezerCrypto}) and, on the request, the live
 * {@code www.deezer.com} session cookie. Everything time-limited is re-minted
 * HERE, at download time, so a deferred download can't go stale:
 * <ol>
 *   <li>{@code gw-light.php?method=deezer.getUserData} → the API token
 *       ({@code checkForm}) + {@code license_token};</li>
 *   <li>{@code gw-light.php?method=song.getData} → a fresh {@code TRACK_TOKEN};</li>
 *   <li>{@code media.deezer.com/v1/get_url} → the encrypted CDN URL (stepping the
 *       format ladder down if the account isn't entitled to the requested one);</li>
 *   <li>stream the ciphertext, Blowfish-decrypting every third 2048-byte stripe.</li>
 * </ol>
 *
 * <p>Pure JDK crypto — no ffmpeg, no native. The wire only ever serves the
 * Blowfish-striped bytes; the plaintext is produced here, which is why a Deezer
 * track can't be grabbed by the generic catcher (it would save undecryptable
 * bytes) and why its CDN is block-listed. This is NOT DRM: the key is derived
 * offline from {@code SNG_ID} + a static secret.
 *
 * <p>Resume is intentionally not implemented (like {@link MegaStrategy}): a fresh
 * token + CDN URL is minted each run, and a re-tap is cheap. The stripe cipher
 * would need the keystream re-aligned to a 2048-byte boundary on resume; not
 * worth it for a single track.
 */
public class DeezerStrategy implements DownloadStrategy {

    private static final String TAG = DeezerStrategy.class.getSimpleName();
    private static final String GW_LIGHT = "https://www.deezer.com/ajax/gw-light.php";
    private static final String GET_URL = "https://media.deezer.com/v1/get_url";
    private static final long UPDATE_RATE = 1500;

    private volatile boolean stopped;
    private long lastUpdated;

    @Override
    public void execute(DownloadRequest request, DownloadContext context, DownloadCallback callback)
            throws IOException {

        // ====================================================================
        // 1. Parse the synthetic capture URL: SNG_ID (path) + requested format.
        // ====================================================================
        Uri uri = Uri.parse(request.getUrl());
        List<String> seg = uri.getPathSegments();
        String sngId = null;
        for (int i = 0; i + 1 < seg.size(); i++) {
            if ("track".equals(seg.get(i))) {
                sngId = seg.get(i + 1);
                break;
            }
        }
        String requestedFmt = uri.getQueryParameter("fmt");
        if (TextUtils.isEmpty(requestedFmt)) requestedFmt = "MP3_128";
        String cookie = resolveCookie(request, context);
        if (TextUtils.isEmpty(sngId) || TextUtils.isEmpty(cookie)) {
            Log.e(TAG, "Deezer: missing SNG_ID or session cookie: " + request.getUrl());
            callback.onError(MessageHelper.IOEXCEPTION);
            return;
        }

        OkHttpClient client = context.getOkHttpClient();

        // ====================================================================
        // 2. getUserData → api token (checkForm) + license_token.
        // ====================================================================
        String apiToken = null;
        String licenseToken = null;
        boolean guest = true;
        try {
            String resp = gwLight(client, "deezer.getUserData", "", "{}", cookie);
            JSONObject results = new JSONObject(resp).optJSONObject("results");
            if (results != null) {
                apiToken = results.optString("checkForm", null);
                JSONObject user = results.optJSONObject("USER");
                // A GUEST session also answers getUserData — with a checkForm and
                // even a (preview-only) license_token — but with USER_ID 0. Only a
                // signed-in account gets FULL media from get_url, so treat guest
                // as "not logged in" here rather than three calls later as a
                // confusing "no playable source".
                String userId = user != null ? user.optString("USER_ID", "0") : "0";
                guest = TextUtils.isEmpty(userId) || "0".equals(userId);
                JSONObject options = user != null ? user.optJSONObject("OPTIONS") : null;
                if (options != null) {
                    licenseToken = options.optString("license_token", null);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Deezer: getUserData failed", e);
            callback.onError(MessageHelper.IOEXCEPTION);
            return;
        }
        if (guest || TextUtils.isEmpty(apiToken) || TextUtils.isEmpty(licenseToken)) {
            // No signed-in session — the arl cookie expired, or the user logged out
            // since capture. The 30s preview is all a logged-out visitor can get.
            Log.e(TAG, "Deezer: not logged in (guest session or no api/license token)");
            callback.onError(MessageHelper.IOEXCEPTION);
            return;
        }

        // ====================================================================
        // 3. song.getData → a fresh TRACK_TOKEN (the captured one may have aged out).
        // ====================================================================
        String trackToken;
        try {
            String body = "{\"sng_id\":\"" + sngId + "\"}";
            String resp = gwLight(client, "song.getData", apiToken, body, cookie);
            JSONObject results = new JSONObject(resp).optJSONObject("results");
            trackToken = results != null ? results.optString("TRACK_TOKEN", null) : null;
        } catch (Exception e) {
            Log.e(TAG, "Deezer: song.getData failed", e);
            callback.onError(MessageHelper.IOEXCEPTION);
            return;
        }
        if (TextUtils.isEmpty(trackToken)) {
            Log.e(TAG, "Deezer: no TRACK_TOKEN for " + sngId);
            callback.onError(MessageHelper.IOEXCEPTION);
            return;
        }

        // ====================================================================
        // 4. get_url — try the requested format, stepping DOWN the ladder if the
        //    account isn't entitled (FILESIZE says the file exists, not that this
        //    account may fetch it). The track token is format-independent.
        // ====================================================================
        String cdnUrl = null;
        String resolvedFmt = null;
        for (String fmt : formatLadder(requestedFmt)) {
            try {
                cdnUrl = getUrl(client, licenseToken, trackToken, fmt);
            } catch (Exception e) {
                Log.w(TAG, "Deezer: get_url failed for " + fmt, e);
            }
            if (!TextUtils.isEmpty(cdnUrl)) {
                resolvedFmt = fmt;
                break;
            }
        }
        if (TextUtils.isEmpty(cdnUrl)) {
            Log.e(TAG, "Deezer: no playable source (not entitled to any format)");
            callback.onError(MessageHelper.IOEXCEPTION);
            return;
        }
        boolean flac = resolvedFmt.startsWith("FLAC");
        Log.d(TAG, "Deezer: resolved " + sngId + " as " + resolvedFmt);

        // ====================================================================
        // 5. Output file + mime (extension follows the resolved format).
        // ====================================================================
        String mimeType = flac ? FileUriHelper.MIMETYPE_AUDIO_FLAC : FileUriHelper.AUDIO_MP3;
        callback.onMimeResolved(mimeType);
        String ext = flac ? "flac" : "mp3";
        File file = context.getOutputFile();
        if (!ext.equalsIgnoreCase(FilenameUtils.getExtension(file.getName()))) {
            file = new File(file.getParent(),
                    FilenameUtils.getBaseName(file.getName()) + "." + ext);
        }
        String resolvedPath = callback.onFilePathResolved(file.getAbsolutePath());
        file = new File(resolvedPath);

        // ====================================================================
        // 6. Stream the ciphertext and Blowfish-decrypt every third stripe.
        //    The stripe loop itself is DeezerCrypto.decryptStream (the harness
        //    drives that method directly); here we only wire HTTP + progress +
        //    cooperative cancellation into it.
        // ====================================================================
        byte[] key = DeezerCrypto.trackKey(sngId);

        Request httpReq = new Request.Builder().url(cdnUrl).build();
        Response httpResp = null;
        ResponseBody rb = null;
        BufferedInputStream input = null;
        BufferedOutputStream output = null;
        try {
            httpResp = client.newCall(httpReq).execute();
            if (!httpResp.isSuccessful()) {
                Log.e(TAG, "Deezer: CDN HTTP " + httpResp.code());
                callback.onError(httpResp.code());
                return;
            }
            rb = httpResp.body();
            if (rb == null) {
                callback.onError(MessageHelper.IOEXCEPTION);
                return;
            }
            long contentLength = rb.contentLength();
            final long total = contentLength > 0 ? contentLength : request.getFileLength();
            input = new BufferedInputStream(rb.byteStream());
            output = new BufferedOutputStream(new FileOutputStream(file, false));

            reportProgress(callback, 0, total);
            DeezerCrypto.decryptStream(input, output, key, written -> {
                if (stopped || context.isInterrupted()) {
                    return false;   // abort — the finally still closes + evicts
                }
                reportProgress(callback, written, total);
                return true;
            });
            output.flush();

            if (stopped || context.isInterrupted()) {
                Log.w(TAG, "Deezer: aborted");
                return;
            }

            callback.onProgress(100, file.length(), file.length());
            callback.onFileSizeKnown(file.length());
            callback.onStatusChanged(Download.FINISHED);

        } catch (Exception e) {
            if (stopped || context.isInterrupted()) return;
            Log.e(TAG, "Deezer: download/decrypt failed", e);
            throw new IOException("Deezer download failed", e);
        } finally {
            closeQuietly(input, output, rb, httpResp);
            // On a user-cancel, evict the now-idle pooled connection so an HTTP/2
            // CDN can't keep pushing DATA frames for the dead stream (see
            // NetworkModule.evictIdleConnections()).
            if (stopped || context.isInterrupted()) {
                NetworkModule.evictIdleConnections();
            }
        }
    }

    @Override
    public void stop() {
        stopped = true;
    }

    // ========================================================================
    // Deezer API helpers
    // ========================================================================

    /**
     * The session cookie, from wherever THIS run carries it.
     *
     * <p>A FIRST download has it on the request ({@code DownloadRequest.fromEntity}
     * copies {@code BrowserDownloadEntity.cookieHeader}). A RETRY / RESUME does
     * NOT: {@code DownloadTask.resume()} rebuilds the request with
     * {@code .headers(existing.getFileHeaders())} and no {@code .cookieHeader(…)}.
     * But {@code initialize()} had merged the cookie into that persisted header
     * string as a raw trailing {@code "\r\nCookie=<value>"} line — so it is still
     * there, only inside the raw string. Without this fallback every retry of an
     * errored Deezer download failed with "missing session cookie".
     *
     * <p>It is read from the RAW string on purpose, not from
     * {@code DownloadContext.getHeaders()}: that map is built by
     * {@code Utils.stringToMap}, which splits each {@code &}-pair on {@code =}.
     * That is fine for the URL-encoded entries {@code mapToString} writes, but
     * the appended Cookie line is NOT encoded, so {@code arl=…; sid=…} would be
     * truncated at its first inner {@code =}. Scanning from the end, the raw
     * appended line is always last; a line containing {@code &} is the encoded
     * map line, not the raw cookie, and is left to the map fallback (where the
     * encoded round-trip IS correct).
     *
     * <p>Package-private so {@code scripts/deezer-harness} can pin all three paths.
     */
    static String resolveCookie(DownloadRequest request, DownloadContext context) {
        String cookie = request.getCookieHeader();
        if (!TextUtils.isEmpty(cookie)) {
            return cookie;
        }
        String raw = request.getHeaders();
        if (!TextUtils.isEmpty(raw)) {
            String[] lines = raw.split("\r?\n");
            for (int i = lines.length - 1; i >= 0; i--) {
                String line = lines[i];
                if (line.regionMatches(true, 0, "Cookie=", 0, 7) && line.indexOf('&') < 0) {
                    String value = line.substring(7).trim();
                    if (!value.isEmpty()) {
                        return value;
                    }
                }
            }
        }
        Map<String, String> headers = context.getHeaders();
        return headers != null ? headers.get("Cookie") : null;
    }

    /** POST a gw-light gateway method with the session cookie; returns the body. */
    private static String gwLight(OkHttpClient client, String method, String apiToken,
                                  String body, String cookie) throws IOException {
        String url = GW_LIGHT
                + "?method=" + method
                + "&input=3&api_version=1.0"
                + "&api_token=" + (apiToken == null ? "" : apiToken)
                + "&cid=" + (Math.abs(new SecureRandom().nextInt()) % 1_000_000_000);
        RequestBody reqBody = RequestBody.create(
                body == null ? "{}" : body, MediaType.parse("application/json"));
        Request req = new Request.Builder()
                .url(url)
                .header("Cookie", cookie)
                .header("Accept", "*/*")
                .post(reqBody)
                .build();
        try (Response resp = client.newCall(req).execute()) {
            ResponseBody rb = resp.body();
            return rb != null ? rb.string() : "";
        }
    }

    /** Resolve the encrypted CDN URL for one format via media.deezer.com. */
    private static String getUrl(OkHttpClient client, String licenseToken,
                                 String trackToken, String format) throws Exception {
        JSONObject fmt = new JSONObject()
                .put("cipher", "BF_CBC_STRIPE")
                .put("format", format);
        JSONObject media = new JSONObject()
                .put("type", "FULL")
                .put("formats", new JSONArray().put(fmt));
        JSONObject payload = new JSONObject()
                .put("license_token", licenseToken)
                .put("media", new JSONArray().put(media))
                .put("track_tokens", new JSONArray().put(trackToken));
        RequestBody reqBody = RequestBody.create(
                payload.toString(), MediaType.parse("application/json"));
        Request req = new Request.Builder().url(GET_URL).post(reqBody).build();
        try (Response resp = client.newCall(req).execute()) {
            ResponseBody rb = resp.body();
            if (rb == null) return null;
            JSONObject json = new JSONObject(rb.string());
            JSONArray data = json.optJSONArray("data");
            if (data == null || data.length() == 0) return null;
            JSONArray medias = data.getJSONObject(0).optJSONArray("media");
            if (medias == null || medias.length() == 0) return null;
            JSONArray sources = medias.getJSONObject(0).optJSONArray("sources");
            if (sources == null || sources.length() == 0) return null;
            return sources.getJSONObject(0).optString("url", null);
        }
    }

    /** Descending format fallback ladder from the requested format. */
    private static List<String> formatLadder(String requested) {
        List<String> all = new ArrayList<>();
        all.add("FLAC");
        all.add("MP3_320");
        all.add("MP3_256");
        all.add("MP3_128");
        int start = all.indexOf(requested);
        if (start < 0) start = all.indexOf("MP3_128");
        return new ArrayList<>(all.subList(start, all.size()));
    }

    // ========================================================================
    // Stream helpers
    // ========================================================================

    private void reportProgress(DownloadCallback callback, long downloaded, long total) {
        long now = System.currentTimeMillis();
        if (now - lastUpdated > UPDATE_RATE || downloaded == 0) {
            lastUpdated = now;
            int percent = total > 0 ? (int) ((downloaded * 100) / total) : 0;
            callback.onProgress(Math.min(percent, 100), downloaded, total);
        }
    }

    private static void closeQuietly(Closeable in, Closeable out, ResponseBody body, Response response) {
        try { if (in != null) in.close(); } catch (IOException ignored) {}
        try { if (out != null) out.close(); } catch (IOException ignored) {}
        if (body != null) body.close();
        if (response != null) response.close();
    }
}
