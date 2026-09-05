package com.solarized.firedown.manager;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * JDK-only harness for the REAL Deezer download code (copied from app/src at run
 * time by run.sh; this class shares the package to reach the package-private
 * API). Two layers:
 *
 * <p><b>DeezerCrypto</b> (Android-free, no stubs): the Blowfish key derivation
 * against known-answer vectors from an INDEPENDENT implementation (node's
 * crypto module — see KATS; a bug that made DeezerCrypto agree with itself can't
 * pass a vector from a different codebase); the stripe cipher round trip
 * ({@code decrypt(encrypt(x)) == x} on the enciphered stripes, verbatim
 * elsewhere incl. the trailing short stripe); {@code decryptStream}'s
 * stripe-alignment invariant (a one-byte-per-read stream must yield the
 * identical file); and the progress-hook abort.
 *
 * <p><b>DeezerStrategy</b> (against the collaborator stubs in ../stub — okhttp3's
 * stub has a scriptable {@code responder}, the sabr-harness pattern): the REAL
 * {@code execute()} driven end to end over scripted gateway bodies — the
 * entitlement fallback (FLAC refused → MP3_320 served → decrypted file is
 * byte-exact and named .mp3), the not-logged-in error path, and the
 * RETRY/RESUME path where the cookie is no longer on the request and must be
 * recovered from the raw persisted header string (the shipped bug: every retry
 * of an errored Deezer download failed "missing session cookie"). Plus
 * {@code resolveCookie}'s three sources in isolation, including the inner-"="
 * truncation the parsed header map would have caused.
 */
public final class DeezerHarness {

    // The Deezer stripe IV — the documented constant, hardcoded here so the
    // harness's ENCRYPT side is independent of DeezerCrypto's private copy.
    private static final byte[] IV = {0, 1, 2, 3, 4, 5, 6, 7};

    // Known-answer vectors: SNG_ID -> hex(trackKey), computed by node:
    //   md5=hex(md5(id)); key[i]=md5[i]^md5[i+16]^"g4el58wc0zvf9na1"[i]
    private static final String[][] KATS = {
        {"123456", "6060603b346b716d62712461346f3336"},
        {"3135556", "6c6c666b39662c37652575603c643439"},
        {"1", "3464656e343a7d3a672c236a33696061"},
    };

    private static final String SNG = "3135556";
    private static final String CDN = "https://e-cdns-proxy-a.dzcdn.net/mobile/1/deadbeef";
    private static final String USER_OK =
        "{\"error\":[],\"results\":{\"checkForm\":\"api-tok\",\"USER\":{\"USER_ID\":12345,\"OPTIONS\":{\"license_token\":\"lic-tok\"}}}}";
    private static final String USER_LOGGED_OUT = "{\"error\":[],\"results\":{\"USER\":{\"USER_ID\":0}}}";
    // A GUEST answers getUserData with a checkForm and a (preview-only) license
    // token — everything but a real USER_ID. Must read as "not logged in".
    private static final String USER_GUEST =
        "{\"error\":[],\"results\":{\"checkForm\":\"guest-tok\",\"USER\":{\"USER_ID\":0,\"OPTIONS\":{\"license_token\":\"guest-lic\"}}}}";
    private static final String SONG_OK = "{\"error\":[],\"results\":{\"SNG_ID\":\"3135556\",\"TRACK_TOKEN\":\"trk-tok\"}}";
    private static final String GETURL_REFUSED = "{\"data\":[{\"media\":[]}]}";
    private static final String GETURL_OK =
        "{\"data\":[{\"media\":[{\"sources\":[{\"url\":\"" + CDN + "\"}]}]}]}";

    private static int failures = 0;

    private static void check(String name, boolean cond, String extra) {
        if (cond) {
            System.out.println("PASS " + name);
        } else {
            failures++;
            System.out.println("FAIL " + name + (extra == null ? "" : " " + extra));
        }
    }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) {
            sb.append(Character.forDigit((x >> 4) & 0xF, 16));
            sb.append(Character.forDigit(x & 0xF, 16));
        }
        return sb.toString();
    }

    // The harness's own encrypt of one full stripe (mirrors decryptStripe with
    // ENCRYPT_MODE) — the independent side of the round trip.
    private static void encryptStripe(byte[] key, byte[] buf, int off) throws Exception {
        Cipher c = Cipher.getInstance("Blowfish/CBC/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "Blowfish"), new IvParameterSpec(IV));
        byte[] ct = c.doFinal(buf, off, DeezerCrypto.STRIPE);
        System.arraycopy(ct, 0, buf, off, DeezerCrypto.STRIPE);
    }

    /** Build Deezer ciphertext: every 3rd full stripe enciphered, rest verbatim. */
    private static byte[] encodeDeezer(byte[] plain, byte[] key) throws Exception {
        byte[] ct = plain.clone();
        int stripe = DeezerCrypto.STRIPE;
        int index = 0;
        for (int off = 0; off + stripe <= ct.length; off += stripe) {
            if (index % 3 == 0) {
                encryptStripe(key, ct, off);
            }
            index++;
        }
        return ct;
    }

    private static byte[] pseudoRandom(int len, int seed) {
        byte[] out = new byte[len];
        for (int i = 0; i < len; i++) {
            seed = seed * 1103515245 + 12345;
            out[i] = (byte) (seed >>> 16);
        }
        return out;
    }

    // An InputStream that hands back at most one byte per read() — the pathology
    // decryptStream's fill() must absorb without drifting the stripe boundary.
    private static InputStream dribble(byte[] data) {
        return new InputStream() {
            int pos = 0;
            @Override public int read() {
                return pos < data.length ? (data[pos++] & 0xFF) : -1;
            }
            @Override public int read(byte[] b, int off, int len) {
                if (pos >= data.length) return -1;
                b[off] = data[pos++];
                return 1;   // never more than one byte, whatever was asked
            }
        };
    }

    // ------------------------------------------------------------------------
    // Strategy drive plumbing
    // ------------------------------------------------------------------------

    /** Records everything DeezerStrategy reports back. */
    private static final class Recorder implements DownloadCallback {
        final List<Integer> statuses = new ArrayList<>();
        final List<Integer> errors = new ArrayList<>();
        String mime;
        String resolvedPath;
        long lastPercent = -1;
        @Override public void onProgress(int percent, long downloaded, long total) { lastPercent = percent; }
        @Override public void onProcessing() {}
        @Override public void onStatusChanged(int status) { statuses.add(status); }
        @Override public void onError(int errorType) { errors.add(errorType); }
        @Override public void onNameResolved(String name) {}
        @Override public void onMimeResolved(String mimeType) { mime = mimeType; }
        @Override public void onFileSizeKnown(long size) {}
        @Override public String onFilePathResolved(String path) { resolvedPath = path; return path; }
        @Override public void onImgResolved(String imgPath) {}
        @Override public void onLiveStream(boolean isLive) {}
        @Override public void onDescriptionResolved(String description) {}
        @Override public void onDurationResolved(long duration, String formatted) {}
        @Override public void onFileDurationProbed(long duration) {}
        @Override public void onFinished() {}
    }

    /** One scripted gateway: answers by URL / body, and logs every request. */
    private static final class Gateway {
        final List<Request> seen = new ArrayList<>();
        String userData = USER_OK;
        byte[] cdnBody;

        void install() {
            seen.clear();
            OkHttpClient.responder = (req, idx) -> {
                seen.add(req);
                String url = req.url();
                if (url.contains("method=deezer.getUserData")) return text(userData);
                if (url.contains("method=song.getData")) return text(SONG_OK);
                if (url.startsWith("https://media.deezer.com/v1/get_url")) {
                    String body = new String(req.body.content);
                    // Entitlement: FLAC refused, MP3_320 served (the ladder).
                    return text(body.contains("\"FLAC\"") ? GETURL_REFUSED : GETURL_OK);
                }
                if (url.equals(CDN)) return Response.ok(cdnBody);
                return new Response(404, "nope", new byte[0], new HashMap<>());
            };
        }
        static Response text(String s) { return Response.ok(s.getBytes()); }

        List<Request> matching(String needle) {
            List<Request> out = new ArrayList<>();
            for (Request r : seen) if (r.url().contains(needle)) out.add(r);
            return out;
        }
    }

    private static DownloadRequest request(String url, String cookieHeader, String rawHeaders) {
        DownloadRequest r = new DownloadRequest();
        r.url = url;
        r.cookieHeader = cookieHeader;
        r.headers = rawHeaders;
        return r;
    }

    private static DownloadContext context(File outputFile, Map<String, String> headers) {
        DownloadContext c = new DownloadContext();
        c.outputFile = outputFile;
        c.headers = headers;
        return c;
    }

    public static void main(String[] args) throws Exception {
        // ==================================================================
        // A. DeezerCrypto — key derivation vs. independent vectors
        // ==================================================================
        for (String[] kat : KATS) {
            String got = hex(DeezerCrypto.trackKey(kat[0]));
            check("crypto: keyDerivation SNG_ID=" + kat[0], got.equals(kat[1]), "got " + got + " want " + kat[1]);
        }
        byte[] key = DeezerCrypto.trackKey("123456");

        // ==================================================================
        // B. DeezerCrypto — round trip, alignment, abort
        // ==================================================================
        // 7 full stripes (0..6; 0,3,6 enciphered) + a 500-byte tail (never enciphered).
        int stripe = DeezerCrypto.STRIPE;
        byte[] plain = pseudoRandom(stripe * 7 + 500, 12345);
        byte[] ct = encodeDeezer(plain, key);

        boolean s0changed = !Arrays.equals(
            Arrays.copyOfRange(ct, 0, stripe), Arrays.copyOfRange(plain, 0, stripe));
        boolean s1same = Arrays.equals(
            Arrays.copyOfRange(ct, stripe, stripe * 2), Arrays.copyOfRange(plain, stripe, stripe * 2));
        check("crypto: stripe 0 enciphered (differs from plain)", s0changed, null);
        check("crypto: stripe 1 left verbatim (every-third rule)", s1same, null);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        long[] lastProgress = {0};
        long written = DeezerCrypto.decryptStream(
            new ByteArrayInputStream(ct), out, key, w -> { lastProgress[0] = w; return true; });
        check("crypto: decryptStream round trip byte-exact", Arrays.equals(out.toByteArray(), plain),
            "len " + out.size() + " vs " + plain.length);
        check("crypto: decryptStream returns full byte count", written == plain.length, "got " + written);
        check("crypto: progress reached total", lastProgress[0] == plain.length, "got " + lastProgress[0]);

        ByteArrayOutputStream dribbled = new ByteArrayOutputStream();
        DeezerCrypto.decryptStream(dribble(ct), dribbled, key, w -> true);
        check("crypto: 1-byte-per-read yields identical file",
            Arrays.equals(dribbled.toByteArray(), plain), "len " + dribbled.size());

        ByteArrayOutputStream partial = new ByteArrayOutputStream();
        long stopAt = DeezerCrypto.decryptStream(new ByteArrayInputStream(ct), partial, key, w -> false);
        check("crypto: abort stops after first stripe", stopAt == stripe, "got " + stopAt);
        check("crypto: abort wrote only what it reported", partial.size() == stripe, "got " + partial.size());
        check("crypto: aborted stripe still decrypted correctly",
            Arrays.equals(partial.toByteArray(), Arrays.copyOfRange(plain, 0, stripe)), null);

        // ==================================================================
        // C. resolveCookie — the three sources, in isolation
        // ==================================================================
        final String COOKIE = "arl=abc123; sid=fr9999";   // two inner '=' — the truncation trap
        DownloadContext noCtxCookie = context(null, new HashMap<>());

        check("cookie: request.cookieHeader wins when present",
            COOKIE.equals(DeezerStrategy.resolveCookie(request("u", COOKIE, "Referer=x"), noCtxCookie)), null);

        // The resume shape: initialize() appended "\r\nCookie=<raw>" after the
        // &-joined encoded map. Must come back INTACT, inner '=' and all.
        String resumeRaw = "Referer=https%3A%2F%2Fwww.deezer.com%2F&Accept=*%2F*\r\nCookie=" + COOKIE;
        String fromRaw = DeezerStrategy.resolveCookie(request("u", null, resumeRaw), noCtxCookie);
        check("cookie: recovered intact from the raw resume header string", COOKIE.equals(fromRaw), "got " + fromRaw);

        // Prove the trap is real: the parsed-map route (what stringToMap yields
        // for that raw string) truncates at the first inner '='.
        String mapValue = resumeRaw.split("\r\n")[1].split("=")[1];
        check("cookie: (trap) split-on-'=' parsing WOULD truncate to '" + mapValue + "'",
            "arl".equals(mapValue), null);

        // A first-line encoded Cookie pair (contains '&') is NOT the raw cookie —
        // it must fall through to the context map, where encoding round-trips.
        Map<String, String> ctxMap = new HashMap<>();
        ctxMap.put("Cookie", COOKIE);
        String encodedLineOnly = "Cookie=arl%3Dabc123%3B%20sid%3Dfr9999&Referer=x";
        String fromMap = DeezerStrategy.resolveCookie(request("u", null, encodedLineOnly), context(null, ctxMap));
        check("cookie: encoded map line skipped, context map used", COOKIE.equals(fromMap), "got " + fromMap);

        check("cookie: nothing anywhere → null",
            DeezerStrategy.resolveCookie(request("u", null, null), noCtxCookie) == null, null);

        // ==================================================================
        // D. DeezerStrategy.execute() — end to end over scripted gateways
        // ==================================================================
        byte[] trackKey = DeezerCrypto.trackKey(SNG);
        byte[] trackPlain = pseudoRandom(stripe * 5 + 321, 777);
        byte[] trackCipher = encodeDeezer(trackPlain, trackKey);
        File dir = Files.createTempDirectory("deezer-harness").toFile();

        // D1. Logged in, FLAC requested but not entitled → ladder steps to
        //     MP3_320, file decrypts byte-exact, named .mp3, FINISHED.
        {
            Gateway gw = new Gateway();
            gw.cdnBody = trackCipher;
            gw.install();
            File outFile = new File(dir, "Sample Track");        // capture name, no ext
            Recorder rec = new Recorder();
            DeezerStrategy s = new DeezerStrategy();
            s.execute(request("https://www.deezer.com/track/" + SNG + "?fmt=FLAC", "arl=abc", null),
                      context(outFile, new HashMap<>()), rec);

            check("e2e: no error reported", rec.errors.isEmpty(), "" + rec.errors);
            check("e2e: FINISHED status reported", rec.statuses.contains(1), "" + rec.statuses);
            check("e2e: mime follows the RESOLVED rung (MP3_320 → audio/mpeg)",
                "audio/mpeg".equals(rec.mime), rec.mime);
            check("e2e: output renamed to .mp3",
                rec.resolvedPath != null && rec.resolvedPath.endsWith("Sample Track.mp3"), rec.resolvedPath);
            byte[] onDisk = rec.resolvedPath != null ? Files.readAllBytes(new File(rec.resolvedPath).toPath()) : new byte[0];
            check("e2e: decrypted file is byte-exact", Arrays.equals(onDisk, trackPlain),
                "len " + onDisk.length + " vs " + trackPlain.length);
            check("e2e: progress reached 100", rec.lastPercent == 100, "" + rec.lastPercent);

            List<Request> getUrls = gw.matching("get_url");
            check("e2e: get_url tried FLAC then MP3_320 (2 calls)", getUrls.size() == 2, "" + getUrls.size());
            boolean order = getUrls.size() == 2
                && new String(getUrls.get(0).body.content).contains("\"FLAC\"")
                && new String(getUrls.get(1).body.content).contains("\"MP3_320\"");
            check("e2e: ladder order FLAC → MP3_320", order, null);
            check("e2e: get_url requests the striped cipher",
                getUrls.size() == 2 && new String(getUrls.get(1).body.content).contains("BF_CBC_STRIPE"), null);
            check("e2e: gateway calls carry the session cookie",
                gw.matching("gw-light").size() == 2
                    && gw.matching("gw-light").stream().allMatch(r -> "arl=abc".equals(r.header("Cookie"))), null);
            check("e2e: song.getData used the api token",
                gw.matching("song.getData").size() == 1
                    && gw.matching("song.getData").get(0).url().contains("api_token=api-tok"), null);
        }

        // D2. Logged out (no checkForm/license) → clean error, no file, no CDN hit.
        {
            Gateway gw = new Gateway();
            gw.userData = USER_LOGGED_OUT;
            gw.cdnBody = trackCipher;
            gw.install();
            File outFile = new File(dir, "Logged Out");
            Recorder rec = new Recorder();
            new DeezerStrategy().execute(
                request("https://www.deezer.com/track/" + SNG + "?fmt=MP3_320", "arl=expired", null),
                context(outFile, new HashMap<>()), rec);
            check("e2e: logged-out → onError", rec.errors.size() == 1, "" + rec.errors);
            check("e2e: logged-out → never FINISHED", !rec.statuses.contains(1), "" + rec.statuses);
            check("e2e: logged-out → stops after getUserData (no song/get_url/CDN)",
                gw.seen.size() == 1, "" + gw.seen.size());
            check("e2e: logged-out → no file written", !new File(dir, "Logged Out.mp3").exists(), null);
        }

        // D3. RETRY / RESUME: cookie NOT on the request, only in the persisted raw
        //     header string (initialize()'s "\r\nCookie=" merge). The shipped bug
        //     failed here with "missing session cookie"; it must now succeed AND
        //     send the recovered cookie to the gateway intact.
        {
            Gateway gw = new Gateway();
            gw.cdnBody = trackCipher;
            gw.install();
            File outFile = new File(dir, "Resumed Track");
            Recorder rec = new Recorder();
            String persisted = "Referer=https%3A%2F%2Fwww.deezer.com%2F\r\nCookie=" + COOKIE;
            new DeezerStrategy().execute(
                request("https://www.deezer.com/track/" + SNG + "?fmt=MP3_320", null, persisted),
                context(outFile, new HashMap<>()), rec);
            check("e2e(resume): succeeds with cookie only in raw headers", rec.errors.isEmpty() && rec.statuses.contains(1),
                "errors=" + rec.errors + " statuses=" + rec.statuses);
            check("e2e(resume): gateway received the INTACT cookie (inner '=' preserved)",
                !gw.matching("gw-light").isEmpty()
                    && gw.matching("gw-light").stream().allMatch(r -> COOKIE.equals(r.header("Cookie"))),
                gw.matching("gw-light").isEmpty() ? "no calls" : gw.matching("gw-light").get(0).header("Cookie"));
            byte[] onDisk = rec.resolvedPath != null ? Files.readAllBytes(new File(rec.resolvedPath).toPath()) : new byte[0];
            check("e2e(resume): decrypted file is byte-exact", Arrays.equals(onDisk, trackPlain), "len " + onDisk.length);
        }

        // D5. GUEST session: getUserData answers with a checkForm AND a
        //     (preview-only) license_token, but USER_ID 0. The strategy must call
        //     it "not logged in" after ONE request — not spend song.getData + the
        //     whole format ladder to end at a confusing "no playable source".
        {
            Gateway gw = new Gateway();
            gw.userData = USER_GUEST;
            gw.cdnBody = trackCipher;
            gw.install();
            Recorder rec = new Recorder();
            new DeezerStrategy().execute(
                request("https://www.deezer.com/track/" + SNG + "?fmt=MP3_320", "sid=guest", null),
                context(new File(dir, "Guest"), new HashMap<>()), rec);
            check("e2e(guest): USER_ID 0 → onError", rec.errors.size() == 1, "" + rec.errors);
            check("e2e(guest): stops after getUserData (1 call, no ladder)", gw.seen.size() == 1, "" + gw.seen.size());
            check("e2e(guest): never FINISHED", !rec.statuses.contains(1), "" + rec.statuses);
        }

        // D4. Bad capture URL (no SNG_ID) → immediate error, zero network.
        {
            Gateway gw = new Gateway();
            gw.install();
            Recorder rec = new Recorder();
            new DeezerStrategy().execute(request("https://www.deezer.com/album/99", "arl=abc", null),
                context(new File(dir, "x"), new HashMap<>()), rec);
            check("e2e: malformed URL → error before any network", rec.errors.size() == 1 && gw.seen.isEmpty(),
                "errors=" + rec.errors + " calls=" + gw.seen.size());
        }

        System.out.println(failures == 0
            ? "\ndeezer-harness: all checks passed"
            : "\ndeezer-harness: " + failures + " FAILURE(S)");
        System.exit(failures == 0 ? 0 : 1);
    }
}
