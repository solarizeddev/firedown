package com.solarized.firedown.sync;

import android.util.Base64;

import androidx.annotation.NonNull;

import com.solarized.firedown.okhttp.RateLimitInterceptor;
import com.solarized.firedown.sync.crypto.Canonical;
import com.solarized.firedown.sync.crypto.Pow;
import com.solarized.firedown.sync.crypto.SyncIdentity;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import okhttp3.Call;
import okhttp3.Interceptor;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * Signed HTTP client for the Firedown encrypted-storage API (cloud-storage-spec.md
 * §8). Shares the bookmark service's identity scheme: the same {@link SyncIdentity}
 * (one recovery code), the same six-line canonical + three X-Firedown-* headers.
 *
 * <p>Bytes never transit our VPS: {@link #createObject} / {@link #getObject}
 * return presigned R2 URLs and the client PUTs/GETs encrypted chunks DIRECTLY to
 * R2 via {@link #putChunk} / {@link #getChunk} (those carry no auth — the URL is
 * the capability).</p>
 */
public final class StorageApiClient {

    private static final MediaType OCTET = MediaType.parse("application/octet-stream");
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    private static final String PATH_CHALLENGE = "/v1/register/challenge";
    private static final String PATH_REGISTER = "/v1/account/register";
    private static final String PATH_MANIFEST = "/v1/storage/manifest";
    private static final String PATH_OBJECTS = "/v1/storage/objects";
    private static final String PATH_ACCOUNT = "/v1/storage/account";
    private static final String PATH_REDEEM = "/v1/storage/credits/redeem";
    private static final String PATH_QUOTA = "/v1/storage/quota";

    /** Cloudflare returns 429 (its "Block" rate-limit action) on a per-IP burst.
     *  OkHttp has NO built-in 429 handling (`retryOnConnectionFailure` only covers
     *  network/connection errors, not HTTP status), so this client adds a small
     *  retry interceptor. Bounded + SHORT backoff so a cancelled worker unwinds
     *  fast and a signed request's timestamp doesn't go stale. */
    private static final int MAX_RETRIES = 3;
    private static final long BACKOFF_BASE_MS = 600L;
    private static final long BACKOFF_CAP_MS = 8_000L;

    private final OkHttpClient client;
    private final String baseUrl; // no trailing slash

    // Cancellation: a backup/restore worker runs synchronously and WorkManager
    // does NOT interrupt its thread on cancel, so a stacked rate-limit retry loop
    // would otherwise sleep through the cancel and keep hammering the (already
    // rate-limited) register endpoint. The worker calls cancel() from onStopped();
    // we cancel the in-flight Call (its retry loop's next chain.proceed throws
    // "Canceled") and refuse further calls.
    private volatile boolean cancelled = false;
    private volatile Call currentCall;

    public StorageApiClient(OkHttpClient client, String baseUrl) {
        // Derive from the shared client (same connection pool / dispatcher) but give
        // storage its OWN retry interceptor — never mutate the global client (it
        // serves downloads/captures too). Drop the shared client's global
        // RateLimitInterceptor here: storage's RetryInterceptor already covers 429
        // (and 503), so inheriting the global one too would NEST two retry loops and
        // multiply requests against the per-IP limit — the opposite of what we want.
        OkHttpClient.Builder b = client.newBuilder();
        b.interceptors().removeIf(i -> i instanceof RateLimitInterceptor);
        b.addInterceptor(new RetryInterceptor());
        this.client = b.build();
        this.baseUrl = stripTrailingSlash(baseUrl);
    }

    /** Creates a call and registers it as the in-flight one so {@link #cancel()}
     *  can abort it. If already cancelled, the returned call is pre-cancelled so
     *  {@code execute()} fails fast instead of issuing a request. */
    private Call beginCall(Request req) {
        Call call = client.newCall(req);
        currentCall = call;
        if (cancelled) {
            call.cancel();
        }
        return call;
    }

    /**
     * Cancels the in-flight request (if any) and refuses further ones. Called from
     * a worker's {@code onStopped()} when the user cancels the transfer, so the
     * rate-limit retry loops unwind at once instead of running to exhaustion.
     */
    public void cancel() {
        cancelled = true;
        Call call = currentCall;
        if (call != null) {
            call.cancel();
        }
    }

    /**
     * Retries 429/503 (edge throttle / temporarily unavailable — the request was
     * rejected at the edge BEFORE the origin ran it, so re-sending even a
     * non-idempotent POST is safe) with bounded backoff honouring Retry-After.
     * A generic 5xx is NOT retried (the origin may have executed it). Applies to
     * every call through this client, including the presigned R2 chunk PUT/GET.
     */
    private static final class RetryInterceptor implements Interceptor {
        @NonNull
        @Override
        public Response intercept(@NonNull Chain chain) throws IOException {
            Request req = chain.request();
            Response resp = chain.proceed(req);
            int attempt = 0;
            while ((resp.code() == 429 || resp.code() == 503) && attempt < MAX_RETRIES) {
                int retryAfter = parseInt(resp.header("Retry-After"), 0);
                resp.close();
                // A cancelled call (worker stopped) must not sleep through the
                // backoff and retry — bail immediately. (A cancel that lands DURING
                // the sleep is caught by the next chain.proceed throwing "Canceled".)
                if (chain.call().isCanceled()) {
                    throw new IOException("cancelled during rate-limit backoff");
                }
                long exp = Math.min(BACKOFF_CAP_MS, BACKOFF_BASE_MS << attempt);
                long delay = Math.min(BACKOFF_CAP_MS,
                        Math.max(exp, (long) Math.max(0, retryAfter) * 1000L));
                try {
                    Thread.sleep(delay);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("interrupted during rate-limit backoff", e);
                }
                attempt++;
                resp = chain.proceed(req);
            }
            return resp;
        }
    }

    // ---- error types (mirror SyncApiClient) ----

    /** Redeem 409 slug: the credit's secret was already burned. On a retry after a
     *  lost response this means the credit WAS applied — the caller treats it as
     *  success, not failure (see {@link RedeemResult#applied}). */
    public static final String SLUG_CREDIT_SPENT = "credit-spent";

    /** A transient failure the caller should retry after {@code retryAfterSeconds}. */
    public static final class TransientException extends IOException {
        public final int retryAfterSeconds;
        TransientException(String msg, int retryAfterSeconds) {
            super(msg);
            this.retryAfterSeconds = retryAfterSeconds;
        }
    }

    /** A presigned chunk PUT was rejected as expired/invalid (R2 403) — the URL
     *  outlived its TTL mid-upload (a large file on a slow link). The caller should
     *  re-mint fresh URLs ({@link #refreshUploadUrls}) and retry the SAME chunk,
     *  rather than fail the whole upload. Distinct from TransientException so the
     *  engine refreshes instead of blindly re-running from chunk 0. */
    public static final class PresignExpiredException extends IOException {
        PresignExpiredException(String msg) {
            super(msg);
        }
    }

    /** A permanent failure (bad request, quota-exhausted, object-not-found, …). */
    public static final class FatalException extends IOException {
        public final String slug;
        FatalException(String msg, String slug) {
            super(msg);
            this.slug = slug;
        }
    }

    // ---- result shapes ----

    /** Result of a GET /v1/storage/manifest. */
    public static final class ManifestPull {
        public final byte[] ciphertext; // null when notFound
        public final long version;      // 0 when notFound
        public final boolean notFound;
        ManifestPull(byte[] ciphertext, long version, boolean notFound) {
            this.ciphertext = ciphertext;
            this.version = version;
            this.notFound = notFound;
        }
    }

    /** Result of a PUT /v1/storage/manifest. */
    public static final class ManifestPut {
        public final boolean ok;
        public final long version;       // on ok
        public final long serverVersion; // on conflict
        ManifestPut(boolean ok, long version, long serverVersion) {
            this.ok = ok;
            this.version = version;
            this.serverVersion = serverVersion;
        }
    }

    /** Result of a POST /v1/storage/objects — one presigned PUT URL per chunk. */
    public static final class CreatedObject {
        public final String objectId;          // hex
        public final List<String> uploadUrls;  // one per chunk, in order
        CreatedObject(String objectId, List<String> uploadUrls) {
            this.objectId = objectId;
            this.uploadUrls = uploadUrls;
        }
    }

    /** Result of a POST /v1/storage/credits/redeem — the new metered balance. */
    public static final class RedeemResult {
        public final int redeemedGbMonths;
        public final double balanceGbMonths;
        public final long balanceMicroGbMonths;
        RedeemResult(int redeemedGbMonths, double balanceGbMonths, long balanceMicroGbMonths) {
            this.redeemedGbMonths = redeemedGbMonths;
            this.balanceGbMonths = balanceGbMonths;
            this.balanceMicroGbMonths = balanceMicroGbMonths;
        }

        /** Synthesizes a result for the credit-spent RECOVERY path — a redeem that
         *  returned 409 credit-spent because an earlier redeem (whose response was
         *  lost) already applied this credit. The exact post-balance isn't in the
         *  409, so it's reported as the redeemed denomination (a floor); the status
         *  hero re-reads the true balance from the quota endpoint. */
        public static RedeemResult applied(int gbMonths) {
            return new RedeemResult(gbMonths, gbMonths, 0);
        }
    }

    /**
     * Result of a GET /v1/storage/quota. In the current UNMETERED phase the server
     * reports a flat {@code bytes_limit}; once metering is on it reports the
     * GB-months balance + a projected runout (or, in grace, the stamped runout +
     * grace window). {@link #metered} says which shape you got.
     */
    public static final class Quota {
        public final boolean metered;
        public final double balanceGbMonths;   // metered
        public final long balanceMicroGbMonths;// metered (exact)
        public final boolean readOnly;         // balance ran out (grace) — read-only
        public final String projectedRunoutAt; // RFC3339, metered + funded (nullable)
        public final String runoutAt;          // RFC3339, stamped once in grace (nullable)
        public final String graceUntil;        // RFC3339, end of read-only grace (nullable)
        public final long bytesLimit;          // unmetered

        Quota(boolean metered, double balanceGbMonths, long balanceMicroGbMonths, boolean readOnly,
              String projectedRunoutAt, String runoutAt, String graceUntil, long bytesLimit) {
            this.metered = metered;
            this.balanceGbMonths = balanceGbMonths;
            this.balanceMicroGbMonths = balanceMicroGbMonths;
            this.readOnly = readOnly;
            this.projectedRunoutAt = projectedRunoutAt;
            this.runoutAt = runoutAt;
            this.graceUntil = graceUntil;
            this.bytesLimit = bytesLimit;
        }
    }

    /** Result of a GET /v1/storage/objects/{id} — presigned GET URLs to reassemble. */
    public static final class ObjectInfo {
        public final long byteSize;
        public final int chunkCount;
        public final List<String> downloadUrls; // one per chunk, in order
        ObjectInfo(long byteSize, int chunkCount, List<String> downloadUrls) {
            this.byteSize = byteSize;
            this.chunkCount = chunkCount;
            this.downloadUrls = downloadUrls;
        }
    }

    // ---- registration (same flow as the bookmark service) ----

    /** Registers this identity with the storage service (idempotent server-side). */
    public void register(SyncIdentity id) throws IOException {
        String q = "account_id=" + id.accountBase32();
        Request chReq = new Request.Builder()
                .url(baseUrl + PATH_CHALLENGE + "?" + q)
                .get()
                .build();
        byte[] challenge;
        int powBits;
        try (Response resp = beginCall(chReq).execute()) {
            throwForStatus(resp, "challenge");
            JSONObject obj = new JSONObject(bodyString(resp));
            challenge = b64urlDecode(obj.getString("challenge"));
            powBits = obj.getInt("pow_bits");
        } catch (org.json.JSONException e) {
            throw new IOException("malformed challenge response", e);
        }

        byte[] nonce = Pow.solve(id.accountId(), challenge, powBits);

        JSONObject body = new JSONObject();
        try {
            body.put("account_id", id.accountBase32());
            body.put("auth_pubkey", b64urlEncode(id.authPublicKey()));
            body.put("challenge", b64urlEncode(challenge));
            body.put("pow_nonce", b64urlEncode(nonce));
        } catch (org.json.JSONException e) {
            throw new IOException("register body", e);
        }
        byte[] bodyBytes = body.toString().getBytes(StandardCharsets.UTF_8);
        Request req = signed(id, "POST", PATH_REGISTER, "", bodyBytes)
                .post(RequestBody.create(bodyBytes, JSON))
                .build();
        try (Response resp = beginCall(req).execute()) {
            if (resp.code() == 200 || resp.code() == 201) {
                return;
            }
            throwForStatus(resp, "register");
        }
    }

    // ---- manifest (OCC, identical to the bookmark doc) ----

    /** GET the encrypted manifest (404 -&gt; notFound = empty/version 0). */
    public ManifestPull pullManifest(SyncIdentity id) throws IOException {
        Request req = signed(id, "GET", PATH_MANIFEST, "", null).get().build();
        try (Response resp = beginCall(req).execute()) {
            if (resp.code() == 404) {
                return new ManifestPull(null, 0, true);
            }
            throwForStatus(resp, "pull manifest");
            long version = parseLong(resp.header("X-Firedown-Version"), 0);
            byte[] bytes = resp.body() != null ? resp.body().bytes() : new byte[0];
            return new ManifestPull(bytes, version, false);
        }
    }

    /** PUT the manifest with optimistic-concurrency on {@code prevVersion}. */
    public ManifestPut pushManifest(SyncIdentity id, byte[] ciphertext, long prevVersion) throws IOException {
        Request req = signed(id, "PUT", PATH_MANIFEST, "", ciphertext)
                .header("X-Firedown-Prev-Version", Long.toString(prevVersion))
                .put(RequestBody.create(ciphertext, OCTET))
                .build();
        try (Response resp = beginCall(req).execute()) {
            int code = resp.code();
            if (code == 200) {
                try {
                    JSONObject obj = new JSONObject(bodyString(resp));
                    return new ManifestPut(true, obj.optLong("version", 0), 0);
                } catch (org.json.JSONException e) {
                    throw new IOException("malformed manifest put response", e);
                }
            }
            if (code == 409) {
                long serverVersion = 0;
                try {
                    serverVersion = new JSONObject(bodyString(resp)).optLong("server_version", 0);
                } catch (org.json.JSONException ignored) {
                    // fall through with 0; the engine re-pulls anyway
                }
                return new ManifestPut(false, 0, serverVersion);
            }
            throwForStatus(resp, "push manifest");
            throw new IOException("unexpected manifest put status " + code); // unreachable
        }
    }

    // ---- objects ----

    /** Reserve a pending object; returns one presigned PUT URL per chunk. */
    public CreatedObject createObject(SyncIdentity id, long byteSize, int chunkCount) throws IOException {
        JSONObject body = new JSONObject();
        try {
            body.put("byte_size", byteSize);
            body.put("chunk_count", chunkCount);
        } catch (org.json.JSONException e) {
            throw new IOException("create object body", e);
        }
        byte[] bodyBytes = body.toString().getBytes(StandardCharsets.UTF_8);
        Request req = signed(id, "POST", PATH_OBJECTS, "", bodyBytes)
                .post(RequestBody.create(bodyBytes, JSON))
                .build();
        try (Response resp = beginCall(req).execute()) {
            throwForStatus(resp, "create object");
            try {
                JSONObject obj = new JSONObject(bodyString(resp));
                return new CreatedObject(obj.getString("object_id"),
                        toStringList(obj.getJSONArray("upload_urls")));
            } catch (org.json.JSONException e) {
                throw new IOException("malformed create object response", e);
            }
        }
    }

    /** Upload one encrypted chunk to its presigned R2 URL (no auth — the URL is the
     *  capability). A 403 means the presigned URL expired (outlived UploadPresignTTL
     *  mid-upload) → PresignExpiredException so the engine can refresh + retry the
     *  chunk; any other non-2xx is transient (retry the whole upload). */
    public void putChunk(String uploadUrl, byte[] encryptedChunk) throws IOException {
        Request req = new Request.Builder()
                .url(uploadUrl)
                .put(RequestBody.create(encryptedChunk, OCTET))
                .build();
        try (Response resp = beginCall(req).execute()) {
            if (resp.code() == 403) {
                throw new PresignExpiredException("chunk PUT: 403 (presign expired)");
            }
            if (!resp.isSuccessful()) {
                throw new TransientException("chunk PUT: " + resp.code(), 0);
            }
        }
    }

    /** Re-mint fresh presigned PUT URLs for a still-PENDING object whose create-time
     *  URLs expired mid-upload, so a slow/large upload can resume without
     *  re-creating the object (and re-uploading from chunk 0). The server refuses
     *  (409) once the object is committed. Returns the fresh URL per chunk, in
     *  order. */
    public List<String> refreshUploadUrls(SyncIdentity id, String objectIdHex) throws IOException {
        String path = PATH_OBJECTS + "/" + objectIdHex + "/upload-urls";
        Request req = signed(id, "POST", path, "", new byte[0])
                .post(RequestBody.create(new byte[0], JSON))
                .build();
        try (Response resp = beginCall(req).execute()) {
            throwForStatus(resp, "refresh upload urls");
            try {
                return toStringList(new JSONObject(bodyString(resp)).getJSONArray("upload_urls"));
            } catch (org.json.JSONException e) {
                throw new IOException("malformed refresh response", e);
            }
        }
    }

    /** Finalize an object; the server HEADs every chunk and commits the real size. */
    public long completeObject(SyncIdentity id, String objectIdHex) throws IOException {
        String path = PATH_OBJECTS + "/" + objectIdHex + "/complete";
        Request req = signed(id, "POST", path, "", new byte[0])
                .post(RequestBody.create(new byte[0], JSON))
                .build();
        try (Response resp = beginCall(req).execute()) {
            throwForStatus(resp, "complete object");
            try {
                return new JSONObject(bodyString(resp)).optLong("committed_size", 0);
            } catch (org.json.JSONException e) {
                throw new IOException("malformed complete response", e);
            }
        }
    }

    /** GET an object's presigned download URLs (one per chunk) + its size. */
    public ObjectInfo getObject(SyncIdentity id, String objectIdHex) throws IOException {
        String path = PATH_OBJECTS + "/" + objectIdHex;
        Request req = signed(id, "GET", path, "", null).get().build();
        try (Response resp = beginCall(req).execute()) {
            throwForStatus(resp, "get object");
            try {
                JSONObject obj = new JSONObject(bodyString(resp));
                return new ObjectInfo(obj.optLong("byte_size", 0), obj.optInt("chunk_count", 0),
                        toStringList(obj.getJSONArray("download_urls")));
            } catch (org.json.JSONException e) {
                throw new IOException("malformed get object response", e);
            }
        }
    }

    /** Download one encrypted chunk from its presigned R2 URL. */
    public byte[] getChunk(String downloadUrl) throws IOException {
        Request req = new Request.Builder().url(downloadUrl).get().build();
        try (Response resp = beginCall(req).execute()) {
            if (!resp.isSuccessful()) {
                throw new TransientException("chunk GET: " + resp.code(), 0);
            }
            return resp.body() != null ? resp.body().bytes() : new byte[0];
        }
    }

    /** Delete an object (frees quota); 204/404 both succeed. */
    public void deleteObject(SyncIdentity id, String objectIdHex) throws IOException {
        Request req = signed(id, "DELETE", PATH_OBJECTS + "/" + objectIdHex, "", null).delete().build();
        try (Response resp = beginCall(req).execute()) {
            if (resp.code() == 404) {
                return;
            }
            throwForStatus(resp, "delete object");
        }
    }

    /** Purge all of this account's storage data (right-to-erasure). */
    public void deleteAccount(SyncIdentity id) throws IOException {
        Request req = signed(id, "DELETE", PATH_ACCOUNT, "", null).delete().build();
        try (Response resp = beginCall(req).execute()) {
            if (resp.code() == 404) {
                return;
            }
            throwForStatus(resp, "delete account");
        }
    }

    /**
     * Redeems a blind-signed mint credit (keyset id / secret / signature, all hex)
     * into the account's metered balance. Returns the new balance. A {@code
     * credit-spent} 409 (or any 4xx) surfaces as {@link FatalException} with the
     * slug so the caller can distinguish already-redeemed from a bad credit.
     */
    public RedeemResult redeemCredit(SyncIdentity id, String keysetIdHex, String secretHex, String signatureHex)
            throws IOException {
        JSONObject body = new JSONObject();
        try {
            body.put("keyset_id", keysetIdHex);
            body.put("secret", secretHex);
            body.put("signature", signatureHex);
        } catch (org.json.JSONException e) {
            throw new IOException("redeem body", e);
        }
        byte[] bodyBytes = body.toString().getBytes(StandardCharsets.UTF_8);
        Request req = signed(id, "POST", PATH_REDEEM, "", bodyBytes)
                .post(RequestBody.create(bodyBytes, JSON))
                .build();
        try (Response resp = beginCall(req).execute()) {
            throwForStatus(resp, "redeem");
            try {
                JSONObject obj = new JSONObject(bodyString(resp));
                return new RedeemResult(
                        obj.optInt("redeemed_gb_months", 0),
                        obj.optDouble("balance_gb_months", 0),
                        obj.optLong("balance_micro_gb_months", 0));
            } catch (org.json.JSONException e) {
                throw new IOException("malformed redeem response", e);
            }
        }
    }

    /**
     * GET the account's quota/balance. Metered vs unmetered is detected from the
     * response shape ({@code balance_gb_months} present ⇒ metered). Used by the
     * Cloud Backup status screen + the home status line to show the balance and
     * projected runout.
     */
    public Quota quota(SyncIdentity id) throws IOException {
        Request req = signed(id, "GET", PATH_QUOTA, "", null).get().build();
        try (Response resp = beginCall(req).execute()) {
            throwForStatus(resp, "quota");
            try {
                JSONObject o = new JSONObject(bodyString(resp));
                boolean metered = o.has("balance_gb_months");
                return new Quota(
                        metered,
                        o.optDouble("balance_gb_months", 0),
                        o.optLong("balance_micro_gb_months", 0),
                        o.optBoolean("read_only", false),
                        o.optString("projected_runout_at", null),
                        o.optString("runout_at", null),
                        o.optString("grace_until", null),
                        o.optLong("bytes_limit", 0));
            } catch (org.json.JSONException e) {
                throw new IOException("malformed quota response", e);
            }
        }
    }

    // ---- signing + helpers (mirror SyncApiClient) ----

    private Request.Builder signed(SyncIdentity id, String method, String path, String query, byte[] body) {
        long ts = System.currentTimeMillis() / 1000L;
        byte[] canonical = Canonical.build(id.accountId(), method, path, query, ts, body);
        byte[] sig = id.sign(canonical);
        String url = baseUrl + path + (query.isEmpty() ? "" : "?" + query);
        return new Request.Builder()
                .url(url)
                .header("X-Firedown-Account", id.accountBase32())
                .header("X-Firedown-Timestamp", Long.toString(ts))
                .header("X-Firedown-Signature", b64urlEncode(sig));
    }

    private void throwForStatus(Response resp, String op) throws IOException {
        int code = resp.code();
        if (code >= 200 && code < 300) {
            return;
        }
        String slug = readErrorSlug(resp);
        if (code == 429 || code == 503) {
            throw new TransientException(op + ": " + code + " " + slug, parseInt(resp.header("Retry-After"), 0));
        }
        if (code >= 500) {
            throw new TransientException(op + ": server " + code, 0);
        }
        throw new FatalException(op + ": " + code + " " + slug, slug);
    }

    private static String readErrorSlug(Response resp) {
        try {
            String s = bodyString(resp);
            if (s != null && s.startsWith("{")) {
                return new JSONObject(s).optString("error", "");
            }
        } catch (Exception ignored) {
            // no parseable body
        }
        return "";
    }

    private static List<String> toStringList(JSONArray arr) throws org.json.JSONException {
        List<String> out = new ArrayList<>(arr.length());
        for (int i = 0; i < arr.length(); i++) {
            out.add(arr.getString(i));
        }
        return out;
    }

    private static String bodyString(Response resp) throws IOException {
        return resp.body() != null ? resp.body().string() : "";
    }

    private static String b64urlEncode(byte[] b) {
        return Base64.encodeToString(b, Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP);
    }

    private static byte[] b64urlDecode(String s) {
        return Base64.decode(s, Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP);
    }

    private static long parseLong(String s, long def) {
        try {
            return s == null ? def : Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static int parseInt(String s, int def) {
        try {
            return s == null ? def : Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static String stripTrailingSlash(String url) {
        if (url == null || url.isEmpty()) {
            return "https://storage.firedown.app";
        }
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
